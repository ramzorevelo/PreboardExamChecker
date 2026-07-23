package com.pbec.preboardexamchecker.ui.exambank

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.ui.exams.MathTextView
import com.pbec.preboardexamchecker.ui.exams.OptionRowInBank
import com.pbec.preboardexamchecker.ui.viewmodels.ExamBankViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSessionDetailsScreen(
    navController: NavController,
    subject: String,
    questionBankId: String,
    viewModel: ExamBankViewModel = hiltViewModel()
) {
    val questionsByImportSession by viewModel.questionsByImportSession.collectAsState()
    val originalQuestions = questionsByImportSession[questionBankId]?.sortedBy { it.questionNumber } ?: emptyList()
    
    var isShuffled by remember { mutableStateOf(false) }
    val questions = remember(originalQuestions, isShuffled) {
        if (isShuffled) originalQuestions.shuffled() else originalQuestions
    }
    
    val sessionName = originalQuestions.firstOrNull()?.fileName ?: "Imported Questions"

    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var questionToDelete by remember { mutableStateOf<Question?>(null) }
    
    var currentlyEditingQuestionId by remember { mutableStateOf<Long?>(null) }
    val localQuestionChanges = remember { mutableStateMapOf<Long, Question>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isShuffled = !isShuffled }) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle Questions",
                            tint = if (isShuffled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (questions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No questions found in this session.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Questions: ${questions.size}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (isShuffled) {
                                Text(
                                    text = "Shuffle Enabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(questions, key = { it.id }) { question ->
                        val isCurrentlyBeingEdited = currentlyEditingQuestionId == question.id
                        var currentQuestionState by remember(question.id) {
                            mutableStateOf(localQuestionChanges[question.id] ?: question)
                        }

                        LaunchedEffect(question) {
                            if (!isCurrentlyBeingEdited) {
                                currentQuestionState = question
                            }
                        }

                        val onValueChange: (Question) -> Unit = { updatedQ ->
                            currentQuestionState = updatedQ
                            localQuestionChanges[updatedQ.id] = updatedQ
                        }

                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("${currentQuestionState.questionNumber}. ", style = MaterialTheme.typography.bodyLarge)
                                
                                if (isCurrentlyBeingEdited) {
                                    OutlinedTextField(
                                        value = currentQuestionState.questionText,
                                        onValueChange = { onValueChange(currentQuestionState.copy(questionText = it)) },
                                        label = { Text("Question Text") },
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                    )
                                } else {
                                    MathTextView(
                                        text = currentQuestionState.questionText,
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                    )
                                }

                                Row {
                                    IconButton(onClick = {
                                        if (isCurrentlyBeingEdited) {
                                            val validation = viewModel.validateQuestion(currentQuestionState)
                                            if (validation.isValid) {
                                                viewModel.saveQuestion(currentQuestionState)
                                                currentlyEditingQuestionId = null
                                                localQuestionChanges.remove(question.id)
                                                focusManager.clearFocus()
                                            } else {
                                                viewModel.setMessage(validation.errorMessage)
                                            }
                                        } else {
                                            currentlyEditingQuestionId = question.id
                                        }
                                    }) {
                                        Icon(
                                            if (isCurrentlyBeingEdited) Icons.Default.Add else Icons.Default.Edit,
                                            contentDescription = "Edit"
                                        )
                                    }
                                    IconButton(onClick = {
                                        questionToDelete = question
                                        showDeleteConfirmationDialog = true
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }

                            if (isCurrentlyBeingEdited) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    OutlinedTextField(
                                        value = currentQuestionState.category ?: "",
                                        onValueChange = { onValueChange(currentQuestionState.copy(category = it)) },
                                        label = { Text("Category (Objective/Computation)") },
                                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                                    )
                                    OutlinedTextField(
                                        value = currentQuestionState.topic ?: "",
                                        onValueChange = { onValueChange(currentQuestionState.copy(topic = it)) },
                                        label = { Text("Topic") },
                                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.padding(start = 24.dp, top = 8.dp)) {
                                if (isCurrentlyBeingEdited) {
                                    OutlinedTextField(value = currentQuestionState.optionA, onValueChange = { onValueChange(currentQuestionState.copy(optionA = it)) }, label = { Text("Option A") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    OutlinedTextField(value = currentQuestionState.optionB, onValueChange = { onValueChange(currentQuestionState.copy(optionB = it)) }, label = { Text("Option B") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    OutlinedTextField(value = currentQuestionState.optionC, onValueChange = { onValueChange(currentQuestionState.copy(topic = it)) }, label = { Text("Option C") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    OutlinedTextField(value = currentQuestionState.optionD, onValueChange = { onValueChange(currentQuestionState.copy(optionD = it)) }, label = { Text("Option D") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    OutlinedTextField(value = currentQuestionState.correctAnswer ?: "", onValueChange = { onValueChange(currentQuestionState.copy(correctAnswer = it.uppercase())) }, label = { Text("Correct Answer") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                } else {
                                    OptionRowInBank("A", currentQuestionState.optionA)
                                    OptionRowInBank("B", currentQuestionState.optionB)
                                    OptionRowInBank("C", currentQuestionState.optionC)
                                    OptionRowInBank("D", currentQuestionState.optionD)
                                    
                                    currentQuestionState.correctAnswer?.let {
                                        Text(
                                            text = "Correct Answer: $it",
                                            modifier = Modifier.padding(top = 8.dp),
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                            
                            if (isCurrentlyBeingEdited) {
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        currentlyEditingQuestionId = null
                                        localQuestionChanges.remove(question.id)
                                        focusManager.clearFocus()
                                    }) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }

        if (showDeleteConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmationDialog = false },
                title = { Text("Delete Question") },
                text = { Text("Are you sure you want to delete this question?") },
                confirmButton = {
                    Button(onClick = {
                        questionToDelete?.let { viewModel.deleteQuestion(it) }
                        showDeleteConfirmationDialog = false
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmationDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}
