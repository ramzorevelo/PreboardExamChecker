package com.pbec.preboardexamchecker.domain.usecase

import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.data.repository.IExamRepository
import com.pbec.preboardexamchecker.data.repository.QuestionRepository
import com.pbec.preboardexamchecker.domain.model.ExamStats
import com.pbec.preboardexamchecker.domain.model.QuestionOption
import com.pbec.preboardexamchecker.domain.model.QuestionStat
import com.pbec.preboardexamchecker.domain.model.ScoreBucket
import javax.inject.Inject

/**
 * Computes score distribution + per-question item analysis for one exam.
 *
 * Reconstructs the answer key post-scan exactly like
 * [com.pbec.preboardexamchecker.ui.scanner.ScannerViewModel] (buildScanContext): the exam's
 * Set A/B question-id order plus the subject's question bank (first occurrence per id). Sets only
 * reorder questions — not option letters — so per-question results are aggregated across both sets
 * by question id, with Set A's order as the canonical question numbering.
 *
 * When the live exam has been deleted, falls back to the snapshot archived at deletion time
 * ([IExamRepository.getArchivedExamById]) so the stats still load for the records that took it.
 */
class ComputeExamStatsUseCase @Inject constructor(
    private val examRepository: IExamRepository,
    private val questionRepository: QuestionRepository,
) {
    private companion object {
        const val MAX_QUESTIONS = 100
    }

    /** Question metadata needed for item analysis, independent of the live/archived source. */
    private data class QMeta(
        val text: String,
        val optionA: String,
        val optionB: String,
        val optionC: String,
        val optionD: String,
    )

    /** Everything the computation needs about the exam, from either the live exam or its archive. */
    private data class ExamKey(
        val examName: String,
        val setAIds: List<Long>,
        val setBIds: List<Long>,
        val keyById: Map<Long, Char?>,
        val metaById: Map<Long, QMeta>,
    )

    suspend operator fun invoke(
        subject: String,
        examId: Long,
        results: List<ScanResult>,
    ): ExamStats? {
        val key = resolveExamKey(subject, examId) ?: return null

        val setAIds = key.setAIds
        val setBIds = key.setBIds.takeIf { it.isNotEmpty() } ?: setAIds
        val keyById = key.keyById
        val metaById = key.metaById

        val pcts = results.map { r -> if (r.total > 0) r.score.toDouble() / r.total * 100.0 else 0.0 }
        val takers = results.size
        val avg = if (pcts.isNotEmpty()) pcts.average() else 0.0
        val median = median(pcts)
        val min = pcts.minOrNull() ?: 0.0
        val max = pcts.maxOrNull() ?: 0.0
        val passRate = if (takers > 0) results.count { it.passed } * 100.0 / takers else 0.0
        val distribution = buildDistribution(pcts)

        data class Acc(var correct: Int = 0, var answered: Int = 0, val dist: HashMap<Char, Int> = HashMap())
        val accById = HashMap<Long, Acc>()

        for (r in results) {
            val ids = if (r.testSet.equals("B", ignoreCase = true)) setBIds else setAIds
            val raw = r.rawAnswers
            val n = minOf(MAX_QUESTIONS, ids.size, raw.length)
            for (i in 0 until n) {
                val qid = ids[i]
                val detected = raw[i].uppercaseChar()
                val acc = accById.getOrPut(qid) { Acc() }
                acc.dist[detected] = (acc.dist[detected] ?: 0) + 1
                if (detected in 'A'..'E') {
                    acc.answered++
                    if (detected == keyById[qid]) acc.correct++
                }
            }
        }

        val questions = setAIds.take(MAX_QUESTIONS).mapIndexed { index, qid ->
            val acc = accById[qid]
            val dist = acc?.dist ?: HashMap()
            val keyLetter = keyById[qid]
            val meta = metaById[qid]
            val options = listOf(
                'A' to (meta?.optionA ?: ""),
                'B' to (meta?.optionB ?: ""),
                'C' to (meta?.optionC ?: ""),
                'D' to (meta?.optionD ?: ""),
            ).map { (letter, text) ->
                QuestionOption(
                    letter = letter,
                    text = text,
                    chosenCount = dist[letter] ?: 0,
                    isCorrect = letter == keyLetter,
                )
            }
            QuestionStat(
                number = index + 1,
                questionId = qid,
                questionText = meta?.text ?: "(question text unavailable)",
                keyLetter = keyLetter,
                options = options,
                correctCount = acc?.correct ?: 0,
                answeredCount = acc?.answered ?: 0,
                totalTakers = takers,
                blankCount = dist['-'] ?: 0,
                multiCount = dist['?'] ?: 0,
            )
        }

        return ExamStats(
            examId = examId,
            examName = key.examName,
            subject = subject,
            takers = takers,
            avgPct = avg,
            medianPct = median,
            minPct = min,
            maxPct = max,
            passRate = passRate,
            distribution = distribution,
            questions = questions,
        )
    }

    /**
     * The positional answer-key string for one paper (Set A/B), aligned to a scan's `rawAnswers`
     * so the per-item grid can color correct/wrong. Reuses the same key reconstruction as the
     * stats computation (live exam, or archived snapshot when the exam was deleted).
     * Returns null only when the exam can't be resolved at all.
     */
    suspend fun answerKeyFor(subject: String, examId: Long, testSet: String): String? {
        val key = resolveExamKey(subject, examId) ?: return null
        val ids = if (testSet.equals("B", ignoreCase = true)) {
            key.setBIds.takeIf { it.isNotEmpty() } ?: key.setAIds
        } else key.setAIds
        return ids.take(MAX_QUESTIONS).joinToString("") { (key.keyById[it]?.toString() ?: "-") }
    }

    /** Resolve the exam's key data from the live exam, or — if deleted — its archived snapshot. */
    private suspend fun resolveExamKey(subject: String, examId: Long): ExamKey? {
        val liveExam = examRepository.getExamsBySubjectOnce(subject).firstOrNull { it.id == examId }
        if (liveExam != null) {
            // Resolve each id to the FIRST question in the subject's bank order — identical to the
            // printed PDF / scanner resolution.
            val subjectQuestions = questionRepository.getAllQuestionsForSubjectOnce(subject)
            val (keyById, metaById) = buildMaps(
                subjectQuestions.map { Triple(it.id, it.correctAnswer, QMeta(it.questionText, it.optionA, it.optionB, it.optionC, it.optionD)) }
            )
            return ExamKey(liveExam.examName, liveExam.setAQuestionIds, liveExam.setBQuestionIds, keyById, metaById)
        }

        val archived = examRepository.getArchivedExamById(examId) ?: return null
        val (keyById, metaById) = buildMaps(
            archived.questions.map { Triple(it.id, it.correctAnswer, QMeta(it.questionText, it.optionA, it.optionB, it.optionC, it.optionD)) }
        )
        return ExamKey(archived.examName, archived.setAQuestionIds, archived.setBQuestionIds, keyById, metaById)
    }

    /** Build the id→key and id→metadata maps, keeping the first occurrence of each question id. */
    private fun buildMaps(rows: List<Triple<Long, String?, QMeta>>): Pair<Map<Long, Char?>, Map<Long, QMeta>> {
        val keyById = HashMap<Long, Char?>()
        val metaById = HashMap<Long, QMeta>()
        for ((id, correctAnswer, meta) in rows) {
            if (!keyById.containsKey(id)) {
                keyById[id] = correctAnswer?.trim()?.uppercase()?.firstOrNull()?.takeIf { it in 'A'..'E' }
                metaById[id] = meta
            }
        }
        return keyById to metaById
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Ten percentage buckets (0–9 … 90–100), 100 folded into the top bucket. */
    private fun buildDistribution(pcts: List<Double>): List<ScoreBucket> {
        val counts = IntArray(10)
        for (p in pcts) {
            val idx = (p / 10.0).toInt().coerceIn(0, 9)
            counts[idx]++
        }
        return (0..9).map { i ->
            val lo = i * 10
            val hi = if (i == 9) 100 else i * 10 + 9
            ScoreBucket(label = "$lo–$hi%", count = counts[i])
        }
    }
}
