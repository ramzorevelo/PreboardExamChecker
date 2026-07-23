package com.pbec.preboardexamchecker.domain.excel

import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a blank "PreBoard Examination Record" template (roster + RESULTS sheets) with two generic
 * sample students, so users can see the exact import layout: number | Name | Student ID | Block,
 * MALE/FEMALE section rows, and the single PROGRAM/INSTRUCTOR/SCHOOL YEAR metadata cells one row
 * below their labels. Mirrors what [RosterExcelParser] expects and [PreboardRecordExcelGenerator]
 * emits.
 */
@Singleton
class RosterTemplateGenerator @Inject constructor() {

    fun write(out: OutputStream) {
        XSSFWorkbook().use { wb ->
            writeRoster(wb)
            writeResults(wb)
            wb.write(out)
        }
    }

    private fun writeRoster(wb: XSSFWorkbook) {
        val s = wb.createSheet("Name of Students")
        s.createRow(0).createCell(0).setCellValue("PREBOARD EXAMINATION RECORDS")
        // Metadata sits right of the new Email column (E): PROGRAM/INSTRUCTOR/SCHOOL YEAR at F/G/H.
        s.createRow(2).apply {
            createCell(5).setCellValue("PROGRAM")
            createCell(6).setCellValue("INSTRUCTOR")
            createCell(7).setCellValue("SCHOOL YEAR")
        }
        s.createRow(3).apply {
            createCell(5).setCellValue("BSEE")
            createCell(6).setCellValue("Engr. Juan Dela Cruz")
            createCell(7).setCellValue("2025-2026")
        }
        // MALE section: gender label shares the row with the Student ID / Block / Email headers
        // (Name has none).
        s.createRow(5).apply {
            createCell(0).setCellValue("MALE")
            createCell(2).setCellValue("Student ID")
            createCell(3).setCellValue("Block")
            createCell(4).setCellValue("Email")
        }
        s.createRow(6).apply {
            createCell(0).setCellValue(1.0); createCell(1).setCellValue("Doe, John")
            createCell(2).setCellValue("210001"); createCell(3).setCellValue("A")
            createCell(4).setCellValue("john.doe@example.com")
        }
        s.createRow(8).apply {
            createCell(0).setCellValue("FEMALE")
            createCell(2).setCellValue("Student ID")
            createCell(3).setCellValue("Block")
            createCell(4).setCellValue("Email")
        }
        s.createRow(9).apply {
            createCell(0).setCellValue(1.0); createCell(1).setCellValue("Doe, Jane")
            createCell(2).setCellValue("210002"); createCell(3).setCellValue("B")
            createCell(4).setCellValue("jane.doe@example.com")
        }
    }

    private fun writeResults(wb: XSSFWorkbook) {
        val s = wb.createSheet("RESULTS")
        s.createRow(1).apply {
            createCell(1).setCellValue("Name of Students")
            createCell(2).setCellValue("Student ID")
            createCell(3).setCellValue("Block")
            createCell(4).setCellValue("MATHEMATICS")
            createCell(6).setCellValue("ENGINEERING SCIENCE AND ALLIED SUBJECTS")
            createCell(8).setCellValue("PROFESSIONAL SUBJECTS")
            createCell(10).setCellValue("RATING")
            createCell(11).setCellValue("REMARKS")
        }
        listOf(4 to 5, 6 to 7, 8 to 9).forEach { (a, b) -> s.addMergedRegion(CellRangeAddress(1, 1, a, b)) }

        // Two sample rows pulling name/ID/block from the roster sheet, with the same scoring formulas.
        listOf(6, 9).forEachIndexed { i, rosterRow ->
            val n = i + 3                 // RESULTS data rows 3,4 (1-based)
            s.createRow(n - 1).apply {
                createCell(0).cellFormula = "'Name of Students'!A$rosterRow"
                createCell(1).cellFormula = "'Name of Students'!B$rosterRow"
                createCell(2).cellFormula = "'Name of Students'!C$rosterRow"
                createCell(3).cellFormula = "'Name of Students'!D$rosterRow"
                createCell(5).cellFormula = "(E$n/100)*100"
                createCell(7).cellFormula = "(G$n/100)*100"
                createCell(9).cellFormula = "(I$n/100)*100"
                createCell(10).cellFormula = "(0.25*F$n)+(0.3*H$n)+(0.45*J$n)"
            }
        }
    }
}
