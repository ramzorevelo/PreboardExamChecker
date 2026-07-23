package com.pbec.preboardexamchecker.ui.scanner.processor

import com.pbec.preboardexamchecker.data.models.Student

/** testSet is "-" when not detected or ambiguous. */
data class ScannedInfo(
    val testSet: String,
    val studentId: String,
    val resolvedStudent: Student? = null
)
