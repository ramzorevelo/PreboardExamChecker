package com.pbec.preboardexamchecker.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exams")
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examName: String,
    val subject: String,
    val setAQuestionIds: List<Long>,
    val setBQuestionIds: List<Long>, // same questions as set A, reordered
    val createdAt: Long
) {
    // Backward-compat single-set view for the current UI.
    val questionIds: List<Long>
        get() = if (setAQuestionIds.isNotEmpty()) setAQuestionIds else setBQuestionIds
}