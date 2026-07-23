package com.pbec.preboardexamchecker.domain.usecase

import android.content.Context
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.pdf.ClassReportPdfGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class ExportClassReportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generator: ClassReportPdfGenerator
) {
    suspend operator fun invoke(rows: List<StudentGwaRow>, blockLabel: String): File =
        generator.generate(context, rows, blockLabel)
}
