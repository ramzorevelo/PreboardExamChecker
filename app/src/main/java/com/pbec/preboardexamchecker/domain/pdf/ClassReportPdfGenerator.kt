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
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.model.SummaryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-block class report: a roster table of students with their per-subject ratings, GWA and
 * final remark, plus a summary line. Written to the app cache and returned as a [File].
 */
@Singleton
class ClassReportPdfGenerator @Inject constructor() {

    suspend fun generate(
        context: Context,
        rows: List<StudentGwaRow>,
        blockLabel: String,
    ): File = withContext(Dispatchers.IO) {
        val safeBlock = blockLabel.ifBlank { "All" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "class_report_${safeBlock}_${System.currentTimeMillis()}.pdf")

        val bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD)
        val normal = PdfFontFactory.createFont(StandardFonts.HELVETICA)

        val pdf = PdfDocument(PdfWriter(outFile))
        // Long bond landscape; the roster table needs the extra width.
        val document = Document(pdf, PageSize(13f * 72f, 8.5f * 72f))
        try {
            document.add(
                Paragraph("Class Summary Report")
                    .setFont(bold).setFontSize(18f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(2f)
            )
            document.add(
                Paragraph("Block: ${blockLabel.ifBlank { "All blocks" }}")
                    .setFont(normal).setFontSize(11f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(0f)
            )
            val generatedOn = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
            document.add(
                Paragraph("Generated $generatedOn")
                    .setFont(normal).setFontSize(9f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8f)
            )

            val pass = rows.count { it.status == SummaryStatus.PASS }
            val fail = rows.count { it.status == SummaryStatus.FAIL }
            val incomplete = rows.count { it.status == SummaryStatus.INCOMPLETE }
            val passRate = if (rows.isNotEmpty()) pass * 100.0 / rows.size else 0.0
            document.add(
                Paragraph(
                    "Students: ${rows.size}    Passed: $pass    Failed: $fail    " +
                        "Incomplete: $incomplete    Pass rate: ${"%.1f".format(passRate)}%"
                ).setFont(normal).setFontSize(10f).setMarginBottom(10f)
            )

            val subjects = ReportPdfStyle.subjectOrder
            val widths = floatArrayOf(4f, 26f, 12f) +
                FloatArray(subjects.size) { 12f } +
                floatArrayOf(10f, 14f)
            val table = Table(UnitValue.createPercentArray(widths)).useAllAvailableWidth()

            fun headerCell(text: String) = Cell().add(
                Paragraph(text).setFont(bold).setFontSize(9f).setFontColor(ReportPdfStyle.headerText)
            ).setBackgroundColor(ReportPdfStyle.headerFill).setPadding(4f)

            table.addHeaderCell(headerCell("#"))
            table.addHeaderCell(headerCell("Student"))
            table.addHeaderCell(headerCell("ID"))
            subjects.forEach { table.addHeaderCell(headerCell(ReportPdfStyle.shortLabel(it))) }
            table.addHeaderCell(headerCell("GWA"))
            table.addHeaderCell(headerCell("Remark"))

            fun bodyCell(text: String, align: TextAlignment = TextAlignment.LEFT) = Cell().add(
                Paragraph(text).setFont(normal).setFontSize(9f).setTextAlignment(align)
            ).setPadding(3f)

            rows.forEachIndexed { index, row ->
                table.addCell(bodyCell("${index + 1}", TextAlignment.CENTER))
                table.addCell(bodyCell(row.name))
                table.addCell(bodyCell(row.studentId, TextAlignment.CENTER))
                subjects.forEach { subject ->
                    val line = row.lines.firstOrNull { it.subject == subject }
                    val cell = if (line == null) {
                        bodyCell("—", TextAlignment.CENTER)
                    } else {
                        val p = Paragraph("%.1f".format(line.percentage))
                            .setFont(normal).setFontSize(9f)
                            .setTextAlignment(TextAlignment.CENTER)
                        if (!line.meetsFloor) p.setFontColor(ReportPdfStyle.belowFloorText)
                        Cell().add(p).setPadding(3f)
                    }
                    table.addCell(cell)
                }
                table.addCell(
                    if (row.status == SummaryStatus.INCOMPLETE) bodyCell("—", TextAlignment.CENTER)
                    else bodyCell("%.2f".format(row.gwa), TextAlignment.CENTER)
                )
                table.addCell(
                    Cell().add(
                        Paragraph(ReportPdfStyle.statusText(row.status))
                            .setFont(bold).setFontSize(9f)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setFontColor(ReportPdfStyle.statusColor(row.status))
                    ).setPadding(3f)
                )
            }

            document.add(table)
        } finally {
            document.close()
        }
        outFile
    }
}
