package com.pbec.preboardexamchecker.domain.usecase

import android.content.Context
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.pdf.StudentSlipPdfGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class ExportStudentSlipUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generator: StudentSlipPdfGenerator
) {
    suspend operator fun invoke(row: StudentGwaRow): File =
        generator.generate(context, row)

    /** Combined: all slips in one multi-page PDF. */
    suspend operator fun invoke(rows: List<StudentGwaRow>): File =
        generator.generate(context, rows)
}
