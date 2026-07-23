package com.pbec.preboardexamchecker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.pbec.preboardexamchecker.data.models.Exam

@Dao
interface ExamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExam(exam: Exam): Long

    @Query("SELECT * FROM exams ORDER BY createdAt DESC")
    fun getAllExams(): Flow<List<Exam>>

    @Query("SELECT * FROM exams WHERE subject = :subject ORDER BY createdAt DESC")
    fun getExamsBySubject(subject: String): Flow<List<Exam>>

    @Query("SELECT * FROM exams WHERE subject = :subject")
    suspend fun getExamsBySubjectOnce(subject: String): List<Exam>

    @Query("SELECT * FROM exams WHERE id = :examId")
    suspend fun getExamById(examId: Long): Exam?

    @Delete
    suspend fun deleteExam(exam: Exam): Int

    @Delete
    suspend fun deleteExams(exams: List<Exam>): Int

    @Update
    suspend fun updateExam(exam: Exam): Int
}