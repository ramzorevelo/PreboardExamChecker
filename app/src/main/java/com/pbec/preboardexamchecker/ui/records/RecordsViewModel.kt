package com.pbec.preboardexamchecker.ui.records

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pbec.preboardexamchecker.data.models.ExamCluster
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.data.repository.ExamClusterRepository
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import com.pbec.preboardexamchecker.data.repository.IStudentRepository
import com.pbec.preboardexamchecker.domain.excel.ClassReportExcelGenerator
import com.pbec.preboardexamchecker.domain.excel.ExamStatsExcelGenerator
import com.pbec.preboardexamchecker.domain.excel.PreboardRecordExcelGenerator
import com.pbec.preboardexamchecker.domain.excel.PreboardRecordRow
import com.pbec.preboardexamchecker.domain.email.EmailSettings
import com.pbec.preboardexamchecker.domain.email.SlipMail
import com.pbec.preboardexamchecker.domain.email.SmtpSlipSender
import com.pbec.preboardexamchecker.domain.model.StudentGwaRow
import com.pbec.preboardexamchecker.domain.model.selectionKey
import com.pbec.preboardexamchecker.domain.model.toSummaryStatus
import com.pbec.preboardexamchecker.domain.pdf.ExamStatsPdfGenerator
import com.pbec.preboardexamchecker.domain.pdf.ReportPdfStyle
import com.pbec.preboardexamchecker.domain.usecase.CalculateGwaUseCase
import com.pbec.preboardexamchecker.domain.usecase.ComputeExamStatsUseCase
import com.pbec.preboardexamchecker.domain.usecase.ExportClassReportUseCase
import com.pbec.preboardexamchecker.domain.usecase.ExportStudentSlipUseCase
import com.pbec.preboardexamchecker.domain.usecase.SubjectScore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Outcome of emailing slips: what sent, who had no address, and who errored. */
data class EmailSlipsResult(
    val configured: Boolean = true,
    val sent: Int = 0,
    val sendFailures: List<Pair<String, String>> = emptyList(),
    val missingNames: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IScanResultRepository,
    private val calculateGwa: CalculateGwaUseCase,
    private val computeExamStats: ComputeExamStatsUseCase,
    private val exportClassReport: ExportClassReportUseCase,
    private val exportStudentSlip: ExportStudentSlipUseCase,
    private val examStatsPdf: ExamStatsPdfGenerator,
    private val examStatsExcel: ExamStatsExcelGenerator,
    private val classReportExcel: ClassReportExcelGenerator,
    private val clusterRepository: ExamClusterRepository,
    private val preboardRecordExcel: PreboardRecordExcelGenerator,
    private val studentRepository: IStudentRepository,
    private val slipSender: SmtpSlipSender,
    private val studentRecordsRequest: StudentRecordsRequest,
) : ViewModel() {

    private val _filters = MutableStateFlow(RecordsFilters())
    val filters: StateFlow<RecordsFilters> = _filters.asStateFlow()

    private val resultsFlow: StateFlow<List<ScanResult>> =
        repository.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val clustersFlow: StateFlow<List<ExamCluster>> =
        clusterRepository.observeClusters()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<RecordsUiState> =
        combine(resultsFlow, clustersFlow, _filters) { results, clusters, f ->
            buildState(results, clusters, f)
        }
            // buildState re-aggregates the full result set per emission; keep it off Main.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordsUiState(loading = true))

    // Memoize the last computed stats so flipping tabs (which doesn't change the exam or data)
    // doesn't trigger a fresh Firestore answer-key rebuild.
    private var cachedStatsKey: Triple<String, Long, Int>? = null
    private var cachedStats: ExamStatsUiState? = null

    val examStats: StateFlow<ExamStatsUiState> =
        combine(resultsFlow, _filters) { results, f -> results to f }
            .transformLatest { (results, f) ->
                val examId = f.examId
                if (f.tab != RecordsTab.EXAM_STATS || examId == null) {
                    emit(ExamStatsUiState.Prompt)
                    return@transformLatest
                }
                // Cluster=null ⇒ overall (all clusters); a cluster ⇒ only that cluster's takers,
                // so the same exam under two clusters yields separate stats.
                val takers = results.filter {
                    it.examId == examId && (f.clusterId == null || it.clusterId == f.clusterId)
                }
                // The exam already belongs to a subject; derive it from the scans when "All" is set.
                val subject = f.subject.takeIf { it != ALL } ?: takers.firstOrNull()?.subject
                if (subject == null) {
                    emit(ExamStatsUiState.Unavailable)
                    return@transformLatest
                }
                // Key by subject+exam and a cheap content signature of the takers' graded answers.
                val signature = takers.fold(0) { acc, r -> acc * 31 + (r.id.hashCode() xor r.rawAnswers.hashCode()) }
                val key = Triple(subject, examId, signature)
                if (key == cachedStatsKey) {
                    cachedStats?.let { emit(it); return@transformLatest }
                }
                emit(ExamStatsUiState.Loading)
                val stats = computeExamStats(subject, examId, takers)
                val result = if (stats == null) ExamStatsUiState.Unavailable else ExamStatsUiState.Ready(stats)
                cachedStatsKey = key
                cachedStats = result
                emit(result)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExamStatsUiState.Prompt)

    /** Reconstructed answer key for one paper, for the By-Subject per-item breakdown. */
    suspend fun answerKeyFor(result: ScanResult): String =
        computeExamStats.answerKeyFor(result.subject, result.examId, result.testSet) ?: ""

    init {
        // Apply a "show only these students" request from the Students screen as soon as it arrives,
        // independent of screen composition/restore timing (the screen LaunchedEffect raced with the
        // tab restore). Consume it so a later plain visit to Records isn't re-scoped.
        viewModelScope.launch {
            studentRecordsRequest.ids.collect { ids ->
                if (!ids.isNullOrEmpty()) {
                    _filters.update { it.copy(studentIds = ids.toSet(), tab = RecordsTab.BY_SUBJECT) }
                    studentRecordsRequest.consume()
                }
            }
        }
        // Sweep out any trashed records that have outlived the 30-day retention window.
        viewModelScope.launch { runCatching { repository.purgeExpired() } }
        // Auto-reset the exam filter to "All" once the selected exam has no records left (e.g. all
        // of its papers were just deleted), so the view doesn't stay stuck on an empty exam.
        viewModelScope.launch {
            resultsFlow.collect { results ->
                val examId = _filters.value.examId ?: return@collect
                if (results.none { it.examId == examId }) {
                    _filters.update { if (it.examId == examId) it.copy(examId = null) else it }
                }
            }
        }
    }

    /** Move a single scanned paper to the Trash (restorable for 30 days). */
    fun trashResult(id: Long) {
        viewModelScope.launch { repository.moveToTrash(listOf(id)) }
    }

    /** Move every record of one exam to the Trash. */
    fun trashExamRecords(examId: Long) {
        viewModelScope.launch { repository.moveExamToTrash(examId) }
    }

    /** Move every record of one subject to the Trash. */
    fun trashSubjectRecords(subject: String) {
        viewModelScope.launch { repository.moveSubjectToTrash(subject) }
    }

    // ---- filter mutators ----
    fun setTab(tab: RecordsTab) = _filters.update { it.copy(tab = tab) }
    fun setSearch(q: String) = _filters.update { it.copy(search = q) }
    fun setBlock(v: String) = _filters.update { it.copy(block = v) }
    // Program is block's parent — switching program invalidates the chosen block.
    fun setProgram(v: String) = _filters.update { it.copy(program = v, block = ALL) }
    // Exams are subject-specific — switching subject invalidates the chosen exam.
    fun setSubject(v: String) = _filters.update { it.copy(subject = v, examId = null) }
    fun setExam(id: Long?) = _filters.update { it.copy(examId = id) }
    fun setCluster(id: Long?) = _filters.update { it.copy(clusterId = id) }
    fun setSchoolYear(v: String) = _filters.update { it.copy(schoolYear = v) }
    fun setStudentIds(ids: Set<String>) = _filters.update { it.copy(studentIds = ids) }
    fun clearStudentIds() = _filters.update { it.copy(studentIds = emptySet()) }
    fun setSort(s: RecordsSort) = _filters.update {
        if (it.tab == RecordsTab.BY_SUBJECT) it.copy(subjectSort = s) else it.copy(overallSort = s)
    }
    fun setExamSort(s: ExamSummarySort) = _filters.update { it.copy(examSort = s) }
    fun setExamSearch(q: String) = _filters.update { it.copy(examSearch = q) }

    // ---- exports ----
    // All Overall-tab exports scope to a set of selection keys; empty set falls through to everyone.
    suspend fun generateClassReport(keys: Set<String>): File {
        val rows = scopedRows(keys)
        return exportClassReport(rows, scopeLabel(rows))
    }

    suspend fun generateClassReportXlsx(keys: Set<String>): File {
        val rows = scopedRows(keys)
        return classReportExcel.generate(context, rows, scopeLabel(rows))
    }

    /** Combined: all selected slips in one multi-page PDF. Null when nothing selected. */
    suspend fun generateStudentSlipsCombined(keys: Set<String>): File? {
        val rows = scopedRows(keys)
        return if (rows.isEmpty()) null else exportStudentSlip(rows)
    }

    /** One separate slip PDF per selected student; for ACTION_SEND_MULTIPLE. Empty when nothing selected. */
    suspend fun generateStudentSlipFiles(keys: Set<String>): List<File> =
        scopedRows(keys).map { exportStudentSlip(it) }

    /** Same slips bundled into one .zip — SAF saves a single file, so loose PDFs aren't an option. */
    suspend fun generateStudentSlipsZip(keys: Set<String>): File? {
        val files = generateStudentSlipFiles(keys)
        if (files.isEmpty()) return null
        return zipFiles(files, "student_slips")
    }

    /** Whether the instructor has configured SMTP; gates the "Email slips" action in the UI. */
    fun isEmailConfigured(): Boolean = EmailSettings.isConfigured(context)

    /**
     * Emails each selected student their own slip in one batch. Looks up each student's email from the
     * roster, generates a slip only for those that have one, and reports students with no email so the
     * instructor knows who to chase.
     */
    suspend fun emailStudentSlips(keys: Set<String>): EmailSlipsResult {
        val config = EmailSettings.load(context)
        if (!config.isConfigured) return EmailSlipsResult(configured = false)

        val rows = scopedRows(keys)
        if (rows.isEmpty()) return EmailSlipsResult()

        val emailByStudentId = runCatching { studentRepository.getAllStudents() }
            .getOrDefault(emptyList())
            .associate { it.studentId to it.email }

        val (withEmail, withoutEmail) = rows.partition {
            !emailByStudentId[it.studentId].isNullOrBlank()
        }
        val missingNames = withoutEmail.map { it.name.ifBlank { it.studentId } }

        val slips = withEmail.map { row ->
            SlipMail(
                recipient = emailByStudentId.getValue(row.studentId),
                studentName = row.name.ifBlank { row.studentId },
                file = exportStudentSlip(row),
            )
        }
        val result = slipSender.send(config, slips)
        return EmailSlipsResult(
            configured = true,
            sent = result.sent,
            sendFailures = result.failures,
            missingNames = missingNames,
        )
    }

    private suspend fun zipFiles(files: List<File>, baseName: String): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "${baseName}_${System.currentTimeMillis()}.zip")
        ZipOutputStream(BufferedOutputStream(out.outputStream())).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        out
    }

    /** Institutional RESULTS workbook for the cluster filter (whole cluster). */
    suspend fun generatePreboardRecordXlsx(): File? {
        val clusterId = _filters.value.clusterId ?: return null
        val rows = uiState.value.overallGroups.flatMap { it.rows }.filter { it.clusterId == clusterId }
        return preboardRecordFor(clusterId, rows)
    }

    /** Same, scoped to a selection; null unless the selected rows share exactly one cluster. */
    suspend fun generatePreboardRecordXlsx(keys: Set<String>): File? {
        val rows = scopedRows(keys)
        val clusterId = rows.mapNotNull { it.clusterId }.distinct().singleOrNull() ?: return null
        return preboardRecordFor(clusterId, rows.filter { it.clusterId == clusterId })
    }

    /**
     * Joins each cluster GWA row with the roster (for gender sectioning) and emits the per-subject
     * percentage so the sheet's RATING formula reproduces the app's GWA. Null on empty rows.
     */
    private suspend fun preboardRecordFor(clusterId: Long, rows: List<StudentGwaRow>): File? {
        if (rows.isEmpty()) return null
        val cluster = runCatching { clusterRepository.getClusterById(clusterId) }.getOrNull()
        val byStudentId = runCatching { studentRepository.getAllStudents() }
            .getOrDefault(emptyList()).associateBy { it.studentId }
        val instructor = byStudentId.values.firstOrNull { it.instructor.isNotBlank() }?.instructor.orEmpty()
        val program = byStudentId.values.firstOrNull { it.program.isNotBlank() }?.program.orEmpty()

        val recordRows = rows.map { row ->
            fun pctOf(subject: String) = row.lines.firstOrNull { it.subject == subject }?.percentage
            PreboardRecordRow(
                name = row.name,
                studentId = row.studentId,
                block = row.block,
                email = byStudentId[row.studentId]?.email.orEmpty(),
                gender = byStudentId[row.studentId]?.gender.orEmpty(),
                mathPct = pctOf("Mathematics"),
                esasPct = pctOf("ESAS"),
                profPct = pctOf("Professional EE"),
                remark = ReportPdfStyle.statusText(row.status),
            )
        }
        return preboardRecordExcel.generate(
            context,
            clusterName = cluster?.name ?: rows.firstOrNull()?.clusterName ?: "cluster",
            program = program,
            schoolYear = cluster?.schoolYear.orEmpty(),
            instructor = instructor,
            rows = recordRows,
        )
    }

    private fun scopedRows(keys: Set<String>): List<StudentGwaRow> {
        val all = uiState.value.overallGroups.flatMap { it.rows }
        return if (keys.isEmpty()) all else all.filter { it.selectionKey() in keys }
    }

    // Report title/filename: the lone block when rows share one, else a neutral "Selected".
    private fun scopeLabel(rows: List<StudentGwaRow>): String =
        rows.map { it.block }.distinct().singleOrNull()?.takeIf { it.isNotBlank() } ?: "Selected"

    suspend fun generateStudentSlip(row: StudentGwaRow): File = exportStudentSlip(row)

    suspend fun generateExamStatsPdf(): File? {
        val stats = (examStats.value as? ExamStatsUiState.Ready)?.stats ?: return null
        return examStatsPdf.generate(context, stats)
    }

    suspend fun generateExamStatsXlsx(): File? {
        val stats = (examStats.value as? ExamStatsUiState.Ready)?.stats ?: return null
        // Scope the appendix takers to the same cluster the on-screen stats used.
        val clusterId = _filters.value.clusterId
        val takers = resultsFlow.value.filter {
            it.examId == stats.examId && (clusterId == null || it.clusterId == clusterId)
        }
        return examStatsExcel.generate(context, stats, takers)
    }

    // ---- state building ----
    private fun buildState(results: List<ScanResult>, clusters: List<ExamCluster>, f: RecordsFilters): RecordsUiState {
        val clusterById = clusters.associateBy { it.id }
        // A scan's school year comes from its cluster (scans don't carry it themselves).
        fun schoolYearOf(r: ScanResult): String =
            r.clusterId?.let { clusterById[it]?.schoolYear }.orEmpty()

        // Cascading option lists: each narrowed by the OTHER active selections (not by search).
        fun blockMatch(r: ScanResult) = f.block == ALL || r.studentBlock.equals(f.block, ignoreCase = true)
        fun programMatch(r: ScanResult) = f.program == ALL || r.studentProgram.equals(f.program, ignoreCase = true)
        fun subjectMatch(r: ScanResult) = f.subject == ALL || r.subject.equals(f.subject, ignoreCase = true)
        fun examMatch(r: ScanResult) = f.examId == null || r.examId == f.examId
        fun clusterMatch(r: ScanResult) = f.clusterId == null || r.clusterId == f.clusterId
        fun schoolYearMatch(r: ScanResult) = f.schoolYear == ALL || schoolYearOf(r).equals(f.schoolYear, ignoreCase = true)
        fun studentIdsMatch(r: ScanResult) = f.studentIds.isEmpty() || r.studentId in f.studentIds

        // Cluster/school-year/selected-students scope everything else, so narrow up front.
        val scoped = results.filter { clusterMatch(it) && schoolYearMatch(it) && studentIdsMatch(it) }
        val allRows = scoped.toStudentRows(clusterById)

        val programOptions = listOf(ALL) + scoped.filter { blockMatch(it) }
            .map { it.studentProgram.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val blockOptions = listOf(ALL) + scoped
            .filter { if (f.tab == RecordsTab.OVERALL) programMatch(it) else subjectMatch(it) && examMatch(it) }
            .map { it.studentBlock.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val subjectOptions = listOf(ALL) + scoped.filter { blockMatch(it) }
            .map { it.subject.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val examOptions = scoped.filter { subjectMatch(it) && blockMatch(it) }
            .map { ExamOption(it.examId, it.examName) }.distinctBy { it.id }.sortedBy { it.name }
        // Cluster/school-year options span all records (not self-narrowed), so any cluster stays pickable.
        val clusterOptions = results.mapNotNull { r -> r.clusterId?.let { it to (r.clusterName ?: "Cluster $it") } }
            .distinctBy { it.first }.map { ClusterOption(it.first, it.second) }.sortedBy { it.name }
        val schoolYearOptions = listOf(ALL) + results.mapNotNull { it.clusterId?.let { id -> clusterById[id]?.schoolYear } }
            .filter { it.isNotBlank() }.distinct().sorted()

        // Lightweight per-exam overview cards for the Exam Stats tab (narrowed by the subject filter).
        val examSummaries = scoped.filter { subjectMatch(it) }
            .groupBy { it.examId }
            .map { (examId, group) ->
                val pcts = group.map { pct(it) }
                ExamSummary(
                    examId = examId,
                    examName = group.first().examName,
                    subject = group.first().subject,
                    takers = group.size,
                    avgPct = if (pcts.isNotEmpty()) pcts.average() else 0.0,
                    passRate = group.count { it.passed } * 100.0 / group.size,
                )
            }
            .filter { matchesSearch(f.examSearch, it.examName, it.subject) }
            .sortedWith(examSummaryComparator(f.examSort))

        // Overall tab.
        val overallGroups = allRows
            .filter { row ->
                (f.block == ALL || row.block.equals(f.block, ignoreCase = true)) &&
                    (f.program == ALL || row.program.equals(f.program, ignoreCase = true)) &&
                    matchesSearch(f.search, row.name, row.studentId)
            }
            .sortedWith(rowComparator(f.overallSort))
            .groupBy { it.block.ifBlank { "—" } }
            .map { (block, rows) -> BlockGroup(block, rows) }

        // By-Subject tab.
        val subjectRows = scoped
            .filter { r ->
                subjectMatch(r) && examMatch(r) && blockMatch(r) &&
                    matchesSearch(f.search, r.studentName, r.studentId)
            }
            .sortedWith(scanComparator(f.subjectSort))
            .map { SubjectScanRow(it) }

        return RecordsUiState(
            loading = false,
            filters = f,
            overallGroups = overallGroups,
            subjectRows = subjectRows,
            blockOptions = blockOptions,
            programOptions = programOptions,
            subjectOptions = subjectOptions,
            examOptions = examOptions,
            clusterOptions = clusterOptions,
            schoolYearOptions = schoolYearOptions,
            examSummaries = examSummaries,
        )
    }

    /**
     * One GWA row per (student, cluster): groups by subject within the cluster (latest scan wins)
     * so a student's three subjects always come from the same round, even when several exams exist
     * per subject and the scanned paper isn't the latest overall.
     */
    private fun List<ScanResult>.toStudentRows(clusterById: Map<Long, ExamCluster>): List<StudentGwaRow> =
        groupBy { it.studentId to it.clusterId }.map { (key, scans) ->
            val (studentId, clusterId) = key
            // getAll() is ordered newest-first, so the first scan per subject is the latest.
            val latestPerSubject = scans.groupBy { it.subject }.map { (_, s) -> s.first() }
            val subjectScores = latestPerSubject.map { SubjectScore(it.subject, it.score, it.total) }
            val gwa = calculateGwa(subjectScores)
            val newest = scans.maxOf { it.scannedAt }
            val ref = scans.first()
            val cluster = clusterId?.let { clusterById[it] }
            StudentGwaRow(
                studentId = studentId,
                name = ref.studentName,
                block = ref.studentBlock,
                yearLevel = ref.studentYearLevel,
                program = ref.studentProgram,
                lines = gwa.lines,
                gwa = gwa.gwa,
                status = gwa.toSummaryStatus(),
                lastScannedAt = newest,
                clusterId = clusterId,
                clusterName = cluster?.name ?: ref.clusterName,
                schoolYear = cluster?.schoolYear,
            )
        }

    private fun matchesSearch(query: String, name: String, id: String): Boolean =
        query.isBlank() || name.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)

    private fun rowComparator(sort: RecordsSort): Comparator<StudentGwaRow> = when (sort) {
        RecordsSort.NAME_ASC -> compareBy { it.name.lowercase() }
        RecordsSort.NAME_DESC -> compareByDescending { it.name.lowercase() }
        RecordsSort.GWA_DESC -> compareByDescending { it.gwa }
        RecordsSort.GWA_ASC -> compareBy { it.gwa }
        RecordsSort.DATE_DESC -> compareByDescending { it.lastScannedAt }
        RecordsSort.DATE_ASC -> compareBy { it.lastScannedAt }
    }

    private fun examSummaryComparator(sort: ExamSummarySort): Comparator<ExamSummary> = when (sort) {
        ExamSummarySort.NAME_ASC -> compareBy { it.examName.lowercase() }
        ExamSummarySort.NAME_DESC -> compareByDescending { it.examName.lowercase() }
        ExamSummarySort.TAKERS_DESC -> compareByDescending { it.takers }
        ExamSummarySort.AVG_DESC -> compareByDescending { it.avgPct }
        ExamSummarySort.PASS_DESC -> compareByDescending { it.passRate }
    }

    private fun scanComparator(sort: RecordsSort): Comparator<ScanResult> = when (sort) {
        RecordsSort.NAME_ASC -> compareBy { it.studentName.lowercase() }
        RecordsSort.NAME_DESC -> compareByDescending { it.studentName.lowercase() }
        // GWA isn't meaningful per scan; fall back to percentage.
        RecordsSort.GWA_DESC -> compareByDescending { pct(it) }
        RecordsSort.GWA_ASC -> compareBy { pct(it) }
        RecordsSort.DATE_DESC -> compareByDescending { it.scannedAt }
        RecordsSort.DATE_ASC -> compareBy { it.scannedAt }
    }

    private fun pct(r: ScanResult): Double =
        if (r.total > 0) r.score.toDouble() / r.total * 100.0 else 0.0
}
