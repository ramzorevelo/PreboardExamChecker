package com.pbec.preboardexamchecker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pbec.preboardexamchecker.data.models.ScanResult
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanResultDao {
    @Insert
    suspend fun insert(result: ScanResult): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(results: List<ScanResult>)

    @Query("SELECT * FROM scan_results WHERE deletedAt IS NULL ORDER BY scannedAt DESC")
    fun getAll(): Flow<List<ScanResult>>

    /** Individually-trashed records only; those cascaded with an exam (trashedByExamId set) live under that exam's Trash entry. */
    @Query("SELECT * FROM scan_results WHERE deletedAt IS NOT NULL AND trashedByExamId IS NULL ORDER BY deletedAt DESC")
    fun getTrashed(): Flow<List<ScanResult>>

    @Query("SELECT * FROM scan_results WHERE subject = :subject AND deletedAt IS NULL ORDER BY scannedAt DESC")
    fun getBySubject(subject: String): Flow<List<ScanResult>>

    /** A student's results, latest first; feeds per-student GWA. */
    @Query("SELECT * FROM scan_results WHERE studentId = :studentId ORDER BY scannedAt DESC")
    suspend fun getResultsForStudent(studentId: String): List<ScanResult>

    /** Active records for a student+exam; used to warn that a fresh-session re-scan would duplicate a row. */
    @Query("SELECT * FROM scan_results WHERE studentId = :studentId AND examId = :examId AND deletedAt IS NULL ORDER BY scannedAt DESC")
    suspend fun getActiveByStudentAndExam(studentId: String, examId: Long): List<ScanResult>

    @Query("SELECT * FROM scan_results WHERE syncedAt IS NULL")
    suspend fun getUnsynced(): List<ScanResult>

    @Query("UPDATE scan_results SET syncedAt = :timestamp WHERE id = :id")
    suspend fun markSynced(id: Long, timestamp: Long)

    @Query("UPDATE scan_results SET remoteId = :remoteId WHERE id = :id")
    suspend fun updateRemoteId(id: Long, remoteId: String)

    // Soft-delete/restore null out syncedAt so the sync worker re-pushes the new deletedAt to Firestore.

    @Query("UPDATE scan_results SET deletedAt = :deletedAt, syncedAt = NULL WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDeleteByIds(ids: List<Long>, deletedAt: Long)

    @Query("UPDATE scan_results SET deletedAt = :deletedAt, syncedAt = NULL WHERE examId = :examId AND deletedAt IS NULL")
    suspend fun softDeleteByExamId(examId: Long, deletedAt: Long)

    @Query("UPDATE scan_results SET deletedAt = :deletedAt, syncedAt = NULL WHERE subject = :subject AND deletedAt IS NULL")
    suspend fun softDeleteBySubject(subject: String, deletedAt: Long)

    /** Cascade-trash an exam's records so they restore/purge as a unit. */
    @Query("UPDATE scan_results SET deletedAt = :deletedAt, trashedByExamId = :examId, syncedAt = NULL WHERE examId = :examId AND deletedAt IS NULL")
    suspend fun softDeleteCascadeByExamId(examId: Long, deletedAt: Long)

    @Query("UPDATE scan_results SET deletedAt = NULL, trashedByExamId = NULL, syncedAt = NULL WHERE trashedByExamId = :examId")
    suspend fun restoreCascadeByExamId(examId: Long)

    @Query("SELECT * FROM scan_results WHERE trashedByExamId = :examId")
    suspend fun getByTrashedExamId(examId: Long): List<ScanResult>

    @Query("UPDATE scan_results SET deletedAt = NULL, syncedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreByIds(ids: List<Long>)

    /** Trashed records past [threshold] — purge candidates. */
    @Query("SELECT * FROM scan_results WHERE deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun getExpired(threshold: Long): List<ScanResult>

    @Query("SELECT * FROM scan_results WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ScanResult>

    @Query("DELETE FROM scan_results WHERE id IN (:ids)")
    suspend fun hardDeleteByIds(ids: List<Long>)

    /** Records still referencing an exam; 0 means its archive is safe to drop. */
    @Query("SELECT COUNT(*) FROM scan_results WHERE examId = :examId")
    suspend fun countByExamId(examId: Long): Int
}
