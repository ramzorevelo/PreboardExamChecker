package com.pbec.preboardexamchecker.ui.clusters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pbec.preboardexamchecker.data.models.Exam
import com.pbec.preboardexamchecker.data.models.ExamCluster

/**
 * Exam-clusters body, hosted as a swipeable tab inside
 * [com.pbec.preboardexamchecker.ui.subjects.SubjectsScreen]. Provides no Scaffold/TopAppBar —
 * the parent supplies the shared "Electrical Engineering" header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClustersContent(
    modifier: Modifier = Modifier,
    viewModel: ClusterViewModel = hiltViewModel(),
) {
    val clusters by viewModel.clusters.collectAsState()
    val examsBySubject by viewModel.examsBySubject.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<ExamCluster?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (clusters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No exam clusters yet.\nTap + to group one exam per subject.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom padding clears the FAB so the last cluster card isn't covered.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(clusters, key = { it.id }) { cluster ->
                    ClusterCard(
                        cluster = cluster,
                        subjects = viewModel.subjects,
                        // Pass the collected state (not viewModel::examName) so the card
                        // recomposes once exams finish loading instead of showing "Exam #id".
                        examsBySubject = examsBySubject,
                        onEdit = { editing = cluster; showDialog = true },
                        onDelete = { viewModel.deleteCluster(cluster) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { editing = null; showDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Default.Add, contentDescription = "New cluster")
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showDialog) {
        ClusterEditDialog(
            existing = editing,
            subjects = viewModel.subjects,
            examsBySubject = examsBySubject,
            onDismiss = { showDialog = false },
            onSave = { name, sy, ids ->
                viewModel.saveCluster(editing, name, sy, ids)
                showDialog = false
            },
        )
    }
}

@Composable
private fun ClusterCard(
    cluster: ExamCluster,
    subjects: List<String>,
    examsBySubject: Map<String, List<Exam>>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cluster.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    cluster.schoolYear?.let {
                        Text("SY $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            subjects.forEach { subject ->
                val id = cluster.examIdsBySubject[subject]
                val label = when {
                    id == null -> "—"
                    // Empty map = exams haven't loaded yet; show a neutral placeholder, not "Exam #id".
                    examsBySubject.isEmpty() -> "Loading…"
                    else -> examsBySubject[subject]?.firstOrNull { it.id == id }?.examName ?: "Exam #$id"
                }
                Text("$subject: $label", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClusterEditDialog(
    existing: ExamCluster?,
    subjects: List<String>,
    examsBySubject: Map<String, List<Exam>>,
    onDismiss: () -> Unit,
    onSave: (name: String, schoolYear: String, examIds: Map<String, Long>) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var schoolYear by remember { mutableStateOf(existing?.schoolYear ?: "") }
    // Per-subject selected exam id; seeded from the edited cluster.
    val selected = remember {
        mutableStateMapOf<String, Long>().apply { existing?.examIdsBySubject?.let { putAll(it) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Exam Cluster" else "Edit Exam Cluster") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Cluster name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = schoolYear, onValueChange = { schoolYear = it },
                    label = { Text("School year (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                subjects.forEach { subject ->
                    ExamPicker(
                        subject = subject,
                        exams = examsBySubject[subject].orEmpty(),
                        selectedId = selected[subject],
                        onSelected = { selected[subject] = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, schoolYear, selected.toMap()) },
                enabled = name.isNotBlank() && subjects.all { selected[it] != null },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamPicker(
    subject: String,
    exams: List<Exam>,
    selectedId: Long?,
    onSelected: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = exams.firstOrNull { it.id == selectedId }?.examName
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName ?: if (exams.isEmpty()) "No exams for $subject" else "Select exam",
            onValueChange = {}, readOnly = true,
            label = { Text(subject) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            exams.forEach { exam ->
                DropdownMenuItem(
                    text = { Text(exam.examName) },
                    onClick = { onSelected(exam.id); expanded = false },
                )
            }
        }
    }
}
