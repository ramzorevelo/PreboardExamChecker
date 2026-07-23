package com.pbec.preboardexamchecker.ui.records

import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.domain.model.ExamStats
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.model.SummaryStatus

const val ALL = "All"

enum class RecordsTab { OVERALL, BY_SUBJECT, EXAM_STATS }

enum class RecordsSort(val label: String) {
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    GWA_DESC("GWA (high–low)"),
    GWA_ASC("GWA (low–high)"),
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
}

/** Sort options for the Exam Stats overview list (per-exam summary cards). */
enum class ExamSummarySort(val label: String) {
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    TAKERS_DESC("Takers (high–low)"),
    AVG_DESC("Avg % (high–low)"),
    PASS_DESC("Pass rate (high–low)"),
}

/** All user-driven selections that shape the summary view. */
data class RecordsFilters(
    val tab: RecordsTab = RecordsTab.OVERALL,
    val search: String = "",
    val block: String = ALL,
    val program: String = ALL,
    val subject: String = ALL,
    val examId: Long? = null,        // null = All exams
    val clusterId: Long? = null,     // null = All clusters
    val schoolYear: String = ALL,
    // Non-empty when opened from the Students screen for a chosen set; clearable by the user.
    val studentIds: Set<String> = emptySet(),
    // Sort is kept independently per tab so each tab remembers its own ordering.
    val overallSort: RecordsSort = RecordsSort.NAME_ASC,
    val subjectSort: RecordsSort = RecordsSort.NAME_ASC,
    val examSort: ExamSummarySort = ExamSummarySort.NAME_ASC,
    // Free-text search scoped to the Exam Stats overview list (kept apart from `search`, which is Overall/By-Subject).
    val examSearch: String = "",
) {
    /** The sort that applies to the currently-selected tab. */
    val activeSort: RecordsSort get() = if (tab == RecordsTab.BY_SUBJECT) subjectSort else overallSort
}

/** One block's students plus its pass/fail tally (Overall tab). */
data class BlockGroup(
    val block: String,
    val rows: List<StudentGwaRow>,
) {
    val total: Int get() = rows.size
    val passCount: Int get() = rows.count { it.status == SummaryStatus.PASS }
    val failCount: Int get() = rows.count { it.status == SummaryStatus.FAIL }
    val incompleteCount: Int get() = rows.count { it.status == SummaryStatus.INCOMPLETE }
    val passRate: Double get() = if (total > 0) passCount * 100.0 / total else 0.0
}

/** One scan row for the By-Subject tab. */
data class SubjectScanRow(val result: ScanResult) {
    val percentage: Double
        get() = if (result.total > 0) result.score.toDouble() / result.total * 100.0 else 0.0
}

/** A selectable exam (id + display name) for the exam filter dropdown. */
data class ExamOption(val id: Long, val name: String)

/** A selectable cluster (id + display name) for the cluster filter dropdown. */
data class ClusterOption(val id: Long, val name: String)

/** Lightweight per-exam overview card (no answer-key rebuild needed — derived from scores only). */
data class ExamSummary(
    val examId: Long,
    val examName: String,
    val subject: String,
    val takers: Int,
    val avgPct: Double,
    val passRate: Double,
)

/** Async state for the Exam Stats tab (computed separately — needs Firestore answer-key rebuild). */
sealed interface ExamStatsUiState {
    /** No subject/exam chosen yet. */
    data object Prompt : ExamStatsUiState
    data object Loading : ExamStatsUiState
    /** Exam or its question bank couldn't be resolved. */
    data object Unavailable : ExamStatsUiState
    data class Ready(val stats: ExamStats) : ExamStatsUiState
}

data class RecordsUiState(
    val loading: Boolean = true,
    val filters: RecordsFilters = RecordsFilters(),
    val overallGroups: List<BlockGroup> = emptyList(),
    val subjectRows: List<SubjectScanRow> = emptyList(),
    // Cascading filter option lists (each narrowed by the other active selections).
    val blockOptions: List<String> = listOf(ALL),
    val programOptions: List<String> = listOf(ALL),
    val subjectOptions: List<String> = listOf(ALL),
    val examOptions: List<ExamOption> = emptyList(),
    val clusterOptions: List<ClusterOption> = emptyList(),
    val schoolYearOptions: List<String> = listOf(ALL),
    val examSummaries: List<ExamSummary> = emptyList(),
) {
    val isEmpty: Boolean
        get() = when (filters.tab) {
            RecordsTab.OVERALL -> overallGroups.isEmpty()
            RecordsTab.BY_SUBJECT -> subjectRows.isEmpty()
            RecordsTab.EXAM_STATS -> false // handled by ExamStatsUiState
        }
}
