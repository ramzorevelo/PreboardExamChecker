package com.pbec.preboardexamchecker.data.models

/**
 * Snapshot of an exam taken at delete time so Summary's per-question Exam Stats can still be rebuilt
 * for records that took it. Self-contained: carries Set A/B id orders plus the resolved answer key
 * and question text/options, independent of the live `exams`/question-bank rows (which may be gone).
 * Stored in Firestore (`deleted_exams`), keyed by [examId].
 */
data class ArchivedExam(
    val examId: Long,
    val examName: String,
    val subject: String,
    val setAQuestionIds: List<Long>,
    val setBQuestionIds: List<Long>,
    val questions: List<ArchivedQuestion>,
    val archivedAt: Long,
)

data class ArchivedQuestion(
    val id: Long,
    val correctAnswer: String?,
    val questionText: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
)
