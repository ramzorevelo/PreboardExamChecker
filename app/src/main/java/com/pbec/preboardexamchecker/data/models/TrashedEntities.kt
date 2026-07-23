package com.pbec.preboardexamchecker.data.models

data class TrashedExam(
    val examId: Long,
    val examName: String,
    val subject: String,
    val deletedAt: Long,
)

data class TrashedBank(
    val bankId: String,
    val displayName: String,
    val subject: String,
    val questionCount: Int,
    val deletedAt: Long,
)
