package com.pbec.preboardexamchecker.domain.excel

import android.content.Context
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A null percentage means the subject wasn't taken, leaving the score cell blank. */
data class PreboardRecordRow(
    val name: String,
    val studentId: String,
    val block: String,
    val email: String,
    val gender: String,        // "MALE" / "FEMALE" / ""
    val mathPct: Double?,
    val esasPct: Double?,
    val profPct: Double?,
    val remark: String,
)

/**
 * Institutional "PreBoard Examination Record" workbook (roster + RESULTS sheets).
 *
 * Clones the bundled [TEMPLATE_ASSET] instead of re-styling from scratch: the school's exact
 * formatting (Cooper Black title, theme-tinted fills, merged headers, column widths, Aptos Narrow
 * scores) is impractical to reproduce cell-by-cell, so the template's header area and cell styles are
 * kept and only the rows are swapped.
 *
 * The score cell holds the percentage (0..100), not the raw count, so the template's (x/100)*100 and
 * weighted RATING formulas survive a quiz that isn't 100 items. Weights are ScoringConfig's, so
 * RATING reproduces the app GWA.
 */
@Singleton
class PreboardRecordExcelGenerator @Inject constructor() {

    suspend fun generate(
        context: Context,
        clusterName: String,
        program: String,
        schoolYear: String,
        instructor: String,
        rows: List<PreboardRecordRow>,
    ): File = withContext(Dispatchers.IO) {
        val safe = clusterName.ifBlank { "cluster" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "preboard_record_${safe}_${System.currentTimeMillis()}.xlsx")

        // Submit order: MALE, FEMALE, ungendered; by name within a section.
        val ordered = rows.sortedWith(compareBy({ genderRank(it.gender) }, { it.name.lowercase() }))
        // Skip empty genders so no bare section header is emitted.
        val sections = listOf("MALE", "FEMALE", "OTHER").mapNotNull { label ->
            val group = ordered.filter { sectionLabel(it.gender) == label }
            if (group.isEmpty()) null else label to group
        }

        val wb = context.assets.open(TEMPLATE_ASSET).use { XSSFWorkbook(it) }
        try {
            val rosterRowOf = writeRosterSheet(wb, program, schoolYear, instructor, sections)
            writeResultsSheet(wb, sections, rosterRowOf)
            FileOutputStream(outFile).use { wb.write(it) }
        } finally {
            wb.close()
        }
        outFile
    }

    private fun genderRank(gender: String): Int = when (gender.uppercase()) {
        "MALE" -> 0
        "FEMALE" -> 1
        else -> 2
    }

    private fun sectionLabel(gender: String): String = when (gender.uppercase()) {
        "MALE" -> "MALE"
        "FEMALE" -> "FEMALE"
        else -> "OTHER"
    }

    /** Returns each student's 1-based roster row, so RESULTS can target it by cross-sheet formula. */
    private fun writeRosterSheet(
        wb: XSSFWorkbook,
        program: String,
        schoolYear: String,
        instructor: String,
        sections: List<Pair<String, List<PreboardRecordRow>>>,
    ): Map<PreboardRecordRow, Int> {
        val sheet = wb.getSheet(SHEET_ROSTER)

        // clearFrom() deletes the rows holding these, so capture first.
        val sectHdr = sheet.styleAt(ROSTER_SECTION_HEADER_ROW, 0)
        // A merge's covered cell may not be materialized; fall back to the label style.
        val sectHdr2 = sheet.styleAtOrNull(ROSTER_SECTION_HEADER_ROW, 1) ?: sectHdr
        val sectIdHdr = sheet.styleAt(ROSTER_SECTION_HEADER_ROW, 2)
        val sectBlkHdr = sheet.styleAt(ROSTER_SECTION_HEADER_ROW, 3)
        // Email column (E) was added to the template between Block and the metadata block; reuse the
        // Block styles if the template's email cells aren't independently styled.
        val sectEmailHdr = sheet.styleAtOrNull(ROSTER_SECTION_HEADER_ROW, 4) ?: sectBlkHdr
        val numStyle = sheet.styleAt(ROSTER_DATA_ROW, 0)
        val nameStyle = sheet.styleAt(ROSTER_DATA_ROW, 1)
        val idStyle = sheet.styleAt(ROSTER_DATA_ROW, 2)
        val blockStyle = sheet.styleAt(ROSTER_DATA_ROW, 3)
        val emailStyle = sheet.styleAtOrNull(ROSTER_DATA_ROW, 4) ?: nameStyle

        // Values sit one row below the PROGRAM/INSTRUCTOR/SCHOOL YEAR labels (shifted right by the
        // new Email column: E/F/G -> F/G/H).
        sheet.getRow(ROSTER_META_VALUE_ROW)?.let { r ->
            r.getCell(5)?.setCellValue(program)
            r.getCell(6)?.setCellValue(instructor)
            r.getCell(7)?.setCellValue(schoolYear)
        }

        clearFrom(sheet, ROSTER_SECTION_HEADER_ROW)

        val rosterRowOf = HashMap<PreboardRecordRow, Int>()
        var rowIdx = ROSTER_SECTION_HEADER_ROW
        for ((label, students) in sections) {
            sheet.createRow(rowIdx).apply {
                heightInPoints = SECTION_HEADER_HEIGHT
                cell(0, sectHdr).setCellValue(label)
                cell(1, sectHdr2)
                cell(2, sectIdHdr).setCellValue("Student ID")
                cell(3, sectBlkHdr).setCellValue("Block")
                cell(4, sectEmailHdr).setCellValue("Email")
            }
            sheet.addMergedRegion(CellRangeAddress(rowIdx, rowIdx, 0, 1))
            rowIdx++
            students.forEachIndexed { i, s ->
                sheet.createRow(rowIdx).apply {
                    heightInPoints = DATA_ROW_HEIGHT
                    cell(0, numStyle).setCellValue((i + 1).toDouble())
                    cell(1, nameStyle).setCellValue(s.name)
                    setIdCell(cell(2, idStyle), s.studentId)
                    cell(3, blockStyle).setCellValue(s.block)
                    cell(4, emailStyle).setCellValue(s.email)
                }
                rosterRowOf[s] = rowIdx + 1   // 1-based
                rowIdx++
            }
        }
        return rosterRowOf
    }

    private fun writeResultsSheet(
        wb: XSSFWorkbook,
        sections: List<Pair<String, List<PreboardRecordRow>>>,
        rosterRowOf: Map<PreboardRecordRow, Int>,
    ) {
        val sheet = wb.getSheet(SHEET_RESULTS)

        val numStyle = sheet.styleAt(RESULTS_DATA_ROW, 0)
        val nameStyle = sheet.styleAt(RESULTS_DATA_ROW, 1)
        val idStyle = sheet.styleAt(RESULTS_DATA_ROW, 2)
        // Block column (D) was added to the template between Student ID and Mathematics; reuse the
        // ID style if the template's block cells aren't independently styled.
        val blockStyle = sheet.styleAtOrNull(RESULTS_DATA_ROW, 3) ?: idStyle
        val scoreStyle = sheet.styleAt(RESULTS_DATA_ROW, 4)   // E/G/I
        val pctStyle = sheet.styleAt(RESULTS_DATA_ROW, 5)     // F/H/J
        val ratingStyle = sheet.styleAt(RESULTS_DATA_ROW, 10)
        val remarkStyle = sheet.styleAt(RESULTS_DATA_ROW, 11)
        val sepStyle = sheet.styleAt(RESULTS_SEPARATOR_ROW, 0)

        clearFrom(sheet, RESULTS_DATA_ROW)

        var rowIdx = RESULTS_DATA_ROW
        sections.forEachIndexed { sectionIdx, (_, students) ->
            if (sectionIdx > 0) {
                // Gray band separates sections, as in the template.
                sheet.createRow(rowIdx).apply {
                    heightInPoints = DATA_ROW_HEIGHT
                    for (c in 0..11) cell(c, sepStyle)
                }
                sheet.addMergedRegion(CellRangeAddress(rowIdx, rowIdx, 0, 11))
                rowIdx++
            }
            for (s in students) {
                val rr = rosterRowOf.getValue(s)   // student's roster row, 1-based
                val n = rowIdx + 1                 // this RESULTS row, 1-based
                sheet.createRow(rowIdx).apply {
                    heightInPoints = DATA_ROW_HEIGHT
                    cell(0, numStyle).cellFormula = "'$SHEET_ROSTER'!A$rr"
                    cell(1, nameStyle).cellFormula = "'$SHEET_ROSTER'!B$rr"
                    cell(2, idStyle).cellFormula = "'$SHEET_ROSTER'!C$rr"
                    // Block pulled from the roster (still col D there — Email was inserted at E).
                    cell(3, blockStyle).cellFormula = "'$SHEET_ROSTER'!D$rr"
                    subjectCells(this, col = 4, pct = s.mathPct, n = n, pctCol = "E", scoreStyle, pctStyle)
                    subjectCells(this, col = 6, pct = s.esasPct, n = n, pctCol = "G", scoreStyle, pctStyle)
                    subjectCells(this, col = 8, pct = s.profPct, n = n, pctCol = "I", scoreStyle, pctStyle)
                    cell(10, ratingStyle).cellFormula = "(0.25*F$n)+(0.3*H$n)+(0.45*J$n)"
                    cell(11, remarkStyle).setCellValue(s.remark)
                }
                rowIdx++
            }
        }
    }

    /** Score cell holds the percentage; the paired cell re-derives it via the template formula. */
    private fun subjectCells(
        row: Row,
        col: Int,
        pct: Double?,
        n: Int,
        pctCol: String,
        scoreStyle: CellStyle,
        pctStyle: CellStyle,
    ) {
        val score = row.cell(col, scoreStyle)
        if (pct != null) score.setCellValue(pct)
        row.cell(col + 1, pctStyle).cellFormula = "($pctCol$n/100)*100"
    }

    /** IDs that are plain digits are written numerically (as in the template); others stay text. */
    private fun setIdCell(cell: Cell, studentId: String) {
        val asNumber = studentId.toLongOrNull()
        if (asNumber != null && !studentId.startsWith("0")) cell.setCellValue(asNumber.toDouble())
        else cell.setCellValue(studentId)
    }

    /** Removes every merged region and row at or below [fromRow], leaving the header area intact. */
    private fun clearFrom(sheet: XSSFSheet, fromRow: Int) {
        for (i in sheet.numMergedRegions - 1 downTo 0) {
            if (sheet.getMergedRegion(i).firstRow >= fromRow) sheet.removeMergedRegion(i)
        }
        for (i in sheet.lastRowNum downTo fromRow) {
            sheet.getRow(i)?.let { sheet.removeRow(it) }
        }
    }

    private fun XSSFSheet.styleAt(rowIdx: Int, colIdx: Int): CellStyle =
        getRow(rowIdx).getCell(colIdx).cellStyle

    private fun XSSFSheet.styleAtOrNull(rowIdx: Int, colIdx: Int): CellStyle? =
        getRow(rowIdx)?.getCell(colIdx)?.cellStyle

    private fun Row.cell(col: Int, style: CellStyle): Cell = createCell(col).apply { cellStyle = style }

    companion object {
        private const val TEMPLATE_ASSET = "preboard_record_template.xlsx"
        private const val SHEET_ROSTER = "Name of Students"
        private const val SHEET_RESULTS = "RESULTS"

        // 0-based row indices in the template.
        private const val ROSTER_META_VALUE_ROW = 3       // PROGRAM/INSTRUCTOR/SCHOOL YEAR values
        private const val ROSTER_SECTION_HEADER_ROW = 4   // first section header ("MALE"); data area start
        private const val ROSTER_DATA_ROW = 5             // first student row (style exemplar)
        private const val RESULTS_DATA_ROW = 2            // first RESULTS data row (style exemplar)
        private const val RESULTS_SEPARATOR_ROW = 52      // gray section separator (style exemplar)

        private const val SECTION_HEADER_HEIGHT = 16.2f
        private const val DATA_ROW_HEIGHT = 15.6f
    }
}
