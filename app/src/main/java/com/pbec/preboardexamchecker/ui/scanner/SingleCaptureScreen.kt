package com.pbec.preboardexamchecker.ui.scanner

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import com.pbec.preboardexamchecker.ui.scanner.processor.MarkerDetector
import com.pbec.preboardexamchecker.ui.scanner.processor.SharpnessChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Role order: [TL, TM, TR, BL, BM, BR].
private const val SIX = 6
private const val SINGLE_STABILITY_PIXEL_THRESHOLD = 8.0
private const val SINGLE_STABILITY_WINDOW_SIZE = 12
private const val SINGLE_STABILITY_WINDOW_MIN_HITS = 10

private val SINGLE_OVERLAY_AMBER = Color(0xFFFFB347)
private val SINGLE_OVERLAY_GREEN = Color(0xFF22D3A0)

// Detects all 6 markers in one frame at any sheet rotation, locks when stable, captures
// once. UI stays portrait-locked; only the sheet rotates.
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@Composable
fun SingleCaptureScreen(
    onCapture: (android.graphics.Bitmap) -> Unit,
    onEndSession: () -> Unit,
    viewModel: ScannerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    val permission = rememberCameraPermissionState()

    val markersFlow = remember { MutableStateFlow<List<PointF?>>(List(SIX) { null }) }
    val markersState by markersFlow.collectAsState()
    val lockedFlow = remember { MutableStateFlow(false) }
    val locked by lockedFlow.collectAsState()
    val hasFired = remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val resetSignal = remember { AtomicBoolean(false) }

    // Keep the UI portrait-locked (the sheet, not the app, rotates).
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }


    LaunchedEffect(viewModel) {
        viewModel.manualCaptureEvent.collect {
            if (!hasFired.value) {
                val capture = imageCaptureUseCase ?: return@collect
                hasFired.value = true
                capturing = true
                triggerCapture(capture, context, 0, scope, onCapture,
                    onCaptureError = {
                        hasFired.value = false
                        capturing = false
                        resetSignal.set(true)
                        markersFlow.value = List(SIX) { null }
                        lockedFlow.value = false
                    })
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.rearmCapture.collect {
            hasFired.value = false
            capturing = false
            resetSignal.set(true)
            markersFlow.value = List(SIX) { null }
            lockedFlow.value = false
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
                    val smoothed = arrayOfNulls<Point>(SIX)
                    val prevSmoothed = arrayOfNulls<Point>(SIX)
                    val windows = Array(SIX) { ArrayDeque<Boolean>() }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (hasFired.value) { imageProxy.close(); return@setAnalyzer }
                        if (resetSignal.getAndSet(false)) {
                            for (i in 0 until SIX) { smoothed[i] = null; prevSmoothed[i] = null; windows[i].clear() }
                        }
                        try {
                            val bitmap = imageProxy.toBitmap()
                            val srcMat = Mat()
                            Utils.bitmapToMat(bitmap, srcMat)
                            val gray = Mat()
                            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_BGR2GRAY)
                            srcMat.release()

                            val laplacianVariance = sharpnessChecker.variance(bitmap)
                            val sharp = laplacianVariance >= 50.0
                            bitmap.recycle()

                            val grayForAnalysis = when (imageProxy.imageInfo.rotationDegrees) {
                                90  -> Mat().also { Core.rotate(gray, it, Core.ROTATE_90_CLOCKWISE); gray.release() }
                                180 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_180); gray.release() }
                                270 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_90_COUNTERCLOCKWISE); gray.release() }
                                else -> gray
                            }
                            val analysisW = grayForAnalysis.cols().toDouble()
                            val analysisH = grayForAnalysis.rows().toDouble()
                            val raw = markerDetector.detectSixForGuide(grayForAnalysis)
                            grayForAnalysis.release()

                            val found6 = raw.all { it != null }
                            var allStable = found6
                            for (i in 0 until SIX) {
                                val r = raw[i]
                                val win = windows[i]
                                if (r == null) {
                                    if (win.size >= SINGLE_STABILITY_WINDOW_SIZE) win.removeFirst()
                                    win.addLast(false)
                                    allStable = false
                                    continue
                                }
                                val prev = smoothed[i]
                                smoothed[i] = if (prev == null) Point(r.x, r.y)
                                else Point(0.7 * prev.x + 0.3 * r.x, 0.7 * prev.y + 0.3 * r.y)
                                val s = smoothed[i]!!
                                val ps = prevSmoothed[i]
                                prevSmoothed[i] = Point(s.x, s.y)
                                if (ps == null) { allStable = false; continue }
                                val delta = kotlin.math.hypot(s.x - ps.x, s.y - ps.y)
                                val frameStable = delta < SINGLE_STABILITY_PIXEL_THRESHOLD && sharp
                                if (win.size >= SINGLE_STABILITY_WINDOW_SIZE) win.removeFirst()
                                win.addLast(frameStable)
                                if (win.count { it } < SINGLE_STABILITY_WINDOW_MIN_HITS) allStable = false
                            }

                            val pvW = previewView.width
                            val pvH = previewView.height
                            val overlay = arrayOfNulls<PointF>(SIX)
                            if (pvW > 0 && pvH > 0) {
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val sensorW = imageProxy.width
                                val sensorH = imageProxy.height
                                val transformer = CoordinateTransformer(
                                    analysisWidth = sensorW, analysisHeight = sensorH,
                                    previewWidth = pvW, previewHeight = pvH, rotationDegrees = rot
                                )
                                for (i in 0 until SIX) {
                                    val m = raw[i] ?: continue
                                    val sx: Double; val sy: Double
                                    when (rot) {
                                        90 -> { sx = m.y; sy = sensorH - 1 - m.x }
                                        180 -> { sx = sensorW - 1 - m.x; sy = sensorH - 1 - m.y }
                                        270 -> { sx = sensorW - 1 - m.y; sy = m.x }
                                        else -> { sx = m.x; sy = m.y }
                                    }
                                    overlay[i] = transformer.transform(PointF(sx.toFloat(), sy.toFloat()))
                                }
                            }
                            markersFlow.value = overlay.toList()
                            lockedFlow.value = found6

                            if (allStable && !hasFired.value && afIsReady.get()) {
                                hasFired.value = true
                                val fracHints = Array(SIX) { i ->
                                    smoothed[i]?.let { pt -> Point(pt.x / analysisW, pt.y / analysisH) }
                                }
                                scope.launch(Dispatchers.Main) {
                                    capturing = true
                                    viewModel.setLockedMarkerHints(fracHints)
                                    triggerCapture(imageCapture, ctx, 0, scope, onCapture,
                                        onCaptureError = {
                                            hasFired.value = false
                                            capturing = false
                                            resetSignal.set(true)
                                            markersFlow.value = List(SIX) { null }
                                            lockedFlow.value = false
                                        })
                                }
                                return@setAnalyzer
                            }

                            // Nav capture arc: min stable hits across corners / required.
                            val progress = if (found6)
                                ((0 until SIX).minOf { windows[it].count { b -> b } }.toFloat() /
                                    SINGLE_STABILITY_WINDOW_MIN_HITS).coerceIn(0f, 1f)
                            else 0f
                            scope.launch(Dispatchers.Main) {
                                if (!hasFired.value) viewModel.setCaptureProgress(progress)
                            }
                        } catch (e: Exception) {
                            Log.e("SingleCaptureScreen", "Analysis error", e)
                        } finally {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, imageCapture, imageAnalysis
                        )
                        // Fixed-focus lenses never report an AF lock, so the AF gate would
                        // block forever. Detect via min focus distance (null/0.0f = can't
                        // focus); mark AF ready and let SharpnessChecker be the sole gate.
                        val minFocusDistance = Camera2CameraInfo.from(camera.cameraInfo)
                            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val isFixedFocus = minFocusDistance == null || minFocusDistance == 0.0f
                        if (isFixedFocus) {
                            afIsReady.set(true)
                        } else {
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(0.5f, 0.5f)
                            camera.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        }
                    } catch (e: Exception) {
                        Log.e("SingleCaptureScreen", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        val processingMsg = viewModel.processing.collectAsState().value
        val isReading = processingMsg != null || capturing

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val sessionSubject = viewModel.activeSubject.collectAsState().value
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "1-Capture: Fit the whole sheet",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (!sessionSubject.isNullOrBlank()) {
                    Text(
                        "Session: $sessionSubject",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        SixMarkerOverlay(
            markers = markersState,
            locked = locked,
            reading = isReading,
            modifier = Modifier.fillMaxSize()
        )

        val suggest = viewModel.suggestTwoPhase.collectAsState().value
        if (suggest && !isReading) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Trouble detecting markers?", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Switch to the 2-phase scan (info, then answers) for closer, easier captures.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.switchScanMode(ScanMode.TWO_PHASE) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Switch to 2-phase")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { viewModel.dismissSuggestTwoPhase() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep trying 1-capture")
                    }
                }
            }
        }

        val blurExhausted = viewModel.blurRetryExhausted.collectAsState().value
        if (blurExhausted && !isReading) {
            Button(
                onClick = { viewModel.forceAcceptCapture() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
            ) { Text("Capture anyway") }
        }

        OutlinedButton(
            onClick = onEndSession,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).width(120.dp)
        ) {
            Text(
                "End\nSession",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            viewModel.setCaptureProgress(0f)
        }
    }
}

@Composable
private fun SixMarkerOverlay(
    markers: List<PointF?>,
    locked: Boolean,
    reading: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = if (locked || reading) SINGLE_OVERLAY_GREEN else SINGLE_OVERLAY_AMBER
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (reading) drawRect(Color.Black.copy(alpha = 0.4f))
            // Outer-corner outline: role indices TL=0, TR=2, BR=5, BL=3.
            val outer = listOf(0, 2, 5, 3).mapNotNull { markers.getOrNull(it)?.let { p -> Offset(p.x, p.y) } }
            if (outer.size == 4) {
                val path = Path().apply {
                    moveTo(outer[0].x, outer[0].y)
                    for (k in 1 until 4) lineTo(outer[k].x, outer[k].y)
                    close()
                }
                drawPath(path, dotColor.copy(alpha = if (locked) 1f else 0.5f),
                    style = Stroke(width = 2.dp.toPx()))
            }
            for (p in markers) {
                if (p == null) continue
                drawCircle(dotColor, radius = (if (locked) 10.dp else 8.dp).toPx(), center = Offset(p.x, p.y))
            }
        }
        val label = when {
            reading -> "Reading…"
            locked -> "Hold Still"
            else -> "Keep all 6 markers in view"
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (reading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
