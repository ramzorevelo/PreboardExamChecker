package com.pbec.preboardexamchecker.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import kotlinx.coroutines.launch

private enum class TrashTab(val label: String) { PAPERS("Papers"), EXAMS("Exams"), BANKS("Banks"), ROSTERS("Rosters") }

/** A pending "delete forever" confirmation: the dialog message + the action to run on confirm. */
private data class PurgeRequest(val message: String, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    navController: NavController,
    initialTab: String? = null,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val papers by viewModel.trashed.collectAsState()
    val exams by viewModel.trashedExams.collectAsState()
    val banks by viewModel.trashedBanks.collectAsState()
    val rosters by viewModel.trashedRosters.collectAsState()
    val trashedStudents by viewModel.trashedStudentsIndividual.collectAsState()
    val scope = rememberCoroutineScope()
    val initialPage = when (initialTab?.lowercase()) {
        "exams" -> 1; "banks" -> 2; "rosters" -> 3; else -> 0
    }
    // Pager so the user can swipe between tabs (not just tap), keeping the tab labels uncrowded.
    val pagerState = rememberPagerState(initialPage = initialPage) { TrashTab.entries.size }
    var purgeTarget by remember { mutableStateOf<PurgeRequest?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrandTopAppBar(
                title = "Trash",
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Scrollable so four tabs size to their content instead of wrapping ("Rosters").
            ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 8.dp) {
                TrashTab.entries.forEachIndexed { i, t ->
                    val count = when (t) {
                        TrashTab.PAPERS -> papers.size
                        TrashTab.EXAMS -> exams.size
                        TrashTab.BANKS -> banks.size
                        TrashTab.ROSTERS -> rosters.size + trashedStudents.size
                    }
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = { Text(if (count > 0) "${t.label} ($count)" else t.label, maxLines = 1) },
                    )
                }
            }
            Text(
                "Deleted items are kept for 30 days, then permanently removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (TrashTab.entries[page]) {
                    TrashTab.PAPERS -> TrashList(papers.isEmpty(), "No deleted papers.") {
                        items(papers, key = { it.id }) { r ->
                            TrashCard(
                                title = r.studentName,
                                subtitle = "${r.subject} · ${r.examName} · ${r.score}/${r.total}",
                                daysLeft = viewModel.daysLeft(r.deletedAt),
                                onRestore = { viewModel.restore(listOf(r.id)) },
                                onDeleteForever = {
                                    purgeTarget = PurgeRequest("${r.studentName}'s ${r.subject} paper") { viewModel.deleteForever(listOf(r.id)) }
                                },
                            )
                        }
                    }
                    TrashTab.EXAMS -> TrashList(exams.isEmpty(), "No deleted exams.") {
                        items(exams, key = { it.examId }) { e ->
                            TrashCard(
                                title = e.examName,
                                subtitle = e.subject,
                                daysLeft = viewModel.daysLeft(e.deletedAt),
                                onRestore = { viewModel.restoreExam(e.examId) },
                                onDeleteForever = {
                                    purgeTarget = PurgeRequest("the exam '${e.examName}'") { viewModel.deleteExamForever(e.examId) }
                                },
                            )
                        }
                    }
                    TrashTab.BANKS -> TrashList(banks.isEmpty(), "No deleted exam banks.") {
                        items(banks, key = { it.bankId }) { b ->
                            TrashCard(
                                title = b.displayName,
                                subtitle = "${b.subject} · ${b.questionCount} questions",
                                daysLeft = viewModel.daysLeft(b.deletedAt),
                                onRestore = { viewModel.restoreBank(b.bankId) },
                                onDeleteForever = {
                                    purgeTarget = PurgeRequest("the exam bank '${b.displayName}' and its questions") { viewModel.deleteBankForever(b.bankId) }
                                },
                            )
                        }
                    }
                    // Rosters: whole-import deletions and individually-deleted students, sectioned.
                    TrashTab.ROSTERS -> TrashList(rosters.isEmpty() && trashedStudents.isEmpty(), "No deleted rosters.") {
                        if (rosters.isNotEmpty()) {
                            item { TrashSectionHeader("Imported rosters") }
                            items(rosters, key = { "roster_${it.batchId}" }) { r ->
                                TrashCard(
                                    title = r.label,
                                    subtitle = "${r.count} student${if (r.count == 1) "" else "s"}",
                                    daysLeft = viewModel.daysLeft(r.deletedAt),
                                    onRestore = { viewModel.restoreRoster(r.batchId) },
                                    onDeleteForever = {
                                        purgeTarget = PurgeRequest("the roster '${r.label}' (${r.count} students)") { viewModel.deleteRosterForever(r.batchId) }
                                    },
                                )
                            }
                        }
                        if (trashedStudents.isNotEmpty()) {
                            item { TrashSectionHeader("Individual students") }
                            items(trashedStudents, key = { "student_${it.docId}" }) { st ->
                                TrashCard(
                                    title = st.name,
                                    subtitle = "ID ${st.studentId}",
                                    daysLeft = viewModel.daysLeft(st.deletedAt),
                                    onRestore = { viewModel.restoreStudent(st.docId) },
                                    onDeleteForever = {
                                        purgeTarget = PurgeRequest("${st.name}") { viewModel.deleteStudentForever(st.docId) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    purgeTarget?.let { req ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("Delete forever") },
            text = { Text("Permanently delete ${req.message}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { req.action(); purgeTarget = null }) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrashList(isEmpty: Boolean, emptyText: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    if (isEmpty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun TrashSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun TrashCard(
    title: String,
    subtitle: String,
    daysLeft: Long,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        // Less bottom padding: the action buttons below already carry their own vertical padding.
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (daysLeft <= 0) "Purges soon" else "$daysLeft day${if (daysLeft == 1L) "" else "s"} left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDeleteForever) { Text("Delete forever") }
                TextButton(onClick = onRestore) { Text("Restore") }
            }
        }
    }
}
