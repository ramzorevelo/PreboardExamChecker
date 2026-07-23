package com.pbec.preboardexamchecker.ui.students

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.pbec.preboardexamchecker.data.models.ExamCluster
import com.pbec.preboardexamchecker.data.models.Student
import com.pbec.preboardexamchecker.data.repository.ExamClusterRepository
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import com.pbec.preboardexamchecker.domain.excel.PreboardRecordExcelGenerator
import com.pbec.preboardexamchecker.domain.excel.PreboardRecordRow
import com.pbec.preboardexamchecker.domain.excel.RosterTemplateGenerator
import com.pbec.preboardexamchecker.domain.model.toSummaryStatus
import com.pbec.preboardexamchecker.domain.pdf.ReportPdfStyle
import com.pbec.preboardexamchecker.domain.usecase.CalculateGwaUseCase
import com.pbec.preboardexamchecker.domain.usecase.SubjectScore
import com.pbec.preboardexamchecker.utils.RosterExcelParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A roster import batch the user can delete as a unit. */
data class ImportGroup(val importId: Long, val label: String, val count: Int)

@HiltViewModel
class StudentsViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val rosterParser: RosterExcelParser,
    private val templateGenerator: RosterTemplateGenerator,
    private val recordGenerator: PreboardRecordExcelGenerator,
    private val scanResultRepository: IScanResultRepository,
    private val clusterRepository: ExamClusterRepository,
    private val calculateGwa: CalculateGwaUseCase,
    private val studentRecordsRequest: com.pbec.preboardexamchecker.ui.records.StudentRecordsRequest,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Hand Records the set of students to scope to, before navigating (see StudentRecordsRequest). */
    fun requestRecordsFor(studentIds: List<String>) = studentRecordsRequest.request(studentIds)

    private val _students = MutableStateFlow<List<Student>>(emptyList())
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _selectedYearLevel = MutableStateFlow("All")
    val selectedYearLevel = _selectedYearLevel.asStateFlow()
    private val _selectedBlock = MutableStateFlow("All")
    val selectedBlock = _selectedBlock.asStateFlow()
    private val _selectedCourse = MutableStateFlow("All")
    val selectedCourse = _selectedCourse.asStateFlow()
    private val _selectedSchoolYear = MutableStateFlow("All")
    val selectedSchoolYear = _selectedSchoolYear.asStateFlow()
    private val _sort = MutableStateFlow(StudentSort.NAME_ASC)
    val sort = _sort.asStateFlow()

    /** Clusters to scope a roster export to; scores come from the chosen cluster's scans only. */
    val clusters: StateFlow<List<ExamCluster>> =
        clusterRepository.observeClusters()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val _studentsState = MutableStateFlow<List<Student>>(emptyList())
    val studentsState: StateFlow<List<Student>> = _studentsState.asStateFlow()
    val allStudents: StateFlow<List<Student>> = _students.asStateFlow()

    /** Distinct import batches present in the active roster, newest first (for delete-by-import). */
    val imports: StateFlow<List<ImportGroup>> = _students.map { list ->
        list.filter { it.importId != 0L }
            .groupBy { it.importId }
            .map { (id, g) -> ImportGroup(id, g.first().importLabel.ifBlank { "Roster" }, g.size) }
            .sortedByDescending { it.importId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        observeStudents()
        
        viewModelScope.launch {
            // Two-stage combine: 5 filter flows fold into one selection, then apply to the list
            // (combine's typed overload tops out at 5 arguments).
            val selections = combine(
                _searchQuery, _selectedBlock, _selectedCourse, _selectedSchoolYear, _selectedYearLevel
            ) { query, block, course, schoolYear, yearLevel ->
                Selections(query, block, course, schoolYear, yearLevel)
            }
            combine(_students, selections, _sort) { students, sel, sort ->
                val filtered = students.filter { student ->
                    val queryMatch = sel.query.isBlank() ||
                        student.name.contains(sel.query, ignoreCase = true) ||
                        student.studentId.contains(sel.query, ignoreCase = true)
                    val yearMatch = sel.yearLevel == "All" || student.yearLevel.equals(sel.yearLevel, ignoreCase = true)
                    val blockMatch = sel.block == "All" || student.block.equals(sel.block, ignoreCase = true)
                    val courseMatch = sel.course == "All" || student.program.equals(sel.course, ignoreCase = true)
                    val schoolYearMatch = sel.schoolYear == "All" || student.schoolYear.equals(sel.schoolYear, ignoreCase = true)

                    queryMatch && yearMatch && blockMatch && courseMatch && schoolYearMatch
                }
                filtered.sortedWith(sort.comparator)
            }
                // StateFlow.value is thread-safe, so assigning from Default is fine.
                .flowOn(Dispatchers.Default)
                .collect {
                    _studentsState.value = it
                }
        }
    }

    private data class Selections(
        val query: String,
        val block: String,
        val course: String,
        val schoolYear: String,
        val yearLevel: String,
    )

    private fun observeStudents() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            _isLoading.value = false
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("User not authenticated. Please log in."))
            return
        }

        _isLoading.value = true
        // No orderBy: whereEqualTo + orderBy on different fields needs a composite index.
        // Sorted client-side below instead.
        firestore.collection("students")
            .whereEqualTo("uploadedByUid", currentUser.uid)
            .addSnapshotListener { snapshot, error ->
                _isLoading.value = false
                if (error != null) {
                    val message = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        "Permission Denied: Please ensure you are logged in as an authorized user."
                    } else {
                        "Error loading students: ${error.message}"
                    }
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar(message))
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    // Hide trashed rosters; they live in the Trash screen until restored or purged.
                    _students.value = snapshot.toObjects(Student::class.java)
                        .filter { it.deletedAt == null }
                        .sortedBy { it.name.lowercase() }
                }
            }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateYearLevelFilter(yearLevel: String) {
        _selectedYearLevel.value = yearLevel
    }

    fun updateBlockFilter(block: String) {
        _selectedBlock.value = block
    }

    fun updateCourseFilter(course: String) {
        _selectedCourse.value = course
    }

    fun updateSchoolYearFilter(schoolYear: String) {
        _selectedSchoolYear.value = schoolYear
    }

    fun updateSort(sort: StudentSort) {
        _sort.value = sort
    }

    fun clearFilters() {
        _selectedYearLevel.value = "All"
        _selectedBlock.value = "All"
        _selectedCourse.value = "All"
        _selectedSchoolYear.value = "All"
    }

    /**
     * Import a roster .xlsx and upsert by Student ID, so re-importing a corrected file updates
     * rows instead of duplicating. Sets uploadedByUid (scanner visibility) and yearLevel=4
     * (preboard is 4th-year only). Repeatable across school years.
     */
    fun importRoster(uri: Uri) {
        viewModelScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Not authenticated. Please log in.")); return@launch
            }
            val parsed = withContext(Dispatchers.IO) {
                runCatching { rosterParser.parse(context, uri) }
            }.getOrElse {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Couldn't read roster: ${it.message}")); return@launch
            }
            if (parsed.isEmpty()) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("No students found in that file.")); return@launch
            }
            try {
                // One existing-doc lookup, so we upsert without a query per student.
                val existing = firestore.collection("students")
                    .whereEqualTo("uploadedByUid", uid).get().await()
                val byStudentId = existing.documents
                    .mapNotNull { d -> d.getString("studentId")?.let { it to d.reference } }
                    .toMap()

                // One batch id + label for this whole import, so it can later be deleted as a unit.
                // Include the import timestamp so two same-program/year imports stay distinguishable.
                val importId = System.currentTimeMillis()
                val meta = parsed.first()
                val stamp = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(importId))
                val importLabel = (listOfNotNull(
                    meta.program.trim().ifBlank { null },
                    meta.schoolYear.trim().ifBlank { null }?.let { "SY $it" },
                ).joinToString(" · ").ifBlank { "Roster" }) + " · $stamp"

                var batch = firestore.batch()
                var ops = 0
                for (rs in parsed) {
                    val existingRef = byStudentId[rs.studentId]
                    val ref = existingRef ?: firestore.collection("students").document()
                    val data = mutableMapOf<String, Any>(
                        "name" to rs.name,
                        "studentId" to rs.studentId,
                        "block" to rs.block,
                        "email" to rs.email,
                        "program" to rs.program,
                        "yearLevel" to "4",
                        "gender" to rs.gender,
                        "schoolYear" to rs.schoolYear,
                        "instructor" to rs.instructor,
                        "uploadedByUid" to uid,
                        "importId" to importId,
                        "importLabel" to importLabel,
                        // Re-importing an ID that was trashed brings it back.
                        "deletedAt" to com.google.firebase.firestore.FieldValue.delete(),
                    )
                    if (existingRef == null) data["createdAt"] = com.google.firebase.Timestamp.now()
                    batch.set(ref, data, com.google.firebase.firestore.SetOptions.merge())
                    // Firestore caps a batch at 500 writes; commit in safe chunks.
                    if (++ops >= 450) { batch.commit().await(); batch = firestore.batch(); ops = 0 }
                }
                if (ops > 0) batch.commit().await()
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Imported ${parsed.size} students."))
            } catch (e: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Import failed: ${e.message}"))
            }
        }
    }

    /** Soft-delete a whole import batch (30-day Trash); deletedBatch tags it as a roster deletion. */
    fun deleteImport(importId: Long) {
        val docs = _students.value.filter { it.importId == importId }
        softDelete(docs, batchId = importId, noun = "students")
    }

    /** Soft-delete individually-chosen students (deletedBatch stays null → "Individual" trash). */
    fun deleteStudents(ids: Set<String>) {
        softDelete(_students.value.filter { it.id in ids }, batchId = null, noun = "students")
    }

    fun deleteStudent(documentId: String) {
        softDelete(_students.value.filter { it.id == documentId }, batchId = null, noun = "student")
    }

    private fun softDelete(docs: List<Student>, batchId: Long?, noun: String) {
        val targets = docs.filter { it.id.isNotBlank() }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val update = mapOf("deletedAt" to System.currentTimeMillis(), "deletedBatch" to batchId)
                var batch = firestore.batch()
                var ops = 0
                for (st in targets) {
                    batch.update(firestore.collection("students").document(st.id), update)
                    if (++ops >= 450) { batch.commit().await(); batch = firestore.batch(); ops = 0 }
                }
                if (ops > 0) batch.commit().await()
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Moved ${targets.size} $noun to Trash."))
            } catch (e: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Delete failed: ${e.message}"))
            }
        }
    }

    /**
     * Export the chosen students in the institutional PreBoard format for one [cluster], filling each
     * subject's score from that cluster's scans. Cluster-scoped because a student's three subject
     * scores must come from the same administration. Written to the user-picked [uri].
     */
    fun exportSelectedRoster(uri: Uri, ids: Set<String>, cluster: ExamCluster) {
        val chosen = _students.value.filter { it.id in ids }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            try {
                // Only this cluster's scans count; newest per subject wins (getAll() is newest-first).
                // Students with no scans in the cluster stay blank.
                val gwaByStudent = scanResultRepository.getAll().first()
                    .filter { it.clusterId == cluster.id }
                    .groupBy { it.studentId }
                    .mapValues { (_, scans) ->
                        val latest = scans.groupBy { it.subject }.map { (_, s) -> s.first() }
                        calculateGwa(latest.map { SubjectScore(it.subject, it.score, it.total) })
                    }
                val rows = chosen.map { s ->
                    val gwa = gwaByStudent[s.studentId]
                    fun pctOf(subject: String) = gwa?.lines?.firstOrNull { it.subject == subject }?.percentage
                    PreboardRecordRow(
                        name = s.name, studentId = s.studentId, block = s.block, email = s.email,
                        gender = s.gender,
                        mathPct = pctOf("Mathematics"),
                        esasPct = pctOf("ESAS"),
                        profPct = pctOf("Professional EE"),
                        remark = gwa?.let { ReportPdfStyle.statusText(it.toSummaryStatus()) }.orEmpty(),
                    )
                }
                val file = recordGenerator.generate(
                    context,
                    clusterName = cluster.name,
                    program = chosen.firstOrNull { it.program.isNotBlank() }?.program.orEmpty(),
                    schoolYear = cluster.schoolYear.orEmpty(),
                    instructor = chosen.firstOrNull { it.instructor.isNotBlank() }?.instructor.orEmpty(),
                    rows = rows,
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("Could not open destination")
                }
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Exported ${rows.size} students."))
            } catch (e: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Export failed: ${e.message}"))
            }
        }
    }

    /** Write the blank import template (with John/Jane Doe samples) to a user-chosen file. */
    fun downloadTemplate(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { templateGenerator.write(it) }
                        ?: error("Could not open destination")
                }
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Template saved."))
            } catch (e: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Couldn't save template: ${e.message}"))
            }
        }
    }

    fun addStudent(name: String, studentId: String, program: String, yearLevel: String, block: String, email: String) {
        // uploadedByUid is required: StudentRepository (used by the scanner to resolve a scanned ID
        // to block/name) filters by it, so an untagged student would be invisible while scanning.
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val student = hashMapOf(
            "name" to name,
            "studentId" to studentId,
            "program" to program,
            "yearLevel" to yearLevel,
            "block" to block,
            "email" to email,
            "uploadedByUid" to (uid ?: ""),
            "createdAt" to com.google.firebase.Timestamp.now()
        )

        firestore.collection("students")
            .add(student)
            .addOnSuccessListener {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Student added successfully"))
            }
            .addOnFailureListener { e ->
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Failed to add student: ${e.message}"))
            }
    }

    fun updateStudent(documentId: String, name: String, studentId: String, program: String, yearLevel: String, block: String, email: String) {
        val updates = mapOf(
            "name" to name,
            "studentId" to studentId,
            "program" to program,
            "yearLevel" to yearLevel,
            "block" to block,
            "email" to email
        )

        firestore.collection("students").document(documentId)
            .update(updates)
            .addOnSuccessListener {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Student updated successfully"))
            }
            .addOnFailureListener { e ->
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Failed to update student: ${e.message}"))
            }
    }

    sealed class UiEvent {
        data class ShowSnackbar(val message: String) : UiEvent()
    }
}

enum class StudentSort(val label: String, val comparator: Comparator<Student>) {
    NAME_ASC("Name (A–Z)", compareBy { it.name.lowercase() }),
    NAME_DESC("Name (Z–A)", compareByDescending { it.name.lowercase() }),
    ID_ASC("Student ID", compareBy { it.studentId }),
    BLOCK_ASC("Block", compareBy({ it.block.lowercase() }, { it.name.lowercase() })),
}
