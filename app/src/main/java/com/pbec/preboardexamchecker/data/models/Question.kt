package com.pbec.preboardexamchecker.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "questions", indices = [Index(value = ["subject", "fileName"]), Index(value = ["importSessionId"])])
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val fileName: String,
    val category: String? = null,
    val topic: String? = null,
    val questionNumber: Int,
    val questionText: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val correctAnswer: String?,
    val questionBankId: String = "manual",
    val importSessionId: Long = 0L,
    val customSessionName: String? = null
)
