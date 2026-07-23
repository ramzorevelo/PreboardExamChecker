package com.pbec.preboardexamchecker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pbec.preboardexamchecker.data.models.Question
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<Question>)

    @Query("SELECT * FROM questions WHERE subject = :subject")
    fun getQuestionsBySubject(subject: String): Flow<List<Question>>

    @Query("SELECT * FROM questions")
    fun getAllQuestionsFlow(): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE subject = :subject")
    suspend fun getAllQuestionsForSubject(subject: String): List<Question>

    @Delete
    suspend fun deleteQuestion(question: Question): Int

    @Query("DELETE FROM questions WHERE importSessionId = :importSessionId")
    suspend fun deleteQuestionsByImportSessionId(importSessionId: Long): Int

    @Query("SELECT * FROM questions WHERE subject = :subject AND importSessionId IN (:importSessionIds)")
    suspend fun getQuestionsByImportSessionIds(subject: String, importSessionIds: List<Long>): List<Question>

    @Query("SELECT * FROM questions WHERE importSessionId IN (:importSessionIds)")
    suspend fun getQuestionsByImportSessionIdsOnly(importSessionIds: List<Long>): List<Question>

    @Query("UPDATE questions SET customSessionName = :newName WHERE importSessionId = :importSessionId")
    suspend fun updateCustomSessionName(importSessionId: Long, newName: String): Int
}