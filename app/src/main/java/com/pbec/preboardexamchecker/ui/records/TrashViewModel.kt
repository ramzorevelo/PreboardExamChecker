package com.pbec.preboardexamchecker.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.data.models.Student
import com.pbec.preboardexamchecker.data.models.TrashedBank
import com.pbec.preboardexamchecker.data.models.TrashedExam
import com.pbec.preboardexamchecker.data.repository.ExamRepository
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import com.pbec.preboardexamchecker.data.repository.QuestionRepository
import com.pbec.preboardexamchecker.data.repository.ScanResultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** A trashed roster import (grouped by deletedBatch) shown in the Trash > Rosters tab. */
data class TrashedRoster(val batchId: Long, val label: String, val count: Int, val deletedAt: Long)

/** A single individually-trashed student shown in the Trash > Rosters tab. */
data class TrashedStudent(val docId: String, val name: String, val studentId: String, val deletedAt: Long)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: IScanResultRepository,
    private val examRepository: ExamRepository,
    private val questionRepository: QuestionRepository,
    private val firestore: FirebaseFirestore,
) : ViewModel() {

    val trashed: StateFlow<List<ScanResult>> =
        repository.getTrashed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashedExams: StateFlow<List<TrashedExam>> =
        examRepository.getTrashedExams().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashedBanks: StateFlow<List<TrashedBank>> =
        questionRepository.getTrashedBanks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All trashed students (Firestore can't query deletedAt != null without an index, so filter
     *  client-side). Split downstream into whole-import groups vs individually-deleted students. */
    private val trashedStudents: StateFlow<List<Student>> = callbackFlow {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val listener = firestore.collection("students")
            .whereEqualTo("uploadedByUid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                trySend(snap?.toObjects(Student::class.java).orEmpty().filter { it.deletedAt != null })
            }
        awaitClose { listener.remove() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whole-import deletions (deletedBatch set), grouped. */
    val trashedRosters: StateFlow<List<TrashedRoster>> = trashedStudents.map { list ->
        list.filter { it.deletedBatch != null }
            .groupBy { it.deletedBatch!! }
            .map { (id, g) -> TrashedRoster(id, g.first().importLabel.ifBlank { "Roster" }, g.size, g.maxOf { it.deletedAt ?: 0L }) }
            .sortedByDescending { it.deletedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Individually-deleted students (deletedBatch null). */
    val trashedStudentsIndividual: StateFlow<List<TrashedStudent>> = trashedStudents.map { list ->
        list.filter { it.deletedBatch == null }
            .map { TrashedStudent(it.id, it.name, it.studentId, it.deletedAt ?: 0L) }
            .sortedByDescending { it.deletedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Drop everything already past the 30-day window so it never shows in the lists.
        viewModelScope.launch { runCatching { repository.purgeExpired() } }
        viewModelScope.launch { runCatching { examRepository.purgeExpiredExams() } }
        viewModelScope.launch {
            runCatching { questionRepository.getExpiredBankIds().forEach { purgeBank(it) } }
        }
        viewModelScope.launch { runCatching { purgeExpiredRosters() } }
    }

    // ---- records (papers) ----
    fun restore(ids: List<Long>) = viewModelScope.launch { repository.restore(ids) }
    fun deleteForever(ids: List<Long>) = viewModelScope.launch { repository.purgeNow(ids) }

    // ---- exams (cascade: the exam's records travel with it) ----
    fun restoreExam(examId: Long) = viewModelScope.launch {
        examRepository.restoreExam(examId)
        runCatching { repository.restoreExamCascade(examId) }
    }

    fun deleteExamForever(examId: Long) = viewModelScope.launch {
        examRepository.purgeExam(examId)
        runCatching { repository.purgeExamCascade(examId) }
    }

    // ---- exam banks (restore/purge spans both repos so the bank's exams travel with it) ----
    fun restoreBank(bankId: String) = viewModelScope.launch {
        questionRepository.restoreQuestionBank(bankId)
        runCatching { examRepository.restoreExamsTrashedByBank(bankId) }
    }

    fun deleteBankForever(bankId: String) = viewModelScope.launch { purgeBank(bankId) }

    private suspend fun purgeBank(bankId: String) {
        questionRepository.purgeQuestionBank(bankId)
        runCatching { examRepository.purgeExamsTrashedByBank(bankId) }
    }

    // ---- rosters (whole-import deletions, keyed by deletedBatch) ----
    fun restoreRoster(batchId: Long) = viewModelScope.launch {
        rosterDocs(batchId).forEach { it.reference.update(clearTrash) }
    }

    fun deleteRosterForever(batchId: Long) = viewModelScope.launch {
        rosterDocs(batchId).forEach { it.reference.delete() }
    }

    // ---- individual students ----
    fun restoreStudent(docId: String) = viewModelScope.launch {
        runCatching { firestore.collection("students").document(docId).update(clearTrash).await() }
    }

    fun deleteStudentForever(docId: String) = viewModelScope.launch {
        runCatching { firestore.collection("students").document(docId).delete().await() }
    }

    private val clearTrash = mapOf("deletedAt" to FieldValue.delete(), "deletedBatch" to FieldValue.delete())

    private suspend fun rosterDocs(batchId: Long): List<com.google.firebase.firestore.DocumentSnapshot> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyList()
        return firestore.collection("students")
            .whereEqualTo("uploadedByUid", uid)
            .whereEqualTo("deletedBatch", batchId)
            .get().await().documents
    }

    private suspend fun purgeExpiredRosters() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val threshold = System.currentTimeMillis() - ScanResultRepository.RETENTION_MS
        firestore.collection("students")
            .whereEqualTo("uploadedByUid", uid)
            .get().await().documents
            .filter { (it.getLong("deletedAt") ?: Long.MAX_VALUE) < threshold }
            .forEach { it.reference.delete() }
    }

    /** Whole days left before [deletedAt] is permanently purged (0 = purges on the next sweep). */
    fun daysLeft(deletedAt: Long?): Long {
        if (deletedAt == null) return 0
        val remaining = ScanResultRepository.RETENTION_MS - (System.currentTimeMillis() - deletedAt)
        return remaining.coerceAtLeast(0L).let { TimeUnit.MILLISECONDS.toDays(it) }
    }
}
