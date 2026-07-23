package com.pbec.preboardexamchecker.data.repository

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.firestore.FirebaseFirestore
import com.pbec.preboardexamchecker.data.dao.ScanResultDao
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.ui.scanner.sync.ScanResultSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanResultRepository @Inject constructor(
    private val dao: ScanResultDao,
    private val firestore: FirebaseFirestore,
    private val examRepository: IExamRepository,
    @ApplicationContext private val context: Context,
) : IScanResultRepository {

    override suspend fun insert(result: ScanResult): Long =
        dao.insert(result).also { scheduleSync() }

    override fun getAll(): Flow<List<ScanResult>> = dao.getAll()

    override suspend fun getActiveByStudentAndExam(studentId: String, examId: Long): List<ScanResult> =
        dao.getActiveByStudentAndExam(studentId, examId)

    override suspend fun getUnsynced(): List<ScanResult> = dao.getUnsynced()

    override suspend fun markSynced(id: Long, timestamp: Long) = dao.markSynced(id, timestamp)

    override suspend fun updateRemoteId(id: Long, remoteId: String) = dao.updateRemoteId(id, remoteId)

    override suspend fun syncFromFirestore(): Result<Unit> = Result.success(Unit)

    override suspend fun upsertFromFirestore(results: List<ScanResult>) = dao.insertOrIgnore(results)

    override fun getTrashed(): Flow<List<ScanResult>> = dao.getTrashed()

    override suspend fun moveToTrash(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.softDeleteByIds(ids, System.currentTimeMillis())
        scheduleSync()
    }

    override suspend fun moveExamToTrash(examId: Long) {
        dao.softDeleteByExamId(examId, System.currentTimeMillis())
        scheduleSync()
    }

    override suspend fun moveSubjectToTrash(subject: String) {
        dao.softDeleteBySubject(subject, System.currentTimeMillis())
        scheduleSync()
    }

    override suspend fun moveExamRecordsToTrashCascade(examId: Long) {
        dao.softDeleteCascadeByExamId(examId, System.currentTimeMillis())
        scheduleSync()
    }

    override suspend fun restoreExamCascade(examId: Long) {
        dao.restoreCascadeByExamId(examId)
        scheduleSync()
    }

    override suspend fun purgeExamCascade(examId: Long) {
        purge(dao.getByTrashedExamId(examId))
    }

    override suspend fun restore(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.restoreByIds(ids)
        scheduleSync()
    }

    override suspend fun purgeExpired() {
        val threshold = System.currentTimeMillis() - RETENTION_MS
        purge(dao.getExpired(threshold))
    }

    override suspend fun purgeNow(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Re-read so we have each row's remoteId for the cloud delete.
        purge(dao.getByIds(ids))
    }

    /**
     * Hard-delete rows from Room and Firestore. The cloud delete is fire-and-forget (offline-durable;
     * Firestore replays on reconnect), so the local row goes immediately.
     */
    private suspend fun purge(rows: List<ScanResult>) {
        if (rows.isEmpty()) return
        for (row in rows) {
            val remoteId = row.remoteId
            if (!remoteId.isNullOrBlank()) {
                runCatching {
                    firestore.collection("scan_results").document(remoteId).delete()
                        .addOnFailureListener { e -> Log.e("ScanResultRepository", "Cloud purge delete failed for remoteId=$remoteId", e) }
                }
            }
        }
        dao.hardDeleteByIds(rows.map { it.id })

        // Drop the archived exam snapshot once nothing references the exam.
        rows.map { it.examId }.distinct().forEach { examId ->
            if (dao.countByExamId(examId) == 0) {
                runCatching { examRepository.deleteArchivedExam(examId) }
            }
        }
    }

    /**
     * APPEND_OR_REPLACE, not KEEP: the worker snapshots getUnsynced() at start, so a row that
     * becomes unsynced during an in-flight run needs a pass enqueued strictly after it.
     */
    private fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<ScanResultSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
        const val SYNC_WORK_NAME = "scan_result_sync"

        /** App-start backlog drain; KEEP suffices — any queued run covers all unsynced rows. */
        fun enqueueBacklogSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanResultSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
