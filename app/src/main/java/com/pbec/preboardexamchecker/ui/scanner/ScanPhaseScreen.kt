package com.pbec.preboardexamchecker.ui.scanner

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.util.Log
import com.pbec.preboardexamchecker.BuildConfig
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import com.pbec.preboardexamchecker.ui.scanner.processor.MarkerDetector
import com.pbec.preboardexamchecker.ui.scanner.processor.SharpnessChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val STABILITY_PIXEL_THRESHOLD = 8.0   // steady phone jitters 2–5px; 8px is headroom
private const val STABILITY_WINDOW_SIZE = 12
private const val STABILITY_WINDOW_MIN_HITS = 10

enum class MarkerLockState { NOT_FOUND, FOUND, LOCKED }

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@Composable
fun ScanPhaseScreen(
    phase: Int,
    onCapture: (Int, android.graphics.Bitmap) -> Unit,
    onEndSession: () -> Unit,
    viewModel: ScannerViewModel? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    // Analyzer runs off the Compose thread; it reads the live phase from this ref.
    val phaseRef = remember { java.util.concurrent.atomic.AtomicInteger(phase) }
    phaseRef.set(phase)

    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    val permission = rememberCameraPermissionState()

    // [TL, TR, BL, BR]
    var cornerStates by remember { mutableStateOf(Array(4) { MarkerLockState.NOT_FOUND }) }
    val liveStateFlow = remember { MutableStateFlow(LiveMarkerState()) }
    val liveState by liveStateFlow.collectAsState()
    val hasFired = remember { mutableStateOf(false) }
    // Covers the shutter-latency window — the one moment the user must hold still —
    // until the bitmap reaches the ViewModel and its "Reading…" overlay takes over.
    var capturing by remember { mutableStateOf(false) }
    // Main thread sets; analysis thread clears + resets EMA state.
    val resetSignal = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(phase) {
        cornerStates = Array(4) { MarkerLockState.NOT_FOUND }
        liveStateFlow.value = LiveMarkerState()
        hasFired.value = false
        capturing = false
        // Clear analyzer EMA on phase change: phase 1 marker positions must not bleed
        // into phase 2's different geometry.
        resetSignal.set(true)
    }

    LaunchedEffect(viewModel) {
        viewModel?.manualCaptureEvent?.collect {
            if (!hasFired.value) {
                val capture = imageCaptureUseCase ?: return@collect
                val capturePhase = phaseRef.get()
                hasFired.value = true
                capturing = true
                triggerCapture(capture, context, capturePhase, scope,
                    onCapture = { bmp -> onCapture(capturePhase, bmp) },
                    onCaptureError = {
                        // Capture failed: ViewModel got no bitmap and can't re-arm, so
                        // re-arm here. On success it advances state / emits rearmCapture.
                        hasFired.value = false
                        capturing = false
                        resetSignal.set(true)
                        cornerStates = Array(4) { MarkerLockState.NOT_FOUND }
                        liveStateFlow.value = LiveMarkerState()
                    })
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel?.rearmCapture?.collect {
            hasFired.value = false
            capturing = false
            resetSignal.set(true)
            cornerStates = Array(4) { MarkerLockState.NOT_FOUND }
            liveStateFlow.value = LiveMarkerState()
        }
    }

    if (!permission.hasPermission) {
        CameraPermissionRequest(onRequest = { permission.request() })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val afIsReady = AtomicBoolean(false)
                    val imageCaptureBuilder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    Camera2Interop.Extender(imageCaptureBuilder)
                        .setSessionCaptureCallback(object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: android.hardware.camera2.CameraCaptureSession,
                                request: android.hardware.camera2.CaptureRequest,
                                result: android.hardware.camera2.TotalCaptureResult
                            ) {
                                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                                val scanning = afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                                               afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
                                afIsReady.set(!scanning)
                            }
                        })
                    val imageCapture = imageCaptureBuilder.build()
                    imageCaptureUseCase = imageCapture

                    val markerDetector = MarkerDetector()
                    val sharpnessChecker = SharpnessChecker()
                    // EMA state: camera-session lifetime, never reset by recomposition.
                    val smoothedMarkers = arrayOfNulls<Point>(4)
                    val prevSmoothedPerCorner = arrayOfNulls<Point>(4)
                    val stabilityWindows = Array(4) { ArrayDeque<Boolean>() }
                    var frameCount = 0
                    var camera: Camera? = null

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (hasFired.value) { imageProxy.close(); return@setAnalyzer }

                        if (resetSignal.getAndSet(false)) {
                            for (i in 0..3) {
                                smoothedMarkers[i] = null
                                prevSmoothedPerCorner[i] = null
                                stabilityWindows[i].clear()
                            }
                        }

                        try {
                            val bitmap = imageProxy.toBitmap()
                            val src = Mat()
                            Utils.bitmapToMat(bitmap, src)
                            val gray = Mat()
                            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                            src.release()

                            val laplacianVariance = sharpnessChecker.variance(bitmap)
                            val sharp = laplacianVariance >= 50.0

                            frameCount++
                            if (DebugFlags.SAVE_SCAN_DEBUG && frameCount % 30 == 0) {
                                DebugImageSaver.saveBitmap(ctx, bitmap, "analysis_raw")
                                DebugImageSaver.saveMat(ctx, gray, "analysis_gray")
                            }
                            bitmap.recycle()

                            // Rotate the raw sensor buffer to display orientation before quadrant splits.
                            val grayForAnalysis = when (imageProxy.imageInfo.rotationDegrees) {
                                90  -> Mat().also { Core.rotate(gray, it, Core.ROTATE_90_CLOCKWISE); gray.release() }
                                180 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_180); gray.release() }
                                270 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_90_COUNTERCLOCKWISE); gray.release() }
                                else -> gray
                            }
                            // Expected quad aspect per phase, so the guide rejects
                            // wrong-aspect distractor quads near the cluttered TM corner.
                            val analyzerPhase = phaseRef.get()
                            val guideAspect = if (analyzerPhase == 1) 920.0 / 1596.0 else 1596.0 / 2524.0
                            val analysisW = grayForAnalysis.cols().toDouble()
                            val analysisH = grayForAnalysis.rows().toDouble()
                            val rawMarkers = markerDetector.detectForGuide(grayForAnalysis, guideAspect, useCalibRois = analyzerPhase == 2)
                            grayForAnalysis.release()

                            val newStates = Array(4) { MarkerLockState.NOT_FOUND }
                            // Stable-frame count per corner, for the stability arc.
                            val stableHitsArr = IntArray(4)
                            var allGreen = true

                            for (i in 0..3) {
                                val raw = rawMarkers[i]
                                val win = stabilityWindows[i]

                                if (raw == null) {
                                    // Record a miss but preserve EMA across brief gaps.
                                    if (win.size >= STABILITY_WINDOW_SIZE) win.removeFirst()
                                    win.addLast(false)
                                    newStates[i] = MarkerLockState.NOT_FOUND
                                    allGreen = false
                                    continue
                                }

                                val prev = smoothedMarkers[i]
                                smoothedMarkers[i] = if (prev == null) Point(raw.x, raw.y)
                                else Point(0.7 * prev.x + 0.3 * raw.x, 0.7 * prev.y + 0.3 * raw.y)

                                val smoothed = smoothedMarkers[i]!!
                                val prevSmoothed = prevSmoothedPerCorner[i]
                                prevSmoothedPerCorner[i] = Point(smoothed.x, smoothed.y)

                                newStates[i] = MarkerLockState.FOUND  // yellow until proven stable

                                if (prevSmoothed == null) {
                                    // No delta on the first frame — skip the stability check.
                                    allGreen = false
                                    continue
                                }

                                val delta = kotlin.math.hypot(smoothed.x - prevSmoothed.x, smoothed.y - prevSmoothed.y)
                                val frameStable = delta < STABILITY_PIXEL_THRESHOLD && sharp

                                if (win.size >= STABILITY_WINDOW_SIZE) win.removeFirst()
                                win.addLast(frameStable)
                                val stableHits = win.count { it }
                                stableHitsArr[i] = stableHits

                                if (stableHits >= STABILITY_WINDOW_MIN_HITS) {
                                    newStates[i] = MarkerLockState.LOCKED
                                } else {
                                    allGreen = false
                                }
                            }

                            // Map each detected corner to PreviewView surface coords.
                            // rawMarkers are in display-rotated space but CoordinateTransformer
                            // expects un-rotated sensor space, so invert the rotation here and
                            // let it re-apply rotation + scaling. The round-trip cancels to a
                            // pure scale, but keeps the transformer testable on raw output.
                            val pvW = previewView.width
                            val pvH = previewView.height
                            val detected = HashMap<MarkerRole, android.graphics.PointF>(4)
                            if (pvW > 0 && pvH > 0) {
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val sensorW = imageProxy.width
                                val sensorH = imageProxy.height
                                val transformer = CoordinateTransformer(
                                    analysisWidth = sensorW,
                                    analysisHeight = sensorH,
                                    previewWidth = pvW,
                                    previewHeight = pvH,
                                    rotationDegrees = rot
                                )
                                val roles = phaseRoles(phase)
                                for (i in 0..3) {
                                    val m = rawMarkers[i] ?: continue
                                    val sx: Double
                                    val sy: Double
                                    when (rot) {
                                        90 -> { sx = m.y; sy = sensorH - 1 - m.x }
                                        180 -> { sx = sensorW - 1 - m.x; sy = sensorH - 1 - m.y }
                                        270 -> { sx = sensorW - 1 - m.y; sy = m.x }
                                        else -> { sx = m.x; sy = m.y }
                                    }
                                    detected[roles[i]] = transformer.transform(
                                        android.graphics.PointF(sx.toFloat(), sy.toFloat())
                                    )
                                }
                            }
                            val found4 = (0..3).all { rawMarkers[it] != null }
                            val stabilityProgress = if (found4)
                                ((0..3).minOf { stableHitsArr[it] }.toFloat() / STABILITY_WINDOW_MIN_HITS)
                                    .coerceIn(0f, 1f)
                            else 0f
                            liveStateFlow.value = LiveMarkerState(
                                detectedMarkers = detected,
                                overlayPhase = if (found4) OverlayPhase.HOLD_STILL else OverlayPhase.SEARCHING,
                                stabilityProgress = stabilityProgress
                            )

                            if (allGreen && !hasFired.value && afIsReady.get()) {
                                hasFired.value = true
                                // Locked positions (analysis-frame fractions) narrow the still
                                // detector's search AND back the WYSIWYG gate that re-captures
                                // if the still locks onto different squares.
                                val fracHints = Array(4) { i ->
                                    smoothedMarkers[i]?.let { pt -> Point(pt.x / analysisW, pt.y / analysisH) }
                                }
                                val capturePhase = analyzerPhase
                                scope.launch(Dispatchers.Main) {
                                    capturing = true
                                    cornerStates = newStates
                                    viewModel?.setLockedMarkerHints(fracHints)
                                    triggerCapture(imageCapture, ctx, capturePhase, scope,
                                        onCapture = { bmp -> onCapture(capturePhase, bmp) },
                                        onCaptureError = {
                                            // Re-arm ONLY on failed capture. Re-arming on success
                                            // fired a second auto-capture mid-processing whose late
                                            // failure clobbered the result.
                                            hasFired.value = false
                                            capturing = false
                                            resetSignal.set(true)
                                            cornerStates = Array(4) { MarkerLockState.NOT_FOUND }
                                            liveStateFlow.value = LiveMarkerState()
                                        })
                                }
                                return@setAnalyzer
                            }

                            scope.launch(Dispatchers.Main) {
                                cornerStates = newStates
                            }
                        } catch (e: Exception) {
                            Log.e("ScanPhaseScreen", "Analysis error", e)
                        } finally {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, imageCapture, imageAnalysis
                        )
                        // Fixed-focus lenses never report an AF lock, so the AF gate would
                        // block forever. Detect via min focus distance (null/0.0f = can't
                        // focus); mark AF ready and let SharpnessChecker be the sole gate.
                        val minFocusDistance = camera?.cameraInfo?.let {
                            Camera2CameraInfo.from(it)
                                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        }
                        val isFixedFocus = minFocusDistance == null || minFocusDistance == 0.0f
                        if (isFixedFocus) {
                            afIsReady.set(true)
                        } else {
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(0.5f, 0.5f)
                            val action = FocusMeteringAction.Builder(point).build()
                            camera?.cameraControl?.startFocusAndMetering(action)
                        }
                    } catch (e: Exception) {
                        Log.e("ScanPhaseScreen", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        val sessionSubject = viewModel?.activeSubject?.collectAsState()?.value
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (phase == 1) "Phase 1: Align info panel (portrait)"
                           else "Phase 2: Turn phone sideways (top toward sheet center)",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!sessionSubject.isNullOrBlank()) {
                    Text(
                        text = "Session: $sessionSubject",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Rotation hint, until the first marker is found.
        if (phase == 2 && cornerStates.all { it == MarkerLockState.NOT_FOUND }) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "↶  Turn phone sideways",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Top of phone → left edge of answer grid",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // READING/ERROR come from ViewModel state and override the analyzer's phase.
        // Both `processing` (reading a frame) and `capturing` (shutter latency) -> READING.
        val processingMsg = viewModel?.processing?.collectAsState()?.value
        val vmState = viewModel?.state?.collectAsState()?.value
        val isReading = processingMsg != null || capturing
        val isError = vmState is ScanSessionState.Error

        // Clear `capturing` once reading starts, so the overlay stays on "Reading…"
        // instead of flipping back when `processing` clears before the next screen.
        LaunchedEffect(processingMsg) {
            if (processingMsg != null) capturing = false
        }

        val effectivePhase = when {
            isError -> OverlayPhase.ERROR
            isReading -> OverlayPhase.READING
            else -> liveState.overlayPhase
        }
        val effectiveState = liveState.copy(overlayPhase = effectivePhase)

        ScanPhaseOverlay(
            state = effectiveState,
            phase = phase,
            modifier = Modifier.fillMaxSize()
        )

        OutlinedButton(
            onClick = onEndSession,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .width(120.dp)
        ) {
            Text(
                text = "End\nSession",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Shown only after the blur budget is spent, so a sheet that won't focus can
        // still be graded; bypasses the content blur gate for the next frame.
        val blurExhausted = viewModel?.blurRetryExhausted?.collectAsState()?.value == true
        if (blurExhausted && !isReading) {
            Button(
                onClick = { viewModel?.forceAcceptCapture() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp)
            ) {
                Text("Capture anyway")
            }
        }

        // Stability arc for the nav Capture button, pushed to the ViewModel so the nav
        // bar renders it. Zero once fired/processing, so the arc clears as the shutter fires.
        val navArcProgress = if (!hasFired.value &&
            (effectivePhase == OverlayPhase.SEARCHING || effectivePhase == OverlayPhase.HOLD_STILL)
        ) effectiveState.stabilityProgress else 0f
        LaunchedEffect(navArcProgress) { viewModel?.setCaptureProgress(navArcProgress) }
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            viewModel?.setCaptureProgress(0f)
        }
    }
}

// Delivers the bitmap via onCapture; does NOT re-arm on success (the ViewModel
// drives what's next). onCaptureError fires only when the capture itself fails,
// since then the ViewModel gets no bitmap and can't re-arm. Re-arming on success
// once let a second capture fire mid-processing whose late error overwrote the result.
internal fun triggerCapture(
    imageCapture: ImageCapture,
    context: android.content.Context,
    phase: Int,
    scope: CoroutineScope,
    onCapture: (android.graphics.Bitmap) -> Unit,
    onCaptureError: () -> Unit
) {
    val file = java.io.File(context.cacheDir, "capture_phase${phase}_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                scope.launch(Dispatchers.IO) {
                    val bytes = output.savedUri
                        ?.let { context.contentResolver.openInputStream(it)?.readBytes() }
                        ?: run {
                            Log.e("AutoCapture", "PIPELINE ERROR — could not read saved bytes")
                            withContext(Dispatchers.Main) { onCaptureError() }
                            return@launch
                        }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                        Log.e("AutoCapture", "PIPELINE ERROR — BitmapFactory returned null")
                        withContext(Dispatchers.Main) { onCaptureError() }
                        return@launch
                    }
                    val corrected = applyExifRotation(bytes, bitmap)
                    if (corrected !== bitmap) bitmap.recycle()
                    withContext(Dispatchers.Main) {
                        onCapture(corrected)
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("AutoCapture", "CAPTURE FAILED — ${exception.imageCaptureError}: ${exception.message}")
                Log.e("ScanPhaseScreen", "Capture failed", exception)
                onCaptureError()
            }
        }
    )
}

private val OVERLAY_GREY = Color.White.copy(alpha = 0.5f)
private val OVERLAY_AMBER = Color(0xFFFFB347)
private val OVERLAY_GREEN = Color(0xFF22D3A0)

// detectedMarkers are already in PreviewView surface coords, so drawn directly.
@Composable
private fun ScanPhaseOverlay(
    state: LiveMarkerState,
    phase: Int,
    modifier: Modifier = Modifier
) {
    val roles = remember(phase) { phaseRoles(phase) }
    val overlayPhase = state.overlayPhase

    // Freeze geometry at HOLD_STILL → READING so the locked quad keeps drawing after
    // the analyzer stops emitting. Cleared on return to SEARCHING / ERROR.
    var frozenMarkers by remember { mutableStateOf<Map<MarkerRole, android.graphics.PointF>?>(null) }
    LaunchedEffect(overlayPhase) {
        when (overlayPhase) {
            OverlayPhase.READING -> if (frozenMarkers == null) frozenMarkers = state.detectedMarkers
            OverlayPhase.SEARCHING, OverlayPhase.ERROR -> frozenMarkers = null
            else -> {}
        }
    }
    val markers = if (overlayPhase == OverlayPhase.READING)
        (frozenMarkers ?: state.detectedMarkers) else state.detectedMarkers

    val showBrackets = overlayPhase == OverlayPhase.SEARCHING || overlayPhase == OverlayPhase.ERROR
    val bracketAlpha by animateFloatAsState(
        targetValue = if (showBrackets) 1f else 0f,
        animationSpec = tween(150), label = "bracketAlpha"
    )

    val baseDotRadius by animateDpAsState(
        targetValue = if (overlayPhase == OverlayPhase.HOLD_STILL || overlayPhase == OverlayPhase.READING)
            10.dp else 8.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dotRadius"
    )

    // READING-only animations, read inside the Canvas only while READING so they don't
    // invalidate the overlay every frame during SEARCHING / HOLD_STILL.
    val infinite = rememberInfiniteTransition(label = "reading")
    val pulseRadiusState = infinite.animateFloat(
        initialValue = 10f, targetValue = 13f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val pingProgressState = infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "ping"
    )

    // Corner slots in clockwise polygon order: TL → TR → BR → BL.
    val cwOrder = listOf(0, 1, 3, 2)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Drawn first, under the dots/quad.
            if (overlayPhase == OverlayPhase.READING) {
                drawRect(Color.Black.copy(alpha = 0.4f))
            }

            // Portrait-rectangle bracket guides. The overlay is always portrait-locked,
            // so one taller rectangle serves both phases: Phase 1's info zone is taller
            // than wide, and Phase 2 holds the phone sideways so the answer grid maps onto
            // screen-Y.
            if (bracketAlpha > 0.01f) {
                val insetX = 20.dp.toPx()
                val top = 96.dp.toPx()
                val bottom = h - 64.dp.toPx()
                val arm = 40.dp.toPx()
                val stroke = 6.dp.toPx()
                val corners = arrayOf(
                    Offset(insetX, top), Offset(w - insetX, top),
                    Offset(insetX, bottom), Offset(w - insetX, bottom)
                )
                val dirX = floatArrayOf(1f, -1f, 1f, -1f)
                val dirY = floatArrayOf(1f, 1f, -1f, -1f)
                for (i in 0..3) {
                    val base = if (markers.containsKey(roles[i])) OVERLAY_AMBER else OVERLAY_GREY
                    val c = base.copy(alpha = base.alpha * bracketAlpha)
                    val o = corners[i]
                    drawLine(c, o, Offset(o.x + dirX[i] * arm, o.y), strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(c, o, Offset(o.x, o.y + dirY[i] * arm), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }

            val ordered = cwOrder.mapNotNull { idx ->
                markers[roles[idx]]?.let { p -> Offset(p.x, p.y) }
            }

            if (overlayPhase == OverlayPhase.HOLD_STILL || overlayPhase == OverlayPhase.READING) {
                if (ordered.size == 4) {
                    val path = Path().apply {
                        moveTo(ordered[0].x, ordered[0].y)
                        for (k in 1 until 4) lineTo(ordered[k].x, ordered[k].y)
                        close()
                    }
                    drawPath(path, OVERLAY_GREEN, style = Stroke(width = 2.dp.toPx()))
                }
            } else {
                // Partial open quad through however many corners are found.
                val lineColor = OVERLAY_AMBER.copy(alpha = 0.5f)
                for (k in 0 until ordered.size - 1) {
                    drawLine(lineColor, ordered[k], ordered[k + 1], strokeWidth = 1.5.dp.toPx())
                }
            }

            val dotColor = when (overlayPhase) {
                OverlayPhase.SEARCHING, OverlayPhase.ERROR -> OVERLAY_AMBER
                else -> OVERLAY_GREEN
            }
            val radiusPx = if (overlayPhase == OverlayPhase.READING)
                pulseRadiusState.value.dp.toPx() else baseDotRadius.toPx()
            for ((_, pt) in markers) {
                val o = Offset(pt.x, pt.y)
                if (overlayPhase == OverlayPhase.READING) {
                    val ping = pingProgressState.value
                    val pingR = radiusPx + (28.dp.toPx() - radiusPx) * ping
                    drawCircle(
                        OVERLAY_GREEN.copy(alpha = 1f - ping),
                        radius = pingR, center = o, style = Stroke(width = 2.dp.toPx())
                    )
                }
                drawCircle(dotColor, radius = radiusPx, center = o)
            }
        }

        val label = when (overlayPhase) {
            OverlayPhase.SEARCHING, OverlayPhase.ERROR -> "Keep all four corners in view"
            OverlayPhase.HOLD_STILL -> "Hold Still"
            OverlayPhase.READING -> "Reading…"
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (overlayPhase == OverlayPhase.READING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

