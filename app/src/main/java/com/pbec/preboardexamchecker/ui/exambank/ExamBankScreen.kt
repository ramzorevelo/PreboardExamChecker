package com.pbec.preboardexamchecker.ui.exambank

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.ui.Screen
import com.pbec.preboardexamchecker.ui.exams.OptionRowInBank
import com.pbec.preboardexamchecker.ui.viewmodels.ExamBankViewModel
import com.pbec.preboardexamchecker.utils.ExcelParser
import java.util.*

/**
 * The Exam Bank body, hosted as a swipeable tab inside [com.pbec.preboardexamchecker.ui.exams.ExamScreen].
 * Provides no Scaffold/TopAppBar of its own — the parent supplies the shared top bar.
 *
 * @param backHandlerEnabled gate the unsaved-edit back prompt to only fire while this tab is showing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamBankContent(
    navController: NavController,
    subject: String,
    modifier: Modifier = Modifier,
    viewModel: ExamBankViewModel = hiltViewModel(),
    backHandlerEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val message by viewModel.message.collectAsState()
    val totalQuestionCount by viewModel.totalQuestionCount.collectAsState()
    val pendingSaveQuestion by viewModel.pendingSaveQuestion.collectAsState()
    val questionsByImportSession by viewModel.questionsByImportSession.collectAsState()
    val generatedExamCountsByImportSession by viewModel.generatedExamCountsByImportSession.collectAsState()
    val nextQuestionToEditId by viewModel.nextQuestionToEdit.collectAsState()
    val manualQuestionCount by viewModel.manualQuestionCount.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    var showConfirmationDialog by remember { mutableStateOf(false) }
    var confirmedImportUri by remember { mutableStateOf<Uri?>(null) }
    var confirmedOriginalFileName by remember { mutableStateOf<String?>(null) }
    var suggestedSubjectForImport by remember { mutableStateOf<String?>(null) }

    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var questionToDelete by remember { mutableStateOf<Question?>(null) }

    var showImportSessionDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var importSessionToDeleteId by remember { mutableStateOf<String?>(null) }
    var importSessionToDeleteName by remember { mutableStateOf<String?>(null) }
    var willBankBeEmptyAfterSessionDeletion by remember { mutableStateOf(false) }
    var generatedExamCountToDelete by remember { mutableStateOf(0) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var importSessionToRenameId by remember { mutableStateOf<String?>(null) }
    var currentSessionName by remember { mutableStateOf("") }
    var newSessionName by remember { mutableStateOf("") }

    val localQuestionChanges = remember { mutableStateMapOf<Long, Question>() }
    var currentlyEditingQuestionId by remember { mutableStateOf<Long?>(null) }
    var newQuestionDraft by remember { mutableStateOf<Question?>(null) }

    var showSaveNewQuestionDialog by remember { mutableStateOf(false) }
    var showSaveExistingQuestionDialog by remember { mutableStateOf(false) }
    var questionToSaveOnBack by remember { mutableStateOf<Question?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    val sortedImportSessionIds = remember(questionsByImportSession.keys) {
        val sessionKeys = questionsByImportSession.keys
        val manualSession = sessionKeys.filter { it == "manual" || it.startsWith("manual_") }
        val importedSessions = sessionKeys.filter { it != "manual" && !it.startsWith("manual_") }.sortedDescending()
        importedSessions + manualSession
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(nextQuestionToEditId) {
        if (nextQuestionToEditId != null) {
            currentlyEditingQuestionId = nextQuestionToEditId
            viewModel.clearNextQuestionToEdit()
        }
    }

    BackHandler(enabled = backHandlerEnabled && (pendingSaveQuestion != null || newQuestionDraft != null || currentlyEditingQuestionId != null)) {
        if (newQuestionDraft != null) {
            showSaveNewQuestionDialog = true
        } else if (pendingSaveQuestion != null) {
            questionToSaveOnBack = pendingSaveQuestion
            showSaveExistingQuestionDialog = true
        } else if (currentlyEditingQuestionId != null) {
            val idToClear = currentlyEditingQuestionId
            val changes = localQuestionChanges[idToClear]
            val originalQuestion = questionsByImportSession.values.flatten().firstOrNull { it.id == idToClear }
            if (changes != null && changes != originalQuestion) {
                questionToSaveOnBack = changes
                showSaveExistingQuestionDialog = true
            } else {
                if (idToClear != null) {
                    localQuestionChanges.remove(idToClear)
                }
                currentlyEditingQuestionId = null
                focusManager.clearFocus()
                navController.popBackStack()
            }
        } else {
            navController.popBackStack()
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = ExcelParser.getFileName(context, it)
            val suggested = ExcelParser().suggestSubjectFromFileName(fileName)

            if (suggested != null && suggested != subject) {
                suggestedSubjectForImport = suggested
                confirmedImportUri = it
                confirmedOriginalFileName = fileName
                showConfirmationDialog = true
            } else {
                viewModel.importQuestionsFromFile(it, subject, fileName)
            }
        }
    }

    val createExcelTemplateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportExamTemplate(it)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Total Questions: $totalQuestionCount",
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (questionsByImportSession.isEmpty() && newQuestionDraft == null) {
                    Text("No questions imported yet.", modifier = Modifier.padding(16.dp))
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    // Clear the bottom-end FABs so the last session / Add-question button isn't covered.
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(sortedImportSessionIds, key = { it }) { importSessionId ->
                        val questionsInSession = questionsByImportSession[importSessionId] ?: emptyList()
                        val isManualSession = importSessionId == "manual" || importSessionId.startsWith("manual_")

                        if (!(isManualSession && questionsInSession.isEmpty())) {
                            val firstQuestion = questionsInSession.firstOrNull()
                            val headerText = if (isManualSession) {
                                "Manually Added"
                            } else {
                                firstQuestion?.customSessionName ?: firstQuestion?.fileName ?: "Imported File"
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        navController.navigate(
                                            Screen.ImportSessionDetails.createRoute(subject, importSessionId)
                                        )
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = headerText,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = if (isManualSession) Modifier else Modifier.weight(1f, fill = false)
                                            )
                                            if (!isManualSession) {
                                                val sessionIndex = sortedImportSessionIds
                                                    .filter { it != "manual" && !it.startsWith("manual_") }
                                                    .reversed()
                                                    .indexOf(importSessionId)
                                                val formattedImportNumber = String.format(Locale.getDefault(), "%03d", sessionIndex + 1)
                                                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                                Text(
                                                    text = "#$formattedImportNumber",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${questionsInSession.size} questions",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                        if (!isManualSession && firstQuestion?.customSessionName != null) {
                                            Text(
                                                text = "Original: ${firstQuestion.fileName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                    
                                    Row {
                                        if (!isManualSession) {
                                            IconButton(
                                                onClick = {
                                                    importSessionToRenameId = importSessionId
                                                    currentSessionName = headerText
                                                    newSessionName = headerText
                                                    showRenameDialog = true
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename session",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                importSessionToDeleteId = importSessionId
                                                importSessionToDeleteName = headerText
                                                val currentQuestionsInSession = questionsByImportSession[importSessionId]?.size ?: 0
                                                willBankBeEmptyAfterSessionDeletion = (totalQuestionCount - currentQuestionsInSession) == 0
                                                generatedExamCountToDelete = generatedExamCountsByImportSession[importSessionId] ?: 0
                                                showImportSessionDeleteConfirmationDialog = true
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete this import session",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        if (newQuestionDraft == null) {
                            Button(
                                onClick = {
                                    if (pendingSaveQuestion != null) {
                                        viewModel.setMessage("Please save or discard pending changes first.")
                                        return@Button
                                    }
                                    currentlyEditingQuestionId = null
                                    newQuestionDraft = Question(
                                        subject = subject,
                                        fileName = "Manually Added",
                                        questionNumber = (manualQuestionCount + 1),
                                        questionText = "",
                                        optionA = "",
                                        optionB = "",
                                        optionC = "",
                                        optionD = "",
                                        correctAnswer = null,
                                        questionBankId = "manual_${subject.lowercase(Locale.ROOT).replace(" ", "_")}",
                                        importSessionId = 0L
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add New Question")
                                Spacer(Modifier.width(8.dp))
                                Text("Add New Question")
                            }
                        } else {
                            val questionTextHasError by remember(newQuestionDraft?.questionText) { mutableStateOf(newQuestionDraft?.questionText.isNullOrBlank()) }
                            val optionAHasError by remember(newQuestionDraft?.optionA) { mutableStateOf(newQuestionDraft?.optionA.isNullOrBlank()) }
                            val optionBHasError by remember(newQuestionDraft?.optionB) { mutableStateOf(newQuestionDraft?.optionB.isNullOrBlank()) }
                            val optionCHasError by remember(newQuestionDraft?.optionC) { mutableStateOf(newQuestionDraft?.optionC.isNullOrBlank()) }
                            val optionDHasError by remember(newQuestionDraft?.optionD) { mutableStateOf(newQuestionDraft?.optionD.isNullOrBlank()) }
                            val correctAnswerHasError by remember(newQuestionDraft?.correctAnswer) { mutableStateOf(newQuestionDraft?.correctAnswer.isNullOrBlank()) }

                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text("New Question:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = newQuestionDraft?.questionText ?: "", onValueChange = { newQuestionDraft = newQuestionDraft?.copy(questionText = it) }, label = { Text("Question Text") }, modifier = Modifier.fillMaxWidth(), isError = questionTextHasError, supportingText = if (questionTextHasError) { { Text("Question text cannot be blank") } } else null)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = newQuestionDraft?.optionA ?: "", onValueChange = { newQuestionDraft = newQuestionDraft?.copy(optionA = it) }, label = { Text("Option A") }, modifier = Modifier.fillMaxWidth(), isError = optionAHasError, supportingText = if (optionAHasError) { { Text("Option A cannot be blank") } } else null)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = newQuestionDraft?.optionB ?: "", onValueChange = { newQuestionDraft = newQuestionDraft?.copy(optionB = it) }, label = { Text("Option B") }, modifier = Modifier.fillMaxWidth(), isError = optionBHasError, supportingText = if (optionBHasError) { { Text("Option B cannot be blank") } } else null)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = newQuestionDraft?.optionC ?: "", onValueChange = { newQuestionDraft = newQuestionDraft?.copy(optionC = it) }, label = { Text("Option C") }, modifier = Modifier.fillMaxWidth(), isError = optionCHasError, supportingText = if (optionCHasError) { { Text("Option C cannot be blank") } } else null)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = newQuestionDraft?.optionD ?: "", onValueChange = { newQuestionDraft = newQuestionDraft?.copy(optionD = it) }, label = { Text("Option D") }, modifier = Modifier.fillMaxWidth(), isError = optionDHasError, supportingText = if (optionDHasError) { { Text("Option D cannot be blank") } } else null)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = newQuestionDraft?.correctAnswer ?: "", onValueChange = { newQuestionDraft = newQuestionDraft?.copy(correctAnswer = it.uppercase()) }, label = { Text("Correct Answer (A, B, C, D, E)") }, modifier = Modifier.fillMaxWidth(), isError = correctAnswerHasError, supportingText = if (correctAnswerHasError) { { Text("Correct answer cannot be blank") } } else null)
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = {
                                        val validationResult = viewModel.validateQuestion(newQuestionDraft!!)
                                        if (validationResult.isValid) {
                                            viewModel.saveQuestion(newQuestionDraft!!)
                                            newQuestionDraft = null
                                            focusManager.clearFocus()
                                        } else {
                                            viewModel.setMessage(validationResult.errorMessage)
                                        }
                                    }) { Text("Save") }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { newQuestionDraft = null; focusManager.clearFocus() }, colors = ButtonDefaults.outlinedButtonColors()) { Text("Cancel") }
                                }
                            }
                        }
                    }
                }
            }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { createExcelTemplateLauncher.launch("question_bank_template.xlsx") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Description, "Export Template")
            }

            FloatingActionButton(
                onClick = { pickFileLauncher.launch(arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/csv",
                    "text/comma-separated-values"
                )) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.UploadFile, "Import Questions")
            }
        }

        // Hosted manually (no Scaffold here) so import/delete messages still surface as snackbars.
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Session") },
                text = {
                    Column {
                        Text("Current: $currentSessionName")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newSessionName,
                            onValueChange = { newSessionName = it },
                            label = { Text("New Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            importSessionToRenameId?.let { id ->
                                if (newSessionName.isNotBlank()) {
                                    viewModel.renameImportSession(id, newSessionName)
                                }
                            }
                            showRenameDialog = false
                        }
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmationDialog = false },
                title = { Text("Subject Mismatch") },
                text = { Text("The file name suggests this file is for '$suggestedSubjectForImport', but you selected '$subject'. Do you want to import it to '$subject' anyway?") },
                confirmButton = {
                    Button(onClick = {
                        confirmedImportUri?.let { uri ->
                            confirmedOriginalFileName?.let { fileName ->
                                viewModel.importQuestionsFromFile(uri, subject, fileName)
                            }
                        }
                        showConfirmationDialog = false
                    }) { Text("Import to $subject") }
                },
                dismissButton = {
                    Button(onClick = { showConfirmationDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showDeleteConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmationDialog = false },
                title = { Text("Confirm Deletion") },
                text = { Text("Are you sure you want to delete question ${questionToDelete?.questionNumber} from '${questionToDelete?.fileName}'? This cannot be undone.") },
                confirmButton = {
                    Button(onClick = {
                        questionToDelete?.let { viewModel.deleteQuestion(it) }
                        showDeleteConfirmationDialog = false
                        questionToDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    Button(onClick = { showDeleteConfirmationDialog = false; questionToDelete = null }) { Text("Cancel") }
                }
            )
        }

        if (showImportSessionDeleteConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showImportSessionDeleteConfirmationDialog = false },
                title = { Text("Confirm Session Deletion") },
                text = {
                    val baseText = "Are you sure you want to delete all questions from '${importSessionToDeleteName}'? This action cannot be undone."
                    val additionalText = if (willBankBeEmptyAfterSessionDeletion) {
                        "\n\nWarning: This action will make the entire exam bank empty for this subject!"
                    } else {
                        ""
                    }
                    val generatedExamWarning = if (generatedExamCountToDelete > 0) {
                        "\n\nWarning: This bank is used by $generatedExamCountToDelete generated exam(s). Deleting this bank will also delete those generated exam(s)."
                    } else {
                        ""
                    }
                    Text(baseText + additionalText + generatedExamWarning)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            importSessionToDeleteId?.let { id -> viewModel.deleteQuestionsForImportSession(id) }
                            showImportSessionDeleteConfirmationDialog = false
                            importSessionToDeleteId = null
                            importSessionToDeleteName = null
                            willBankBeEmptyAfterSessionDeletion = false
                            generatedExamCountToDelete = 0
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete All") }
                },
                dismissButton = {
                    Button(onClick = {
                        showImportSessionDeleteConfirmationDialog = false
                        importSessionToDeleteId = null
                        importSessionToDeleteName = null
                        willBankBeEmptyAfterSessionDeletion = false
                        generatedExamCountToDelete = 0
                    }) { Text("Cancel") }
                }
            )
        }

        if (showSaveNewQuestionDialog) {
            AlertDialog(
                onDismissRequest = { /* Do nothing */ },
                title = { Text("Save New Question?") },
                text = { Text("You have an unsaved new question. Do you want to save it?") },
                confirmButton = {
                    Button(onClick = {
                        val questionToSave = newQuestionDraft ?: return@Button
                        val validationResult = viewModel.validateQuestion(questionToSave)
                        if (validationResult.isValid) {
                            viewModel.saveQuestion(questionToSave)
                            newQuestionDraft = null
                            showSaveNewQuestionDialog = false
                            focusManager.clearFocus()
                            navController.popBackStack()
                        } else {
                            viewModel.setMessage(validationResult.errorMessage)
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    Button(onClick = {
                        newQuestionDraft = null
                        showSaveNewQuestionDialog = false
                        focusManager.clearFocus()
                        viewModel.clearMessage()
                        navController.popBackStack()
                    }) { Text("Discard") }
                }
            )
        }

        if (showSaveExistingQuestionDialog) {
            AlertDialog(
                onDismissRequest = { /* Do nothing */ },
                title = { Text("Save Changes?") },
                text = { Text("You have unsaved changes for question ${questionToSaveOnBack?.questionNumber ?: "Unknown"}. Do you want to save them?") },
                confirmButton = {
                    Button(onClick = {
                        val questionToSave = questionToSaveOnBack ?: return@Button
                        val validationResult = viewModel.validateQuestion(questionToSave)
                        if (validationResult.isValid) {
                            viewModel.saveQuestion(questionToSave)
                            currentlyEditingQuestionId = null
                            localQuestionChanges.remove(questionToSave.id)
                            showSaveExistingQuestionDialog = false
                            questionToSaveOnBack = null
                            focusManager.clearFocus()
                            navController.popBackStack()
                        } else {
                            viewModel.setMessage(validationResult.errorMessage)
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    Button(onClick = {
                        currentlyEditingQuestionId = null
                        questionToSaveOnBack?.let { localQuestionChanges.remove(it.id) }
                        viewModel.discardPendingQuestionChanges()
                        showSaveExistingQuestionDialog = false
                        questionToSaveOnBack = null
                        focusManager.clearFocus()
                        navController.popBackStack()
                    }) { Text("Discard") }
                }
            )
        }

        if (isDeleting || isImporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (isDeleting) "Deleting..." else "Importing question bank...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
