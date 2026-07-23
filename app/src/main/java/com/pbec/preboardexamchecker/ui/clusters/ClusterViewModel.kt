package com.pbec.preboardexamchecker.ui.clusters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pbec.preboardexamchecker.data.models.Exam
import com.pbec.preboardexamchecker.data.models.ExamCluster
import com.pbec.preboardexamchecker.data.repository.ExamClusterRepository
import com.pbec.preboardexamchecker.data.repository.ExamRepository
import com.pbec.preboardexamchecker.domain.pdf.ReportPdfStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ClusterViewModel @Inject constructor(
    private val clusterRepository: ExamClusterRepository,
    private val examRepository: ExamRepository,
) : ViewModel() {

    /** Canonical subject order — same source the GWA weights and exports use. */
    val subjects: List<String> = ReportPdfStyle.subjectOrder

    val clusters: StateFlow<List<ExamCluster>> =
        clusterRepository.observeClusters()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Picker source for the create/edit dialog; also resolves examId -> name for the list. */
    private val _examsBySubject = MutableStateFlow<Map<String, List<Exam>>>(emptyMap())
    val examsBySubject: StateFlow<Map<String, List<Exam>>> = _examsBySubject.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _examsBySubject.value = subjects.associateWith { subject ->
                runCatching { examRepository.getExamsBySubjectOnce(subject) }.getOrDefault(emptyList())
            }
        }
    }

    /** [existing] non-null => edit (preserves id/createdAt); null => create. */
    fun saveCluster(
        existing: ExamCluster?,
        name: String,
        schoolYear: String,
        examIdsBySubject: Map<String, Long>,
    ) {
        if (name.isBlank()) { _message.value = "Cluster name cannot be empty."; return }
        if (examIdsBySubject.size < subjects.size) {
            _message.value = "Pick one exam for each subject."; return
        }
        viewModelScope.launch {
            runCatching {
                clusterRepository.saveCluster(
                    (existing ?: ExamCluster(createdAt = Date().time)).copy(
                        name = name.trim(),
                        schoolYear = schoolYear.trim().ifBlank { null },
                        examIdsBySubject = examIdsBySubject,
                    )
                )
            }.onSuccess { _message.value = "Cluster '${name.trim()}' saved." }
                .onFailure { _message.value = "Error saving cluster: ${it.message}" }
        }
    }

    fun deleteCluster(cluster: ExamCluster) {
        viewModelScope.launch {
            runCatching { clusterRepository.deleteCluster(cluster.id) }
                .onFailure { _message.value = "Error deleting cluster: ${it.message}" }
        }
    }

    fun clearMessage() { _message.value = null }
}
