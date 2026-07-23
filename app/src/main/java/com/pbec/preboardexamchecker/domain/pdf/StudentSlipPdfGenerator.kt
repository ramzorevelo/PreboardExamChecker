package com.pbec.preboardexamchecker.domain.pdf

import android.content.Context
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.LineSeparator
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.model.SummaryStatus
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-student result slip: header, per-subject breakdown, GWA and final remark.
 * Written to the app cache and returned as a [File].
 */
@Singleton
class StudentSlipPdfGenerator @Inject constructor() {

    suspend fun generate(context: Context, row: StudentGwaRow): File = withContext(Dispatchers.IO) {
        val safeName = row.name.ifBlank { row.studentId }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "student_slip_${safeName}_${System.currentTimeMillis()}.pdf")

        val bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD)
        val normal = PdfFontFactory.createFont(StandardFonts.HELVETICA)

        val pdf = PdfDocument(PdfWriter(outFile))
        val document = Document(pdf, PageSize(8.5f * 72f, 13f * 72f))
        try {
            render(document, row, bold, normal)
        } finally {
            document.close()
        }
        outFile
    }

    /** All slips in one PDF, a student per page; a single file shares to apps that reject multi-send. */
    suspend fun generate(context: Context, rows: List<StudentGwaRow>): File = withContext(Dispatchers.IO) {
        val first = rows.firstOrNull()
        val safeName = (first?.let { it.name.ifBlank { it.studentId } } ?: "slips")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "student_slips_${safeName}_${System.currentTimeMillis()}.pdf")

        val bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD)
        val normal = PdfFontFactory.createFont(StandardFonts.HELVETICA)

        val pdf = PdfDocument(PdfWriter(outFile))
        val document = Document(pdf, PageSize(8.5f * 72f, 13f * 72f))
        try {
            rows.forEachIndexed { i, row ->
                if (i > 0) document.add(AreaBreak())
                render(document, row, bold, normal)
            }
        } finally {
            document.close()
        }
        outFile
    }

    private fun render(
        document: Document,
        row: StudentGwaRow,
        bold: com.itextpdf.kernel.font.PdfFont,
        normal: com.itextpdf.kernel.font.PdfFont,
    ) {
        document.add(
                Paragraph("Student Result Slip")
                    .setFont(bold).setFontSize(18f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8f)
            )
            document.add(LineSeparator(SolidLine(0.8f)).setMarginBottom(8f))

            fun infoCell(labelText: String, valueText: String) = Cell()
                .add(Paragraph().add(label(bold, labelText)).add(value(normal, valueText)))
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .setPadding(2f)

            val blockYear = listOf(row.block, row.yearLevel).filter { it.isNotBlank() }.joinToString(" / ")
            val infoTable = Table(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
                .useAllAvailableWidth()
            infoTable.addCell(infoCell("Name: ", row.name))
            infoTable.addCell(infoCell("Student ID: ", row.studentId))
            infoTable.addCell(infoCell("Block/Year: ", blockYear.ifBlank { "—" }))
            infoTable.addCell(infoCell("Program: ", row.program.ifBlank { "—" }))
            document.add(infoTable)
            document.add(Paragraph(" ").setMarginBottom(14f))

            val table = Table(UnitValue.createPercentArray(floatArrayOf(40f, 24f, 18f, 18f)))
                .useAllAvailableWidth()

            fun headerCell(text: String) = Cell().add(
                Paragraph(text).setFont(bold).setFontSize(10f).setFontColor(ReportPdfStyle.headerText)
            ).setBackgroundColor(ReportPdfStyle.headerFill).setPadding(5f)

            table.addHeaderCell(headerCell("Subject"))
            table.addHeaderCell(headerCell("Rating"))
            table.addHeaderCell(headerCell("Weight"))
            table.addHeaderCell(headerCell("Remarks"))

            fun cell(text: String, align: TextAlignment = TextAlignment.LEFT) = Cell().add(
                Paragraph(text).setFont(normal).setFontSize(10f).setTextAlignment(align)
            ).setPadding(4f)

            for (subject in ReportPdfStyle.subjectOrder) {
                val line = row.lines.firstOrNull { it.subject == subject }
                table.addCell(cell(subject))
                if (line == null) {
                    table.addCell(cell("—", TextAlignment.CENTER))
                    val weight = ScoringConfig.SUBJECT_WEIGHTS[subject] ?: 0.0
                    table.addCell(cell("${(weight * 100).toInt()}%", TextAlignment.CENTER))
                    table.addCell(cell("Not taken", TextAlignment.CENTER))
                } else {
                    val rating = Paragraph("%.1f%%".format(line.percentage))
                        .setFont(normal).setFontSize(10f).setTextAlignment(TextAlignment.CENTER)
                    if (!line.meetsFloor) rating.setFontColor(ReportPdfStyle.belowFloorText)
                    table.addCell(Cell().add(rating).setPadding(4f))
                    table.addCell(cell("${(line.weight * 100).toInt()}%", TextAlignment.CENTER))
                    table.addCell(cell(if (line.meetsFloor) "Passed" else "Failed", TextAlignment.CENTER))
                }
            }
            document.add(table)

            document.add(Paragraph(" ").setMarginBottom(12f))
            document.add(
                Paragraph()
                    .add(label(bold, "General Weighted Average (GWA): "))
                    .add(
                        value(bold, if (row.status == SummaryStatus.INCOMPLETE) "—" else "%.2f%%".format(row.gwa))
                    )
                    .setFontSize(12f)
            )
            document.add(
                Paragraph()
                    .add(label(bold, "Result: "))
                    .add(
                        com.itextpdf.layout.element.Text(resultText(row.status))
                            .setFont(bold).setFontColor(ReportPdfStyle.statusColor(row.status))
                    )
                    .setFontSize(14f)
            )

            document.add(
                Paragraph(
                    "Passing requires a GWA of at least ${(ScoringConfig.GWA_PASSING_AVERAGE * 100).toInt()}% " +
                        "with no subject below ${(ScoringConfig.SUBJECT_PASS_FLOOR * 100).toInt()}%."
                ).setFont(normal).setFontSize(8f).setMarginTop(12f)
            )
            val generatedOn = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
            document.add(Paragraph("Generated $generatedOn").setFont(normal).setFontSize(8f))
    }

    /** Slip-local past-tense wording for the overall result (the shared report keeps PASS/FAIL). */
    private fun resultText(status: SummaryStatus): String = when (status) {
        SummaryStatus.PASS -> "Passed"
        SummaryStatus.FAIL -> "Failed"
        SummaryStatus.INCOMPLETE -> "Incomplete"
    }

    private fun label(font: com.itextpdf.kernel.font.PdfFont, text: String) =
        com.itextpdf.layout.element.Text(text).setFont(font)

    private fun value(font: com.itextpdf.kernel.font.PdfFont, text: String) =
        com.itextpdf.layout.element.Text(text).setFont(font)
}
