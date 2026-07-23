package com.pbec.preboardexamchecker.data.repository

import com.pbec.preboardexamchecker.data.models.ArchivedExam
import com.pbec.preboardexamchecker.data.models.Exam

interface IExamRepository {
    suspend fun getExamsBySubjectOnce(subject: String): List<Exam>

    /** Archived snapshot of a deleted exam, or null; rebuilds Exam Stats after the live exam is gone. */
    suspend fun getArchivedExamById(examId: Long): ArchivedExam?

    /** Drop an exam's archived snapshot, e.g. once its last record is permanently purged. */
    suspend fun deleteArchivedExam(examId: Long)
}
