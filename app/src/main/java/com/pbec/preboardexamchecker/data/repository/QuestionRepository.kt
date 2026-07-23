package com.pbec.preboardexamchecker.data.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.data.models.TrashedBank
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val QUESTION_BANKS_COLLECTION = "question_banks"
        private const val QUESTIONS_SUBCOLLECTION = "questions"
    }

    suspend fun insertQuestions(questions: List<Question>) {
        if (questions.isEmpty()) return
        val uid = ensureFirebaseUserUid()
        val teacherId = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("teacher_id", null)
            ?: uid

        questions.groupBy { it.questionBankId }.forEach { (bankId, groupedQuestions) ->
            val questionsRef = firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .collection(QUESTIONS_SUBCOLLECTION)

            // One upfront id read distinguishes new docs from overwrites, so the bank count
            // below needs no re-download.
            val existingIds = questionsRef.get().await().documents.map { it.id }.toSet()

            val normalizedQuestions = groupedQuestions.map { question ->
                if (question.id == 0L) question.copy(id = generateId()) else question
            }

            // Chunked at 450; Firestore caps a batch at 500 ops.
            val writtenDocIds = mutableSetOf<String>()
            var batch = firestore.batch()
            var ops = 0
            for (normalizedQuestion in normalizedQuestions) {
                val docId = normalizedQuestion.id.toString()
                writtenDocIds.add(docId)
                batch.set(
                    questionsRef.document(docId),
                    mapOf(
                        "id" to normalizedQuestion.id,
                        "subject" to normalizedQuestion.subject,
                        "fileName" to normalizedQuestion.fileName,
                        "category" to normalizedQuestion.category,
                        "topic" to normalizedQuestion.topic,
                        "questionNumber" to normalizedQuestion.questionNumber,
                        "questionText" to normalizedQuestion.questionText,
                        "optionA" to normalizedQuestion.optionA,
                        "optionB" to normalizedQuestion.optionB,
                        "optionC" to normalizedQuestion.optionC,
                        "optionD" to normalizedQuestion.optionD,
                        "correctAnswer" to normalizedQuestion.correctAnswer,
                        "questionBankId" to normalizedQuestion.questionBankId,
                        "importSessionId" to normalizedQuestion.importSessionId,
                        "customSessionName" to normalizedQuestion.customSessionName,
                        "sourceFileName" to normalizedQuestion.fileName,
                        "uploadedByUid" to uid,
                        "uploadedByTeacherId" to teacherId,
                        "syncedAt" to com.google.firebase.Timestamp.now()
                    )
                )
                if (++ops >= 450) { batch.commit().await(); batch = firestore.batch(); ops = 0 }
            }
            if (ops > 0) batch.commit().await()

            // Overwrites of existing ids don't change the count.
            val refreshedCount = existingIds.size + writtenDocIds.count { it !in existingIds }

            val representative = groupedQuestions.first()
            firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .set(
                    mapOf(
                        "questionBankId" to bankId,
                        "subject" to representative.subject,
                        "sourceFileName" to representative.fileName,
                        "displayName" to (representative.customSessionName ?: representative.fileName),
                        "questionCount" to refreshedCount,
                        "uploadedByUid" to uid,
                        "uploadedByTeacherId" to teacherId,
                        "legacyImportSessionId" to representative.importSessionId,
                        "updatedAt" to com.google.firebase.Timestamp.now(),
                        "createdAt" to com.google.firebase.Timestamp.now()
                    )
                )
                .await()
        }
    }

    fun getQuestionsBySubject(subject: String): Flow<List<Question>> {
        return callbackFlow {
            val uid = runCatching { ensureFirebaseUserUid() }.getOrElse {
                trySend(emptyList())
                awaitClose { }
                return@callbackFlow
            }

            val listener = firestore.collection(QUESTION_BANKS_COLLECTION)
                .whereEqualTo("uploadedByUid", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val bankIds = snapshot?.documents
                        ?.filter { it.getString("subject") == subject && it.getLong("deletedAt") == null }
                        ?.map { it.id }
                        .orEmpty()
                    launch {
                        val questions = loadQuestionsFromBanks(uid, bankIds)
                            .filter { it.subject == subject }
                            .sortedWith(compareBy<Question> { it.questionBankId }.thenBy { it.questionNumber })
                        trySend(questions)
                    }
                }

            awaitClose { listener.remove() }
        }
    }

    suspend fun getAllQuestionsForSubjectOnce(subject: String): List<Question> {
        val uid = ensureFirebaseUserUid()
        val bankSnapshot = firestore.collection(QUESTION_BANKS_COLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("subject", subject)
            .get()
            .await()
        val bankIds = bankSnapshot.documents
            .filter { it.getLong("deletedAt") == null }
            .map { it.id }
        return loadQuestionsFromBanks(uid, bankIds)
            .filter { it.subject == subject }
            .sortedWith(compareBy<Question> { it.questionBankId }.thenBy { it.questionNumber })
    }

    suspend fun deleteQuestion(question: Question): Int {
        val uid = ensureFirebaseUserUid()
        val directDocRef = firestore.collection(QUESTION_BANKS_COLLECTION)
            .document(question.questionBankId)
            .collection(QUESTIONS_SUBCOLLECTION)
            .document(question.id.toString())
        val directDoc = directDocRef.get().await()
        if (directDoc.exists()) {
            directDocRef.delete().await()
            refreshQuestionBankCount(uid, question.questionBankId)
            return 1
        }

        // Legacy/mismatched bank IDs: scan the user's banks directly (avoids a collection-group index).
        val bankIds = getUserBankIds(uid)
        var deleted = 0
        bankIds.forEach { bankId ->
            val docRef = firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .collection(QUESTIONS_SUBCOLLECTION)
                .document(question.id.toString())
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                docRef.delete().await()
                refreshQuestionBankCount(uid, bankId)
                deleted++
            }
        }
        return deleted
    }

    suspend fun deleteQuestionsByImportSessionId(importSessionId: Long): Int {
        val uid = ensureFirebaseUserUid()
        var deletedCount = 0
        getUserBankIds(uid).forEach { bankId ->
            val col = firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .collection(QUESTIONS_SUBCOLLECTION)
            val matchingDocs = findByImportSessionId(col, uid, importSessionId)
            if (matchingDocs.isNotEmpty()) {
                batchDelete(matchingDocs.map { it.reference })
                refreshQuestionBankCount(uid, bankId)
            }
            deletedCount += matchingDocs.size
        }
        return deletedCount
    }

    suspend fun deleteQuestionsByQuestionBankId(questionBankId: String): Int {
        val uid = ensureFirebaseUserUid()
        val bankDocs = firestore.collection(QUESTION_BANKS_COLLECTION)
            .document(questionBankId)
            .collection(QUESTIONS_SUBCOLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
            .documents
        if (bankDocs.isNotEmpty()) {
            batchDelete(bankDocs.map { it.reference })
            firestore.collection(QUESTION_BANKS_COLLECTION).document(questionBankId).delete().await()
            return bankDocs.size
        }

        // Legacy fallback: locate matching docs by metadata across user's banks.
        var deletedCount = 0
        getUserBankIds(uid).forEach { bankId ->
            val docs = firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .collection(QUESTIONS_SUBCOLLECTION)
                .whereEqualTo("uploadedByUid", uid)
                .get()
                .await()
                .documents
            val matchingDocs = docs.filter { doc ->
                val docBankId = doc.getString("questionBankId")
                val matchesBankId = docBankId == questionBankId
                val legacyImportSession = doc.getLong("importSessionId")
                    ?: doc.getString("importSessionId")?.toLongOrNull()
                val matchesLegacyBank = questionBankId.startsWith("legacy_") &&
                    legacyImportSession?.let { "legacy_$it" == questionBankId } == true
                matchesBankId || matchesLegacyBank
            }
            if (matchingDocs.isNotEmpty()) {
                batchDelete(matchingDocs.map { it.reference })
                refreshQuestionBankCount(uid, bankId)
            }
            deletedCount += matchingDocs.size
        }
        return deletedCount
    }

    suspend fun getQuestionsByImportSessionIds(subject: String, importSessionIds: List<Long>): List<Question> {
        if (importSessionIds.isEmpty()) return emptyList()
        return getAllQuestionsForSubjectOnce(subject)
            .filter { importSessionIds.contains(it.importSessionId) }
    }

    suspend fun getQuestionsByImportSessionIdsOnly(importSessionIds: List<Long>): List<Question> {
        if (importSessionIds.isEmpty()) return emptyList()
        val uid = ensureFirebaseUserUid()
        val docs = firestore.collectionGroup(QUESTIONS_SUBCOLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
        return docs.documents
            .mapNotNull { it.toQuestion() }
            .filter { importSessionIds.contains(it.importSessionId) }
            .sortedWith(compareBy<Question> { it.questionBankId }.thenBy { it.questionNumber })
    }

    suspend fun getQuestionsByQuestionBankIdsOnly(questionBankIds: List<String>): List<Question> {
        if (questionBankIds.isEmpty()) return emptyList()
        val uid = ensureFirebaseUserUid()
        // Skip banks that are in the Trash so a deleted bank's questions can't be drawn into a new exam.
        val activeBankIds = questionBankIds.filter { bankId ->
            firestore.collection(QUESTION_BANKS_COLLECTION).document(bankId).get().await()
                .getLong("deletedAt") == null
        }
        return loadQuestionsFromBanks(uid, activeBankIds)
            .filter { activeBankIds.contains(it.questionBankId) }
            .sortedWith(compareBy<Question> { it.questionBankId }.thenBy { it.questionNumber })
    }

    suspend fun updateCustomSessionName(importSessionId: Long, newName: String): Int {
        val uid = ensureFirebaseUserUid()
        // Per-bank queries: a collectionGroup filter on importSessionId would need a
        // collection-group index.
        val matchingDocs = getUserBankIds(uid).flatMap { bankId ->
            val col = firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .collection(QUESTIONS_SUBCOLLECTION)
            findByImportSessionId(col, uid, importSessionId)
        }.distinctBy { it.reference.path }
        batchUpdate(matchingDocs.map { it.reference }, mapOf("customSessionName" to newName))
        return matchingDocs.size
    }

    suspend fun updateCustomSessionNameByQuestionBankId(questionBankId: String, newName: String): Int {
        val uid = ensureFirebaseUserUid()
        // legacy_<sessionId> banks: old docs may carry only the session id, so match both ways.
        val legacySessionId = questionBankId.takeIf { it.startsWith("legacy_") }
            ?.removePrefix("legacy_")?.toLongOrNull()
        val matchingDocs = getUserBankIds(uid).flatMap { bankId ->
            val col = firestore.collection(QUESTION_BANKS_COLLECTION)
                .document(bankId)
                .collection(QUESTIONS_SUBCOLLECTION)
            val byBankField = col.whereEqualTo("uploadedByUid", uid)
                .whereEqualTo("questionBankId", questionBankId)
                .get()
                .await()
                .documents
            val byLegacySession = legacySessionId?.let { findByImportSessionId(col, uid, it) }.orEmpty()
            byBankField + byLegacySession
        }.distinctBy { it.reference.path }
        batchUpdate(matchingDocs.map { it.reference }, mapOf("customSessionName" to newName))
        firestore.collection(QUESTION_BANKS_COLLECTION)
            .document(questionBankId)
            .set(
                mapOf(
                    "displayName" to newName,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                ),
                SetOptions.merge()
            )
            .await()
        return matchingDocs.size
    }

    /** Soft-delete a bank: its questions stop being available for exam generation, restorable for
     *  30 days. Fire-and-forget (offline-durable). */
    suspend fun softDeleteQuestionBank(questionBankId: String) {
        firestore.collection(QUESTION_BANKS_COLLECTION).document(questionBankId)
            .set(mapOf("deletedAt" to System.currentTimeMillis()), SetOptions.merge())
    }

    fun getTrashedBanks(): Flow<List<TrashedBank>> = callbackFlow {
        val uid = runCatching { ensureFirebaseUserUid() }.getOrElse {
            trySend(emptyList()); awaitClose { }; return@callbackFlow
        }
        val listener = firestore.collection(QUESTION_BANKS_COLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { trySend(emptyList()); return@addSnapshotListener }
                val trashed = snapshot?.documents.orEmpty()
                    .filter { it.getLong("deletedAt") != null }
                    .map { doc ->
                        TrashedBank(
                            bankId = doc.id,
                            displayName = doc.getString("displayName")
                                ?: doc.getString("sourceFileName") ?: "Imported Bank",
                            subject = doc.getString("subject") ?: "",
                            questionCount = doc.getLong("questionCount")?.toInt() ?: 0,
                            deletedAt = doc.getLong("deletedAt") ?: 0L,
                        )
                    }
                    .sortedByDescending { it.deletedAt }
                trySend(trashed)
            }
        awaitClose { listener.remove() }
    }

    suspend fun restoreQuestionBank(questionBankId: String) {
        firestore.collection(QUESTION_BANKS_COLLECTION).document(questionBankId)
            .set(mapOf("deletedAt" to null), SetOptions.merge())
    }

    /** Permanently delete a bank and all of its questions. */
    suspend fun purgeQuestionBank(questionBankId: String) {
        val uid = ensureFirebaseUserUid()
        val questionDocs = firestore.collection(QUESTION_BANKS_COLLECTION)
            .document(questionBankId)
            .collection(QUESTIONS_SUBCOLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
            .documents
        questionDocs.forEach { it.reference.delete() }
        firestore.collection(QUESTION_BANKS_COLLECTION).document(questionBankId).delete()
    }

    /** Ids of trashed banks past the 30-day window — the caller purges each (and its linked exams). */
    suspend fun getExpiredBankIds(): List<String> {
        val uid = ensureFirebaseUserUid()
        val threshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        return firestore.collection(QUESTION_BANKS_COLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
            .documents
            .filter { val d = it.getLong("deletedAt"); d != null && d < threshold }
            .map { it.id }
    }

    private suspend fun ensureFirebaseUserUid(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        val authResult = auth.signInAnonymously().await()
        return authResult.user?.uid ?: throw IllegalStateException("Firebase sign-in failed: missing user.")
    }

    // Question docs are keyed by id.toString(): a collision overwrites another question.
    private fun generateId(): Long = com.pbec.preboardexamchecker.utils.IdGenerator.newId()

    private suspend fun refreshQuestionBankCount(uid: String, questionBankId: String) {
        if (questionBankId.isBlank()) return
        val bankRef = firestore.collection(QUESTION_BANKS_COLLECTION).document(questionBankId)
        val countSnapshot = bankRef.collection(QUESTIONS_SUBCOLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
        val count = countSnapshot.size()
        if (count == 0) {
            bankRef.delete().await()
            return
        }
        bankRef.set(
            mapOf(
                "questionBankId" to questionBankId,
                "questionCount" to count,
                "uploadedByUid" to uid,
                "updatedAt" to com.google.firebase.Timestamp.now()
            ),
            SetOptions.merge()
        ).await()
    }

    private suspend fun loadQuestionsFromBanks(uid: String, bankIds: List<String>): List<Question> {
        if (bankIds.isEmpty()) return emptyList()
        // One round-trip per bank, fetched concurrently.
        return coroutineScope {
            bankIds.map { bankId ->
                async {
                    firestore.collection(QUESTION_BANKS_COLLECTION)
                        .document(bankId)
                        .collection(QUESTIONS_SUBCOLLECTION)
                        .whereEqualTo("uploadedByUid", uid)
                        .get()
                        .await()
                        .documents.mapNotNull { it.toQuestion() }
                }
            }.awaitAll()
        }.flatten()
    }

    private suspend fun getUserBankIds(uid: String): List<String> {
        return firestore.collection(QUESTION_BANKS_COLLECTION)
            .whereEqualTo("uploadedByUid", uid)
            .get()
            .await()
            .documents
            .map { it.id }
    }

    /**
     * importSessionId is stored as Long or String depending on doc age: query both types,
     * fall back to a client-side scan of the bank only when both miss (odd legacy encodings).
     */
    private suspend fun findByImportSessionId(
        col: com.google.firebase.firestore.CollectionReference,
        uid: String,
        importSessionId: Long,
    ): List<com.google.firebase.firestore.DocumentSnapshot> {
        val asLong = col.whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("importSessionId", importSessionId)
            .get().await().documents
        val asString = col.whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("importSessionId", importSessionId.toString())
            .get().await().documents
        val narrowed = (asLong + asString).distinctBy { it.reference.path }
        if (narrowed.isNotEmpty()) return narrowed
        return col.whereEqualTo("uploadedByUid", uid).get().await().documents.filter { doc ->
            doc.getLong("importSessionId") == importSessionId ||
                doc.getString("importSessionId")?.toLongOrNull() == importSessionId
        }
    }

    /** Chunked at 450; Firestore caps a batch at 500 ops. */
    private suspend fun batchDelete(refs: List<com.google.firebase.firestore.DocumentReference>) {
        var batch = firestore.batch()
        var ops = 0
        for (ref in refs) {
            batch.delete(ref)
            if (++ops >= 450) { batch.commit().await(); batch = firestore.batch(); ops = 0 }
        }
        if (ops > 0) batch.commit().await()
    }

    /** Same [updates] to every doc; chunked at 450 (batch cap 500). */
    private suspend fun batchUpdate(
        refs: List<com.google.firebase.firestore.DocumentReference>,
        updates: Map<String, Any>,
    ) {
        var batch = firestore.batch()
        var ops = 0
        for (ref in refs) {
            batch.update(ref, updates)
            if (++ops >= 450) { batch.commit().await(); batch = firestore.batch(); ops = 0 }
        }
        if (ops > 0) batch.commit().await()
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toQuestion(): Question? {
        val subject = getString("subject") ?: return null
        val fileName = getString("fileName")
            ?: getString("sourceFileName")
            ?: "Imported Bank"
        val questionText = getString("questionText") ?: return null
        val optionA = getString("optionA").orEmpty()
        val optionB = getString("optionB").orEmpty()
        val optionC = getString("optionC").orEmpty()
        val optionD = getString("optionD").orEmpty()

        val fallbackId = id.hashCode().toLong().let { if (it < 0) -it else it }
        val normalizedId = getLong("id") ?: fallbackId

        val parsedImportSessionId = getLong("importSessionId")
            ?: getString("importSessionId")?.toLongOrNull()
            ?: 0L
        val sourceFileName = getString("sourceFileName")
        val parsedQuestionBankId = getString("questionBankId")
            ?: when {
                parsedImportSessionId != 0L -> "legacy_$parsedImportSessionId"
                !sourceFileName.isNullOrBlank() -> "legacy_file_${sourceFileName.trim().lowercase()}"
                else -> "manual"
            }

        return Question(
            id = normalizedId,
            subject = subject,
            fileName = fileName,
            category = getString("category"),
            topic = getString("topic"),
            questionNumber = getLong("questionNumber")?.toInt()
                ?: getDouble("questionNumber")?.toInt()
                ?: getString("questionNumber")?.toIntOrNull()
                ?: 0,
            questionText = questionText,
            optionA = optionA,
            optionB = optionB,
            optionC = optionC,
            optionD = optionD,
            correctAnswer = getString("correctAnswer"),
            questionBankId = parsedQuestionBankId,
            importSessionId = parsedImportSessionId,
            customSessionName = getString("customSessionName")
        )
    }
}