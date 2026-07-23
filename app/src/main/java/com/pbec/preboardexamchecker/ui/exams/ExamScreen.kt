package com.pbec.preboardexamchecker.ui.exams

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.data.models.Exam
import com.pbec.preboardexamchecker.ui.Screen
import com.pbec.preboardexamchecker.ui.exambank.ExamBankContent
import com.pbec.preboardexamchecker.ui.viewmodels.ExamBankViewModel
import kotlinx.coroutines.launch
import com.pbec.preboardexamchecker.ui.viewmodels.ExamViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(
    navController: NavController,
    subject: String,
    viewModel: ExamViewModel = hiltViewModel(),
    examBankViewModel: ExamBankViewModel = hiltViewModel()
) {
    val exams by viewModel.exams.collectAsState()
    val message by viewModel.message.collectAsState()
    val questionsByImportSession by examBankViewModel.questionsByImportSession.collectAsState()

    var showGenerateExamDialog by remember { mutableStateOf(false) }
    var numberOfQuestionsInput by remember { mutableStateOf("100") }
    var numberOfQuestionsInputError by remember { mutableStateOf(false) }
    var selectedImportSessionIds by remember { mutableStateOf(setOf<String>()) }
    var importSessionSelectionError by remember { mutableStateOf(false) }

    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var examToDelete by remember { mutableStateOf<Exam?>(null) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var examToRename by remember { mutableStateOf<Exam?>(null) }
    var newExamNameInput by remember { mutableStateOf("") }

    // Which exam's Set A/Set B rows are currently expanded (null = none). Tapping an exam toggles it.
    var expandedExamId by remember { mutableStateOf<Long?>(null) }
    // The exam + set the in-flight CreateDocument launch is exporting.
    var pendingExportExam by remember { mutableStateOf<Exam?>(null) }
    var pendingExportSet by remember { mutableStateOf("A") }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onResult = { uri: Uri? ->
            val e = pendingExportExam
            if (uri != null && e != null) {
                viewModel.exportSet(e, pendingExportSet, uri)
            }
        }
    )
    val launchSetExport: (Exam, String) -> Unit = { exam, set ->
        pendingExportExam = exam
        pendingExportSet = set
        createDocumentLauncher.launch("${exam.examName}_Set${set}_${System.currentTimeMillis()}.pdf")
    }

    val sortedImportSessionIds = remember(questionsByImportSession.keys) {
        val sessionKeys = questionsByImportSession.keys
        val manualSession = sessionKeys.filter { it == "manual" || it.startsWith("manual_") }
        val importedSessions = sessionKeys.filter { it != "manual" && !it.startsWith("manual_") }.sortedDescending()
        importedSessions + manualSession
    }

    val scope = rememberCoroutineScope()
    // Two swipeable tabs: 0 = Exams (this screen), 1 = Question Bank (ExamBankContent).
    val pagerState = rememberPagerState(initialPage = 0) { 2 }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                BrandTopAppBar(
                    title = subject,
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Deep-link to the Trash tab matching whichever page is showing.
                        IconButton(onClick = {
                            navController.navigate(
                                Screen.Trash.createRoute(if (pagerState.currentPage == 1) "banks" else "exams")
                            )
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Trash")
                        }
                    },
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding()
                    )
            ) {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("Exams") },
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text("Question Bank") },
                    )
                }
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    if (page == 0) {
                        // ── Exams tab ──
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (exams.isEmpty()) {
                                    Text(
                                        text = if (questionsByImportSession.isEmpty()) {
                                            "No exams or questions yet. Swipe to the Question Bank to import questions."
                                        } else {
                                            "No exams yet. Generate an exam from the question bank."
                                        },
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp, bottom = 16.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(top = 16.dp) 
                    ) {
                        itemsIndexed(exams.reversed(), key = { _, exam -> exam.id }) { _, exam ->
                            val isExpanded = expandedExamId == exam.id
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Toggle the Set A/Set B rows for this exam.
                                            expandedExamId = if (isExpanded) null else exam.id
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = exam.examName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Created: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(exam.createdAt))}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    examToRename = exam
                                                    newExamNameInput = exam.examName
                                                    showRenameDialog = true
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename Exam",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    examToDelete = exam
                                                    showDeleteConfirmationDialog = true
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Exam",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isExpanded) {
                                    listOf("A", "B").forEach { set ->
                                        val setIds = if (set == "B") exam.setBQuestionIds else exam.setAQuestionIds
                                        val available = setIds.isNotEmpty()
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, top = 8.dp)
                                                .clickable(enabled = available) {
                                                    navController.navigate(Screen.ExamContent.createRoute(exam.id, set))
                                                },
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Set $set",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = if (available) "${setIds.size} questions" else "Not generated",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                IconButton(
                                                    enabled = available,
                                                    onClick = { launchSetExport(exam, set) }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.FileDownload,
                                                        contentDescription = "Export Set $set"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                                    }
                                }
                            }
                            FloatingActionButton(
                                onClick = { showGenerateExamDialog = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Filled.Add, "Generate Exam")
                            }
                        }
                    } else {
                        // ── Question Bank tab ──
                        ExamBankContent(
                            navController = navController,
                            subject = subject,
                            modifier = Modifier.fillMaxSize(),
                            viewModel = examBankViewModel,
                            backHandlerEnabled = pagerState.currentPage == 1,
                        )
                    }
                }
            }
        }

        message?.let {
            AlertDialog(
                onDismissRequest = { viewModel.clearMessage() },
                title = { Text("Information") },
                text = { Text(it) },
                confirmButton = {
                    Button(onClick = { viewModel.clearMessage() }) {
                        Text("OK")
                    }
                }
            )
        }

        if (showGenerateExamDialog) {
            AlertDialog(
                onDismissRequest = {
                    showGenerateExamDialog = false
                    numberOfQuestionsInputError = false
                    importSessionSelectionError = false
                    selectedImportSessionIds = emptySet()
                },
                title = { Text("Generate Exam") },
                text = {
                    Column {
                        Text("Enter the number of questions (1-1000):")
                        OutlinedTextField(
                            value = numberOfQuestionsInput,
                            onValueChange = { newValue ->
                                val filteredValue = newValue.filter { it.isDigit() }
                                if (filteredValue.length <= 4) {
                                    numberOfQuestionsInput = filteredValue
                                    numberOfQuestionsInputError = try {
                                        val num = filteredValue.toInt()
                                        num < 1 || num > 1000
                                    } catch (e: NumberFormatException) {
                                        filteredValue.isNotEmpty()
                                    }
                                }
                            },
                            isError = numberOfQuestionsInputError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("Number of Questions") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                        if (numberOfQuestionsInputError) {
                            Text(
                                "Please enter a number between 1 and 1000.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Select question banks to generate from:")
                        if (questionsByImportSession.isEmpty()) {
                            Text(
                                "No question banks available.",
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                itemsIndexed(sortedImportSessionIds, key = { _, id -> id }) { index, importSessionId ->
                                    val questionsInSession = questionsByImportSession[importSessionId] ?: emptyList()
                                    val headerText = if (importSessionId == "manual" || importSessionId.startsWith("manual_")) {
                                        "Manually Added"
                                    } else {
                                        questionsInSession.firstOrNull()?.fileName ?: "Imported File"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = importSessionId in selectedImportSessionIds,
                                            onCheckedChange = { isChecked ->
                                                selectedImportSessionIds = if (isChecked) {
                                                    selectedImportSessionIds + importSessionId
                                                } else {
                                                    selectedImportSessionIds - importSessionId
                                                }
                                                importSessionSelectionError = selectedImportSessionIds.isEmpty()
                                            }
                                        )
                                        Text(
                                            text = if (importSessionId == "manual" || importSessionId.startsWith("manual_")) {
                                                "$headerText (${questionsInSession.size} questions)"
                                            } else {
                                                "$headerText #${String.format(Locale.getDefault(), "%03d", index + 1)} (${questionsInSession.size} questions)"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = {
                                        selectedImportSessionIds = sortedImportSessionIds.toSet()
                                        importSessionSelectionError = false
                                    }
                                ) {
                                    Text("Select All")
                                }
                                TextButton(
                                    onClick = {
                                        selectedImportSessionIds = emptySet()
                                        importSessionSelectionError = true
                                    }
                                ) {
                                    Text("Deselect All")
                                }
                            }
                        }
                        if (importSessionSelectionError) {
                            Text(
                                "Please select at least one question bank.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val numQuestions = numberOfQuestionsInput.toIntOrNull()
                            importSessionSelectionError = selectedImportSessionIds.isEmpty()
                            if (numQuestions != null && numQuestions >= 1 && numQuestions <= 1000 && !importSessionSelectionError) {
                                viewModel.generateExam(numQuestions, selectedImportSessionIds.toList())
                                showGenerateExamDialog = false
                                numberOfQuestionsInputError = false
                                importSessionSelectionError = false
                                selectedImportSessionIds = emptySet()
                            } else {
                                numberOfQuestionsInputError = numQuestions == null || numQuestions < 1 || numQuestions > 1000
                                importSessionSelectionError = selectedImportSessionIds.isEmpty()
                            }
                        },
                        enabled = !numberOfQuestionsInputError && numberOfQuestionsInput.isNotEmpty() && !importSessionSelectionError
                    ) {
                        Text("Generate")
                    }
                },
                dismissButton = {
                    Button(onClick = {
                        showGenerateExamDialog = false
                        numberOfQuestionsInputError = false
                        importSessionSelectionError = false
                        selectedImportSessionIds = emptySet()
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showDeleteConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmationDialog = false },
                title = { Text("Delete exam") },
                text = {
                    Text(
                        "Delete '${examToDelete?.examName}'?\n\n" +
                            "• Delete exam only — keeps the records of students who already took it.\n" +
                            "• Delete exam & records — also moves those records to the Trash (restorable for 30 days)."
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            examToDelete?.let { viewModel.deleteExam(it) }
                            showDeleteConfirmationDialog = false
                            examToDelete = null
                        }) {
                            Text("Exam only")
                        }
                        Button(onClick = {
                            examToDelete?.let { viewModel.deleteExamWithRecords(it) }
                            showDeleteConfirmationDialog = false
                            examToDelete = null
                        }) {
                            Text("Exam & records")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteConfirmationDialog = false
                        examToDelete = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Exam") },
                text = {
                    OutlinedTextField(
                        value = newExamNameInput,
                        onValueChange = { newExamNameInput = it },
                        label = { Text("Exam Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        examToRename?.let { viewModel.renameExam(it, newExamNameInput) }
                        showRenameDialog = false
                        examToRename = null
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showRenameDialog = false
                        examToRename = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}