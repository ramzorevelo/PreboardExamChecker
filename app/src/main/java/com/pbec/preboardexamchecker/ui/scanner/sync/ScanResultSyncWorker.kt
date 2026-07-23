package com.pbec.preboardexamchecker.ui.scanner.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await

@HiltWorker
class ScanResultSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: IScanResultRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val unsynced = repository.getUnsynced()
            if (unsynced.isEmpty()) return Result.success()
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                ?: return Result.failure()
            for (result in unsynced) {
                val doc = mapOf(
                    "studentId"        to result.studentId,
                    "studentName"      to result.studentName,
                    "studentBlock"     to result.studentBlock,
                    "studentYearLevel" to result.studentYearLevel,
                    "studentProgram"   to result.studentProgram,
                    "subject"          to result.subject,
                    "examId"           to result.examId,
                    "examName"         to result.examName,
                    "clusterId"        to result.clusterId,
                    "clusterName"      to result.clusterName,
                    "testSet"          to result.testSet,
                    "rawAnswers"       to result.rawAnswers,
                    "score"            to result.score,
                    "total"            to result.total,
                    "passed"           to result.passed,
                    "scannedAt"        to result.scannedAt,
                    // Trash state mirrored to the cloud backup: null = active, timestamp = in Trash.
                    "deletedAt"        to result.deletedAt,
                    "uploadedByUid"    to uid,
                    "uploadedAt"       to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                // Already-uploaded rows (a re-sync triggered by a trash/restore that nulled syncedAt)
                // update their existing document by remoteId so the backup stays a single doc; brand
                // new rows create a fresh document.
                val existingRemoteId = result.remoteId
                if (existingRemoteId.isNullOrBlank()) {
                    val ref = firestore.collection("scan_results").document()
                    ref.set(doc).await()
                    repository.markSynced(result.id, System.currentTimeMillis())
                    repository.updateRemoteId(result.id, ref.id)
                } else {
                    firestore.collection("scan_results").document(existingRemoteId)
                        .set(doc, com.google.firebase.firestore.SetOptions.merge()).await()
                    repository.markSynced(result.id, System.currentTimeMillis())
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
