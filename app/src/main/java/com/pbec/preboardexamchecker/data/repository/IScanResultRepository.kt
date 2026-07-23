package com.pbec.preboardexamchecker.data.repository

import com.pbec.preboardexamchecker.data.models.ScanResult
import kotlinx.coroutines.flow.Flow

interface IScanResultRepository {
    suspend fun insert(result: ScanResult): Long
    fun getAll(): Flow<List<ScanResult>>
    /** Active (not trashed) records for one student on one exam (duplicate detection). */
    suspend fun getActiveByStudentAndExam(studentId: String, examId: Long): List<ScanResult>
    suspend fun getUnsynced(): List<ScanResult>
    suspend fun markSynced(id: Long, timestamp: Long)
    suspend fun updateRemoteId(id: Long, remoteId: String)
    suspend fun syncFromFirestore(): Result<Unit>
    suspend fun upsertFromFirestore(results: List<ScanResult>)

    /** Trashed records, most recently deleted first. */
    fun getTrashed(): Flow<List<ScanResult>>
    suspend fun moveToTrash(ids: List<Long>)
    /** Trash one exam's active records as individual papers (not cascaded with the exam). */
    suspend fun moveExamToTrash(examId: Long)
    suspend fun moveSubjectToTrash(subject: String)
    /** Trash an exam's records as a unit with the exam, so they restore/purge with it. */
    suspend fun moveExamRecordsToTrashCascade(examId: Long)
    /** Restore the records that were trashed together with [examId]. */
    suspend fun restoreExamCascade(examId: Long)
    /** Permanently delete the records that were trashed together with [examId]. */
    suspend fun purgeExamCascade(examId: Long)
    suspend fun restore(ids: List<Long>)
    /** Permanently delete trashed records older than the 30-day retention window. */
    suspend fun purgeExpired()
    suspend fun purgeNow(ids: List<Long>)
}
