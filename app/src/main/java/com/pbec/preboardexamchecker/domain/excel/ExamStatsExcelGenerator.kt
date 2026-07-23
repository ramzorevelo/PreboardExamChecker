package com.pbec.preboardexamchecker.domain.excel

import android.content.Context
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.domain.model.ExamStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Exam statistics as an .xlsx workbook: Summary, Per-Question, and per-taker Scores sheets. */
@Singleton
class ExamStatsExcelGenerator @Inject constructor() {

    suspend fun generate(context: Context, stats: ExamStats, takerResults: List<ScanResult>): File =
        withContext(Dispatchers.IO) {
            val safe = "${stats.subject}_${stats.examName}".replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(context.cacheDir, "exam_stats_${safe}_${System.currentTimeMillis()}.xlsx")

            val wb = XSSFWorkbook()
            try {
                val summary = wb.createSheet("Summary")
                var r = 0
                fun kv(label: String, value: String) {
                    val row = summary.createRow(r++)
                    row.createCell(0).setCellValue(label)
                    row.createCell(1).setCellValue(value)
                }
                kv("Subject", stats.subject)
                kv("Exam", stats.examName)
                kv("Takers", stats.takers.toString())
                kv("Average %", "%.2f".format(stats.avgPct))
                kv("Median %", "%.2f".format(stats.medianPct))
                kv("Highest %", "%.2f".format(stats.maxPct))
                kv("Lowest %", "%.2f".format(stats.minPct))
                kv("Pass rate %", "%.2f".format(stats.passRate))
                r++
                summary.createRow(r++).apply {
                    createCell(0).setCellValue("Range")
                    createCell(1).setCellValue("Count")
                }
                for (b in stats.distribution) {
                    summary.createRow(r++).apply {
                        createCell(0).setCellValue(b.label)
                        createCell(1).setCellValue(b.count.toDouble())
                    }
                }

                val pq = wb.createSheet("Per-Question")
                val letters = listOf('A', 'B', 'C', 'D')
                pq.createRow(0).apply {
                    val headers = mutableListOf("#", "Question", "Key", "% Correct", "Correct", "Wrong", "Answered")
                    letters.forEach { headers.add("$it. choice"); headers.add("$it picked") }
                    headers.add("Blank"); headers.add("Multi")
                    headers.forEachIndexed { i, h -> createCell(i).setCellValue(h) }
                }
                stats.questions.forEachIndexed { idx, q ->
                    pq.createRow(idx + 1).apply {
                        var c = 0
                        createCell(c++).setCellValue(q.number.toDouble())
                        createCell(c++).setCellValue(q.questionText)
                        createCell(c++).setCellValue(q.keyLetter?.toString() ?: "")
                        createCell(c++).setCellValue(q.correctPct)
                        createCell(c++).setCellValue(q.correctCount.toDouble())
                        createCell(c++).setCellValue(q.wrongCount.toDouble())
                        createCell(c++).setCellValue(q.answeredCount.toDouble())
                        val byLetter = q.options.associateBy { it.letter }
                        letters.forEach { letter ->
                            val opt = byLetter[letter]
                            // Tag the correct choice so the key is identifiable from this sheet alone.
                            val text = opt?.text.orEmpty().let { if (opt?.isCorrect == true) "$it  ✓" else it }
                            createCell(c++).setCellValue(text)
                            createCell(c++).setCellValue((opt?.chosenCount ?: 0).toDouble())
                        }
                        createCell(c++).setCellValue(q.blankCount.toDouble())
                        createCell(c).setCellValue(q.multiCount.toDouble())
                    }
                }

                val scores = wb.createSheet("Scores")
                // Exam name is written into the sheet (not just the file name) so it survives a rename.
                scores.createRow(0).apply {
                    createCell(0).setCellValue("Exam")
                    createCell(1).setCellValue("${stats.subject} — ${stats.examName}")
                }
                scores.createRow(1).apply {
                    listOf("Student", "ID", "Block", "Set", "Score", "Total", "%", "Passed")
                        .forEachIndexed { i, h -> createCell(i).setCellValue(h) }
                }
                takerResults.forEachIndexed { idx, s ->
                    val pct = if (s.total > 0) s.score.toDouble() / s.total * 100.0 else 0.0
                    scores.createRow(idx + 2).apply {
                        createCell(0).setCellValue(s.studentName)
                        createCell(1).setCellValue(s.studentId)
                        createCell(2).setCellValue(s.studentBlock)
                        createCell(3).setCellValue(s.testSet)
                        createCell(4).setCellValue(s.score.toDouble())
                        createCell(5).setCellValue(s.total.toDouble())
                        createCell(6).setCellValue(pct)
                        createCell(7).setCellValue(if (s.passed) "Yes" else "No")
                    }
                }

                FileOutputStream(outFile).use { wb.write(it) }
            } finally {
                wb.close()
            }
            outFile
        }
}
