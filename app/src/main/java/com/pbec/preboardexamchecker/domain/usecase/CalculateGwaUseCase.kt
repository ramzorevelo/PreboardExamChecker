package com.pbec.preboardexamchecker.domain.usecase

import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringConfig
import javax.inject.Inject

/** One subject's raw result for a student. */
data class SubjectScore(
    val subject: String,
    val score: Int,
    val total: Int,
)

/** Per-subject line in a GWA breakdown. */
data class SubjectGwaLine(
    val subject: String,
    val score: Int,
    val total: Int,
    val percentage: Double,   // 0..100
    val weight: Double,       // 0..1 (0 if subject has no configured weight)
    val meetsFloor: Boolean,  // percentage/100 >= SUBJECT_PASS_FLOOR
)

/** Full GWA result for one student. */
data class GwaResult(
    val lines: List<SubjectGwaLine>,
    val gwa: Double,                 // weighted average, 0..100
    val allSubjectsMeetFloor: Boolean,
    val complete: Boolean,           // all weighted subjects present
    val passed: Boolean,             // gwa >= passing AND no subject below floor AND complete
)

/**
 * Computes a student's General Weighted Average and pass/fail per [ScoringConfig].
 *
 * Logic/storage layer only — not yet wired to UI. The future per-student / per-block summary
 * screen (and its PDF export) will feed this the student's per-subject [SubjectScore]s, which are
 * derived by grouping persisted ScanResults by studentId (see ScanResultDao.getResultsForStudent).
 */
class CalculateGwaUseCase @Inject constructor() {

    operator fun invoke(scores: List<SubjectScore>): GwaResult {
        val lines = scores.map { s ->
            val pct = if (s.total > 0) s.score.toDouble() / s.total * 100.0 else 0.0
            val weight = ScoringConfig.SUBJECT_WEIGHTS[s.subject] ?: 0.0
            SubjectGwaLine(
                subject = s.subject,
                score = s.score,
                total = s.total,
                percentage = pct,
                weight = weight,
                meetsFloor = pct / 100.0 >= ScoringConfig.SUBJECT_PASS_FLOOR,
            )
        }

        val gwa = lines.sumOf { it.percentage * it.weight }

        // Complete requires every configured subject present, not just the scanned ones.
        val presentWeighted = lines.filter { it.weight > 0.0 }.map { it.subject }.toSet()
        val complete = ScoringConfig.SUBJECT_WEIGHTS.keys.all { it in presentWeighted }

        val allMeetFloor = lines.all { it.meetsFloor }
        val passed = complete && allMeetFloor && gwa / 100.0 >= ScoringConfig.GWA_PASSING_AVERAGE

        return GwaResult(
            lines = lines,
            gwa = gwa,
            allSubjectsMeetFloor = allMeetFloor,
            complete = complete,
            passed = passed,
        )
    }
}
