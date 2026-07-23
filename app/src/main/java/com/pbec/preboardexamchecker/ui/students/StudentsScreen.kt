package com.pbec.preboardexamchecker.ui.students

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.data.models.ExamCluster
import com.pbec.preboardexamchecker.data.models.Student
import com.pbec.preboardexamchecker.ui.Screen
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import kotlinx.coroutines.flow.collectLatest

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentsScreen(
    navController: NavController? = null,
    viewModel: StudentsViewModel = hiltViewModel(),
) {
    val students by viewModel.studentsState.collectAsState()
    val allStudents by viewModel.allStudents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedSchoolYear by viewModel.selectedSchoolYear.collectAsState()
    val selectedBlock by viewModel.selectedBlock.collectAsState()
    val selectedCourse by viewModel.selectedCourse.collectAsState()
    val selectedSort by viewModel.sort.collectAsState()
    val imports by viewModel.imports.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importRoster(it) }
    }
    val templateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri?.let { viewModel.downloadTemplate(it) }
    }

    // Multi-select (entered by long-press); tracks Student.id (doc id).
    val selectedIds = remember { mutableStateListOf<String>() }
    val selectionMode = selectedIds.isNotEmpty()
    fun clearSelection() = selectedIds.clear()
    val clusters by viewModel.clusters.collectAsState()
    // Chosen between the cluster picker and the file dialog, so the launcher knows which cluster to scope to.
    var pendingExportCluster by remember { mutableStateOf<ExamCluster?>(null) }
    var showClusterPicker by remember { mutableStateOf(false) }
    val rosterExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        val cluster = pendingExportCluster
        if (uri != null && cluster != null) { viewModel.exportSelectedRoster(uri, selectedIds.toSet(), cluster); clearSelection() }
        pendingExportCluster = null
    }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingStudent by remember { mutableStateOf<Student?>(null) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteImport by remember { mutableStateOf(false) }

    val schoolYearOptions = remember(allStudents) {
        listOf("All") + allStudents.map { it.schoolYear.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }
    val blockOptions = remember(allStudents) {
        listOf("All") + allStudents.map { it.block.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }
    val courseOptions = remember(allStudents) {
        listOf("All") + allStudents.map { it.program.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is StudentsViewModel.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    fun openRecordsFor(ids: Collection<String>) {
        val studentIds = students.filter { it.id in ids }.map { it.studentId }.filter { it.isNotBlank() }
        if (studentIds.isEmpty()) return
        viewModel.requestRecordsFor(studentIds)
        // Switch to the Records tab (not a push onto Students' stack), so the bottom nav keeps working.
        navController?.let { nav ->
            nav.navigate(Screen.Records.route) {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        // MainActivity's outer Scaffold already reserves the status-bar space; zero this inner one.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { clearSelection() },
                    onDelete = { showDeleteSelected = true },
                    onExportRoster = { showClusterPicker = true },
                    onViewInRecords = { openRecordsFor(selectedIds.toList()); clearSelection() },
                )
            } else {
                BrandTopAppBar(
                    title = "Students",
                    actions = {
                        IconButton(onClick = { navController?.navigate(Screen.Trash.createRoute("rosters")) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Trash")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Import roster") }, onClick = { menuOpen = false; importLauncher.launch(XLSX_MIME) })
                            DropdownMenuItem(text = { Text("Download template") }, onClick = { menuOpen = false; templateLauncher.launch("PreBoard Examination Record.xlsx") })
                            DropdownMenuItem(
                                text = { Text("Delete an import…") },
                                enabled = imports.isNotEmpty(),
                                onClick = { menuOpen = false; showDeleteImport = true },
                            )
                        }
                    },
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isLoading && students.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Records-style control row: visible sort + expandable search + filter toggle.
                    ControlRow(
                        search = searchQuery,
                        onSearch = viewModel::updateSearchQuery,
                        sort = selectedSort,
                        onSort = viewModel::updateSort,
                        filtersExpanded = filtersExpanded,
                        onToggleFilters = { filtersExpanded = !filtersExpanded },
                    )
                    AnimatedVisibility(visible = filtersExpanded) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudentFilterDropdown("School Yr", selectedSchoolYear, schoolYearOptions, viewModel::updateSchoolYearFilter, Modifier.weight(1f))
                                StudentFilterDropdown("Block", selectedBlock, blockOptions, viewModel::updateBlockFilter, Modifier.weight(1f))
                            }
                            StudentFilterDropdown("Course", selectedCourse, courseOptions, viewModel::updateCourseFilter, Modifier.fillMaxWidth())
                        }
                    }

                    // Select-all sits above the list (appears in selection mode), not in the header.
                    if (selectionMode) {
                        val allSelected = students.isNotEmpty() && selectedIds.size == students.size
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = {
                                    if (selectedIds.size == students.size) clearSelection()
                                    else { selectedIds.clear(); selectedIds.addAll(students.map { it.id }) }
                                },
                            )
                            Text(
                                if (allSelected) "Deselect all" else "Select all (${students.size})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    if (students.isEmpty()) {
                        EmptyListPlaceholder(Modifier.align(Alignment.CenterHorizontally), searchQuery.isNotEmpty())
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(students, key = { it.id }) { student ->
                                StudentItem(
                                    student = student,
                                    selectionMode = selectionMode,
                                    selected = student.id in selectedIds,
                                    onTap = {
                                        if (selectionMode) {
                                            if (student.id in selectedIds) selectedIds.remove(student.id) else selectedIds.add(student.id)
                                        } else openRecordsFor(listOf(student.id))
                                    },
                                    onLongPress = { if (student.id !in selectedIds) selectedIds.add(student.id) },
                                    onEdit = { editingStudent = student; showAddEditDialog = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteSelected) {
        AlertDialog(
            onDismissRequest = { showDeleteSelected = false },
            title = { Text("Delete ${selectedIds.size} student${if (selectedIds.size == 1) "" else "s"}") },
            text = { Text("Move them to Trash? You can restore them within 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteStudents(selectedIds.toSet()); showDeleteSelected = false; clearSelection()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteSelected = false }) { Text("Cancel") } },
        )
    }

    if (showClusterPicker) {
        ClusterPickerDialog(
            clusters = clusters,
            onDismiss = { showClusterPicker = false },
            onPick = { cluster ->
                showClusterPicker = false
                pendingExportCluster = cluster
                rosterExportLauncher.launch("${cluster.name.ifBlank { "roster" }}.xlsx")
            },
        )
    }

    if (showDeleteImport) {
        AlertDialog(
            onDismissRequest = { showDeleteImport = false },
            title = { Text("Delete an import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Moves the whole roster to Trash (restorable for 30 days).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    imports.forEach { imp ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(imp.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${imp.count} student${if (imp.count == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.deleteImport(imp.importId); showDeleteImport = false }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDeleteImport = false }) { Text("Close") } },
        )
    }

    if (showAddEditDialog) {
        AddEditStudentDialog(
            student = editingStudent,
            onDismiss = { showAddEditDialog = false },
            onConfirm = { name, sid, prog, year, sect, email ->
                val target = editingStudent
                if (target == null) viewModel.addStudent(name, sid, prog, year, sect, email)
                else viewModel.updateStudent(target.id, name, sid, prog, year, sect, email)
                showAddEditDialog = false
            },
            onDelete = {
                editingStudent?.let { viewModel.deleteStudent(it.id) }
                showAddEditDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onExportRoster: () -> Unit,
    onViewInRecords: () -> Unit,
) {
    var exportMenu by remember { mutableStateOf(false) }
    BrandTopAppBar(
        title = "$count selected",
        selection = true,
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
        },
        actions = {
            IconButton(onClick = onViewInRecords) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "View in Records") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete selected") }
            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.FileDownload, contentDescription = "Export") }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                DropdownMenuItem(text = { Text("Export as roster (Excel)") }, onClick = { exportMenu = false; onExportRoster() })
            }
        },
    )
}

/** Picks the cluster to scope a roster export to; exam scores come from its scans only. */
@Composable
private fun ClusterPickerDialog(
    clusters: List<ExamCluster>,
    onDismiss: () -> Unit,
    onPick: (ExamCluster) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export roster for which cluster?") },
        text = {
            if (clusters.isEmpty()) {
                Text(
                    "No exam clusters yet. Create one to group a round's exams, then export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Scores come from the chosen cluster's scans.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    clusters.forEach { cluster ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onPick(cluster) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(cluster.name.ifBlank { "Untitled cluster" }, style = MaterialTheme.typography.bodyMedium)
                            cluster.schoolYear?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlRow(
    search: String,
    onSearch: (String) -> Unit,
    sort: StudentSort,
    onSort: (StudentSort) -> Unit,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
) {
    var searchExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchExpanded) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                placeholder = { Text("Search name or ID…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = {
                    IconButton(onClick = { onSearch(""); searchExpanded = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
            )
        } else {
            StudentSortDropdown(sort, onSort, Modifier.weight(1f))
            IconButton(onClick = { searchExpanded = true }) { Icon(Icons.Default.Search, contentDescription = "Search") }
        }
        IconButton(onClick = onToggleFilters) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "Filters",
                tint = if (filtersExpanded) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentSortDropdown(selected: StudentSort, onSelect: (StudentSort) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, Modifier.size(18.dp)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StudentSort.entries.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.label) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentFilterDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth().height(56.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueSelected(option); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StudentItem(
    student: Student,
    selectionMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onTap() })
                Spacer(Modifier.width(8.dp))
            } else {
                StudentAvatar(name = student.name)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(student.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("ID: ${student.studentId}", style = MaterialTheme.typography.bodySmall)
                Text(student.block.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (student.email.isNotBlank()) {
                    Text(student.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
}
            if (!selectionMode) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun StudentAvatar(name: String) {
    val initials = name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyListPlaceholder(modifier: Modifier, isSearching: Boolean) {
    Column(modifier = modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = if (isSearching) Icons.Default.SearchOff else Icons.Default.PersonOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isSearching) "No students found matching your search." else "No students enrolled yet.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStudentDialog(
    student: Student?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(student?.name ?: "") }
    var studentId by remember { mutableStateOf(student?.studentId ?: "") }
    var program by remember { mutableStateOf(student?.program ?: "") }
    var yearLevel by remember { mutableStateOf(student?.yearLevel ?: "") }
    var block by remember { mutableStateOf(student?.block ?: "") }
    var email by remember { mutableStateOf(student?.email ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (student == null) "Add Student" else "Edit Student") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = studentId, onValueChange = { studentId = it }, label = { Text("Student ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = program, onValueChange = { program = it }, label = { Text("Program") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = yearLevel, onValueChange = { yearLevel = it }, label = { Text("Year Level") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = block, onValueChange = { block = it }, label = { Text("Block") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (for result slips)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, studentId, program, yearLevel, block, email.trim()) }, enabled = name.isNotBlank() && studentId.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (student != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
