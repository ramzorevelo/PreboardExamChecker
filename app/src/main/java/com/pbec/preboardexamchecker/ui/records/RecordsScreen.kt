package com.pbec.preboardexamchecker.ui.records

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.ui.Screen
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import com.pbec.preboardexamchecker.ui.theme.LocalExtendedColors
import com.pbec.preboardexamchecker.ui.scanner.ResultAnswerGrid
import com.pbec.preboardexamchecker.ui.scanner.ScannerViewModel
import com.pbec.preboardexamchecker.domain.model.ExamStats
import com.pbec.preboardexamchecker.domain.model.QuestionStat
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.model.SummaryStatus
import com.pbec.preboardexamchecker.domain.model.selectionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val PDF_MIME = "application/pdf"
private const val ZIP_MIME = "application/zip"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    navController: NavController? = null,
    viewModel: RecordsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val examStats by viewModel.examStats.collectAsState()
    val filters = state.filters
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pager so the user can swipe between Overall / By Subject / Exam Stats (not just tap).
    // The ViewModel's filters.tab stays the source of truth; the two effects below keep them synced.
    val pagerState = rememberPagerState(initialPage = filters.tab.ordinal) { RecordsTab.entries.size }
    val snackbarHostState = remember { SnackbarHostState() }
    // Note: a "show only these students" request from the Students screen is applied in the
    // ViewModel (see StudentRecordsRequest), then surfaced as the clearable banner below.

    // Opening Records from a student is a tab switch (so the bottom nav keeps working), which leaves
    // Home — not Students — as the back target. While scoped to a student, send Back to Students.
    if (filters.studentIds.isNotEmpty() && navController != null) {
        BackHandler {
            viewModel.clearStudentIds()
            navController.navigate(Screen.Students.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Re-scan reuses the Activity-scoped scanner session (same instance ScannerEntryPoint drives),
    // so loading a session here and then navigating to the Capture tab resumes it mid-flow.
    val scannerViewModel: ScannerViewModel = hiltViewModel(
        viewModelStoreOwner = LocalContext.current as androidx.activity.ComponentActivity
    )
    fun rescanPaper(row: SubjectScanRow) {
        val r = row.result
        scannerViewModel.loadSession(
            r.subject, r.examId, replaceResultId = r.id,
            clusterId = r.clusterId, clusterName = r.clusterName,
        )
        navController?.navigate(Screen.Capture.route)
    }

    var exporting by remember { mutableStateOf(false) }
    // Email results can be long (per-student failure reasons); a dialog stays up until dismissed,
    // unlike a snackbar which times out before it can be read.
    var emailResultDialog by remember { mutableStateOf<String?>(null) }
    var slipTarget by remember { mutableStateOf<StudentGwaRow?>(null) }
    var recordToDelete by remember { mutableStateOf<SubjectScanRow?>(null) }
    // Tapped By-Subject paper whose per-item breakdown overlay is showing (null = none).
    var detailTarget by remember { mutableStateOf<SubjectScanRow?>(null) }
    // Long-press multi-select over Overall rows; identity is StudentGwaRow.selectionKey().
    val selectedKeys = remember { mutableStateListOf<String>() }
    val selectionMode = selectedKeys.isNotEmpty()
    fun clearSelection() = selectedKeys.clear()
    fun toggleKey(k: String) { if (k in selectedKeys) selectedKeys.remove(k) else selectedKeys.add(k) }
    // Non-selection class-report export waits on a block choice (which class?).
    var blockPicker by remember { mutableStateOf<BlockExportSpec?>(null) }
    // Filter-panel expansion is kept independently per tab and retained across tab switches.
    var overallFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    var subjectFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    var examFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    var showSubjectScores by rememberSaveable { mutableStateOf(true) }

    // One pending generator at a time; the matching SAF launcher copies its bytes out.
    var pendingSave by remember { mutableStateOf<(suspend () -> File?)?>(null) }

    fun performSave(uri: Uri?) {
        val generate = pendingSave
        pendingSave = null
        if (uri == null || generate == null) return
        scope.launch {
            exporting = true
            try {
                val file = generate()
                if (file == null) {
                    snackbarHostState.showSnackbar("Nothing to export")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
                snackbarHostState.showSnackbar("Saved")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Save failed: ${e.message}")
            } finally {
                exporting = false
            }
        }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { performSave(it) }
    val saveXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { performSave(it) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { performSave(it) }

    fun saveExport(name: String, mime: String, generate: suspend () -> File?) {
        pendingSave = generate
        when (mime) {
            XLSX_MIME -> saveXlsx.launch("$name.xlsx")
            ZIP_MIME -> saveZip.launch("$name.zip")
            else -> savePdf.launch("$name.pdf")
        }
    }

    fun share(mime: String, generate: suspend () -> File?) {
        scope.launch {
            exporting = true
            try {
                val file = generate()
                if (file == null) {
                    snackbarHostState.showSnackbar("Nothing to export")
                    return@launch
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Export failed: ${e.message}")
            } finally {
                exporting = false
            }
        }
    }

    // Selection is an Overall-tab concept; drop it when navigating away so the bar doesn't linger.
    LaunchedEffect(filters.tab) { if (filters.tab != RecordsTab.OVERALL) clearSelection() }

    // Settling the pager (via swipe or tap) drives the ViewModel's active tab.
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setTab(RecordsTab.entries[pagerState.currentPage])
    }
    // Reverse sync for programmatic tab changes; skip while dragging so we don't fight the gesture.
    LaunchedEffect(filters.tab) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != filters.tab.ordinal) {
            pagerState.animateScrollToPage(filters.tab.ordinal)
        }
    }

    // Class report scoped to keys; label only drives the saved filename (title comes from the VM).
    fun runClassReport(keys: Set<String>, label: String, mime: String, save: Boolean) {
        val nm = "class_report_${label.ifBlank { "all" }.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val gen: suspend () -> File? =
            if (mime == XLSX_MIME) ({ viewModel.generateClassReportXlsx(keys) })
            else ({ viewModel.generateClassReport(keys) })
        if (save) saveExport(nm, mime, gen) else share(mime, gen)
    }
    // One multi-page PDF: shares via ACTION_SEND, so chat apps (Messenger) accept it.
    fun runSlipsCombined(keys: Set<String>, save: Boolean) {
        val gen: suspend () -> File? = { viewModel.generateStudentSlipsCombined(keys) }
        if (save) saveExport("student_slips", PDF_MIME, gen) else share(PDF_MIME, gen)
    }
    // One .zip of separate PDFs: a single file, so it both saves and shares (email, not Messenger).
    fun runSlipsZip(keys: Set<String>, save: Boolean) {
        val gen: suspend () -> File? = { viewModel.generateStudentSlipsZip(keys) }
        if (save) saveExport("student_slips", ZIP_MIME, gen) else share(ZIP_MIME, gen)
    }
    // Genuinely separate PDFs at once; ACTION_SEND_MULTIPLE, so chat apps drop out of the sheet.
    fun shareSlipsSeparate(keys: Set<String>) {
        scope.launch {
            exporting = true
            try {
                val files = viewModel.generateStudentSlipFiles(keys)
                if (files.isEmpty()) {
                    snackbarHostState.showSnackbar("Nothing to export")
                    return@launch
                }
                val uris = ArrayList(files.map {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                })
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = PDF_MIME
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Export failed: ${e.message}")
            } finally {
                exporting = false
            }
        }
    }
    fun runPreboardRecord(keys: Set<String>, save: Boolean) {
        val gen: suspend () -> File? = { viewModel.generatePreboardRecordXlsx(keys) }
        if (save) saveExport("preboard_record", XLSX_MIME, gen) else share(XLSX_MIME, gen)
    }
    // Emails each selected student their slip in one tap; no email app opens. Reports missing addresses.
    fun emailSlips(keys: Set<String>) {
        if (!viewModel.isEmailConfigured()) {
            scope.launch {
                val res = snackbarHostState.showSnackbar(
                    message = "Set up your email account first.",
                    actionLabel = "Set up",
                )
                if (res == SnackbarResult.ActionPerformed) {
                    navController?.navigate(Screen.EmailSettings.route)
                }
            }
            return
        }
        scope.launch {
            exporting = true
            try {
                val r = viewModel.emailStudentSlips(keys)
                val parts = mutableListOf<String>()
                parts.add("Sent ${r.sent} slip${if (r.sent == 1) "" else "s"}.")
                if (r.missingNames.isNotEmpty()) {
                    parts.add("No email:\n" + r.missingNames.joinToString("\n") { "• $it" })
                }
                if (r.sendFailures.isNotEmpty()) {
                    parts.add("Failed:\n" + r.sendFailures.joinToString("\n") { "• ${it.first}: ${it.second}" })
                }
                emailResultDialog = parts.joinToString("\n\n")
            } catch (e: Exception) {
                emailResultDialog = "Email failed: ${e.message}"
            } finally {
                exporting = false
            }
        }
    }

    val overallRows = state.overallGroups.flatMap { it.rows }
    val selectedRows = overallRows.filter { it.selectionKey() in selectedKeys }
    val selectionLabel = selectedRows.map { it.block }.distinct()
        .singleOrNull()?.takeIf { it.isNotBlank() } ?: "Selected"
    // Preboard Record needs one round's three subjects, so it's offered only within a single cluster.
    val selectionSingleCluster = selectedRows.isNotEmpty() &&
        selectedRows.all { it.clusterId != null } &&
        selectedRows.mapNotNull { it.clusterId }.distinct().size == 1
    val allKeys = overallRows.map { it.selectionKey() }.toSet()

    BackHandler(enabled = selectionMode) { clearSelection() }

    Scaffold(
        // MainActivity's outer Scaffold already reserves the status-bar and bottom-nav-bar space,
        // so zero this inner Scaffold's content insets to avoid a doubled gap top and bottom.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
          if (selectionMode) {
            RecordsSelectionBar(
                count = selectedRows.size,
                allSelected = allKeys.isNotEmpty() && selectedKeys.size == allKeys.size,
                showPreboardRecord = selectionSingleCluster,
                onToggleAll = {
                    if (selectedKeys.size == allKeys.size) clearSelection()
                    else { selectedKeys.clear(); selectedKeys.addAll(allKeys) }
                },
                onClose = { clearSelection() },
                onExport = { format, action ->
                    val keys = selectedKeys.toSet()
                    val save = action == ExportAction.SAVE
                    when (format) {
                        ExportFormat.PREBOARD -> runPreboardRecord(keys, save)
                        ExportFormat.REPORT_PDF -> runClassReport(keys, selectionLabel, PDF_MIME, save)
                        ExportFormat.REPORT_XLSX -> runClassReport(keys, selectionLabel, XLSX_MIME, save)
                        ExportFormat.SLIPS_COMBINED -> runSlipsCombined(keys, save)
                        // Saving loose files isn't possible, so a saved "separate" collapses to the zip.
                        ExportFormat.SLIPS_SEPARATE -> if (save) runSlipsZip(keys, true) else shareSlipsSeparate(keys)
                        ExportFormat.SLIPS_ZIP -> runSlipsZip(keys, save)
                        ExportFormat.SLIPS_EMAIL -> emailSlips(keys)
                    }
                },
            )
          } else {
            var menuOpen by remember { mutableStateOf(false) }
            val statsReady = examStats is ExamStatsUiState.Ready
            val canExport = (filters.tab == RecordsTab.OVERALL && state.overallGroups.isNotEmpty()) ||
                (filters.tab == RecordsTab.EXAM_STATS && statsReady)
            BrandTopAppBar(
                title = "Records",
                actions = {
                    IconButton(onClick = { navController?.navigate(Screen.Trash.createRoute()) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Trash")
                    }
                    IconButton(onClick = { menuOpen = true }, enabled = canExport) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (filters.tab == RecordsTab.OVERALL) {
                            // Institutional submit format; needs a cluster selected (one round's 3 subjects).
                            if (filters.clusterId != null) {
                                ExportItem("Share Preboard Record (Excel)") { menuOpen = false; share(XLSX_MIME) { viewModel.generatePreboardRecordXlsx() } }
                                ExportItem("Save Preboard Record (Excel)") { menuOpen = false; saveExport("preboard_record", XLSX_MIME) { viewModel.generatePreboardRecordXlsx() } }
                            }
                            // Class report asks "which block(s)?" first (see BlockPickerDialog).
                            ExportItem("Share class report (PDF)") { menuOpen = false; blockPicker = BlockExportSpec(PDF_MIME, false) }
                            ExportItem("Share class report (Excel)") { menuOpen = false; blockPicker = BlockExportSpec(XLSX_MIME, false) }
                            ExportItem("Save class report (PDF)") { menuOpen = false; blockPicker = BlockExportSpec(PDF_MIME, true) }
                            ExportItem("Save class report (Excel)") { menuOpen = false; blockPicker = BlockExportSpec(XLSX_MIME, true) }
                        } else if (filters.tab == RecordsTab.EXAM_STATS) {
                            ExportItem("Share stats (PDF)") { menuOpen = false; share(PDF_MIME) { viewModel.generateExamStatsPdf() } }
                            ExportItem("Share stats (Excel)") { menuOpen = false; share(XLSX_MIME) { viewModel.generateExamStatsXlsx() } }
                            ExportItem("Save stats (PDF)") { menuOpen = false; saveExport("exam_stats", PDF_MIME) { viewModel.generateExamStatsPdf() } }
                            ExportItem("Save stats (Excel)") { menuOpen = false; saveExport("exam_stats", XLSX_MIME) { viewModel.generateExamStatsXlsx() } }
                        }
                    }
                },
            )
          }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                val tabLabels = listOf("Overall", "By Subject", "Exam Stats")
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    RecordsTab.entries.forEachIndexed { i, _ ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = { Text(tabLabels[i]) },
                        )
                    }
                }
                // Scoped to a set picked on the Students screen; let the user drop back to everyone.
                if (filters.studentIds.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Showing ${filters.studentIds.size} selected student${if (filters.studentIds.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.clearStudentIds() }) { Text("Clear") }
                        }
                    }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    val tab = RecordsTab.entries[page]
                    // Column so the ResultsList/ControlRow weight(1f) modifiers resolve in a ColumnScope.
                    Column(Modifier.fillMaxSize()) {
                        when (tab) {
                            RecordsTab.EXAM_STATS -> ExamStatsTab(
                                state = state,
                                examStats = examStats,
                                viewModel = viewModel,
                                filtersExpanded = examFiltersExpanded,
                                onToggleFilters = { examFiltersExpanded = !examFiltersExpanded },
                            )
                            else -> {
                                val filtersExpanded = if (tab == RecordsTab.OVERALL) overallFiltersExpanded else subjectFiltersExpanded
                                ControlRow(
                                    state = state,
                                    viewModel = viewModel,
                                    tab = tab,
                                    filtersExpanded = filtersExpanded,
                                    onToggleFilters = {
                                        if (tab == RecordsTab.OVERALL) overallFiltersExpanded = !overallFiltersExpanded
                                        else subjectFiltersExpanded = !subjectFiltersExpanded
                                    },
                                )
                                AnimatedVisibility(visible = filtersExpanded) {
                                    FilterPanel(state, viewModel, tab, showSubjectScores) { showSubjectScores = it }
                                }
                                ResultsList(
                                    state = state,
                                    tab = tab,
                                    showSubjectScores = showSubjectScores,
                                    selectionMode = selectionMode,
                                    selectedKeys = selectedKeys,
                                    onRowTap = { row ->
                                        if (selectionMode) toggleKey(row.selectionKey()) else slipTarget = row
                                    },
                                    onRowLongPress = { row -> toggleKey(row.selectionKey()) },
                                    onBlockToggle = { group ->
                                        val keys = group.rows.map { it.selectionKey() }
                                        if (keys.all { it in selectedKeys }) selectedKeys.removeAll(keys)
                                        else { selectedKeys.removeAll(keys); selectedKeys.addAll(keys) }
                                    },
                                    onShowPaper = { detailTarget = it },
                                    onRescan = { rescanPaper(it) },
                                    onDeleteRecord = { recordToDelete = it },
                                )
                            }
                        }
                    }
                }
            }

            if (exporting) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // Per-item breakdown for a tapped By-Subject paper (mirrors the post-capture view).
            detailTarget?.let { row ->
                BackHandler { detailTarget = null }
                var answerKey by remember(row) { mutableStateOf<String?>(null) }
                LaunchedEffect(row) { answerKey = viewModel.answerKeyFor(row.result) }
                PaperDetailOverlay(
                    result = row.result,
                    answerKey = answerKey,
                    onClose = { detailTarget = null },
                )
            }
        }
    }

    recordToDelete?.let { row ->
        val r = row.result
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text("Delete paper") },
            text = { Text("Move ${r.studentName}'s ${r.subject} paper (${r.examName}) to the Trash? You can restore it within 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.trashResult(r.id)
                    recordToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) { Text("Cancel") }
            }
        )
    }

    slipTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { slipTarget = null },
            title = { Text(row.name) },
            text = { Text("Export this student's result slip.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { slipTarget = null; emailSlips(setOf(row.selectionKey())) }) { Text("Email") }
                    TextButton(onClick = { slipTarget = null; share(PDF_MIME) { viewModel.generateStudentSlip(row) } }) { Text("Share") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    slipTarget = null
                    val safe = row.name.ifBlank { row.studentId }.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    saveExport("slip_$safe", PDF_MIME) { viewModel.generateStudentSlip(row) }
                }) { Text("Save") }
            }
        )
    }

    emailResultDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { emailResultDialog = null },
            title = { Text("Email result") },
            text = {
                Text(
                    message,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { emailResultDialog = null }) { Text("OK") }
            },
        )
    }

    blockPicker?.let { spec ->
        BlockPickerDialog(
            blocks = state.overallGroups.map { it.block },
            onDismiss = { blockPicker = null },
            onConfirm = { chosen ->
                blockPicker = null
                val keys = overallRows.filter { it.block.ifBlank { "—" } in chosen }
                    .map { it.selectionKey() }.toSet()
                val label = chosen.singleOrNull()?.takeIf { it != "—" } ?: "Selected"
                runClassReport(keys, label, spec.mime, spec.save)
            },
        )
    }
}

/** Class-report export awaiting a block choice: format + save-vs-share intent. */
private data class BlockExportSpec(val mime: String, val save: Boolean)

private enum class ExportFormat { PREBOARD, REPORT_PDF, REPORT_XLSX, SLIPS_COMBINED, SLIPS_SEPARATE, SLIPS_ZIP, SLIPS_EMAIL }
private enum class ExportAction { SHARE, SAVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordsSelectionBar(
    count: Int,
    allSelected: Boolean,
    showPreboardRecord: Boolean,
    onToggleAll: () -> Unit,
    onClose: () -> Unit,
    onExport: (ExportFormat, ExportAction) -> Unit,
) {
    var exportMenu by remember { mutableStateOf(false) }
    // Two-level menu: pick Share/Save first, then the format. Halves the flat list.
    var action by remember { mutableStateOf<ExportAction?>(null) }
    fun close() { exportMenu = false; action = null }
    BrandTopAppBar(
        title = "$count selected",
        selection = true,
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
        },
        actions = {
            TextButton(onClick = onToggleAll) { Text(if (allSelected) "Deselect all" else "Select all") }
            IconButton(onClick = { exportMenu = true }, enabled = count > 0) {
                Icon(Icons.Default.FileDownload, contentDescription = "Export")
            }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { close() }) {
                fun fire(format: ExportFormat) { val a = action!!; close(); onExport(format, a) }
                when (val a = action) {
                    null -> {
                        ExportItem("Email slips to students") { close(); onExport(ExportFormat.SLIPS_EMAIL, ExportAction.SHARE) }
                        ExportItem("Share…") { action = ExportAction.SHARE }
                        ExportItem("Save…") { action = ExportAction.SAVE }
                    }
                    else -> {
                        ExportItem("‹ Back") { action = null }
                        if (showPreboardRecord) ExportItem("Preboard Record (Excel)") { fire(ExportFormat.PREBOARD) }
                        ExportItem("Class report (PDF)") { fire(ExportFormat.REPORT_PDF) }
                        ExportItem("Class report (Excel)") { fire(ExportFormat.REPORT_XLSX) }
                        ExportItem("Combined slips (PDF)") { fire(ExportFormat.SLIPS_COMBINED) }
                        // Loose multi-file output only works when sharing; Save offers the zip instead.
                        if (a == ExportAction.SHARE) ExportItem("Separate slips (PDFs)") { fire(ExportFormat.SLIPS_SEPARATE) }
                        ExportItem("Slips (ZIP)") { fire(ExportFormat.SLIPS_ZIP) }
                    }
                }
            }
        },
    )
}

/** Picks which block(s) a class report covers; defaults to all so the common case is one tap. */
@Composable
private fun BlockPickerDialog(
    blocks: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val checked = remember { mutableStateListOf<String>().apply { addAll(blocks) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Class report for which block(s)?") },
        text = {
            Column {
                blocks.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (b in checked) checked.remove(b) else checked.add(b)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = b in checked,
                            onCheckedChange = { if (it) checked.add(b) else checked.remove(b) },
                        )
                        Text(if (b == "—") "(no block)" else "Block $b")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked.toSet()) }, enabled = checked.isNotEmpty()) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

// ---------- control row (sort + search + filters toggle) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlRow(
    state: RecordsUiState,
    viewModel: RecordsViewModel,
    tab: RecordsTab,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
) {
    val f = state.filters
    // Tab-derived (not f.activeSort) so each pager page shows its own sort during a swipe.
    val activeSort = if (tab == RecordsTab.BY_SUBJECT) f.subjectSort else f.overallSort
    var searchExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searchExpanded) {
            OutlinedTextField(
                value = f.search,
                onValueChange = viewModel::setSearch,
                placeholder = { Text("Search name or ID…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = {
                    IconButton(onClick = { viewModel.setSearch(""); searchExpanded = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
        } else {
            // Label-less sort so its height matches the search field (no floating-label offset).
            SortDropdown(activeSort, viewModel::setSort, Modifier.weight(1f))
            IconButton(onClick = { searchExpanded = true }) { Icon(Icons.Default.Search, contentDescription = "Search") }
        }
        IconButton(onClick = onToggleFilters) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "Filters",
                tint = if (filtersExpanded) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(selected: RecordsSort, onSelect: (RecordsSort) -> Unit, modifier: Modifier = Modifier) {
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
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecordsSort.entries.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.label) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamStatsControlRow(
    state: RecordsUiState,
    viewModel: RecordsViewModel,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
) {
    val f = state.filters
    var searchExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searchExpanded) {
            OutlinedTextField(
                value = f.examSearch,
                onValueChange = viewModel::setExamSearch,
                placeholder = { Text("Search exam or subject…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = {
                    IconButton(onClick = { viewModel.setExamSearch(""); searchExpanded = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
        } else {
            ExamSortDropdown(f.examSort, viewModel::setExamSort, Modifier.weight(1f))
            IconButton(onClick = { searchExpanded = true }) { Icon(Icons.Default.Search, contentDescription = "Search") }
        }
        IconButton(onClick = onToggleFilters) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "Filters",
                tint = if (filtersExpanded) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamSortDropdown(selected: ExamSummarySort, onSelect: (ExamSummarySort) -> Unit, modifier: Modifier = Modifier) {
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
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExamSummarySort.entries.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.label) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

// ---------- collapsible filter panel ----------

@Composable
private fun FilterPanel(
    state: RecordsUiState,
    viewModel: RecordsViewModel,
    tab: RecordsTab,
    showSubjectScores: Boolean,
    onToggleSubjectScores: (Boolean) -> Unit,
) {
    val f = state.filters
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == RecordsTab.OVERALL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown("Program", f.program, state.programOptions, viewModel::setProgram, Modifier.weight(1f))
                FilterDropdown("Block", f.block, state.blockOptions, viewModel::setBlock, Modifier.weight(1f))
            }
            // Cluster scopes a student's three subjects to one round; school year comes from it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val clusterLabel = state.clusterOptions.firstOrNull { it.id == f.clusterId }?.name ?: ALL
                FilterDropdown(
                    "Cluster", clusterLabel, listOf(ALL) + state.clusterOptions.map { it.name },
                    onSelect = { name -> viewModel.setCluster(state.clusterOptions.firstOrNull { it.name == name }?.id) },
                    Modifier.weight(1f),
                )
                FilterDropdown("School Year", f.schoolYear, state.schoolYearOptions, viewModel::setSchoolYear, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Subject scores", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = showSubjectScores, onCheckedChange = onToggleSubjectScores)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown("Block", f.block, state.blockOptions, viewModel::setBlock, Modifier.weight(1f))
                FilterDropdown("Subject", f.subject, state.subjectOptions, viewModel::setSubject, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExamDropdown(state, viewModel, Modifier.weight(1f))
                val clusterLabel = state.clusterOptions.firstOrNull { it.id == f.clusterId }?.name ?: ALL
                FilterDropdown(
                    "Cluster", clusterLabel, listOf(ALL) + state.clusterOptions.map { it.name },
                    onSelect = { name -> viewModel.setCluster(state.clusterOptions.firstOrNull { it.name == name }?.id) },
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ExamDropdown(state: RecordsUiState, viewModel: RecordsViewModel, modifier: Modifier = Modifier) {
    val f = state.filters
    val examLabel = state.examOptions.firstOrNull { it.id == f.examId }?.name ?: ALL
    FilterDropdown(
        "Exam", examLabel, listOf(ALL) + state.examOptions.map { it.name },
        onSelect = { name -> viewModel.setExam(state.examOptions.firstOrNull { it.name == name }?.id) },
        modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
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
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

// ---------- Overall / By Subject lists ----------

@Composable
private fun ColumnScope.ResultsList(
    state: RecordsUiState,
    tab: RecordsTab,
    showSubjectScores: Boolean,
    selectionMode: Boolean,
    selectedKeys: List<String>,
    onRowTap: (StudentGwaRow) -> Unit,
    onRowLongPress: (StudentGwaRow) -> Unit,
    onBlockToggle: (BlockGroup) -> Unit,
    onShowPaper: (SubjectScanRow) -> Unit,
    onRescan: (SubjectScanRow) -> Unit,
    onDeleteRecord: (SubjectScanRow) -> Unit,
) {
    val filters = state.filters
    if (state.loading) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Tab-derived emptiness (not state.isEmpty, which keys off the global tab) so each
    // pager page reflects its own data while swiping.
    val pageEmpty = if (tab == RecordsTab.OVERALL) state.overallGroups.isEmpty() else state.subjectRows.isEmpty()
    if (pageEmpty) {
        EmptyState(
            if (filters.search.isNotBlank()) "No results match your search."
            else "No scan results yet. Scan some answer sheets to see summaries here."
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (tab == RecordsTab.OVERALL) {
            state.overallGroups.forEach { group ->
                val blockKeys = group.rows.map { it.selectionKey() }
                val selCount = blockKeys.count { it in selectedKeys }
                val blockState = when (selCount) {
                    0 -> ToggleableState.Off
                    blockKeys.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                item(key = "h-${group.block}") {
                    BlockHeader(group, selectionMode, blockState) { onBlockToggle(group) }
                }
                items(group.rows, key = { it.selectionKey() }) { row ->
                    StudentCard(
                        row = row,
                        showSubjectScores = showSubjectScores,
                        selectionMode = selectionMode,
                        selected = row.selectionKey() in selectedKeys,
                        onTap = { onRowTap(row) },
                        onLongPress = { onRowLongPress(row) },
                    )
                }
            }
        } else {
            items(state.subjectRows, key = { it.result.id }) {
                ScanCard(it, onShowDetail = { onShowPaper(it) }, onRescan = { onRescan(it) }, onDelete = { onDeleteRecord(it) })
            }
        }
    }
}

@Composable
private fun BlockHeader(
    group: BlockGroup,
    selectionMode: Boolean,
    blockState: ToggleableState,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Tristate: whole-block toggle, indeterminate when only some of the block is selected.
        if (selectionMode) {
            TriStateCheckbox(state = blockState, onClick = onToggle)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Block ${group.block}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "${group.total} students · ${group.passCount} pass · ${group.failCount} fail · " +
                    "${group.incompleteCount} incomplete · ${"%.0f".format(group.passRate)}% pass rate",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudentCard(
    row: StudentGwaRow,
    showSubjectScores: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLongPress),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                             else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onTap() })
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (row.status == SummaryStatus.INCOMPLETE) "GWA —" else "GWA ${"%.2f".format(row.gwa)}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RemarkBadge(row.status)
            }
            if (showSubjectScores) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectChip("M", row, "Mathematics")
                    SubjectChip("E", row, "ESAS")
                    SubjectChip("P", row, "Professional EE")
                }
            }
        }
    }
}

@Composable
private fun SubjectChip(letter: String, row: StudentGwaRow, subject: String) {
    val line = row.lines.firstOrNull { it.subject == subject }
    val below = line != null && !line.meetsFloor
    val bg = if (below) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (below) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            if (line == null) "$letter —" else "$letter ${"%.0f".format(line.percentage)}",
            style = MaterialTheme.typography.labelMedium, color = fg
        )
    }
}

@Composable
private fun RemarkBadge(status: SummaryStatus) {
    val ext = LocalExtendedColors.current
    val (bg, fg, label) = when (status) {
        SummaryStatus.PASS -> Triple(ext.passContainer, ext.onPassContainer, "PASS")
        SummaryStatus.FAIL -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "FAIL")
        SummaryStatus.INCOMPLETE -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "INCOMPLETE")
    }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScanCard(row: SubjectScanRow, onShowDetail: () -> Unit, onRescan: () -> Unit, onDelete: () -> Unit) {
    val r = row.result
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { onShowDetail() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.studentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${r.subject} · ${r.examName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${r.score}/${r.total}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${"%.0f".format(row.percentage)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.passed) LocalExtendedColors.current.pass else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Paper actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Re-scan paper") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = { menuOpen = false; onRescan() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete paper") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/**
 * Read-only per-item breakdown for a tapped By-Subject paper — the same score header + colored
 * answer grid the scanner shows right after a capture. [answerKey] null = still loading.
 */
@Composable
private fun PaperDetailOverlay(
    result: com.pbec.preboardexamchecker.data.models.ScanResult,
    answerKey: String?,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    result.studentName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("ID: ${result.studentId}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Block: ${result.studentBlock}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${result.subject} — Set ${result.testSet}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${result.score} / ${result.total}", style = MaterialTheme.typography.displaySmall)
                        val pct = if (result.total > 0) "%.2f%%".format(result.score.toDouble() / result.total * 100) else "0.00%"
                        Text(pct, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (result.passed) "PASSED" else "FAILED",
                        color = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                when {
                    result.rawAnswers.isBlank() -> Text(
                        "No per-item data for this paper.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    answerKey == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> ResultAnswerGrid(
                        rawAnswers = result.rawAnswers,
                        answerKey = answerKey,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ---------- Exam Stats tab ----------

@Composable
private fun ColumnScope.ExamStatsTab(
    state: RecordsUiState,
    examStats: ExamStatsUiState,
    viewModel: RecordsViewModel,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
) {
    val f = state.filters
    // Pending bulk-delete confirmation: label shown in the dialog + the action to run on confirm.
    var pendingBulk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val selectedExam = state.examOptions.firstOrNull { it.id == f.examId }

    ExamStatsControlRow(state, viewModel, filtersExpanded, onToggleFilters)
    AnimatedVisibility(visible = filtersExpanded) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown("Subject", f.subject, state.subjectOptions, viewModel::setSubject, Modifier.weight(1f))
                ExamDropdown(state, viewModel, Modifier.weight(1f))
            }
            // Cluster scopes the per-exam stats: All = overall (every cluster), or one cluster only.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val clusterLabel = state.clusterOptions.firstOrNull { it.id == f.clusterId }?.name ?: ALL
                FilterDropdown(
                    "Cluster", clusterLabel, listOf(ALL) + state.clusterOptions.map { it.name },
                    onSelect = { name -> viewModel.setCluster(state.clusterOptions.firstOrNull { it.name == name }?.id) },
                    Modifier.weight(1f),
                )
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    val canDelete = f.subject != ALL || selectedExam != null
                    IconButton(onClick = { menuOpen = true }, enabled = canDelete) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Delete records")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (selectedExam != null) {
                            DropdownMenuItem(
                                text = { Text("Delete all records for ${selectedExam.name}") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pendingBulk = "all records for ${selectedExam.name}" to { viewModel.trashExamRecords(selectedExam.id) }
                                }
                            )
                        }
                        if (f.subject != ALL) {
                            DropdownMenuItem(
                                text = { Text("Delete all ${f.subject} records") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pendingBulk = "all ${f.subject} records" to { viewModel.trashSubjectRecords(f.subject) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (f.examId == null) {
        // No specific exam chosen: show an overview list (filtered by the subject selection).
        if (state.examSummaries.isEmpty()) {
            EmptyState("No exam results yet. Scan some answer sheets to see exam statistics here.")
        } else {
            ExamSummaryList(
                summaries = state.examSummaries,
                onSelect = { viewModel.setExam(it) },
                onDeleteRecords = { s -> pendingBulk = "all records for ${s.examName}" to { viewModel.trashExamRecords(s.examId) } },
            )
        }
    } else {
        when (examStats) {
            is ExamStatsUiState.Ready -> ExamStatsContent(examStats.stats)
            is ExamStatsUiState.Unavailable -> EmptyState("Couldn't load this exam's questions. Make sure the exam and its question bank still exist.")
            else -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    pendingBulk?.let { (label, action) ->
        AlertDialog(
            onDismissRequest = { pendingBulk = null },
            title = { Text("Delete records") },
            text = { Text("Move $label to the Trash? They can be restored within 30 days.") },
            confirmButton = {
                TextButton(onClick = { action(); pendingBulk = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulk = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ColumnScope.ExamSummaryList(
    summaries: List<ExamSummary>,
    onSelect: (Long) -> Unit,
    onDeleteRecords: (ExamSummary) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(summaries, key = { it.examId }) { s ->
            ExamSummaryCard(s, onClick = { onSelect(s.examId) }, onDeleteRecords = { onDeleteRecords(s) })
        }
    }
}

@Composable
private fun ExamSummaryCard(s: ExamSummary, onClick: () -> Unit, onDeleteRecords: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.examName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${s.subject} · ${s.takers} takers · avg ${"%.0f".format(s.avgPct)}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.0f".format(s.passRate)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("pass rate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Exam actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete all records") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDeleteRecords() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ExamStatsContent(stats: ExamStats) {
    val keyed = stats.questions.filter { it.keyLetter != null }
    val mostMissed = keyed.sortedByDescending { it.wrongCount }.take(10)
    val mostCorrect = keyed.sortedByDescending { it.correctCount }.take(10)

    var distExpanded by rememberSaveable(stats.examId) { mutableStateOf(true) }
    var missedExpanded by rememberSaveable(stats.examId) { mutableStateOf(true) }
    var correctExpanded by rememberSaveable(stats.examId) { mutableStateOf(false) }
    var allExpanded by rememberSaveable(stats.examId) { mutableStateOf(false) }
    // Global question toggle; per-item overrides win until "expand/collapse all" is tapped again.
    var allQuestionsExpanded by rememberSaveable(stats.examId) { mutableStateOf(false) }
    val overrides = remember(stats.examId) { mutableStateMapOf<String, Boolean>() }
    fun isQ(k: String) = overrides[k] ?: allQuestionsExpanded
    fun toggleQ(k: String) { overrides[k] = !isQ(k) }

    val green = LocalExtendedColors.current.pass
    val red = MaterialTheme.colorScheme.error

    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("summary") { StatsSummaryCard(stats) }

        item("dist-h") { SectionHeader("Score distribution", distExpanded) { distExpanded = !distExpanded } }
        if (distExpanded) item("dist") { DistributionChart(stats) }

        if (!stats.perQuestionAvailable) {
            item("noqa") {
                InfoNote("Per-question analysis isn't available — these scans didn't record individual answers.")
            }
        } else {
            item("q-controls") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { allQuestionsExpanded = !allQuestionsExpanded; overrides.clear() }) {
                        Text(if (allQuestionsExpanded) "Collapse all questions" else "Expand all questions")
                    }
                }
            }

            item("missed-h") { SectionHeader("Most missed", missedExpanded) { missedExpanded = !missedExpanded } }
            if (missedExpanded) items(mostMissed, key = { "m-${it.questionId}-${it.number}" }) { q ->
                QuestionItem(q, "${q.wrongCount} wrong", red, isQ("m-${q.questionId}")) { toggleQ("m-${q.questionId}") }
            }

            item("correct-h") { SectionHeader("Most correct", correctExpanded) { correctExpanded = !correctExpanded } }
            if (correctExpanded) items(mostCorrect, key = { "c-${it.questionId}-${it.number}" }) { q ->
                QuestionItem(q, "${q.correctCount} correct", green, isQ("c-${q.questionId}")) { toggleQ("c-${q.questionId}") }
            }

            item("all-h") { SectionHeader("All questions", allExpanded) { allExpanded = !allExpanded } }
            if (allExpanded) items(stats.questions, key = { "a-${it.questionId}-${it.number}" }) { q ->
                QuestionItem(q, "${q.correctCount}/${q.totalTakers} correct", green, isQ("a-${q.questionId}")) { toggleQ("a-${q.questionId}") }
            }
        }
    }
}

@Composable
private fun StatsSummaryCard(stats: ExamStats) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("${stats.subject} · ${stats.examName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Takers ${stats.takers}   Avg ${"%.1f".format(stats.avgPct)}%   Median ${"%.1f".format(stats.medianPct)}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "High ${"%.0f".format(stats.maxPct)}%   Low ${"%.0f".format(stats.minPct)}%   Pass rate ${"%.1f".format(stats.passRate)}%",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DistributionChart(stats: ExamStats) {
    val max = (stats.distribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        stats.distribution.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(16.dp)) {
                    Box(
                        Modifier.fillMaxWidth(b.count.toFloat() / max).fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(b.count.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** One question row that expands to reveal the question text and per-option breakdown. */
@Composable
private fun QuestionItem(q: QuestionStat, valueText: String, valueColor: Color, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onToggle() }, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Q${q.number}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))
                Text(
                    q.questionText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(valueText, style = MaterialTheme.typography.labelMedium, color = valueColor, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                q.options.forEach { OptionRow(it, q.totalTakers) }
                if (q.blankCount > 0 || q.multiCount > 0) {
                    Text(
                        "Blank ${q.blankCount} · Multiple marks ${q.multiCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(opt: com.pbec.preboardexamchecker.domain.model.QuestionOption, totalTakers: Int) {
    val green = LocalExtendedColors.current.pass
    val frac = if (totalTakers > 0) opt.chosenCount.toFloat() / totalTakers else 0f
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${opt.letter}.",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (opt.isCorrect) green else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(22.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                opt.text.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = if (opt.isCorrect) green else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Box(Modifier.fillMaxWidth().height(5.dp).padding(top = 1.dp)) {
                Box(
                    Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                        .background(if (opt.isCorrect) green else MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(
            opt.chosenCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(30.dp).padding(start = 6.dp),
            textAlign = TextAlign.End
        )
        if (opt.isCorrect) {
            Icon(Icons.Default.Check, contentDescription = "Correct answer", tint = green, modifier = Modifier.size(16.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun InfoNote(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.EmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().weight(1f).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
    }
}
