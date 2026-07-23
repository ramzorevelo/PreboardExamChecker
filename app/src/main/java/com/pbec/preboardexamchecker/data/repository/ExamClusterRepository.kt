package com.pbec.preboardexamchecker.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pbec.preboardexamchecker.data.models.ExamCluster
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamClusterRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : IExamClusterRepository {

    /** Live list for cluster-management UI. Hard-deleted clusters simply drop out. */
    fun observeClusters(): Flow<List<ExamCluster>> = callbackFlow {
        val uid = runCatching { ensureUid() }.getOrElse {
            trySend(emptyList()); awaitClose { }; return@callbackFlow
        }
        val listener = firestore.collection("exam_clusters")
            .whereEqualTo("uploadedByUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { trySend(emptyList()); return@addSnapshotListener }
                val clusters = snapshot?.documents
                    ?.mapNotNull { it.toCluster() }
                    ?.sortedByDescending { it.createdAt }
                    .orEmpty()
                trySend(clusters)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getClustersOnce(): List<ExamCluster> {
        val uid = ensureUid()
        return firestore.collection("exam_clusters")
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
            .documents
            .mapNotNull { it.toCluster() }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun getClusterById(clusterId: Long): ExamCluster? {
        val uid = ensureUid()
        return firestore.collection("exam_clusters")
            .whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("id", clusterId)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.toCluster()
    }

    /** Upsert by stable [ExamCluster.id]; a new cluster gets an id before its first write. */
    suspend fun saveCluster(cluster: ExamCluster): Long {
        val uid = ensureUid()
        val normalized = if (cluster.id == 0L) cluster.copy(id = generateId()) else cluster
        val existing = firestore.collection("exam_clusters")
            .whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("id", normalized.id)
            .get()
            .await()
        val target = existing.documents.firstOrNull()?.reference
            ?: firestore.collection("exam_clusters").document()
        target.set(
            mapOf(
                "id" to normalized.id,
                "name" to normalized.name,
                "examIdsBySubject" to normalized.examIdsBySubject,
                "schoolYear" to normalized.schoolYear,
                "createdAt" to normalized.createdAt,
                "uploadedByUid" to uid,
            )
        ).await()
        return normalized.id
    }

    suspend fun deleteCluster(clusterId: Long) {
        val uid = ensureUid()
        firestore.collection("exam_clusters")
            .whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("id", clusterId)
            .get()
            .await()
            .documents
            .forEach { it.reference.delete() }
    }

    private suspend fun ensureUid(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        return auth.signInAnonymously().await().user?.uid
            ?: throw IllegalStateException("Firebase sign-in failed: missing user.")
    }

    // Non-monotonic is fine: clusters sort by createdAt, never by id.
    private fun generateId(): Long = com.pbec.preboardexamchecker.utils.IdGenerator.newId()

    private fun com.google.firebase.firestore.DocumentSnapshot.toCluster(): ExamCluster? {
        val name = getString("name") ?: return null
        // Firestore numbers deserialize as Long/Double; coerce map values defensively.
        val map = (get("examIdsBySubject") as? Map<*, *>)?.entries
            ?.mapNotNull { (k, v) ->
                val subject = k as? String ?: return@mapNotNull null
                val examId = (v as? Number)?.toLong() ?: return@mapNotNull null
                subject to examId
            }?.toMap().orEmpty()
        return ExamCluster(
            id = getLong("id") ?: return null,
            name = name,
            examIdsBySubject = map,
            schoolYear = getString("schoolYear"),
            createdAt = getLong("createdAt") ?: 0L,
        )
    }
}
