package com.pbec.preboardexamchecker.ui.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pbec.preboardexamchecker.data.repository.IExamRepository
import kotlinx.coroutines.launch

@Composable
fun SubjectMismatchDialog(
    detectedSubject: String,
    sessionSubject: String,
    examRepository: IExamRepository,
    onUseCurrentSession: () -> Unit,
    onRescan: () -> Unit,
    onSwitchExam: (String, Long) -> Unit
) {
    var showExamPicker by remember { mutableStateOf(false) }
    var isLoadingExams by remember { mutableStateOf(false) }
    var exams by remember { mutableStateOf<List<com.pbec.preboardexamchecker.data.models.Exam>>(emptyList()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Subject Mismatch") },
        text = {
            Column {
                Text("Detected subject: $detectedSubject")
                Text("Session subject: $sessionSubject")
                Spacer(Modifier.height(8.dp))
                if (showExamPicker) {
                    when {
                        isLoadingExams -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Loading $detectedSubject exams…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        exams.isEmpty() -> {
                            Text(
                                "No exams found for $detectedSubject. " +
                                    "Grade against the current session or re-scan instead.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            Text(
                                "Select the $detectedSubject exam to grade against:",
                                style = MaterialTheme.typography.labelMedium
                            )
                            exams.forEach { exam ->
                                TextButton(onClick = { onSwitchExam(detectedSubject, exam.id) }) {
                                    Text(exam.examName)
                                }
                            }
                        }
                    }
                } else {
                    Text("How would you like to proceed?")
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        showExamPicker = true
                        isLoadingExams = true
                        scope.launch {
                            exams = try {
                                examRepository.getExamsBySubjectOnce(detectedSubject)
                                    .sortedByDescending { it.createdAt }
                            } catch (e: Exception) { emptyList() }
                            isLoadingExams = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Switch Exam") }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onUseCurrentSession,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grade as $sessionSubject") }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Re-scan") }
            }
        }
    )
}
