package com.pbec.preboardexamchecker.utils

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** JVM unit tests for [RosterExcelParser]: header location, MALE/FEMALE sections, blank rows. */
class RosterExcelParserTest {

    private fun workbookBytes(build: (XSSFWorkbook) -> Unit): ByteArray {
        val wb = XSSFWorkbook()
        build(wb)
        val out = ByteArrayOutputStream()
        wb.use { it.write(out) }
        return out.toByteArray()
    }

    @Test
    fun parsesRealLayoutSkipsResultsAndBlanks() {
        val bytes = workbookBytes { wb ->
            val s = wb.createSheet("Name of Students")
            s.createRow(0).createCell(0).setCellValue("PREBOARD EXAMINATION RECORDS")
            // Program / Instructor / School Year are single cells one row below their labels.
            s.createRow(2).apply {
                createCell(4).setCellValue("PROGRAM")
                createCell(5).setCellValue("INSTRUCTOR")
                createCell(6).setCellValue("SCHOOL YEAR")
            }
            s.createRow(3).apply {
                createCell(4).setCellValue("BSEE")
                createCell(5).setCellValue("Dela Cruz")
                createCell(6).setCellValue("2025-2026")
            }
            // Header row: only "Student ID" + "Block"; Name has NO header (col B, left of ID).
            s.createRow(4).apply {
                createCell(0).setCellValue("MALE")
                createCell(2).setCellValue("Student ID")
                createCell(3).setCellValue("Block")
            }
            s.createRow(5).apply {
                createCell(0).setCellValue(1.0)
                createCell(1).setCellValue("Banico, Jessa")
                createCell(2).setCellValue("119046")
                createCell(3).setCellValue("A")
            }
            s.createRow(6) // blank spacer row — must be ignored
            // FEMALE section repeats the Student ID header — must not be read as a student.
            s.createRow(7).apply {
                createCell(0).setCellValue("FEMALE")
                createCell(2).setCellValue("Student ID")
            }
            s.createRow(8).apply {
                createCell(0).setCellValue(1.0)
                createCell(1).setCellValue("Anonuevo, Micah")
                createCell(2).setCellValue("210332")
                createCell(3).setCellValue("B")
            }

            // RESULTS sheet with cross-sheet formulas — must be skipped, not read as 100 students.
            val results = wb.createSheet("RESULTS")
            results.createRow(1).apply {
                createCell(1).setCellValue("Name of Students")
                createCell(2).setCellValue("Student ID")
            }
            for (i in 2..50) results.createRow(i).apply {
                createCell(1).cellFormula = "'Name of Students'!B${i + 4}"
                createCell(2).cellFormula = "'Name of Students'!C${i + 4}"
            }
        }

        val rows = RosterExcelParser().parse(ByteArrayInputStream(bytes))

        assertEquals(2, rows.size)
        rows[0].let {
            assertEquals("Banico, Jessa", it.name)
            assertEquals("119046", it.studentId)
            assertEquals("A", it.block)
            assertEquals("BSEE", it.program)
            assertEquals("Dela Cruz", it.instructor)
            assertEquals("2025-2026", it.schoolYear)
            assertEquals("MALE", it.gender)
            assertEquals("", it.email) // no Email column -> blank
        }
        rows[1].let {
            assertEquals("Anonuevo, Micah", it.name)
            assertEquals("210332", it.studentId)
            assertEquals("B", it.block)
            assertEquals("BSEE", it.program)
            assertEquals("FEMALE", it.gender)
        }
    }

    @Test
    fun parsesEmailColumnWhenPresent() {
        val bytes = workbookBytes { wb ->
            val s = wb.createSheet("Name of Students")
            // Header: Student ID | Block | Email (Name has no header, sits left of Student ID).
            s.createRow(0).apply {
                createCell(2).setCellValue("Student ID")
                createCell(3).setCellValue("Block")
                createCell(4).setCellValue("Email")
            }
            s.createRow(1).apply {
                createCell(1).setCellValue("Banico, Jessa")
                createCell(2).setCellValue("119046")
                createCell(3).setCellValue("A")
                createCell(4).setCellValue("jessa@example.com")
            }
        }

        val rows = RosterExcelParser().parse(ByteArrayInputStream(bytes))

        assertEquals(1, rows.size)
        assertEquals("jessa@example.com", rows[0].email)
    }
}
