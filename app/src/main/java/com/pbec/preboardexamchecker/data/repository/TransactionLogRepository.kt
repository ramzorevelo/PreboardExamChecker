package com.pbec.preboardexamchecker.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pbec.preboardexamchecker.data.models.TransactionLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionLogRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun insertTransaction(action: String, subject: String?, details: String) {
        val uid = ensureFirebaseUserUid()
        firestore.collection("transaction_logs")
            .add(
                mapOf(
                    "id" to generateId(),
                    "action" to action,
                    "subject" to subject,
                    "details" to details,
                    "createdAt" to System.currentTimeMillis(),
                    "uploadedByUid" to uid
                )
            )
            .await()
    }

    fun getAllTransactions(): Flow<List<TransactionLog>> {
        return callbackFlow {
            val uid = runCatching { ensureFirebaseUserUid() }.getOrElse {
                trySend(emptyList())
                awaitClose { }
                return@callbackFlow
            }

            val listener = firestore.collection("transaction_logs")
                .whereEqualTo("uploadedByUid", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val logs = snapshot?.documents?.mapNotNull { doc ->
                        val action = doc.getString("action") ?: return@mapNotNull null
                        val details = doc.getString("details") ?: return@mapNotNull null
                        val fallbackId = doc.id.hashCode().toLong().let { if (it < 0) -it else it }
                        TransactionLog(
                            id = doc.getLong("id") ?: fallbackId,
                            action = action,
                            subject = doc.getString("subject"),
                            details = details,
                            createdAt = doc.getLong("createdAt") ?: 0L
                        )
                    }?.sortedByDescending { it.createdAt }.orEmpty()
                    trySend(logs)
                }

            awaitClose { listener.remove() }
        }
    }

    private suspend fun ensureFirebaseUserUid(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        val authResult = auth.signInAnonymously().await()
        return authResult.user?.uid ?: throw IllegalStateException("Firebase sign-in failed: missing user.")
    }

    // Non-monotonic is fine: logs sort by createdAt, never by id.
    private fun generateId(): Long = com.pbec.preboardexamchecker.utils.IdGenerator.newId()
}
