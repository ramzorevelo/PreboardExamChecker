package com.pbec.preboardexamchecker.utils

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject

/** One parsed roster line. Gender comes from MALE/FEMALE section headers, not a column. */
data class RosterStudent(
    val name: String,
    val studentId: String,
    val block: String,
    val email: String,
    val program: String,
    val instructor: String,
    val schoolYear: String,
    val gender: String,
)

/**
 * Parses the institutional "Name of Students" roster.
 *
 * Layout: number | Name | Student ID | Block, with MALE / FEMALE section-header rows splitting the
 * list. The Name column has NO header (its header cell holds the MALE/FEMALE label), so the header
 * row is found by its "Student ID" cell and Name is taken as the column immediately to its left.
 * Program, Instructor and School Year are single sheet-level cells (one per block) sitting one row
 * BELOW their labels, read once and applied to every student. No year level (preboard is 4th-year).
 *
 * The RESULTS sheet is skipped: its rows are cross-sheet formulas (='Name of Students'!B6) that
 * would otherwise be read as ~100 bogus students.
 */
class RosterExcelParser @Inject constructor() {

    fun parse(context: Context, uri: Uri): List<RosterStudent> =
        context.contentResolver.openInputStream(uri)?.use { parse(it) }
            ?: throw IllegalArgumentException("Could not open roster file")

    /** Parses an open workbook stream. Separated from the Uri path so it is JVM-unit-testable. */
    fun parse(stream: InputStream): List<RosterStudent> {
        val out = mutableListOf<RosterStudent>()
        val fmt = DataFormatter()
        XSSFWorkbook(stream).use { wb ->
            for (s in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(s)
                if (sheet.sheetName.lowercase(Locale.ROOT).contains("result")) continue
                parseSheet(sheet, fmt, out)
            }
        }
        return out
    }

    private class Header(
        val rowIdx: Int,
        val nameCol: Int,
        val idCol: Int,
        val blockCol: Int,
        val emailCol: Int,
    )

    private fun parseSheet(sheet: Sheet, fmt: DataFormatter, out: MutableList<RosterStudent>) {
        val header = findHeader(sheet, fmt) ?: return
        val meta = findMetadata(sheet, fmt)

        // The first section's MALE label shares the header row (alongside "Student ID"), so seed
        // from it; later FEMALE sits on its own row and is picked up in the loop.
        var gender = sheet.getRow(header.rowIdx)?.let { sectionGender(it, fmt) }.orEmpty()
        for (r in (header.rowIdx + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val section = sectionGender(row, fmt)
            if (section != null) { gender = section; continue }

            val name = cellAt(row, header.nameCol, fmt)
            val studentId = cellAt(row, header.idCol, fmt)
            // A real student row needs a name + ID; skip banners, spacers, and any stray formula.
            if (name.isBlank() || studentId.isBlank()) continue
            if (name.startsWith("=") || name.equals("name of students", ignoreCase = true)) continue

            out.add(
                RosterStudent(
                    name = name,
                    studentId = studentId,
                    block = if (header.blockCol >= 0) cellAt(row, header.blockCol, fmt) else "",
                    email = if (header.emailCol >= 0) cellAt(row, header.emailCol, fmt) else "",
                    program = meta.program,
                    instructor = meta.instructor,
                    schoolYear = meta.schoolYear,
                    gender = gender,
                )
            )
        }
    }

    /** Locate the data header by its "Student ID" cell; Name is the column just left of it. */
    private fun findHeader(sheet: Sheet, fmt: DataFormatter): Header? {
        val limit = minOf(sheet.lastRowNum, 20)
        for (r in 0..limit) {
            val row = sheet.getRow(r) ?: continue
            var idCol = -1
            var blockCol = -1
            var nameCol = -1
            var emailCol = -1
            for (c in 0 until row.lastCellNum.toInt().coerceAtLeast(0)) {
                when (fmt.formatCellValue(row.getCell(c)).trim().lowercase(Locale.ROOT)) {
                    "student id", "id", "student no", "student number" -> if (idCol < 0) idCol = c
                    "block", "section" -> if (blockCol < 0) blockCol = c
                    "name", "name of students", "student name" -> if (nameCol < 0) nameCol = c
                    "email", "e-mail", "email address" -> if (emailCol < 0) emailCol = c
                }
            }
            if (idCol >= 0) {
                if (nameCol < 0) nameCol = (idCol - 1).coerceAtLeast(0)
                return Header(r, nameCol, idCol, blockCol, emailCol)
            }
        }
        return null
    }

    private class Metadata(val program: String, val instructor: String, val schoolYear: String)

    /** Program / Instructor / School Year are the cells directly under their labels (one per block). */
    private fun findMetadata(sheet: Sheet, fmt: DataFormatter): Metadata {
        var program = ""
        var instructor = ""
        var schoolYear = ""
        val limit = minOf(sheet.lastRowNum, 20)
        for (r in 0..limit) {
            val row = sheet.getRow(r) ?: continue
            for (c in 0 until row.lastCellNum.toInt().coerceAtLeast(0)) {
                val h = fmt.formatCellValue(row.getCell(c)).trim().lowercase(Locale.ROOT)
                when {
                    program.isBlank() && h.contains("program") -> program = below(sheet, r, c, fmt)
                    instructor.isBlank() && h.contains("instructor") -> instructor = below(sheet, r, c, fmt)
                    schoolYear.isBlank() && (h.contains("school year") || h.contains("school yr") || h == "sy") ->
                        schoolYear = below(sheet, r, c, fmt)
                }
            }
        }
        return Metadata(program, instructor, schoolYear)
    }

    private fun below(sheet: Sheet, r: Int, c: Int, fmt: DataFormatter): String =
        sheet.getRow(r + 1)?.let { cellAt(it, c, fmt) }.orEmpty()

    /** "MALE"/"FEMALE" anywhere in an otherwise-label row marks the start of a gendered section. */
    private fun sectionGender(row: Row, fmt: DataFormatter): String? {
        for (c in 0 until row.lastCellNum.toInt().coerceAtLeast(0)) {
            when (fmt.formatCellValue(row.getCell(c)).trim().uppercase(Locale.ROOT)) {
                "MALE" -> return "MALE"
                "FEMALE" -> return "FEMALE"
            }
        }
        return null
    }

    private fun cellAt(row: Row, col: Int, fmt: DataFormatter): String =
        fmt.formatCellValue(row.getCell(col)).trim()
}
