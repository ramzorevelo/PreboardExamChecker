package com.pbec.preboardexamchecker.domain.pdf

import android.content.Context
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.pbec.preboardexamchecker.domain.model.ExamStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Exam statistics report: summary, score distribution, and per-question item analysis. */
@Singleton
class ExamStatsPdfGenerator @Inject constructor() {

    suspend fun generate(context: Context, stats: ExamStats): File = withContext(Dispatchers.IO) {
        val safe = "${stats.subject}_${stats.examName}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "exam_stats_${safe}_${System.currentTimeMillis()}.pdf")

        val bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD)
        val normal = PdfFontFactory.createFont(StandardFonts.HELVETICA)

        val pdf = PdfDocument(PdfWriter(outFile))
        val document = Document(pdf, PageSize(8.5f * 72f, 13f * 72f))
        try {
            document.add(
                Paragraph("Exam Statistics")
                    .setFont(bold).setFontSize(18f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(2f)
            )
            document.add(
                Paragraph("${stats.subject} · ${stats.examName}")
                    .setFont(normal).setFontSize(11f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8f)
            )
            document.add(
                Paragraph(
                    "Takers: ${stats.takers}    Average: ${"%.1f".format(stats.avgPct)}%    " +
                        "Median: ${"%.1f".format(stats.medianPct)}%    " +
                        "High: ${"%.0f".format(stats.maxPct)}%    Low: ${"%.0f".format(stats.minPct)}%    " +
                        "Pass rate: ${"%.1f".format(stats.passRate)}%"
                ).setFont(normal).setFontSize(10f).setMarginBottom(10f)
            )

            document.add(Paragraph("Score Distribution").setFont(bold).setFontSize(12f).setMarginBottom(4f))
            val distTable = Table(UnitValue.createPercentArray(floatArrayOf(30f, 20f, 50f))).useAllAvailableWidth()
            distTable.addHeaderCell(headerCell("Range", bold))
            distTable.addHeaderCell(headerCell("Count", bold))
            distTable.addHeaderCell(headerCell("", bold))
            val maxBucket = (stats.distribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
            for (b in stats.distribution) {
                distTable.addCell(bodyCell(b.label, normal))
                distTable.addCell(bodyCell(b.count.toString(), normal, TextAlignment.CENTER))
                val bars = "█".repeat((b.count * 30 / maxBucket))
                distTable.addCell(bodyCell(bars, normal))
            }
            document.add(distTable.setMarginBottom(12f))

            document.add(Paragraph("Per-Question Analysis").setFont(bold).setFontSize(12f).setMarginBottom(4f))
            val qTable = Table(
                UnitValue.createPercentArray(floatArrayOf(6f, 34f, 8f, 11f, 11f, 7.5f, 7.5f, 7.5f, 7.5f))
            ).useAllAvailableWidth()
            listOf("#", "Question", "Key", "Correct", "Wrong", "A", "B", "C", "D").forEach {
                qTable.addHeaderCell(headerCell(it, bold))
            }
            for (q in stats.questions) {
                qTable.addCell(bodyCell(q.number.toString(), normal, TextAlignment.CENTER))
                qTable.addCell(bodyCell(q.questionText.take(120), normal))
                qTable.addCell(bodyCell(q.keyLetter?.toString() ?: "—", normal, TextAlignment.CENTER))
                qTable.addCell(bodyCell(q.correctCount.toString(), normal, TextAlignment.CENTER))
                qTable.addCell(bodyCell(q.wrongCount.toString(), normal, TextAlignment.CENTER))
                for (opt in q.options) {
                    qTable.addCell(bodyCell(opt.chosenCount.toString(), normal, TextAlignment.CENTER))
                }
            }
            document.add(qTable)

            val generatedOn = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
            document.add(Paragraph("Generated $generatedOn").setFont(normal).setFontSize(8f).setMarginTop(10f))
        } finally {
            document.close()
        }
        outFile
    }

    private fun headerCell(text: String, font: com.itextpdf.kernel.font.PdfFont) = Cell().add(
        Paragraph(text).setFont(font).setFontSize(9f).setFontColor(ReportPdfStyle.headerText)
    ).setBackgroundColor(ReportPdfStyle.headerFill).setPadding(4f)

    private fun bodyCell(
        text: String,
        font: com.itextpdf.kernel.font.PdfFont,
        align: TextAlignment = TextAlignment.LEFT,
    ) = Cell().add(Paragraph(text).setFont(font).setFontSize(9f).setTextAlignment(align)).setPadding(3f)
}
