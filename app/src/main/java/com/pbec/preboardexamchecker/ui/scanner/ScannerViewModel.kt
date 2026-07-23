package com.pbec.preboardexamchecker.ui.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.data.models.Student
import com.pbec.preboardexamchecker.data.repository.IExamClusterRepository
import com.pbec.preboardexamchecker.data.repository.IExamRepository
import com.pbec.preboardexamchecker.data.repository.QuestionRepository
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import com.pbec.preboardexamchecker.data.repository.IStudentRepository
import com.pbec.preboardexamchecker.ui.scanner.processor.AnswerZoneProcessor
import com.pbec.preboardexamchecker.ui.scanner.processor.InfoZoneProcessor
import com.pbec.preboardexamchecker.ui.scanner.processor.ScanContext
import com.pbec.preboardexamchecker.ui.scanner.processor.ScannedInfo
import com.pbec.preboardexamchecker.ui.scanner.processor.SharpnessChecker
import com.pbec.preboardexamchecker.ui.scanner.processor.SingleCaptureProcessor
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringStrategy
import com.pbec.preboardexamchecker.domain.pdf.ReportPdfStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val studentRepository: IStudentRepository,
    private val examRepository: IExamRepository,
    private val clusterRepository: IExamClusterRepository,
    private val scanResultRepository: IScanResultRepository,
    private val scoringStrategy: ScoringStrategy,
    private val questionRepository: QuestionRepository,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    private val _state = MutableStateFlow<ScanSessionState>(ScanSessionState.Setup)
    val state: StateFlow<ScanSessionState> = _state.asStateFlow()

    // Drives the bottom-nav Capture button: capture circle vs default Search icon.
    val sessionActive: StateFlow<Boolean> = state
        .map { it !is ScanSessionState.Setup && it !is ScanSessionState.Loading && it !is ScanSessionState.Error }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Shown on the overlay so the instructor sees the subject captures grade against,
    // especially after a "Switch Exam" resolution. Null off-session.
    private val _activeSubject = MutableStateFlow<String?>(null)
    val activeSubject: StateFlow<String?> = _activeSubject.asStateFlow()

    private val _manualCaptureEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val manualCaptureEvent: SharedFlow<Unit> = _manualCaptureEvent.asSharedFlow()

    private val _rearmCapture = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rearmCapture: SharedFlow<Unit> = _rearmCapture.asSharedFlow()

    // Non-null while processing; shown as a blocking overlay (hold still).
    private val _processing = MutableStateFlow<String?>(null)
    val processing: StateFlow<String?> = _processing.asStateFlow()

    // 0f–1f stability countdown for the Capture button's arc; 0f when not stabilising.
    private val _captureProgress = MutableStateFlow(0f)
    val captureProgress: StateFlow<Float> = _captureProgress.asStateFlow()

    fun setCaptureProgress(progress: Float) {
        _captureProgress.value = progress
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Null unless SAVE_SCAN_DEBUG is on, so debug writes stay out of normal runs.
    private val debugCtx: android.content.Context?
        get() = if (DebugFlags.SAVE_SCAN_DEBUG) appContext else null

    fun requestManualCapture() {
        _manualCaptureEvent.tryEmit(Unit)
    }

    private var currentContext: ScanContext? = null
    private var activeContext: ScanContext? = null
    private var currentScannedInfo: ScannedInfo? = null

    // Non-null during a cluster session: one prebuilt context per subject, so a scanned paper
    // routes to its subject's exam instantly (no re-fetch). Null for single-exam sessions.
    private var clusterContexts: Map<String, ScanContext>? = null

    // Held until accept (Next Student / End Session) so retryPhase2() can discard a
    // wrong-marker result without leaving a stale/duplicate Room row + Firestore sync.
    private var pendingScanResult: ScanResult? = null

    // Existing active records for the pending student+exam (excludes the Records re-scan
    // target). Surfaced as a duplicate warning; trashed only on "Replace existing".
    private var pendingDuplicateIds: List<Long> = emptyList()

    // Record being re-scanned (Records flow); trashed once the new scan persists so
    // taker counts don't double-count. Stays restorable for 30 days.
    private var replaceResultId: Long? = null

    // Frame that tripped the subject-mismatch gate, held so the instructor can re-score
    // this exact sheet instead of re-capturing. Recycled on consume / abandon.
    private var pendingMismatchBitmap: Bitmap? = null
    // Locked hints from the first read, reused so the re-score warps off the same
    // corners. Without them a sheet that only detected via the live lock would fail
    // "find 4 markers" on re-process and raise a misleading mismatch dialog.
    private var pendingMismatchHints: Array<org.opencv.core.Point?>? = null

    private fun clearPendingMismatchBitmap() {
        pendingMismatchBitmap?.recycle()
        pendingMismatchBitmap = null
        pendingMismatchHints = null
    }

    // JPEG-encoded raw frame of the held success scan, produced off-thread as soon as the
    // result is shown (when "Save raw" is on) so we never hold a full-res bitmap through the
    // result dwell or queue several behind a slow folder write. Written on persist; dropped
    // on discard/re-scan.
    private var pendingRawEncode: kotlinx.coroutines.Deferred<ByteArray?>? = null

    private fun clearPendingRawCapture() {
        pendingRawEncode?.cancel()
        pendingRawEncode = null
    }

    // Cancelable delayed "Next Student" used by the auto-advance setting on a clean result.
    private var autoAdvanceJob: Job? = null

    // Bumped whenever a session ends/changes. An in-flight read captures the epoch at the
    // moment of capture and ignores its own result if the epoch moved on (e.g. End Session was
    // tapped mid-"Reading…"), so a stale result can't resurrect the ended session or re-arm.
    private var sessionEpoch = 0

    private val infoProcessor = InfoZoneProcessor()
    private val answerProcessor = AnswerZoneProcessor()
    private val singleCaptureProcessor = SingleCaptureProcessor()
    private val sharpnessChecker = SharpnessChecker()

    // Initialized from ScanSettings at loadSession; overridable in-session.
    @Volatile
    private var scanMode: ScanMode = ScanMode.SINGLE

    private val _scanModeFlow = MutableStateFlow(ScanMode.SINGLE)
    val scanModeFlow: StateFlow<ScanMode> = _scanModeFlow.asStateFlow()

    // After this many consecutive single-capture failures, suggest the 2-phase flow.
    private var singleFailCount = 0
    private val singleFailThreshold = 5
    private val _suggestTwoPhase = MutableStateFlow(false)
    val suggestTwoPhase: StateFlow<Boolean> = _suggestTwoPhase.asStateFlow()

    private fun registerSingleFailure() {
        singleFailCount++
        if (singleFailCount >= singleFailThreshold) _suggestTwoPhase.value = true
    }

    private fun resetSingleFailures() {
        singleFailCount = 0
        _suggestTwoPhase.value = false
    }

    private fun initialCaptureState(ctx: ScanContext): ScanSessionState =
        if (scanMode == ScanMode.SINGLE) ScanSessionState.SingleCapture(ctx)
        else ScanSessionState.Phase1(ctx)

    // Where a re-capture lands: the single sheet, or Phase 2.
    private fun recaptureState(): ScanSessionState {
        val ctx = activeContext
        return if (scanMode == ScanMode.SINGLE && ctx != null)
            ScanSessionState.SingleCapture(ctx)
        else ScanSessionState.Phase2
    }

    @Volatile
    private var lockedMarkerHints: Array<org.opencv.core.Point?>? = null

    fun setLockedMarkerHints(hints: Array<org.opencv.core.Point?>) {
        lockedMarkerHints = hints
    }

    // Post-capture quality gate: retake when blurrier than preview implied, or when
    // re-detected markers diverge from the locked dots (WYSIWYG). Capped so a hard
    // sheet still resolves — past the cap, the next attempt reads best-effort.
    private var recaptureCount = 0
    private val maxRecaptures = 3
    // Deliberately very low: the info sheet's center crop is mostly blank paper, so a
    // sharp-but-sparse still scores low and a high floor would reject good captures.
    // Reject only EXTREME blur until calibrated from logged "still variance=" values.
    private val stillBlurVarianceMin = 15.0

    // Content blur gate force-recaptures up to blurBudget (re-shoot, don't grade a
    // blurry sheet); only then offer a manual "Capture anyway". Separate from
    // recaptureCount, which also covers WYSIWYG divergence and falls back silently.
    private var blurRecaptureCount = 0
    private val blurBudget = 6
    @Volatile
    private var forceAcceptNextCapture = false

    // True once the blur recapture budget is spent; drives "Capture anyway".
    private val _blurRetryExhausted = MutableStateFlow(false)
    val blurRetryExhausted: StateFlow<Boolean> = _blurRetryExhausted.asStateFlow()

    private fun resetBlurState() {
        blurRecaptureCount = 0
        forceAcceptNextCapture = false
        _blurRetryExhausted.value = false
    }

    fun forceAcceptCapture() {
        forceAcceptNextCapture = true
        _blurRetryExhausted.value = false
        requestManualCapture()
    }

    // replaceResultId set => re-scanning that record: on save the new scan replaces it
    // and the old one is trashed.
    // clusterId/clusterName carry through a Records re-scan so the replacement keeps its cluster tag
    // (and its place in that round's GWA); null for a plain single-exam session.
    fun loadSession(
        subject: String,
        examId: Long,
        replaceResultId: Long? = null,
        mode: ScanMode? = null,
        clusterId: Long? = null,
        clusterName: String? = null,
    ) {
        this.replaceResultId = replaceResultId
        scanMode = mode ?: ScanSettings.getMode(appContext)
        _scanModeFlow.value = scanMode
        resetSingleFailures()
        clusterContexts = null  // single-exam path: no auto-routing
        viewModelScope.launch {
            _state.value = ScanSessionState.Loading
            try {
                val ctx = buildScanContext(subject, examId, clusterId, clusterName)
                currentContext = ctx
                activeContext = ctx
                _activeSubject.value = ctx.subject
                _state.value = initialCaptureState(ctx)
            } catch (e: Exception) {
                _state.value = ScanSessionState.Error(
                    reason = e.message ?: "Failed to load session",
                    phase = 0
                )
            }
        }
    }

    /**
     * Load a whole cluster: prebuild a context per subject and seed the active one with the first
     * (canonical order). The scanned subject bubble then routes each paper to its exam — see the
     * SubjectMismatch handling in [processPhase2]/[processSingle]. A paper whose subject is not in
     * the cluster still raises the manual mismatch dialog.
     */
    fun loadClusterSession(clusterId: Long, mode: ScanMode? = null) {
        replaceResultId = null
        scanMode = mode ?: ScanSettings.getMode(appContext)
        _scanModeFlow.value = scanMode
        resetSingleFailures()
        viewModelScope.launch {
            _state.value = ScanSessionState.Loading
            try {
                val cluster = clusterRepository.getClusterById(clusterId)
                    ?: throw IllegalStateException("Cluster not found")
                // Canonical subject order so the seeded subject is deterministic (Math first).
                val ordered = ReportPdfStyle.subjectOrder.mapNotNull { subject ->
                    cluster.examIdsBySubject[subject]?.let { subject to it }
                }
                // One student fetch shared across subjects; contexts built concurrently.
                val students = try { studentRepository.getAllStudents() } catch (e: Exception) { emptyList() }
                val contextResults = coroutineScope {
                    ordered.map { (subject, examId) ->
                        async {
                            runCatching {
                                buildScanContext(subject, examId, cluster.id, cluster.name, students)
                            }
                        }
                    }.awaitAll()
                }
                val contexts = LinkedHashMap<String, ScanContext>()
                for ((index, result) in contextResults.withIndex()) {
                    result.onSuccess { contexts[ordered[index].first] = it }
                }
                if (contexts.isEmpty()) throw IllegalStateException("Cluster has no loadable exams")
                clusterContexts = contexts
                val first = contexts.values.first()
                currentContext = first
                activeContext = first
                _activeSubject.value = first.subject
                _state.value = initialCaptureState(first)
            } catch (e: Exception) {
                clusterContexts = null
                _state.value = ScanSessionState.Error(
                    reason = e.message ?: "Failed to load cluster",
                    phase = 0
                )
            }
        }
    }

    // Shared by loadSession, loadClusterSession and the "Switch Exam" flow (swaps context, no re-scan).
    private suspend fun buildScanContext(
        subject: String,
        examId: Long,
        clusterId: Long? = null,
        clusterName: String? = null,
        preloadedStudents: List<Student>? = null,
    ): ScanContext = coroutineScope {
        // Independent fetches run concurrently; load is bound by the slowest, not the sum.
        // Cluster path resolves students once and passes them via preloadedStudents.
        val studentsDeferred: Deferred<List<Student>>? = if (preloadedStudents != null) null
            else async { try { studentRepository.getAllStudents() } catch (e: Exception) { emptyList() } }
        val examsDeferred = async { examRepository.getExamsBySubjectOnce(subject) }
        val questionsDeferred = async { questionRepository.getAllQuestionsForSubjectOnce(subject) }

        val students = preloadedStudents ?: studentsDeferred!!.await()
        val studentMap = students.associateBy { it.studentId }

        val exam = examsDeferred.await()
            .firstOrNull { it.id == examId }
            ?: throw IllegalStateException("Exam $examId not found for $subject")

        // Legacy exams may have an empty Set B; fall back to Set A's order/key in that case.
        val setAQuestionIds = exam.setAQuestionIds
        val setBQuestionIds = exam.setBQuestionIds.takeIf { it.isNotEmpty() } ?: setAQuestionIds

        // Questions live in Firestore, not Room (Room returns nothing here -> old 0/0).
        // getAllQuestionsForSubjectOnce spans all banks, so two questions can share an id
        // with different correctAnswers. Resolve each id to the FIRST in (bank, number)
        // order, matching the printed PDF / key grid; associateBy kept the LAST and
        // mis-graded colliding items.
        val subjectQuestions = questionsDeferred.await()
        val keyById = HashMap<Long, String?>()
        for (q in subjectQuestions) if (!keyById.containsKey(q.id)) keyById[q.id] = q.correctAnswer

        val setAKeyMap: Map<Long, String?> =
            setAQuestionIds.associateWith { keyById[it] }
        val setBKeyMap: Map<Long, String?> =
            setBQuestionIds.associateWith { keyById[it] }

        android.util.Log.d(
            "Scoring",
            "loadSession exam=$examId setA=${setAQuestionIds.size} setB=${setBQuestionIds.size} " +
                "questionsLoaded=${subjectQuestions.size} uniqueIds=${keyById.size} " +
                "setAWithKey=${setAKeyMap.values.count { it != null }} " +
                "setBWithKey=${setBKeyMap.values.count { it != null }}"
        )

        ScanContext(
            examId = examId,
            examName = exam.examName,
            subject = subject,
            setAQuestionIds = setAQuestionIds,
            setBQuestionIds = setBQuestionIds,
            setAKeyMap = setAKeyMap,
            setBKeyMap = setBKeyMap,
            studentMap = studentMap,
            clusterId = clusterId,
            clusterName = clusterName,
        )
    }

    /** Held frame's subject is in the cluster: swap to its prebuilt context and re-score in place. */
    private fun autoRouteToClusterSubject(ctx: ScanContext) {
        currentContext = ctx
        activeContext = ctx
        _activeSubject.value = ctx.subject
        toast("Detected ${ctx.subject} — scoring against “${ctx.examName}”")
        rescoreHeldBitmap(ctx)
    }

    fun processPhase1(bitmap: Bitmap) {
        android.util.Log.d("AutoCapture", "VIEWMODEL received — processPhase1 bitmap:${bitmap.width}x${bitmap.height} state:${_state.value::class.simpleName}")
        val currentState = _state.value as? ScanSessionState.Phase1 ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processPhase1 called in wrong state: ${_state.value::class.simpleName}")
            viewModelScope.launch { _rearmCapture.emit(Unit) }
            return
        }
        val ctx = currentContext ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processPhase1: currentContext is null")
            return
        }
        val hints = lockedMarkerHints.also { lockedMarkerHints = null }
        val epoch = sessionEpoch
        viewModelScope.launch {
            if (rejectAsBlurry(bitmap)) { bitmap.recycle(); return@launch }
            android.util.Log.d("AutoCapture", "PROCESSING START — InfoZoneProcessor.process()")
            _processing.value = "Reading student info…"
            val result = try {
                withContext(Dispatchers.Default) {
                    infoProcessor.process(bitmap, ctx.studentMap, hints, debugContext = debugCtx)
                }
            } catch (e: Exception) {
                android.util.Log.e("AutoCapture", "PIPELINE ERROR", e)
                if (epoch != sessionEpoch) return@launch
                _processing.value = null
                toast("Scan failed — reposition and try again")
                _rearmCapture.emit(Unit)
                return@launch
            }
            if (epoch != sessionEpoch) return@launch
            _processing.value = null
            android.util.Log.d("AutoCapture", "PROCESSING DONE — result:$result")
            when (result) {
                is InfoZoneProcessor.Result.Success -> {
                    recaptureCount = 0
                    currentScannedInfo = result.info
                    _state.value = ScanSessionState.Phase1Review(result.info)
                }
                is InfoZoneProcessor.Result.Recapture -> {
                    recaptureCount++
                    android.util.Log.w("AutoCapture", "WYSIWYG retake ${recaptureCount}/$maxRecaptures — ${result.reason}")
                    toast("Adjusting focus — hold steady")
                    _rearmCapture.emit(Unit)
                }
                is InfoZoneProcessor.Result.Error -> {
                    toast(
                        if (result.reason.startsWith("BLURRY"))
                            "Too blurry — hold steady and wait for focus"
                        else
                            "Couldn't read info panel — reposition and try again"
                    )
                    _rearmCapture.emit(Unit)
                }
            }
        }
    }

    // True (and emits a retake) when the still is too blurry AND budget remains;
    // false when acceptable or budget spent (read best-effort).
    private suspend fun rejectAsBlurry(bitmap: Bitmap): Boolean {
        val v = withContext(Dispatchers.Default) { sharpnessChecker.variance(bitmap) }
        android.util.Log.d("AutoCapture", "still variance=%.1f (floor %.1f, retakes %d/%d)"
            .format(v, stillBlurVarianceMin, recaptureCount, maxRecaptures))
        if (recaptureCount >= maxRecaptures) return false
        if (v >= stillBlurVarianceMin) return false
        recaptureCount++
        android.util.Log.w("AutoCapture", "Still too blurry — retake %d/%d".format(recaptureCount, maxRecaptures))
        toast("Too blurry — hold steady and wait for focus")
        _rearmCapture.emit(Unit)
        return true
    }

    fun retryPhase1() {
        recaptureCount = 0
        val ctx = activeContext
        if (ctx != null) {
            // Same phase, so the persistent camera won't auto-reset on a phase change.
            _rearmCapture.tryEmit(Unit)
            _state.value = ScanSessionState.Phase1(ctx)
        } else {
            _state.value = ScanSessionState.Setup
        }
    }

    fun confirmPhase1() {
        if (currentScannedInfo != null) {
            recaptureCount = 0
            resetBlurState()
            _state.value = ScanSessionState.Phase2
        }
    }

    fun processPhase2(bitmap: Bitmap) {
        android.util.Log.d("AutoCapture", "VIEWMODEL received — processPhase2 bitmap:${bitmap.width}x${bitmap.height} state:${_state.value::class.simpleName}")
        _state.value as? ScanSessionState.Phase2 ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processPhase2 called in wrong state: ${_state.value::class.simpleName}")
            viewModelScope.launch { _rearmCapture.emit(Unit) }
            return
        }
        val ctx = currentContext ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processPhase2: currentContext is null")
            return
        }
        val info = currentScannedInfo ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processPhase2: currentScannedInfo is null")
            return
        }
        val hints = lockedMarkerHints.also { lockedMarkerHints = null }
        val bypassBlur = forceAcceptNextCapture.also { forceAcceptNextCapture = false }
        val epoch = sessionEpoch
        viewModelScope.launch {
            if (!bypassBlur && rejectAsBlurry(bitmap)) { bitmap.recycle(); return@launch }
            android.util.Log.d("AutoCapture", "PROCESSING START — AnswerZoneProcessor.process()")
            _processing.value = "Reading answers…"
            val result = try {
                withContext(Dispatchers.Default) {
                    answerProcessor.process(bitmap, ctx, info, scoringStrategy, hints,
                        debugContext = debugCtx, bypassBlurGate = bypassBlur)
                }
            } catch (e: Exception) {
                android.util.Log.e("AutoCapture", "PIPELINE ERROR", e)
                if (epoch != sessionEpoch) return@launch
                _processing.value = null
                toast("Scan failed — reposition and try again")
                _rearmCapture.emit(Unit)
                return@launch
            }
            if (epoch != sessionEpoch) return@launch
            _processing.value = null
            android.util.Log.d("AutoCapture", "PROCESSING DONE — result:$result")
            when (result) {
                is AnswerZoneProcessor.Result.Success -> {
                    recaptureCount = 0
                    resetBlurState()
                    holdRawCaptureIfEnabled(bitmap)
                    presentResult(result.scanResult, result.answerKey)
                }
                is AnswerZoneProcessor.Result.SubjectMismatch -> {
                    recaptureCount = 0
                    resetBlurState()
                    // Hold this frame + its hints so the mismatch can be re-scored
                    // without re-capturing. capturedBitmap === bitmap.
                    clearPendingMismatchBitmap()
                    pendingMismatchBitmap = result.capturedBitmap
                    pendingMismatchHints = hints
                    val routed = clusterContexts?.get(result.detectedSubject)
                    if (routed != null) {
                        autoRouteToClusterSubject(routed)
                    } else {
                        // Subject not in this cluster (or single-exam session): ask the instructor.
                        _state.value = ScanSessionState.SubjectMismatch(
                            detectedSubject = result.detectedSubject,
                            context = ctx
                        )
                    }
                }
                is AnswerZoneProcessor.Result.Recapture -> {
                    if (result.reason.startsWith("BLURRY")) {
                        blurRecaptureCount++
                        android.util.Log.w("AutoCapture",
                            "BLURRY retake ${blurRecaptureCount}/$blurBudget — ${result.reason}")
                        if (blurRecaptureCount > blurBudget) {
                            _blurRetryExhausted.value = true
                            toast("Still blurry — tap “Capture anyway” to use this shot")
                        } else {
                            toast("Too blurry — hold steady and wait for focus")
                        }
                        _rearmCapture.emit(Unit)
                    } else {
                        recaptureCount++
                        android.util.Log.w("AutoCapture", "WYSIWYG retake ${recaptureCount}/$maxRecaptures — ${result.reason}")
                        toast("Adjusting focus — hold steady")
                        _rearmCapture.emit(Unit)
                    }
                }
                is AnswerZoneProcessor.Result.Error -> {
                    toast("Couldn't read answer sheet — flatten the paper and try again")
                    _rearmCapture.emit(Unit)
                }
            }
        }
    }

    // Read both zones from one full-sheet frame. Failures count toward suggestTwoPhase.
    fun processSingle(bitmap: Bitmap) {
        android.util.Log.d("AutoCapture", "VIEWMODEL received — processSingle bitmap:${bitmap.width}x${bitmap.height} state:${_state.value::class.simpleName}")
        _state.value as? ScanSessionState.SingleCapture ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processSingle called in wrong state: ${_state.value::class.simpleName}")
            viewModelScope.launch { _rearmCapture.emit(Unit) }
            return
        }
        val ctx = currentContext ?: run {
            android.util.Log.e("AutoCapture", "PIPELINE ERROR — processSingle: currentContext is null")
            return
        }
        val hints = lockedMarkerHints.also { lockedMarkerHints = null }
        val bypassBlur = forceAcceptNextCapture.also { forceAcceptNextCapture = false }
        val epoch = sessionEpoch
        viewModelScope.launch {
            if (!bypassBlur && rejectAsBlurry(bitmap)) { bitmap.recycle(); return@launch }
            _processing.value = "Reading sheet…"
            val result = try {
                withContext(Dispatchers.Default) {
                    singleCaptureProcessor.process(
                        bitmap, ctx, scoringStrategy, hints,
                        bypassBlurGate = bypassBlur, debugContext = debugCtx
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("AutoCapture", "PIPELINE ERROR", e)
                if (epoch != sessionEpoch) return@launch
                _processing.value = null
                toast("Scan failed — reposition and try again")
                registerSingleFailure()
                _rearmCapture.emit(Unit)
                return@launch
            }
            if (epoch != sessionEpoch) return@launch
            _processing.value = null
            android.util.Log.d("AutoCapture", "PROCESSING DONE — result:$result")
            when (result) {
                is SingleCaptureProcessor.Result.Success -> {
                    recaptureCount = 0
                    resetBlurState()
                    resetSingleFailures()
                    currentScannedInfo = result.info
                    holdRawCaptureIfEnabled(bitmap)
                    presentResult(result.scanResult, result.answerKey)
                }
                is SingleCaptureProcessor.Result.SubjectMismatch -> {
                    recaptureCount = 0
                    resetBlurState()
                    resetSingleFailures()
                    // Keep student info + frame + 6 hints so a re-score against another
                    // exam doesn't need to re-read the sheet.
                    currentScannedInfo = result.info
                    clearPendingMismatchBitmap()
                    pendingMismatchBitmap = result.capturedBitmap
                    pendingMismatchHints = hints
                    val routed = clusterContexts?.get(result.detectedSubject)
                    if (routed != null) {
                        autoRouteToClusterSubject(routed)
                    } else {
                        _state.value = ScanSessionState.SubjectMismatch(
                            detectedSubject = result.detectedSubject,
                            context = ctx
                        )
                    }
                }
                is SingleCaptureProcessor.Result.Recapture -> {
                    if (result.reason.startsWith("BLURRY")) {
                        blurRecaptureCount++
                        android.util.Log.w("AutoCapture", "BLURRY retake ${blurRecaptureCount}/$blurBudget — ${result.reason}")
                        if (blurRecaptureCount > blurBudget) {
                            _blurRetryExhausted.value = true
                            toast("Still blurry — tap “Capture anyway” to use this shot")
                        } else {
                            toast("Too blurry — hold steady and wait for focus")
                        }
                    } else {
                        registerSingleFailure()
                        toast("Adjusting — hold steady and keep all 6 markers in view")
                    }
                    _rearmCapture.emit(Unit)
                }
                is SingleCaptureProcessor.Result.Error -> {
                    registerSingleFailure()
                    toast("Couldn't read the sheet — flatten the paper and try again")
                    _rearmCapture.emit(Unit)
                }
            }
        }
    }

    // Persists the choice, resets counters, re-enters capture; keeps the same context.
    fun switchScanMode(mode: ScanMode) {
        ScanSettings.setMode(appContext, mode)
        scanMode = mode
        _scanModeFlow.value = mode
        resetSingleFailures()
        recaptureCount = 0
        resetBlurState()
        lockedMarkerHints = null
        autoAdvanceJob?.cancel()
        pendingScanResult = null
        clearPendingRawCapture()
        _captureProgress.value = 0f
        val ctx = activeContext
        if (ctx != null) {
            currentScannedInfo = null
            _state.value = initialCaptureState(ctx)
        }
    }

    fun dismissSuggestTwoPhase() {
        _suggestTwoPhase.value = false
        singleFailCount = 0
    }

    // Re-scan answers, discarding the pending (wrong-marker) result; keeps
    // currentScannedInfo/Context so Phase 1 info carries over.
    fun retryPhase2() {
        autoAdvanceJob?.cancel()
        recaptureCount = 0
        resetBlurState()
        pendingScanResult = null
        clearPendingRawCapture()
        pendingDuplicateIds = emptyList()
        _captureProgress.value = 0f
        _rearmCapture.tryEmit(Unit)  // re-enters the same phase; persistent camera needs an explicit re-arm
        _state.value = recaptureState()
    }

    // When saving is on (and a folder is set), encode the success frame to JPEG immediately on
    // a background thread and recycle the bitmap, so only one frame is ever in memory and the
    // persist path just writes a few KB. No-op (and bitmap left to GC, as before) otherwise.
    private fun holdRawCaptureIfEnabled(bitmap: Bitmap) {
        if (ScanSettings.isSaveRawImages(appContext) && ScanSettings.getRawImageTreeUri(appContext) != null) {
            pendingRawEncode?.cancel()
            pendingRawEncode = viewModelScope.async(Dispatchers.Default) {
                try {
                    java.io.ByteArrayOutputStream().use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
                        os.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    // Hold the result and show the card; looks up existing duplicates to warn.
    private suspend fun presentResult(scanResult: ScanResult, answerKey: String) {
        pendingScanResult = scanResult
        pendingDuplicateIds = findActiveDuplicates(scanResult.studentId, scanResult.examId)
        _state.value = ScanSessionState.ResultDisplay(scanResult, answerKey, pendingDuplicateIds.size)

        // Auto-advance only on a clean result; duplicates need a manual choice.
        autoAdvanceJob?.cancel()
        if (ScanSettings.isAutoAdvance(appContext) && pendingDuplicateIds.isEmpty()) {
            val delayMs = ScanSettings.getAutoAdvanceSpeed(appContext).delayMs
            autoAdvanceJob = viewModelScope.launch {
                delay(delayMs)
                if (_state.value is ScanSessionState.ResultDisplay) nextStudent()
            }
        }
    }

    // Excludes the record being replaced. Empty unless studentId is a clean 6-digit
    // read, so a misread never raises a spurious duplicate warning.
    private suspend fun findActiveDuplicates(studentId: String, examId: Long): List<Long> {
        if (studentId.length != 6 || !studentId.all { it.isDigit() }) return emptyList()
        return scanResultRepository.getActiveByStudentAndExam(studentId, examId)
            .map { it.id }
            .filter { it != replaceResultId }
    }

    // trashDuplicates also retires the detected duplicates ("Replace existing"); a
    // normal save keeps them. replaceResultId is always honored.
    private fun persistPendingResult(trashDuplicates: Boolean = false) {
        val pending = pendingScanResult ?: return
        pendingScanResult = null
        val replacing = replaceResultId
        replaceResultId = null
        val dupes = if (trashDuplicates) pendingDuplicateIds else emptyList()
        pendingDuplicateIds = emptyList()
        // Write the already-encoded raw frame of this successfully-recorded scan, if any.
        val encode = pendingRawEncode
        pendingRawEncode = null
        val treeUri = ScanSettings.getRawImageTreeUri(appContext)
        if (encode != null && treeUri != null) {
            val sid = pending.studentId; val subj = pending.subject; val set = pending.testSet
            viewModelScope.launch {
                val bytes = try { encode.await() } catch (e: Exception) { null }
                if (bytes != null) ScanImageStore.save(appContext, bytes, treeUri, sid, subj, set)
            }
        } else {
            encode?.cancel()
        }
        viewModelScope.launch {
            try {
                scanResultRepository.insert(pending)
                // Retire the replaced paper and/or chosen duplicates so stats don't double-count.
                val toTrash = (listOfNotNull(replacing) + dupes).distinct()
                if (toTrash.isNotEmpty()) scanResultRepository.moveToTrash(toTrash)
            } catch (e: Exception) {
                // UI has already advanced; the pending state is gone. Recovery is a re-scan, so say so.
                Log.e("ScannerViewModel", "Failed to save scan for ${pending.studentId} / ${pending.subject}", e)
                toast("Failed to save scan for ${pending.studentId}. Please re-scan this student.")
            }
        }
    }

    fun nextStudent() = advanceToNextStudent(persist = true, trashDuplicates = false)

    // "Replace existing": accept and retire the duplicate(s), then advance.
    fun replaceExistingAndNext() = advanceToNextStudent(persist = true, trashDuplicates = true)

    // Discard without saving, then advance — skip a duplicate or bad read.
    fun discardAndNext() = advanceToNextStudent(persist = false, trashDuplicates = false)

    private fun advanceToNextStudent(persist: Boolean, trashDuplicates: Boolean) {
        autoAdvanceJob?.cancel()
        if (persist) {
            persistPendingResult(trashDuplicates)
        } else {
            // Drop the held result + duplicates; don't retire the re-scan target.
            pendingScanResult = null
            clearPendingRawCapture()
            pendingDuplicateIds = emptyList()
            replaceResultId = null
        }
        recaptureCount = 0
        currentScannedInfo = null
        _captureProgress.value = 0f
        val ctx = activeContext
        if (ctx != null) {
            _state.value = initialCaptureState(ctx)
        } else {
            _state.value = ScanSessionState.Setup
        }
    }

    fun endSession() {
        autoAdvanceJob?.cancel()
        // Invalidate any in-flight read and clear the "Reading…" overlay so a result that
        // lands after this can't flip the UI back into the ended session.
        sessionEpoch++
        _processing.value = null
        persistPendingResult()
        replaceResultId = null
        pendingDuplicateIds = emptyList()
        clearPendingMismatchBitmap()
        currentContext = null
        activeContext = null
        clusterContexts = null
        currentScannedInfo = null
        _activeSubject.value = null
        _captureProgress.value = 0f
        _state.value = ScanSessionState.Setup
    }

    fun dismissError() {
        val ctx = activeContext
        _state.value = if (ctx != null)
            initialCaptureState(ctx)
        else
            ScanSessionState.Setup
    }

    // Grade the held sheet against the current exam, ignoring the detected subject.
    fun resolveSubjectMismatch_useCurrentSession() {
        val ctx = currentContext ?: run { resolveSubjectMismatch_rescan(); return }
        rescoreHeldBitmap(ctx)
    }

    fun resolveSubjectMismatch_rescan() {
        clearPendingMismatchBitmap()
        recaptureCount = 0
        resetBlurState()
        _captureProgress.value = 0f
        _rearmCapture.tryEmit(Unit)
        _state.value = recaptureState()
    }

    // Switch to the picked exam, then grade the held sheet against it — no re-capture.
    fun resolveSubjectMismatch_switchExam(newSubject: String, newExamId: Long) {
        if (pendingMismatchBitmap == null) {
            // Nothing held to re-score: load the new exam and re-scan from Phase 1.
            loadSession(newSubject, newExamId)
            return
        }
        _state.value = ScanSessionState.Loading
        viewModelScope.launch {
            val ctx = try {
                buildScanContext(newSubject, newExamId)
            } catch (e: Exception) {
                _state.value = ScanSessionState.Error(
                    reason = e.message ?: "Failed to switch exam",
                    phase = 2
                )
                return@launch
            }
            currentContext = ctx
            activeContext = ctx
            _activeSubject.value = ctx.subject
            toast("Session switched to ${ctx.subject} — next scans use “${ctx.examName}”")
            rescoreHeldBitmap(ctx)
        }
    }

    // Re-run scoring on the held frame against ctx, skipping the subject-mismatch gate.
    private fun rescoreHeldBitmap(ctx: ScanContext) {
        val bitmap = pendingMismatchBitmap ?: run { resolveSubjectMismatch_rescan(); return }
        val info = currentScannedInfo ?: run { resolveSubjectMismatch_rescan(); return }
        val hints = pendingMismatchHints
        val singleMode = scanMode == ScanMode.SINGLE
        _state.value = ScanSessionState.Loading
        viewModelScope.launch {
            _processing.value = "Scoring answers…"
            // Single-capture held frame is a FULL SHEET, so re-score with the
            // single-capture processor; the answer processor expects an answer-zone crop.
            val scanResult: ScanResult?
            val answerKey: String?
            try {
                if (singleMode) {
                    val r = withContext(Dispatchers.Default) {
                        singleCaptureProcessor.process(
                            bitmap, ctx, scoringStrategy, hints,
                            bypassBlurGate = true, skipSubjectCheck = true, debugContext = debugCtx
                        )
                    }
                    if (r is SingleCaptureProcessor.Result.Success) {
                        scanResult = r.scanResult; answerKey = r.answerKey
                    } else {
                        android.util.Log.w("AutoCapture", "rescore (single) non-success: $r")
                        scanResult = null; answerKey = null
                    }
                } else {
                    val r = withContext(Dispatchers.Default) {
                        answerProcessor.process(
                            bitmap, ctx, info, scoringStrategy, hints,
                            debugContext = debugCtx, bypassBlurGate = true, skipSubjectCheck = true
                        )
                    }
                    if (r is AnswerZoneProcessor.Result.Success) {
                        scanResult = r.scanResult; answerKey = r.answerKey
                    } else {
                        android.util.Log.w("AutoCapture", "rescore non-success: $r")
                        scanResult = null; answerKey = null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AutoCapture", "PIPELINE ERROR (rescore)", e)
                _processing.value = null
                failRescore("Couldn't score this sheet — re-scan it")
                return@launch
            }
            _processing.value = null
            if (scanResult != null && answerKey != null) {
                clearPendingMismatchBitmap()
                presentResult(scanResult, answerKey)
            } else {
                failRescore("Couldn't score this sheet — re-scan it")
            }
        }
    }

    // Held frame can't be graded: drop it and re-capture against the current session.
    private fun failRescore(message: String) {
        toast(message)
        clearPendingMismatchBitmap()
        recaptureCount = 0
        resetBlurState()
        _captureProgress.value = 0f
        _rearmCapture.tryEmit(Unit)
        _state.value = recaptureState()
    }
}
