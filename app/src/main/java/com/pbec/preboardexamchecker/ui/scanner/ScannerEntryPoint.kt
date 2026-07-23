package com.pbec.preboardexamchecker.ui.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.data.repository.IExamRepository
import com.pbec.preboardexamchecker.ui.theme.LocalExtendedColors
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringConfig
import kotlinx.coroutines.launch
import javax.inject.Inject

@Composable
fun ScannerEntryPoint(
    navController: NavController,
    examRepository: IExamRepository,
    viewModel: ScannerViewModel = hiltViewModel(
        viewModelStoreOwner = LocalContext.current as androidx.activity.ComponentActivity
    )
) {
    val state by viewModel.state.collectAsState()
    val scanMode by viewModel.scanModeFlow.collectAsState()

    when (val s = state) {
        is ScanSessionState.Setup -> {
            SessionSetupScreen(viewModel = viewModel)
        }

        is ScanSessionState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Loading session...")
                }
            }
        }

        is ScanSessionState.SingleCapture -> {
            SingleCaptureScreen(
                onCapture = { bitmap -> viewModel.processSingle(bitmap) },
                onEndSession = { viewModel.endSession() },
                viewModel = viewModel
            )
        }

        is ScanSessionState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        "Error (Phase ${s.phase})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(s.reason, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.dismissError() }) {
                        Text("Try Again")
                    }
                }
            }
        }

        // Phase1 / Phase1Review / Phase2 / SubjectMismatch / ResultDisplay share one camera
        // call site, so it binds once and is never torn down between captures within a paper.
        else -> {
            // Single-mode ResultDisplay shows only the card; single owns its own camera.
            val showCamera = s !is ScanSessionState.ResultDisplay || scanMode == ScanMode.TWO_PHASE
            Box(Modifier.fillMaxSize()) {
                if (showCamera) {
                    val phase = if (s is ScanSessionState.Phase1 || s is ScanSessionState.Phase1Review) 1 else 2
                    ScanPhaseScreen(
                        phase = phase,
                        onCapture = { p, bitmap ->
                            if (p == 1) viewModel.processPhase1(bitmap) else viewModel.processPhase2(bitmap)
                        },
                        onEndSession = { viewModel.endSession() },
                        viewModel = viewModel
                    )
                }
                when (s) {
                    is ScanSessionState.Phase1Review -> Phase1ReviewCard(
                        info = s.info,
                        onRetry = { viewModel.retryPhase1() },
                        onConfirm = { viewModel.confirmPhase1() },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                    is ScanSessionState.SubjectMismatch -> SubjectMismatchDialog(
                        detectedSubject = s.detectedSubject,
                        sessionSubject = s.context.subject,
                        examRepository = examRepository,
                        onUseCurrentSession = { viewModel.resolveSubjectMismatch_useCurrentSession() },
                        onRescan = { viewModel.resolveSubjectMismatch_rescan() },
                        onSwitchExam = { newSubject, newExamId ->
                            viewModel.resolveSubjectMismatch_switchExam(newSubject, newExamId)
                        }
                    )
                    is ScanSessionState.ResultDisplay -> ResultDisplayCard(
                        result = s.result,
                        answerKey = s.answerKey,
                        duplicateCount = s.duplicateCount,
                        onNextStudent = { viewModel.nextStudent() },
                        onReplaceExisting = { viewModel.replaceExistingAndNext() },
                        onDiscard = { viewModel.discardAndNext() },
                        onRetry = { viewModel.retryPhase2() },
                        onEndSession = { viewModel.endSession() }
                    )
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun Phase1ReviewCard(
    info: com.pbec.preboardexamchecker.ui.scanner.processor.ScannedInfo,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Student Info", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val student = info.resolvedStudent
            if (student != null) {
                Text("Name: ${student.name}")
                Text("Block: ${student.block}")
                Text("Year: ${student.yearLevel}")
                Text("Program: ${student.program}")
            } else {
                Text(
                    "Student ID: ${info.studentId} — not found in database",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text("Test Set: ${if (info.testSet == "-") "Not detected" else info.testSet}")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Proceed to Answer Scan")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry Info Scan")
            }
        }
    }
}

@Composable
fun ResultDisplayCard(
    result: com.pbec.preboardexamchecker.data.models.ScanResult,
    answerKey: String,
    onNextStudent: () -> Unit,
    onRetry: () -> Unit,
    onEndSession: () -> Unit,
    duplicateCount: Int = 0,
    onReplaceExisting: () -> Unit = {},
    onDiscard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 12.dp)
        ) {
            // Header/score/grid scroll as a unit; action buttons stay pinned below.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(result.studentName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Block: ${result.studentBlock}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "ID: ${result.studentId}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${result.subject} — Set ${result.testSet}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                if (duplicateCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠ This student already has ${if (duplicateCount == 1) "a record" else "$duplicateCount records"} " +
                                "for this exam. Saving will create a duplicate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "${result.score} / ${result.total}",
                            style = MaterialTheme.typography.displaySmall
                        )
                        val pct = if (result.total > 0)
                            "%.2f%%".format(result.score.toDouble() / result.total * 100)
                        else "0.00%"
                        Text(pct, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (result.passed) "PASSED" else "FAILED",
                            color = if (result.passed)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            "Passing: ${(ScoringConfig.SUBJECT_PASS_FLOOR * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                ResultAnswerGrid(
                    rawAnswers = result.rawAnswers,
                    answerKey = answerKey,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            if (duplicateCount > 0) {
                Button(onClick = onReplaceExisting, modifier = Modifier.fillMaxWidth()) {
                    Text("Replace existing record")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onNextStudent, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep both & continue")
                }
                Spacer(Modifier.height(4.dp))
                // Skip without saving, so End Session can't silently save the duplicate.
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Discard (don't save)")
                }
            } else {
                Button(onClick = onNextStudent, modifier = Modifier.fillMaxWidth()) {
                    Text("Next Student")
                }
            }
            Spacer(Modifier.height(4.dp))
            // Re-scan answers (wrong-marker lock). The result is unsaved, so this discards it.
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry Answer Scan")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onEndSession, modifier = Modifier.fillMaxWidth()) {
                Text("End Session")
            }
        }
    }
}

// 5×20 column-major grid to mirror the answer sheet; green=correct, red=wrong,
// grey=blank/no key.
@Composable
internal fun ResultAnswerGrid(
    rawAnswers: String,
    answerKey: String,
    modifier: Modifier = Modifier
) {
    val rows = 20
    val cols = 5
    // Row height tracks the 10sp cell text (via sp→dp) so it grows with the Appearance
    // font-size setting instead of clipping; still tight to minimize scrolling.
    val rowHeight = with(LocalDensity.current) { 12.sp.toDp() }
    Column(modifier = modifier) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (c in 0 until cols) {
                    val index = c * rows + r
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (index < rows * cols) {
                            val student = rawAnswers.getOrNull(index)?.toString() ?: "-"
                            val key = answerKey.getOrNull(index)?.toString() ?: "-"
                            val color = when {
                                key == "-" -> MaterialTheme.colorScheme.onSurfaceVariant
                                student == key -> LocalExtendedColors.current.pass
                                else -> MaterialTheme.colorScheme.error
                            }
                            Text(
                                text = "${index + 1}.$student",
                                color = color,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

