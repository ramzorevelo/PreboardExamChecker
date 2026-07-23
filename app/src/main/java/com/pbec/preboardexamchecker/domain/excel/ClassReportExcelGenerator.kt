package com.pbec.preboardexamchecker.domain.excel

import android.content.Context
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.model.SummaryStatus
import com.pbec.preboardexamchecker.domain.pdf.ReportPdfStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Per-block class roster as an .xlsx workbook (mirrors the class-report PDF columns). */
@Singleton
class ClassReportExcelGenerator @Inject constructor() {

    suspend fun generate(context: Context, rows: List<StudentGwaRow>, blockLabel: String): File =
        withContext(Dispatchers.IO) {
            val safeBlock = blockLabel.ifBlank { "all" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(context.cacheDir, "class_report_${safeBlock}_${System.currentTimeMillis()}.xlsx")

            val wb = XSSFWorkbook()
            try {
                val sheet = wb.createSheet("Class Report")
                val subjects = ReportPdfStyle.subjectOrder

                sheet.createRow(0).apply {
                    var c = 0
                    createCell(c++).setCellValue("#")
                    createCell(c++).setCellValue("Student")
                    createCell(c++).setCellValue("ID")
                    subjects.forEach { createCell(c++).setCellValue(ReportPdfStyle.shortLabel(it) + " %") }
                    createCell(c++).setCellValue("GWA")
                    createCell(c).setCellValue("Remark")
                }

                rows.forEachIndexed { index, row ->
                    sheet.createRow(index + 1).apply {
                        var c = 0
                        createCell(c++).setCellValue((index + 1).toDouble())
                        createCell(c++).setCellValue(row.name)
                        createCell(c++).setCellValue(row.studentId)
                        subjects.forEach { subject ->
                            val line = row.lines.firstOrNull { it.subject == subject }
                            val cell = createCell(c++)
                            if (line != null) cell.setCellValue(line.percentage) else cell.setCellValue("—")
                        }
                        val gwaCell = createCell(c++)
                        if (row.status == SummaryStatus.INCOMPLETE) gwaCell.setCellValue("—")
                        else gwaCell.setCellValue(row.gwa)
                        createCell(c).setCellValue(ReportPdfStyle.statusText(row.status))
                    }
                }

                val pass = rows.count { it.status == SummaryStatus.PASS }
                val fail = rows.count { it.status == SummaryStatus.FAIL }
                val incomplete = rows.count { it.status == SummaryStatus.INCOMPLETE }
                sheet.createRow(rows.size + 2).apply {
                    createCell(0).setCellValue("Summary")
                    createCell(1).setCellValue(
                        "Students: ${rows.size}, Pass: $pass, Fail: $fail, Incomplete: $incomplete"
                    )
                }

                FileOutputStream(outFile).use { wb.write(it) }
            } finally {
                wb.close()
            }
            outFile
        }
}
