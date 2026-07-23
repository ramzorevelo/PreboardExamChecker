package com.pbec.preboardexamchecker.ui.scanner.processor

import android.graphics.Bitmap
import android.util.Log
import com.pbec.preboardexamchecker.BuildConfig
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.ui.scanner.DebugImageSaver
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringStrategy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Processes a Phase 2 (answer zone) bitmap.
 * Pipeline: BGR → grayscale → CLAHE → Otsu threshold → MarkerDetector →
 *           PerspectiveCorrector → computeColumnBinary → readSubject →
 *           readAnswerGrid (AnswerColumnDetector per column) → score → ScanResult
 */
class AnswerZoneProcessor(
    private val markerDetector: MarkerDetector = MarkerDetector(),
    private val perspectiveCorrector: PerspectiveCorrector = PerspectiveCorrector(),
    private val bubbleReader: BubbleReader = BubbleReader(),
    private val boxFinder: BoxFinder = BoxFinder(),
    private val answerColumnDetector: AnswerColumnDetector = AnswerColumnDetector(),
    private val sharpnessChecker: SharpnessChecker = SharpnessChecker(),
    private val curveRectifier: CurveRectifier = CurveRectifier()
) {

    sealed class Result {
        data class Success(val scanResult: ScanResult, val answerKey: String) : Result()
        data class SubjectMismatch(
            val detectedSubject: String,
            val capturedBitmap: Bitmap
        ) : Result()
        data class Error(val reason: String) : Result()
        /** The still's re-detected markers diverged from the live lock (WYSIWYG
         *  gate) — the caller should re-capture rather than warp with the wrong
         *  corners. See [process]'s validateMarkers gate. */
        data class Recapture(val reason: String) : Result()
    }

    companion object {
        private const val TAG = "AnswerZoneProcessor"
        // Subject bubble decision, relative to the median of the 3 bubbles (no printed
        // letters, so blank ≈ paper; thresholds sit far below the shaded level).
        private const val SUBJECT_FDEV_MIN = 0.20f
        private const val SUBJECT_TDEV_MIN = 0.05f
        // The 3 bubbles as fractions within the subject box (not the warp): on a shifted warp
        // the warp-relative x lands on the label, so re-normalizing to the detected box
        // borders recenters each window. From bubble warp-x (855/1350/1845) vs box span.
        private val SUBJECT_BUBBLE_BOX_FRACS = floatArrayOf(0.354f, 0.570f, 0.785f)
        // Subject box border: a near-full-strip-height dark column in the outer edge.
        private const val SUBJECT_BORDER_LINE_FRAC = 0.70f
        private const val SUBJECT_BORDER_EDGE_FRAC = 0.15f
        // Min dark px for a bracket-bar row inside a subject search window
        private const val SUBJECT_BAR_MIN_PX = 12
        // Locate-window half-width as a multiple of cellW — wider than the bubble so a
        // curve-shifted bubble is still found (the nearest-cluster x-refine rejects the
        // adjacent label even at this width).
        private const val SUBJECT_LOCATE_HALF_W_MULT = 1.8f
        // Min dark mass for a column-cluster to be a valid x-refine target (rejects noise).
        private const val SUBJECT_CX_MIN_MASS = 15
        // Max accepted x shift from the blueprint x (×cellW): a nearer-cluster farther
        // than this is the label/a neighbor, so the blueprint x is kept instead.
        private const val SUBJECT_CX_MAX_SHIFT_MULT = 1.5f
        // Read rect enlargement vs the printed bubble — tolerates residual
        // localization error, like the answer cells' oversized rects
        private const val SUBJECT_READ_SCALE_W = 1.5f
        private const val SUBJECT_READ_SCALE_H = 1.3f
        // Subject row-line reconcile: a located bubble whose y deviates from the
        // fitted row line by more than this (×cellH) is snapped to the line; non-
        // located bubbles take the line's y. Generous because the 3 bubbles are far
        // apart, so curvature bows the shared row more than within one answer column.
        private const val SUBJECT_ROW_TOL_FRAC = 0.7f
        // Max |slope| of the fitted subject row (px y per px x) — clamps a 2-point
        // fit so a single mis-lock can't tilt the row implausibly.
        private const val SUBJECT_ROW_MAX_SLOPE = 0.05
        // ── Answer cell decision ──────────────────────────────────────────
        // Each measure is doubly normalized before thresholding:
        //  1. letter baseline — p25 over the column's 20 rows of that letter (the printed
        //     glyph differs per letter, e.g. fill baseline E≈0.05 vs B≈0.28, so absolutes fail);
        //  2. row-median centering — the adaptive threshold fattens strokes on locally darker
        //     paper, shifting whole rows; subtracting the row median (≤2 marked cells, so the
        //     median stays blank) cancels it.
        // Decision on the *_dev2 values: candidate = fillDev2 ≥ FDEV2_MIN and ≥ DOMINANCE×row
        // max; shade = tintDev2 ≥ TINT_MIN (graphite tints the paper, X/slash/ellipse don't);
        // stroke veto = a candidate whose background-mid shows no tint is a drawn stroke, not
        // a shade, unless near-solid (VETO_IFILL_MAX). Tuned by sweep on 340 ground-truth cells.
        private const val ANSWER_FDEV2_MIN     = 0.03f
        private const val ANSWER_DOMINANCE     = 0.6f
        private const val ANSWER_TINT_MIN      = 0.08f
        private const val ANSWER_VETO_BG       = 0.04f
        private const val ANSWER_VETO_IFILL_MAX = 0.72f
        private const val ANSWER_INTERIOR_INSET = 0.24f
        private const val ANSWER_TINT_PERCENTILE = 25f
        // Color neutralization: blue ink (S≈150+) masked, black markers (S≈0–30) kept.
        private const val COLOR_SAT_THRESHOLD = 60
        // Adaptive threshold parameters for computeColumnBinary.
        private const val ADAPTIVE_BLOCK_SIZE = 51   // must be odd
        private const val ADAPTIVE_C = 8.0

        // ── Content-aware blur gate ───────────────────────────────────────────
        // The still gate measures the raw bitmap's sparse center crop, pinning its floor very
        // low. The warped answer grid is dense, so its Laplacian variance separates sharp from
        // blurry cleanly; below the floor we return Recapture("BLURRY"). The floor is
        // provisional — the variance is logged every frame; calibrate ANSWER_BLUR_VARIANCE_MIN
        // from those on-device values (tools/validation/blur_metrics.py).
        const val ENABLE_CONTENT_BLUR_GATE = true
        private const val ANSWER_BLUR_VARIANCE_MIN = 300.0
        // Band = union x-span of the 5 column regions, y from the grid top down (excludes the
        // blank subject header strip).
        private const val BLUR_BAND_X0_FRAC = 0.0096f
        private const val BLUR_BAND_X1_FRAC = 0.9829f
        private const val BLUR_BAND_Y0_FRAC = 0.0967f

        // ── Per-cell local snap (A2) ──────────────────────────────────────────
        // A final snap nudges each read rect onto the local dark-mass centroid (bracket +
        // letter, symmetric about the cell center) to mop up residual bend at col1/col5.
        // Disabled: it operates at the wrong layer — when the column rectification
        // under-corrects a bend, the bracket and its cell move together, so snapping barely
        // shifts anything. The fix belongs in the per-column rectification. Kept behind the
        // flag (+ magenta overlay) for experimentation; metric: tools/validation/cell_center_error.py.
        const val ENABLE_CELL_LOCAL_SNAP = false
        // Snap window half-extent and max shift, per axis. Y is the safe axis (bow rides rows
        // ~0.3 cell, the only confounder a full cellH away) so it gets a generous window; X is
        // hazardous (question number / neighbour brackets ~1 cell away) so a tight nudge only.
        private const val CELL_SNAP_SEARCH_FRAC_Y = 0.33f
        private const val CELL_SNAP_MAX_FRAC_Y = 0.30f
        private const val CELL_SNAP_SEARCH_FRAC_X = 0.15f
        private const val CELL_SNAP_MAX_FRAC_X = 0.12f
        // Min dark fraction of the window for the centroid to be trusted; else keep the rect.
        private const val CELL_SNAP_MIN_MASS_FRAC = 0.04f

        // ── Global column-box detection ───────────────────────────────────────
        // Detect the 5 printed column rectangles globally and perspective-warp each from its
        // real quad, instead of cropping 5 fixed regions and hunting a box inside each (which
        // on a bent sheet grabs whitespace / right-edge shadow and misregisters the column).
        // Self-falls back to the fixed-region path when it can't return a clean 5.
        const val ENABLE_GLOBAL_COL_BOXES = true
        // Rectify a found column by following its left/right border curves (per-row horizontal
        // stretch) instead of a 4-corner warp, which forces straight edges and can't undo a
        // bow. Falls back to the 4-corner warp when the edges can't be traced.
        const val ENABLE_CURVE_RECTIFY = true
        // A column box is tall (≥45% H), ~1/5 wide (10–30% W), below the header (top ≥8% H).
        private const val COLBOX_MIN_H_FRAC = 0.45
        private const val COLBOX_MIN_W_FRAC = 0.10
        private const val COLBOX_MAX_W_FRAC = 0.30
        private const val COLBOX_MIN_TOP_FRAC = 0.08

        // ── Subject-box curve rectification ───────────────────────────────────
        // Detect the subject box as a wide box in the clean answer-warp binary (as
        // findColumnBoxes does) and curve-rectify it to a flat sub-warp where the 3 bubbles
        // sit on a fixed locus. The old fixed strip doesn't follow a bow, so it cut off the
        // borders/bubbles. Self-falls back to the fixed-strip reader.
        const val ENABLE_SUBJECT_BOX_DETECT = true
        // The subject box: nearly full width, short, at the very top of the warp.
        private const val SUBJBOX_MIN_W_FRAC = 0.80
        private const val SUBJBOX_MIN_H_FRAC = 0.030
        private const val SUBJBOX_MAX_H_FRAC = 0.150
        private const val SUBJBOX_MAX_TOP_FRAC = 0.10
        // Restrict the contour search to the top band: on noisy captures the box border
        // connects to the column grid below, so a full-image RETR_EXTERNAL returns one giant
        // contour and loses the box. Cropping to the top band severs that link.
        private const val SUBJBOX_SEARCH_FRAC = 0.15
        // ── Bubble x-localization on the deskewed sub-warp ────────────────────
        // The empty [ ] bracket is too faint to find by its ink, so exploit the row's block
        // structure: pentagon | MATH+bubble | ESAS+bubble | PROF EE+bubble, each ending in
        // its bubble before a wide gap. The content edge just before a wide gap is a bubble's
        // right edge (center half a bubble-width left); each prior snaps to the nearest such
        // gap-edge. Tracks the bubble's true position and reads gaps, not faint bracket ink.
        private const val SUBJECT_LOC_BAND_LO = 0.30f     // central band (excludes box borders)
        private const val SUBJECT_LOC_BAND_HI = 0.70f
        private const val SUBJECT_LOC_DARK_FRAC = 0.12f   // column 'dark' if > this frac of band height
        private const val SUBJECT_LOC_SIG_GAP = 0.015f    // a gap this wide separates two label blocks
        private const val SUBJECT_LOC_HALF_BUB = 0.0065f  // bubble half-width (center = right edge − this)
        private const val SUBJECT_LOC_SNAP_WIN = 0.075f   // snap a prior to a gap-edge within this
    }

    fun process(
        bitmap: Bitmap,
        context: ScanContext,
        scannedInfo: ScannedInfo,
        scoringStrategy: ScoringStrategy,
        cornerHints: Array<Point?>? = null,
        debugContext: android.content.Context? = null,
        // When true, skip the content-aware blur rejection for this frame — the
        // user pressed "Capture anyway" after exhausting the recapture budget.
        bypassBlurGate: Boolean = false,
        // When true, never return SubjectMismatch — score against [context] even if
        // the detected subject differs. Used when the instructor resolves a mismatch
        // by re-scoring the already-captured sheet ("Use Current Session" / "Switch
        // Exam"), so the held bitmap is graded rather than re-captured.
        skipSubjectCheck: Boolean = false
    ): Result {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        if (debugContext != null) DebugImageSaver.saveMat(debugContext, src, "answer_captured")

        // Mask colored pixels (e.g. blue ink) to white so they can't register as dark
        // marker candidates in the binary.
        val hsvMat = Mat()
        Imgproc.cvtColor(src, hsvMat, Imgproc.COLOR_BGR2HSV)
        val hsvChannels = ArrayList<Mat>()
        Core.split(hsvMat, hsvChannels)
        val satChannel = hsvChannels[1]
        hsvMat.release()

        val colorMask = Mat()
        Imgproc.threshold(satChannel, colorMask, COLOR_SAT_THRESHOLD.toDouble(),
            255.0, Imgproc.THRESH_BINARY)
        satChannel.release()
        hsvChannels.forEach { it.release() }

        // Gray for marker detection: colored pixels blanked to white
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        gray.setTo(Scalar(255.0), colorMask)
        colorMask.release()

        // Gray for cell reading: unmodified
        val grayForCells = Mat()
        Imgproc.cvtColor(src, grayForCells, Imgproc.COLOR_BGR2GRAY)
        // src held until after image 2 — released after answerMarkers reorder

        // Layer A: CLAHE(3.0, 32×32) for marker detection — handles wide shadow gradients
        val markerClahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
        val markerEnhanced = Mat()
        markerClahe.apply(gray, markerEnhanced)

        val binary = Mat()
        Imgproc.threshold(markerEnhanced, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        // Layer B: CLAHE(2.0, 8×8) for cell reading — applied to unmodified gray
        val cellClahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        cellClahe.apply(grayForCells, enhanced)
        gray.release()
        grayForCells.release()

        // markerEnhanced doubles as the cornerSubPix reference. Expected quad aspect:
        // the answer zone is captured rotated in portrait, so image width = zone height
        // (1596/2524 ≈ 0.632). Rejects stray dark squares that win a quadrant on area.
        val rawMarkers = markerDetector.detect(binary, markerEnhanced,
            expectedQuadAspect = 1596.0 / 2524.0,
            useCalibRois = true,
            cornerHints = cornerHints)
        val markerImgW = binary.cols()
        val markerImgH = binary.rows()
        binary.release()

        // Live-lock hints → still pixels, each refined onto the real black square's centre
        // (needs markerEnhanced, so refine before releasing it).
        val hintPx: List<Point?>? = cornerHints?.map { h ->
            h?.let { markerDetector.refineToNearestSquare(
                markerEnhanced, Point(it.x * markerImgW, it.y * markerImgH), markerImgW) }
        }
        markerEnhanced.release()

        if (hintPx != null && rawMarkers != null) {
            var maxDiv = 0.0
            for (q in rawMarkers.indices) hintPx.getOrNull(q)?.let {
                maxDiv = maxOf(maxDiv, Math.hypot(rawMarkers[q].x - it.x, rawMarkers[q].y - it.y) / markerImgW)
            }
            Log.w(TAG, "Marker WYSIWYG raw divergence=%.3f (gate %.3f)".format(
                maxDiv, MarkerDetector.MARKER_HINT_MAX_DIVERGENCE_FRAC))
        }
        // Per-corner correction against the refined live lock (see InfoZoneProcessor):
        // keep good detected corners, replace mis-picked / missing ones with the hint.
        val markers = markerDetector.reconcileWithHints(rawMarkers, hintPx, markerImgW)
        if (markers == null) {
            src.release()
            enhanced.release()
            return Result.Error("Could not find 4 registration markers in answer zone")
        }

        // Phone held CCW landscape: portrait quadrants TL/TR/BL/BR = BM/TM/BR/TR markers.
        // Reorder to answer-zone warp order [TM, TR, BM, BR].
        val answerMarkers = listOf(markers[1], markers[3], markers[0], markers[2])

        if (debugContext != null) saveAnswerCapturedOverlay(debugContext, src, answerMarkers)
        src.release()

        val result = readWarpedZone(
            enhanced, answerMarkers, context, scannedInfo, scoringStrategy,
            bitmap, bypassBlurGate, skipSubjectCheck, debugContext
        )
        enhanced.release()
        return result
    }

    /**
     * Warps [enhanced] (CLAHE cell-reading gray) to the canonical answer zone using
     * [answerMarkers] ([TM, TR, BM, BR]), then runs the blur gate, subject-mismatch gate and
     * answer-grid scoring. Shared by [process] and SingleCaptureProcessor. [enhanced] is
     * borrowed — the caller releases it. [bitmap] is returned in a SubjectMismatch for re-scoring.
     */
    fun readWarpedZone(
        enhanced: Mat,
        answerMarkers: List<Point>,
        context: ScanContext,
        scannedInfo: ScannedInfo,
        scoringStrategy: ScoringStrategy,
        bitmap: Bitmap,
        bypassBlurGate: Boolean = false,
        skipSubjectCheck: Boolean = false,
        debugContext: android.content.Context? = null
    ): Result {
        val warpedGray = perspectiveCorrector.warp(
            enhanced, answerMarkers,
            SheetBlueprint.ANSWER_WARP_W, SheetBlueprint.ANSWER_WARP_H
        )

        if (debugContext != null) {
            DebugImageSaver.saveMat(debugContext, warpedGray, "answer_warped")
        }

        val warpedBinary = computeColumnBinary(warpedGray, "answer_warp", debugContext)

        if (debugContext != null) {
            DebugImageSaver.saveMat(debugContext, warpedBinary, "answer_warped_binary")
        }

        // Blur gate: measure sharpness on the dense answer-grid band (not the sparse raw-bitmap
        // center). Logged every frame for calibration; only rejects when enabled.
        run {
            val bx0 = (BLUR_BAND_X0_FRAC * SheetBlueprint.ANSWER_WARP_W).toInt()
            val bx1 = (BLUR_BAND_X1_FRAC * SheetBlueprint.ANSWER_WARP_W).toInt()
            val by0 = (BLUR_BAND_Y0_FRAC * SheetBlueprint.ANSWER_WARP_H).toInt()
            val band = Mat(warpedGray, Rect(bx0, by0, bx1 - bx0, SheetBlueprint.ANSWER_WARP_H - by0))
            val variance = sharpnessChecker.varianceNoCrop(band)
            band.release()
            Log.d(TAG, "answer-grid variance=%.1f (floor %.1f, gate %s)".format(
                variance, ANSWER_BLUR_VARIANCE_MIN,
                if (ENABLE_CONTENT_BLUR_GATE) "on" else "off"))
            if (ENABLE_CONTENT_BLUR_GATE && !bypassBlurGate && variance < ANSWER_BLUR_VARIANCE_MIN) {
                warpedGray.release()
                warpedBinary.release()
                return Result.Recapture("BLURRY answer grid (variance=%.0f < %.0f)".format(
                    variance, ANSWER_BLUR_VARIANCE_MIN))
            }
        }

        // Read subject (curve-rectified path, fixed-strip fallback; handles its own overlay).
        val subjectResult = readSubjectZone(warpedGray, warpedBinary, context.subject, debugContext)
        val mappedSubject = mapSubjectString(subjectResult.subject)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Subject detected=${subjectResult.subject} mapped=$mappedSubject session=${context.subject}")
        }

        if (debugContext != null) {
            // Full-warp boxes overlay (column regions only; subject has its own overlay).
            saveAnswerWarpedBoxes(
                debugContext, warpedGray, emptyList(),
                SheetBlueprint.answerColSearchRegions,
                List(5) { null }
            )
        }

        // Check subject mismatch (skipped per [skipSubjectCheck]).
        if (!skipSubjectCheck && !mappedSubject.equals(context.subject, ignoreCase = true)) {
            warpedGray.release()
            warpedBinary.release()
            return Result.SubjectMismatch(mappedSubject, bitmap)
        }

        // Read answers
        val rawAnswers = readAnswerGrid(warpedGray, warpedBinary, debugContext)
        warpedGray.release()
        warpedBinary.release()

        if (rawAnswers == null) {
            return Result.Error("Answer grid detection failed")
        }

        // Score; '-' (not detected) is wrong. Iterate in printed order: position i is
        // setXQuestionIds[i]. Do NOT sort by id — that discards the per-set shuffle.
        val effectiveTestSet = if (scannedInfo.testSet == "B") "B" else "A"
        val keyMap = if (effectiveTestSet == "A") context.setAKeyMap else context.setBKeyMap
        val examQuestionIds = if (effectiveTestSet == "A")
            context.setAQuestionIds
        else
            context.setBQuestionIds

        // Build the answer key in printed order, exactly one char per item ('-' for no usable
        // key) so it stays aligned with the answers. A key counts only as a clean A–E letter;
        // blank/malformed keys are kept in place and scored wrong. Appending the raw value
        // would add 0 chars for "" and shift every later item — that was the bug.
        val keyBuilder = StringBuilder()
        var score = 0
        var total = 0
        for (i in 0 until minOf(100, examQuestionIds.size)) {
            val questionId = examQuestionIds[i]
            val correctAnswer = keyMap[questionId]
                ?.trim()?.uppercase()
                ?.takeIf { it.length == 1 && it[0] in 'A'..'E' }
            keyBuilder.append(correctAnswer ?: "-")
            total++
            val studentAnswer = rawAnswers.getOrNull(i)?.toString()
            if (correctAnswer != null && studentAnswer == correctAnswer) score++
        }
        val answerKey = keyBuilder.toString()

        val passed = scoringStrategy.isPassed(score, total)
        android.util.Log.d(
            "Scoring",
            "score set=$effectiveTestSet ids=${examQuestionIds.size} rawLen=${rawAnswers.length} " +
                "total=$total score=$score keyNonNull=${keyMap.values.count { it != null }}"
        )

        val student = scannedInfo.resolvedStudent
        val scanResult = ScanResult(
            studentId        = scannedInfo.studentId,
            studentName      = student?.name ?: "Unknown",
            studentBlock     = student?.block ?: "Unknown",
            studentYearLevel = student?.yearLevel ?: "Unknown",
            studentProgram   = student?.program ?: "Unknown",
            subject          = context.subject,
            examId           = context.examId,
            examName         = context.examName,
            clusterId        = context.clusterId,
            clusterName      = context.clusterName,
            testSet          = scannedInfo.testSet,
            rawAnswers       = rawAnswers,
            score            = score,
            total            = total,
            passed           = passed
        )

        return Result.Success(scanResult, answerKey)
    }

    // ── Binary production ─────────────────────────────────────────────────

    private fun computeColumnBinary(subWarp: Mat, label: String,
        debugContext: android.content.Context?): Mat {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(subWarp, enhanced)
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            enhanced, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            ADAPTIVE_BLOCK_SIZE,
            ADAPTIVE_C
        )
        enhanced.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
        binary.release()
        kernel.release()
        // No debug save: the caller already saves the full-warp result as "answer_warped_binary".
        return closed
    }

    // ── Subject reading ───────────────────────────────────────────────────

    /** One subject bubble's read, kept for the debug overlay. */
    private class SubjectRead(
        val rect: Rect,        // warp coords
        val located: Boolean,  // bubble found dynamically (vs blueprint fallback)
        val ifill: Float,
        val tint: Float,
        var shaded: Boolean = false
    )

    private class SubjectResult(val subject: String, val reads: List<SubjectRead>)

    /**
     * Reads the subject zone, preferring the detected-box path over the fixed-strip reader.
     * Both produce a [SubjectResult] and save the same overlay filenames.
     *
     * The box path top-anchored curve-rectifies the box: it traces the clean top border and
     * stretches each column down by the box's median height. A 4-corner warp can't flatten a
     * bow (a banana-bowed box loses its middle off-frame), and a full top+bottom stretch
     * explodes because the column grid below contaminates the bottom edge — so the top edge
     * alone flattens rotation + bow + keystone. Falls back to the minAreaRect warp when the
     * top can't be traced; residual bow is absorbed by the gap-edge bubble localization.
     */
    private fun readSubjectZone(
        warpedGray: Mat, warpedBinary: Mat, sessionSubject: String,
        debugContext: android.content.Context?
    ): SubjectResult {
        if (ENABLE_SUBJECT_BOX_DETECT) {
            val box = findSubjectBox(warpedBinary)
            if (box != null) {
                val subGray = curveRectifier.rectifyGrayTopAnchored(
                    warpedGray, box.contour,
                    SheetBlueprint.SUBJECT_WARP_W, SheetBlueprint.SUBJECT_WARP_H)
                    ?: perspectiveCorrector.warp(
                        warpedGray, box.quad,
                        SheetBlueprint.SUBJECT_WARP_W, SheetBlueprint.SUBJECT_WARP_H)
                box.contour.release()
                val subBinary = computeSubjectBinary(subGray)
                val result = readSubjectRectified(subGray, subBinary, sessionSubject)
                if (debugContext != null) saveSubjectRectifiedOverlay(debugContext, subGray, result.reads)
                subGray.release(); subBinary.release()
                return result
            }
        }
        // Fixed-strip fallback (+ its overlay).
        val result = readSubject(warpedGray, warpedBinary, sessionSubject)
        if (debugContext != null) {
            val stripY0 = (SheetBlueprint.SUBJECT_STRIP_Y_FRAC_START * SheetBlueprint.ANSWER_WARP_H).toInt()
            val stripY1 = (SheetBlueprint.SUBJECT_STRIP_Y_FRAC_END * SheetBlueprint.ANSWER_WARP_H).toInt()
                .coerceAtMost(SheetBlueprint.ANSWER_WARP_H)
            val stripH = (stripY1 - stripY0).coerceAtLeast(1)
            val sub = Mat(warpedGray, Rect(0, stripY0, SheetBlueprint.ANSWER_WARP_W, stripH))
            saveSubjectSubwarpOverlay(debugContext, sub, result.reads, stripY0)
            sub.release()
        }
        return result
    }

    /** Binary for the subject sub-warp: global Otsu on the CLAHE strip, then a small open.
     *  The strip is small/uniform, so global keeps the thin brackets crisp and the margins
     *  empty; the block-adaptive threshold speckles the shadowed right side and breaks the
     *  island detector's empty-margin test. */
    private fun computeSubjectBinary(subWarp: Mat): Mat {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat(); clahe.apply(subWarp, enhanced)
        val binary = Mat()
        Imgproc.threshold(enhanced, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        enhanced.release()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val opened = Mat()
        Imgproc.morphologyEx(binary, opened, Imgproc.MORPH_OPEN, k)
        binary.release(); k.release()
        return opened
    }

    /**
     * Detects the SUBJECT box as a single wide box in the clean answer-warp binary, like
     * [findColumnBoxes]. A horizontal-biased close bridges gaps along the long top/bottom
     * borders without merging into the columns below. Returns the box's quad + contour
     * (caller releases [ColBox.contour]), or null (caller falls back to the fixed strip).
     */
    private fun findSubjectBox(warpedBinary: Mat): ColBox? {
        val w = warpedBinary.cols()
        val h = warpedBinary.rows()
        // Top band only; the crop is top-left aligned, so contour coords are full-warp coords.
        val cropH = (SUBJBOX_SEARCH_FRAC * h).toInt().coerceIn(1, h)
        val topBand = Mat(warpedBinary, Rect(0, 0, w, cropH))
        val mask = Mat()
        Imgproc.threshold(topBand, mask, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
        topBand.release()
        // Horizontal-only close (ky=1) bridges the long top/bottom borders without bridging
        // vertically into the rows/columns below.
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 1.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k)
        k.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release(); mask.release()

        val best = contours.filter { c ->
            val br = Imgproc.boundingRect(c)
            br.width >= SUBJBOX_MIN_W_FRAC * w &&
                br.height >= SUBJBOX_MIN_H_FRAC * h && br.height <= SUBJBOX_MAX_H_FRAC * h &&
                br.y <= SUBJBOX_MAX_TOP_FRAC * h
        }.maxByOrNull {
            val br = Imgproc.boundingRect(it); br.width.toDouble() * br.height
        }
        // minAreaRect corners, not approxPolyDP: the pentagon notch makes approxPolyDP pick
        // interior vertices and shear the warp; the rotated rect always contains the box.
        val result = best?.let { ColBox(minAreaQuad(it), MatOfPoint(*it.toArray())) }
        contours.forEach { it.release() }
        if (result == null) {
            Log.w(TAG, "findSubjectBox: no clean wide subject box — fixed-strip fallback")
        }
        return result
    }

    /** The 4 ordered corners ([TL,TR,BL,BR]) of a contour's minAreaRect — the rotated
     *  bounding rect, which always contains the whole contour (no interior vertices). */
    private fun minAreaQuad(c: MatOfPoint): List<Point> {
        val c2f = MatOfPoint2f(*c.toArray())
        val rr = Imgproc.minAreaRect(c2f)
        val box = Array(4) { Point() }
        rr.points(box)
        c2f.release()
        return orderQuad(box.toList())
    }

    /**
     * Reads the 3 subject bubbles on the deskewed sub-warp. x-localization snaps each prior
     * to the nearest block gap-edge (see the SUBJECT_LOC_* constants); y is one shared
     * straightened row (median of the per-bubble bands). Shaded = the relative fill+tint rule
     * (median of the 3 = blank baseline; at most one is shaded).
     */
    private fun readSubjectRectified(subGray: Mat, subBinary: Mat, sessionSubject: String): SubjectResult {
        val w = subBinary.cols()
        val h = subBinary.rows()
        val binPx = ByteArray(w * h); subBinary.get(0, 0, binPx)
        val grayPx = ByteArray(w * h); subGray.get(0, 0, grayPx)
        fun isDark(x: Int, y: Int) = (binPx[y * w + x].toInt() and 0xFF) < 128

        val cellW = (SheetBlueprint.SUBJECT_SW_CELL_W_FRAC * w).toInt().coerceAtLeast(8)
        val cellH = (SheetBlueprint.SUBJECT_SW_CELL_H_FRAC * h).toInt().coerceAtLeast(12)
        val cy0 = SheetBlueprint.SUBJECT_SW_BUBBLE_Y_FRAC * h
        val fracs = SheetBlueprint.subjectBubbleXFracs
        val n = fracs.size

        // x-localization: snap each prior to the nearest block gap-edge. Per-column dark
        // count over a central band (excludes box borders); a column is dark above a fraction.
        val locLo = (SUBJECT_LOC_BAND_LO * h).toInt()
        val locHi = (SUBJECT_LOC_BAND_HI * h).toInt()
        val locBandH = (locHi - locLo).coerceAtLeast(1)
        val colDark = IntArray(w)
        for (y in locLo until locHi) { val base = y * w; for (x in 0 until w) if ((binPx[base + x].toInt() and 0xFF) < 128) colDark[x]++ }
        val darkThr = SUBJECT_LOC_DARK_FRAC * locBandH
        val colMask = BooleanArray(w) { colDark[it] > darkThr }
        // Contiguous content runs (drop 1–2px speckle). maxGap=1 keeps adjacent dark columns
        // in one run; maxGap=0 would split every adjacent pair into 1px runs that the filter
        // drops, leaving no gap-edges.
        val runs = clusterExtents(colMask, maxGap = 1).filter { it.second - it.first >= 2 }
        // A bubble's right edge = the content edge just before a wide gap. The pentagon→MATH
        // gap also yields an edge but sits far left of every prior, so the per-prior snap
        // ignores it — no pentagon special-case (an explicit skip once swallowed a shaded
        // MATH bubble whose small label→bubble gaps merged pentagon+MATH into one block).
        val sigGap = SUBJECT_LOC_SIG_GAP * w
        val edges = ArrayList<Int>()
        for (i in 1 until runs.size) if (runs[i].first - runs[i - 1].second >= sigGap) edges.add(runs[i - 1].second)
        if (runs.isNotEmpty() && (w - runs.last().second) >= sigGap) edges.add(runs.last().second)

        val halfBub = SUBJECT_LOC_HALF_BUB * w
        val snapWin = SUBJECT_LOC_SNAP_WIN * w
        val cxArr = FloatArray(n) { fracs[it] * w }
        val cyArr = FloatArray(n) { cy0 }
        val bandCy = FloatArray(n) { -1f }   // per-bubble detected band y (-1 = none)
        val located = BooleanArray(n)
        for (i in 0 until n) {
            val target = fracs[i] * w + halfBub  // expected bubble RIGHT edge
            val e = edges.filter { kotlin.math.abs(it - target) <= snapWin }
                .minByOrNull { kotlin.math.abs(it - target) }
            if (e != null) { cxArr[i] = (e - halfBub).toFloat(); located[i] = true }
        }

        // Per-bubble band cy, collected; one shared row is applied after (the box is
        // straightened, so the 3 bubbles share a row and must not drift apart).
        for (i in 0 until n) {
            val rcx = cxArr[i]
            val rx0 = (rcx - cellW / 2).toInt().coerceAtLeast(0)
            val rx1 = (rcx + cellW / 2).toInt().coerceAtMost(w)
            val bandLo = (0.20f * h).toInt(); val bandHi = (0.80f * h).toInt()
            val rowMask = BooleanArray(h)
            for (y in bandLo until bandHi) {
                var c = 0; for (x in rx0 until rx1) if (isDark(x, y)) c++
                rowMask[y] = c >= 2
            }
            val band = clusterExtents(rowMask, maxGap = 4).maxByOrNull { it.second - it.first }
            if (band != null && band.second - band.first >= 0.15f * h) {
                bandCy[i] = (band.first + band.second) / 2f
            }
        }

        // Shared straightened row: median of the bubbles with a clear band (robust to
        // one mis-located bubble), falling back to the canonical y. All 3 get this y.
        val foundBands = bandCy.filter { it >= 0f }
        val sharedCy = if (foundBands.isNotEmpty()) median(foundBands) else cy0
        for (i in 0 until n) cyArr[i] = sharedCy

        // ── measure interior fill + tint per bubble, then decide relatively ──
        val readW = (cellW * SUBJECT_READ_SCALE_W).toInt()
        val readH = (cellH * SUBJECT_READ_SCALE_H).toInt()
        val reads = ArrayList<SubjectRead>(n)
        for (i in 0 until n) {
            val cx = cxArr[i]
            val cy = cyArr[i].coerceIn(readH / 2f, h - readH / 2f)
            val ix = (readW * ANSWER_INTERIOR_INSET).toInt()
            val iy = (readH * ANSWER_INTERIOR_INSET).toInt()
            val ix0 = (cx - readW / 2).toInt() + ix
            val ix1 = (cx + readW / 2).toInt() - ix
            val iy0 = (cy - readH / 2).toInt() + iy
            val iy1 = (cy + readH / 2).toInt() - iy
            var darkN = 0; var total = 0
            val darkness = ArrayList<Float>(((ix1 - ix0).coerceAtLeast(0)) * ((iy1 - iy0).coerceAtLeast(0)))
            for (y in iy0.coerceAtLeast(0) until iy1.coerceAtMost(h)) {
                val base = y * w
                for (x in ix0.coerceAtLeast(0) until ix1.coerceAtMost(w)) {
                    if ((binPx[base + x].toInt() and 0xFF) < 128) darkN++
                    total++
                    darkness.add(1f - (grayPx[base + x].toInt() and 0xFF) / 255f)
                }
            }
            val ifill = if (total > 0) darkN.toFloat() / total else 0f
            val tint = if (darkness.size > 10) percentile(darkness.sorted(), ANSWER_TINT_PERCENTILE) else 0f
            val rect = Rect((cx - readW / 2).toInt(), (cy - readH / 2).toInt(), readW, readH)
            reads.add(SubjectRead(rect, located[i], ifill, tint))
        }

        val medF = median(reads.map { it.ifill })
        val medT = median(reads.map { it.tint })
        for (r in reads) {
            r.shaded = (r.ifill - medF) >= SUBJECT_FDEV_MIN && (r.tint - medT) >= SUBJECT_TDEV_MIN
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Subject(rectified) reads: " + reads.mapIndexed { i, r ->
                "${listOf("MATH", "ESAS", "PROF EE")[i]}(located=${r.located} " +
                "ifill=%.2f tint=%.3f shaded=${r.shaded})".format(r.ifill, r.tint)
            }.joinToString(" "))
        }
        val shadedIdx = reads.indices.filter { reads[it].shaded }
        val subject = if (shadedIdx.size == 1) {
            when (shadedIdx[0]) { 0 -> "MATH"; 1 -> "ESAS"; else -> "PROF EE" }
        } else sessionSubject
        return SubjectResult(subject, reads)
    }

    /** The subject box's left/right printed borders within the strip (full-canvas x),
     *  or null if not found. A border is a near-full-strip-height dark column in the
     *  outer [SUBJECT_BORDER_EDGE_FRAC] of the width; the "SUBJECT" pentagon, labels and
     *  bubbles are partial-height so they don't qualify. */
    private fun detectSubjectBoxBounds(isDark: (Int, Int) -> Boolean, w: Int, stripH: Int): Pair<Int, Int>? {
        val thr = (SUBJECT_BORDER_LINE_FRAC * stripH).toInt()
        val edge = (SUBJECT_BORDER_EDGE_FRAC * w).toInt().coerceAtLeast(1)
        fun colDark(x: Int): Int { var c = 0; for (y in 0 until stripH) if (isDark(x, y)) c++; return c }
        var left = -1
        for (x in 0 until edge) if (colDark(x) >= thr) { left = x; break }
        var right = -1
        for (x in w - 1 downTo (w - edge).coerceAtLeast(0)) if (colDark(x) >= thr) { right = x; break }
        if (left < 0 || right < 0 || right - left < 0.5f * w) return null
        return left to right
    }

    /** The subject box's vertical CENTER at a bubble's x-window — the midpoint of the
     *  box's top/bottom borders (full-window-width dark rows). On extreme bow the fixed
     *  blueprint row prior drifts onto the top border; the box center follows the bow so
     *  the bubble search stays on the row. Returns null unless a real box is found (the
     *  border pair must span most of the strip — guards against locking onto a content
     *  band), so the caller falls back to the fixed prior (no regression). */
    private fun subjectBoxCenterY(isDark: (Int, Int) -> Boolean, x0: Int, x1: Int, stripH: Int): Float? {
        val winW = x1 - x0
        if (winW <= 0) return null
        val thr = (0.70f * winW).toInt().coerceAtLeast(1)
        fun rowDark(y: Int): Int { var c = 0; for (x in x0 until x1) if (isDark(x, y)) c++; return c }
        var top = -1
        for (y in 0 until stripH) if (rowDark(y) >= thr) { top = y; break }
        var bot = -1
        for (y in stripH - 1 downTo 0) if (rowDark(y) >= thr) { bot = y; break }
        if (top < 0 || bot < 0) return null
        // The border pair must bracket the box (top near the strip top, bottom near the
        // bottom); a narrow band is a content row, not the box.
        if (top > 0.30f * stripH || bot < 0.70f * stripH) return null
        return (top + bot) / 2f
    }

    private fun readSubject(warpedGray: Mat, warpedBinary: Mat, sessionSubject: String): SubjectResult {
        val w = SheetBlueprint.ANSWER_WARP_W
        val cellW = (SheetBlueprint.SUBJECT_CELL_W_FRAC * w).toInt().coerceAtLeast(8)
        val cellH = (SheetBlueprint.SUBJECT_CELL_H_FRAC * SheetBlueprint.ANSWER_WARP_H).toInt().coerceAtLeast(12)
        val stripY0 = (SheetBlueprint.SUBJECT_STRIP_Y_FRAC_START * SheetBlueprint.ANSWER_WARP_H).toInt()
        val stripY1 = (SheetBlueprint.SUBJECT_STRIP_Y_FRAC_END * SheetBlueprint.ANSWER_WARP_H).toInt()
            .coerceAtMost(SheetBlueprint.ANSWER_WARP_H)
        val stripH = stripY1 - stripY0
        val fallbackY = SheetBlueprint.SUBJECT_FALLBACK_Y_FRAC * SheetBlueprint.ANSWER_WARP_H

        // Bulk strip pixels
        val binStrip = ByteArray(w * stripH)
        val grayStrip = ByteArray(w * stripH)
        Mat(warpedBinary, Rect(0, stripY0, w, stripH)).also { it.get(0, 0, binStrip); it.release() }
        Mat(warpedGray, Rect(0, stripY0, w, stripH)).also { it.get(0, 0, grayStrip); it.release() }
        fun isDark(x: Int, y: Int) = (binStrip[y * w + x].toInt() and 0xFF) < 128

        // Re-normalize bubble x's to the box's detected borders (recenters each window on its
        // bubble after a row shift); fall back to the warp-relative blueprint x.
        val boxLR = detectSubjectBoxBounds(::isDark, w, stripH)
        val fxs = if (boxLR != null) {
            val (bl, brr) = boxLR
            val bw = (brr - bl).toFloat()
            SUBJECT_BUBBLE_BOX_FRACS.map { bl + it * bw }
        } else {
            SheetBlueprint.subjectFallbackXFracs.map { it * w }
        }
        val n = fxs.size
        val fyStrip = fallbackY - stripY0

        // ── Pass 1: locate each bubble's row center (cy) + refined cx ──────────
        val cxArr = FloatArray(n) { fxs[it] }
        val cyArr = FloatArray(n) { fyStrip }
        val located = BooleanArray(n)
        for (i in 0 until n) {
            val fx = fxs[i]
            // Wider locate window than the bubble: the top-of-warp strip bows, shifting a
            // bubble up to ~1 bubble-width off its blueprint x that a ±cellW window misses.
            // The nearest-cluster x-refine below keeps the bold label out of the result.
            val searchHalfW = (cellW * SUBJECT_LOCATE_HALF_W_MULT).toInt().coerceAtLeast(cellW)
            val x0 = (fx - searchHalfW).toInt().coerceAtLeast(0)
            val x1 = (fx + searchHalfW).toInt().coerceAtMost(w)
            val winW = x1 - x0

            // Row prior = the box's vertical center at this x (follows the bow), else the
            // fixed strip prior — so an extreme bow can't pull the bubble onto the top border.
            val priorY = subjectBoxCenterY(::isDark, x0, x1, stripH) ?: fyStrip

            val barMask = BooleanArray(stripH)
            for (y in 0 until stripH) {
                var c = 0
                for (x in x0 until x1) if (isDark(x, y)) c++
                barMask[y] = c >= SUBJECT_BAR_MIN_PX && c < 0.90f * winW
            }
            val clusters = clusterExtents(barMask, maxGap = 4)
            val candidates = ArrayList<Float>()
            val thin = ArrayList<Float>()
            for ((s, e) in clusters) {
                val center = (s + e) / 2f
                if (e - s + 1 >= 0.60f * cellH) candidates.add(center)  // thick = shaded blob
                else thin.add(center)
            }
            for (j in 0 until thin.size - 1) {
                val sep = thin[j + 1] - thin[j]
                if (sep >= 0.70f * cellH && sep <= 1.30f * cellH) {
                    candidates.add((thin[j] + thin[j + 1]) / 2f)
                }
            }
            located[i] = candidates.isNotEmpty()
            var cy = if (located[i]) candidates.minByOrNull { kotlin.math.abs(it - priorY) }!! else priorY

            // x refine via dark column-clusters, not a whole-window centroid: the bold label
            // to the bubble's left would drag a full-window centroid into the text. Clustering
            // isolates the bracket (its two bars merge) from the label (a separate cluster).
            var cx = fx
            val yy0 = (cy - cellH / 2).toInt().coerceAtLeast(0)
            val yy1 = (cy + cellH / 2).toInt().coerceAtMost(stripH)
            val colDark = IntArray(winW)
            for (y in yy0 until yy1) for (x in x0 until x1) if (isDark(x, y)) colDark[x - x0]++
            val colMask = BooleanArray(winW) { colDark[it] > 0 }
            val colClusters = clusterExtents(colMask, maxGap = (cellW * 0.5f).toInt().coerceAtLeast(1))
            // The bracket sits right of its label, so pick the right-most qualifying cluster
            // within reach of fx (the nearest cluster would lock onto the heavier label).
            var bestC: Float? = null
            for ((s, e) in colClusters) {
                var m = 0L
                var mx = 0L
                for (k in s..e) { m += colDark[k]; mx += colDark[k].toLong() * (k + x0) }
                if (m < SUBJECT_CX_MIN_MASS) continue
                val c = mx.toFloat() / m
                if (kotlin.math.abs(c - fx) > SUBJECT_CX_MAX_SHIFT_MULT * cellW) continue
                if (bestC == null || c > bestC!!) bestC = c
            }
            if (bestC != null) cx = bestC!!
            cxArr[i] = cx
            cyArr[i] = cy
        }

        // Row-line reconcile: the 3 bubbles share one row that bows on curved paper. Fit a
        // line through the located bubbles, fill non-located cy from it, and pull an outlier
        // cy (a mis-lock) back onto it. Slope clamped so two points can't define a steep row.
        val locIdx = (0 until n).filter { located[it] }
        if (locIdx.size >= 2) {
            val mx = locIdx.map { cxArr[it].toDouble() }.average()
            val my = locIdx.map { cyArr[it].toDouble() }.average()
            var cov = 0.0; var varx = 0.0
            for (i in locIdx) { cov += (cxArr[i] - mx) * (cyArr[i] - my); varx += (cxArr[i] - mx) * (cxArr[i] - mx) }
            val b = (if (varx > 1e-6) cov / varx else 0.0).coerceIn(-SUBJECT_ROW_MAX_SLOPE, SUBJECT_ROW_MAX_SLOPE)
            val a = my - b * mx
            val tol = SUBJECT_ROW_TOL_FRAC * cellH
            for (i in 0 until n) {
                val pred = (a + b * cxArr[i]).toFloat()
                if (!located[i] || kotlin.math.abs(cyArr[i] - pred) > tol) cyArr[i] = pred
            }
        }

        // ── Pass 2: measure interior + build reads ─────────────────────────────
        val readW = (cellW * SUBJECT_READ_SCALE_W).toInt()
        val readH = (cellH * SUBJECT_READ_SCALE_H).toInt()
        val reads = ArrayList<SubjectRead>(n)
        for (i in 0 until n) {
            val cx = cxArr[i]
            val cy = cyArr[i].coerceIn(cellH / 2f, stripH - cellH / 2f)
            // Enlarged read rect: localization can be a few px off and there's no second snap
            // stage here, so the bigger rect guarantees the shade is contained. Blank baselines
            // are relative, so the extra paper cancels out.
            val ix = (readW * ANSWER_INTERIOR_INSET).toInt()
            val iy = (readH * ANSWER_INTERIOR_INSET).toInt()
            val ix0 = (cx - readW / 2).toInt() + ix
            val ix1 = (cx + readW / 2).toInt() - ix
            val iy0 = (cy - readH / 2).toInt() + iy
            val iy1 = (cy + readH / 2).toInt() - iy
            var darkN = 0
            var total = 0
            val darkness = ArrayList<Float>(((ix1 - ix0).coerceAtLeast(0)) * ((iy1 - iy0).coerceAtLeast(0)))
            for (y in iy0.coerceAtLeast(0) until iy1.coerceAtMost(stripH)) {
                val base = y * w
                for (x in ix0.coerceAtLeast(0) until ix1.coerceAtMost(w)) {
                    if ((binStrip[base + x].toInt() and 0xFF) < 128) darkN++
                    total++
                    darkness.add(1f - (grayStrip[base + x].toInt() and 0xFF) / 255f)
                }
            }
            val ifill = if (total > 0) darkN.toFloat() / total else 0f
            val tint = if (darkness.size > 10) percentile(darkness.sorted(), ANSWER_TINT_PERCENTILE) else 0f
            val rect = Rect((cx - readW / 2).toInt(), (cy - readH / 2).toInt() + stripY0, readW, readH)
            reads.add(SubjectRead(rect, located[i], ifill, tint))
        }

        // Relative decision: ≥2 of 3 bubbles are blank, median = blank baseline
        val medF = median(reads.map { it.ifill })
        val medT = median(reads.map { it.tint })
        for (r in reads) {
            r.shaded = (r.ifill - medF) >= SUBJECT_FDEV_MIN && (r.tint - medT) >= SUBJECT_TDEV_MIN
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Subject reads: " + reads.mapIndexed { i, r ->
                "${listOf("MATH", "ESAS", "PROF EE")[i]}(located=${r.located} " +
                "ifill=%.2f tint=%.3f shaded=${r.shaded})".format(r.ifill, r.tint)
            }.joinToString(" "))
        }
        val shadedIdx = reads.indices.filter { reads[it].shaded }
        val subject = if (shadedIdx.size == 1) {
            when (shadedIdx[0]) {
                0    -> "MATH"
                1    -> "ESAS"
                else -> "PROF EE"
            }
        } else {
            sessionSubject   // none or ambiguous — cannot verify, trust the session
        }
        return SubjectResult(subject, reads)
    }

    /** Clusters consecutive true runs of [mask] (gaps ≤ [maxGap]) into extents. */
    private fun clusterExtents(mask: BooleanArray, maxGap: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        var prev = -1
        for (y in mask.indices) {
            if (!mask[y]) continue
            if (start < 0) start = y
            else if (y - prev > maxGap) {
                out.add(start to prev)
                start = y
            }
            prev = y
        }
        if (start >= 0) out.add(start to prev)
        return out
    }

    private fun mapSubjectString(detected: String): String = when (detected.uppercase()) {
        "MATH"    -> "Mathematics"
        "ESAS"    -> "ESAS"
        "PROF EE" -> "Professional EE"
        else      -> detected  // session subject pass-through, or already mapped
    }

    // ── Answer grid reading ───────────────────────────────────────────────

    /** Per-cell read outcome, kept for the debug overlays. */
    private class CellRead(
        val row: Int,
        val choice: Int,
        val rect: Rect,        // sub-warp coords
        val fill: Float,       // binary dark fraction, full rect (1px inset)
        val ifill: Float,      // binary dark fraction, interior (24% inset)
        val tint: Float,       // p25 interior darkness on gray
        val bgmid: Float,      // darkness at the middle of the background fraction
        var fdev2: Float = 0f,
        var tdev2: Float = 0f,
        var bdev2: Float = 0f,
        var shaded: Boolean = false,
        /** Candidate by mass that failed the tint test or stroke veto —
         *  an improper mark (X/slash/ellipse) or glyph noise. */
        var rejected: Boolean = false,
        /** The pre-snap geometric rect, set ONLY when the per-cell local snap (A2)
         *  actually moved this cell. The debug overlay draws it in magenta so the
         *  snap's effect is visible on the same pixels (no second capture needed). */
        var geomRect: Rect? = null
    )

    /** Per-column detection state captured in phase A, before cross-column row
     *  reconciliation (phase B) and per-cell measurement (phase C). */
    private class ColState(
        val col: Int,
        val cells: ColumnCells,
        val fromDetection: Boolean,
        val binPx: ByteArray,
        val grayPx: ByteArray,
        // The sub-warp's 4 source corners in full-warp coords ([TL,TR,BL,BR]) — the
        // column-box quad when rectified by perspective warp, else the full-crop rect.
        // Maps sub-warp cell positions back to the full warp for the decision overlay.
        val dstCorners: List<Point>,
        // Axis-aligned bbox of dstCorners — an approximate full-warp framing used by
        // the (gross-offset only) cross-column row reconcile.
        val cropOffX: Int,
        val cropOffY: Int,
        val finalCropW: Int,
        val finalCropH: Int,
        val subGray: Mat?,    // kept only in debug builds for the overlays
        val subBinary: Mat?
    )

    /** A globally-detected column box: 4 ordered corners (for the perspective fallback) and
     *  its raw contour (for curve rectification's silhouette/extent). Caller releases [contour]. */
    private class ColBox(val quad: List<Point>, val contour: MatOfPoint)

    /**
     * Detects the 5 printed column boxes globally, left→right, or null for an unclean set
     * (caller falls back to the fixed-region search). Whitespace and right-edge shadow aren't
     * closed tall rectangles, so they're never selected — fixing the shifted-column failures.
     */
    private fun findColumnBoxes(
        warpedBinary: Mat,
        debugContext: android.content.Context?,
        warpedGray: Mat
    ): List<ColBox>? {
        val w = warpedBinary.cols()
        val h = warpedBinary.rows()

        // Border ink is 0 in warpedBinary — invert so it's the white foreground, bridge gaps,
        // drop speckle.
        val mask = Mat()
        Imgproc.threshold(warpedBinary, mask, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k7)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k3)
        k7.release(); k3.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        mask.release()

        // A column box: tall, ~1/5 of the width, below the header strip.
        val kept = contours.filter { c ->
            val br = Imgproc.boundingRect(c)
            br.height >= COLBOX_MIN_H_FRAC * h &&
                br.width >= COLBOX_MIN_W_FRAC * w && br.width <= COLBOX_MAX_W_FRAC * w &&
                br.y >= COLBOX_MIN_TOP_FRAC * h
        }
        val top5 = kept.sortedByDescending {
            val br = Imgproc.boundingRect(it); br.width.toDouble() * br.height
        }.take(5)

        val result = if (top5.size < 5) {
            Log.w(TAG, "findColumnBoxes: only ${top5.size}/5 column boxes — fixed-region fallback")
            null
        } else {
            top5.sortedBy { Imgproc.boundingRect(it).x }
                .map { ColBox(quadFromContour(it), MatOfPoint(*it.toArray())) }  // clone contour
        }

        contours.forEach { it.release() }
        if (result != null && debugContext != null) {
            saveColBoxesOverlay(debugContext, warpedGray, result.map { it.quad })
        }
        return result
    }

    /** 4 ordered corners ([TL,TR,BL,BR]) of a box contour: approxPolyDP when it
     *  cleanly reduces to a quad, else the minAreaRect (rotated) corners. */
    private fun quadFromContour(c: MatOfPoint): List<Point> {
        val c2f = MatOfPoint2f(*c.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
        val pts = approx.toArray()
        approx.release()
        val quad = if (pts.size == 4) {
            pts.toList()
        } else {
            val rr = Imgproc.minAreaRect(c2f)
            val box = Array(4) { Point() }
            rr.points(box)
            box.toList()
        }
        c2f.release()
        return orderQuad(quad)
    }

    /** Orders 4 arbitrary points as [topLeft, topRight, bottomLeft, bottomRight]. */
    private fun orderQuad(pts: List<Point>): List<Point> {
        val bySum = pts.sortedBy { it.x + it.y }
        val byDiff = pts.sortedBy { it.x - it.y }
        return listOf(bySum.first(), byDiff.last(), byDiff.first(), bySum.last())
    }

    /** Rectifies a column by following its left/right border curves (tall box → horizontal
     *  stretch). See [CurveRectifier]. Null when the edges can't be traced (caller falls back
     *  to the 4-corner warp). */
    private fun curveRectifyColumn(
        warpedGray: Mat, warpedBinary: Mat, contour: MatOfPoint, subW: Int, subH: Int
    ): Pair<Mat, Mat>? =
        curveRectifier.rectify(warpedGray, warpedBinary, contour, subW, subH, vertical = false)

    private fun saveColBoxesOverlay(
        context: android.content.Context, warpedGray: Mat, quads: List<List<Point>>
    ) {
        val overlay = Mat()
        Imgproc.cvtColor(warpedGray, overlay, Imgproc.COLOR_GRAY2BGR)
        for ((i, q) in quads.withIndex()) {
            // q is [TL,TR,BL,BR]; draw the boundary TL→TR→BR→BL.
            val poly = listOf(q[0], q[1], q[3], q[2])
            for (j in 0 until 4) {
                Imgproc.line(overlay, poly[j], poly[(j + 1) % 4], Scalar(0.0, 220.0, 0.0), 3)
            }
            Imgproc.putText(overlay, "${i + 1}", q[0],
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, Scalar(255.0, 0.0, 0.0), 3)
        }
        DebugImageSaver.saveMat(context, overlay, "answer_col_boxes")
        overlay.release()
    }

    private fun readAnswerGrid(
        warpedGray: Mat,
        warpedBinary: Mat,
        debugContext: android.content.Context?
    ): String? {
        val sb = StringBuilder(100)
        val subWarpW = SheetBlueprint.ANSWER_COL_WARP_W
        val subWarpH = SheetBlueprint.ANSWER_COL_WARP_H
        val cellW = (SheetBlueprint.ANSWER_CELL_W_FRAC * subWarpW).toInt().coerceAtLeast(4)
        val cellH = (SheetBlueprint.ANSWER_CELL_H_FRAC * subWarpH).toInt().coerceAtLeast(4)

        // Full-warp decision overlay, accumulated across the 5 columns
        val decisionOverlay: Mat? = if (debugContext != null) {
            Mat().also { Imgproc.cvtColor(warpedGray, it, Imgproc.COLOR_GRAY2BGR) }
        } else null

        // Global pass: the 5 real column boxes (quad + contour) in full-warp coords.
        val globalBoxes = if (ENABLE_GLOBAL_COL_BOXES)
            findColumnBoxes(warpedBinary, debugContext, warpedGray) else null

        // ── Phase A: locate + detect each column's grid. Measurement is deferred
        //    so the rows can be reconciled across columns first (phase B). ──
        val keepMats = debugContext != null
        val cols = ArrayList<ColState>(5)
        for (col in 0..4) {
            val subGray:   Mat
            val subBinary: Mat
            val dstCorners: List<Point>

            val box = globalBoxes?.getOrNull(col)
            if (box != null) {
                // Rectify from the globally-detected box (keyed on the printed border, so
                // immune to whitespace / right-edge shadow). Follow the border curves to undo
                // a bow; fall back to a 4-corner warp when the edges can't be traced.
                val curved = if (ENABLE_CURVE_RECTIFY)
                    curveRectifyColumn(warpedGray, warpedBinary, box.contour, subWarpW, subWarpH)
                else null
                if (curved != null) {
                    subGray = curved.first
                    subBinary = curved.second
                } else {
                    subGray   = perspectiveCorrector.warp(warpedGray,   box.quad, subWarpW, subWarpH)
                    subBinary = perspectiveCorrector.warp(warpedBinary, box.quad, subWarpW, subWarpH,
                        Imgproc.INTER_NEAREST)
                }
                dstCorners = box.quad
            } else {
            val region   = SheetBlueprint.answerColSearchRegions[col]
            val xStart   = (region.first.start           * SheetBlueprint.ANSWER_WARP_W).toInt()
            val xEnd     = (region.first.endInclusive    * SheetBlueprint.ANSWER_WARP_W).toInt()
                .coerceAtMost(SheetBlueprint.ANSWER_WARP_W)
            val yStart   = (region.second.start          * SheetBlueprint.ANSWER_WARP_H).toInt()
            val yEnd     = (region.second.endInclusive   * SheetBlueprint.ANSWER_WARP_H).toInt()
                .coerceAtMost(SheetBlueprint.ANSWER_WARP_H)
            val cropW    = (xEnd - xStart).coerceAtLeast(1)
            val cropH    = (yEnd - yStart).coerceAtLeast(1)

            val cropGray   = Mat(warpedGray,   Rect(xStart, yStart, cropW, cropH))
            val cropBinary = Mat(warpedBinary, Rect(xStart, yStart, cropW, cropH))

            // Perspective-warp the drawn box's quad: a crop + resize would only scale a
            // slanted box, leaving the bubbles on a tilted locus the strip fit drifts off.
            // Strict quad first; retry relaxed on a bowed outer column so it can still deskew
            // rather than fall to a plain resize (the col1/col5 drift source).
            val quadLocal = boxFinder.findBoxQuad(cropGray, tall = true)
                ?: boxFinder.findBoxQuad(cropGray, tall = true, strict = false)
            if (quadLocal != null) {
                subGray   = perspectiveCorrector.warp(cropGray,   quadLocal, subWarpW, subWarpH)
                subBinary = perspectiveCorrector.warp(cropBinary, quadLocal, subWarpW, subWarpH,
                    Imgproc.INTER_NEAREST)
                dstCorners = quadLocal.map { Point(it.x + xStart, it.y + yStart) }
            } else {
                subGray   = Mat()
                subBinary = Mat()
                Imgproc.resize(cropGray,   subGray,   Size(subWarpW.toDouble(), subWarpH.toDouble()))
                Imgproc.resize(cropBinary, subBinary, Size(subWarpW.toDouble(), subWarpH.toDouble()),
                    0.0, 0.0, Imgproc.INTER_NEAREST)
                dstCorners = listOf(
                    Point(xStart.toDouble(),           yStart.toDouble()),
                    Point((xStart + cropW).toDouble(), yStart.toDouble()),
                    Point(xStart.toDouble(),           (yStart + cropH).toDouble()),
                    Point((xStart + cropW).toDouble(), (yStart + cropH).toDouble())
                )
            }
            cropGray.release()
            cropBinary.release()
            }

            // Axis-aligned bbox of the source quad — approximate framing for the cross-column
            // row reconcile (gross-offset guard only).
            val dxs = dstCorners.map { it.x }
            val dys = dstCorners.map { it.y }
            val cropOffX = dxs.min().toInt()
            val cropOffY = dys.min().toInt()
            val finalCropW = (dxs.max() - dxs.min()).toInt().coerceAtLeast(1)
            val finalCropH = (dys.max() - dys.min()).toInt().coerceAtLeast(1)

            val detectedCells = answerColumnDetector.detect(subBinary, subWarpW, subWarpH)
            if (detectedCells == null) {
                Log.w(TAG, "AnswerColumnDetector failed for column $col — using blueprint fallback")
            }
            val cells = detectedCells ?: blueprintFallback(subWarpW, subWarpH)

            // Bulk pixel access; per-cell submats would be 100s of JNI calls.
            val binPx = ByteArray(subWarpW * subWarpH)
            val grayPx = ByteArray(subWarpW * subWarpH)
            subBinary.get(0, 0, binPx)
            subGray.get(0, 0, grayPx)

            cols.add(ColState(
                col, cells, detectedCells != null, binPx, grayPx,
                dstCorners, cropOffX, cropOffY, finalCropW, finalCropH,
                if (keepMats) subGray else null, if (keepMats) subBinary else null
            ))
            if (!keepMats) { subGray.release(); subBinary.release() }
        }
        globalBoxes?.forEach { it.contour.release() }

        // ── Phase B: reconcile row Y across columns (fixes the one-row grid slip). ──
        reconcileRowsAcrossColumns(cols, subWarpH)

        // ── Phase C: measure cells + decide, per column ──
        for (cs in cols) {
            val cells = cs.cells
            val binPx = cs.binPx
            val grayPx = cs.grayPx

            // Pass 1: measure all 20×5 cells
            val reads = ArrayList<CellRead>(100)
            val grid = Array(20) { arrayOfNulls<CellRead>(5) }
            for (row in 0..19) {
                for (choice in 0..4) {
                    // Per-cell x/y for curved paper; fall back to the straight line / row center.
                    val xCtr = (cells.cellXPositions.getOrNull(row)?.getOrNull(choice)
                        ?: cells.choiceXPositions.getOrNull(choice) ?: continue).toInt()
                    val yCtr = (cells.cellYPositions.getOrNull(row)?.getOrNull(choice)
                        ?: cells.rowYPositions.getOrNull(row) ?: continue).toInt()
                    val rx = (xCtr - cellW / 2).coerceIn(0, subWarpW - cellW)
                    val ry = (yCtr - cellH / 2).coerceIn(0, subWarpH - cellH)
                    val geomRect = Rect(rx, ry, cellW, cellH)
                    var rect = geomRect
                    if (ENABLE_CELL_LOCAL_SNAP) {
                        rect = refineCellRectLocal(binPx, subWarpW, subWarpH, geomRect, cellW, cellH)
                    }
                    val read = measureCell(binPx, grayPx, subWarpW, row, choice, rect)
                    if (rect.x != geomRect.x || rect.y != geomRect.y) read.geomRect = geomRect
                    reads.add(read)
                    grid[row][choice] = read
                }
            }

            // Pass 2: per-letter baselines (25th percentile over the 20 rows)
            val fillBase = FloatArray(5)
            val tintBase = FloatArray(5)
            val bgBase = FloatArray(5)
            for (choice in 0..4) {
                fillBase[choice] = percentile((0..19).mapNotNull { grid[it][choice]?.fill }.sorted(), 25f)
                tintBase[choice] = percentile((0..19).mapNotNull { grid[it][choice]?.tint }.sorted(), 25f)
                bgBase[choice]   = percentile((0..19).mapNotNull { grid[it][choice]?.bgmid }.sorted(), 25f)
            }

            // Pass 3: row-median centering + mass/tint/veto decision
            for (row in 0..19) {
                val fdevs = ArrayList<Float>(5)
                val tdevs = ArrayList<Float>(5)
                val bdevs = ArrayList<Float>(5)
                for (choice in 0..4) {
                    val r = grid[row][choice] ?: continue
                    fdevs.add(r.fill - fillBase[choice])
                    tdevs.add(r.tint - tintBase[choice])
                    bdevs.add(r.bgmid - bgBase[choice])
                }
                val fMed = median(fdevs)
                val tMed = median(tdevs)
                val bMed = median(bdevs)
                var maxFdev2 = 0f
                for (choice in 0..4) {
                    val r = grid[row][choice] ?: continue
                    r.fdev2 = (r.fill - fillBase[choice]) - fMed
                    r.tdev2 = (r.tint - tintBase[choice]) - tMed
                    r.bdev2 = (r.bgmid - bgBase[choice]) - bMed
                    if (r.fdev2 > maxFdev2) maxFdev2 = r.fdev2
                }
                val shadedChoices = mutableListOf<Int>()
                for (choice in 0..4) {
                    val r = grid[row][choice] ?: continue
                    val candidate = r.fdev2 >= ANSWER_FDEV2_MIN &&
                                    r.fdev2 >= ANSWER_DOMINANCE * maxFdev2
                    if (!candidate) continue
                    val tinted = r.tdev2 >= ANSWER_TINT_MIN
                    val strokeVeto = r.ifill < ANSWER_VETO_IFILL_MAX && r.bdev2 < ANSWER_VETO_BG
                    if (tinted && !strokeVeto) {
                        r.shaded = true
                        shadedChoices.add(choice)
                    } else {
                        r.rejected = true
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Q${cs.col * 20 + row + 1} ${'A' + choice}: candidate rejected " +
                                "(fdev2=%.3f tdev2=%.3f bdev2=%.3f ifill=%.2f) — improper mark or noise"
                                    .format(r.fdev2, r.tdev2, r.bdev2, r.ifill))
                        }
                    }
                }
                sb.append(when (shadedChoices.size) {
                    0    -> '-'
                    1    -> 'A' + shadedChoices[0]
                    else -> '?'
                })
            }

            if (debugContext != null) {
                cs.subBinary?.let { DebugImageSaver.saveMat(debugContext, it, "answer_col${cs.col + 1}_binary") }
                cs.subGray?.let {
                    saveColumnSubwarpOverlay(debugContext, it, cs.col, cells, reads, cs.fromDetection)
                }
                if (decisionOverlay != null) {
                    drawColumnDecisions(decisionOverlay, cs.col, sb, reads,
                        cs.dstCorners, subWarpW, subWarpH)
                }
            }

            cs.subGray?.release()
            cs.subBinary?.release()
        }

        if (decisionOverlay != null && debugContext != null) {
            DebugImageSaver.saveMat(debugContext, decisionOverlay, "answer_warped_decisions")
            decisionOverlay.release()
        }

        return if (sb.length == 100) sb.toString() else null
    }

    /**
     * Cross-column row-Y consistency guard. All 5 columns share the physical rows, so their
     * full-warp Y should agree (curvature shifts them only a few px). A subgroup off by ≈one
     * row stride — AnswerColumnDetector's grid fit slipped a row — is a one-stride outlier no
     * single-column check catches; detect it against the cross-column median and shift it
     * back. Only acts on gross (>0.5 stride) offsets, leaving genuine curvature untouched.
     */
    private fun reconcileRowsAcrossColumns(cols: List<ColState>, subWarpH: Int) {
        if (cols.size < 3) return
        val strideFull = SheetBlueprint.ANSWER_ROW_STRIDE_FRAC * SheetBlueprint.ANSWER_WARP_H
        fun syOf(cs: ColState) = cs.finalCropH.toDouble() / subWarpH
        fun fullY(cs: ColState, r: Int) = cs.cropOffY + cs.cells.rowYPositions[r] * syOf(cs)

        // Consensus computed once from the original positions; each outlier is then
        // corrected toward it (median tolerates up to two misaligned columns).
        val consensus = DoubleArray(20) { r -> medianD(cols.map { fullY(it, r) }) }
        val maxShiftFull = 2.0 * strideFull

        for (cs in cols) {
            val syc = syOf(cs)
            for (sg in 0..1) {
                val rows = (sg * 10) until (sg * 10 + 10)
                val delta = medianD(rows.map { fullY(cs, it) - consensus[it] })
                if (kotlin.math.abs(delta) <= 0.5 * strideFull) continue
                val shiftSub = (delta.coerceIn(-maxShiftFull, maxShiftFull) / syc).toFloat()
                for (r in rows) {
                    cs.cells.rowYPositions[r] -= shiftSub
                    val cy = cs.cells.cellYPositions[r]
                    for (si in cy.indices) cy[si] -= shiftSub
                }
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "RowReconcile: col${cs.col} subgroup${sg + 1} was ${delta.toInt()}px " +
                        "off the cross-column consensus (row stride≈${strideFull.toInt()}px) — " +
                        "shifted ${(-shiftSub).toInt()}px in sub-warp to realign")
                }
            }
        }
    }

    private fun medianD(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Final local correction (A2, flagged): nudges a placed cell [rect] onto the dark-mass
     * centroid of a tight window (the symmetric bracket+glyph centroid is the cell center,
     * shaded or not), mopping up residual bend at col1/col5. Returns [rect] unchanged when
     * the window lacks structure (mass gate); the shift is hard-clamped against a neighbour.
     */
    private fun refineCellRectLocal(
        binPx: ByteArray,
        subW: Int,
        subH: Int,
        rect: Rect,
        cellW: Int,
        cellH: Int
    ): Rect {
        val padX = (cellW * CELL_SNAP_SEARCH_FRAC_X).toInt()
        val padY = (cellH * CELL_SNAP_SEARCH_FRAC_Y).toInt()
        val wx0 = (rect.x - padX).coerceAtLeast(0)
        val wy0 = (rect.y - padY).coerceAtLeast(0)
        val wx1 = (rect.x + rect.width + padX).coerceAtMost(subW)
        val wy1 = (rect.y + rect.height + padY).coerceAtMost(subH)
        if (wx1 - wx0 < 4 || wy1 - wy0 < 4) return rect

        var mass = 0L
        var sumX = 0L
        var sumY = 0L
        for (y in wy0 until wy1) {
            val base = y * subW
            for (x in wx0 until wx1) {
                if ((binPx[base + x].toInt() and 0xFF) < 128) {
                    mass++; sumX += x; sumY += y
                }
            }
        }
        val winArea = (wx1 - wx0).toLong() * (wy1 - wy0)
        if (mass < winArea * CELL_SNAP_MIN_MASS_FRAC) return rect

        val cx = sumX.toFloat() / mass
        val cy = sumY.toFloat() / mass
        val curCx = rect.x + rect.width / 2f
        val curCy = rect.y + rect.height / 2f
        val maxDx = cellW * CELL_SNAP_MAX_FRAC_X
        val maxDy = cellH * CELL_SNAP_MAX_FRAC_Y
        val dx = (cx - curCx).coerceIn(-maxDx, maxDx)
        val dy = (cy - curCy).coerceIn(-maxDy, maxDy)
        val nx = (rect.x + dx).toInt().coerceIn(0, subW - rect.width)
        val ny = (rect.y + dy).toInt().coerceIn(0, subH - rect.height)
        return Rect(nx, ny, rect.width, rect.height)
    }

    /**
     * Measures one cell from the bulk arrays: fill (full rect), ifill (24% interior), tint
     * (p25 interior darkness), bgmid (darkness at the middle of the background fraction,
     * q = clamp((1−ifill)·50, 5, 40)) — paper-level for strokes, tinted for shades.
     */
    private fun measureCell(
        binPx: ByteArray,
        grayPx: ByteArray,
        stride: Int,
        row: Int,
        choice: Int,
        rect: Rect
    ): CellRead {
        fun darkFrac(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            var dark = 0
            var total = 0
            for (y in y0 until y1) {
                val base = y * stride
                for (x in x0 until x1) {
                    if ((binPx[base + x].toInt() and 0xFF) < 128) dark++
                    total++
                }
            }
            return if (total > 0) dark.toFloat() / total else 0f
        }

        val fill = darkFrac(rect.x + 1, rect.y + 1,
            rect.x + rect.width - 1, rect.y + rect.height - 1)

        val ix = (rect.width * ANSWER_INTERIOR_INSET).toInt()
        val iy = (rect.height * ANSWER_INTERIOR_INSET).toInt()
        val x0 = rect.x + ix
        val y0 = rect.y + iy
        val x1 = rect.x + rect.width - ix
        val y1 = rect.y + rect.height - iy
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            return CellRead(row, choice, rect, fill, 0f, 0f, 0f)
        }

        val ifill = darkFrac(x0, y0, x1, y1)
        val darkness = FloatArray((x1 - x0) * (y1 - y0))
        var i = 0
        for (y in y0 until y1) {
            val base = y * stride
            for (x in x0 until x1) {
                darkness[i++] = 1f - (grayPx[base + x].toInt() and 0xFF) / 255f
            }
        }
        darkness.sort()
        val sorted = darkness.toList()
        val tint = percentile(sorted, ANSWER_TINT_PERCENTILE)
        val bgQ = ((1f - ifill) * 50f).coerceIn(5f, 40f)
        val bgmid = percentile(sorted, bgQ)
        return CellRead(row, choice, rect, fill, ifill, tint, bgmid)
    }

    /** Percentile [q] (0–100) of a pre-sorted list, linear interpolation. */
    private fun percentile(sorted: List<Float>, q: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val pos = q / 100f * (sorted.size - 1)
        val lo = pos.toInt().coerceIn(0, sorted.size - 1)
        val frac = pos - lo
        return if (lo + 1 < sorted.size) sorted[lo] * (1 - frac) + sorted[lo + 1] * frac
               else sorted[lo]
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    private fun blueprintFallback(subWarpW: Int, subWarpH: Int): ColumnCells {
        val h = subWarpH.toFloat()
        val rowYs = FloatArray(20) { i ->
            val extraGap = if (i >= 10) SheetBlueprint.ANSWER_SUBGROUP_GAP_FRAC * h else 0f
            SheetBlueprint.ANSWER_ROW_FIRST_Y_FRAC * h + i * SheetBlueprint.ANSWER_ROW_STRIDE_FRAC * h + extraGap
        }
        val choiceXs = FloatArray(5) { c -> SheetBlueprint.answerChoiceXFracs[c] * subWarpW }
        return ColumnCells(
            rowYPositions    = rowYs,
            choiceXPositions = choiceXs,
            cellYPositions   = Array(20) { row -> FloatArray(5) { rowYs[row] } },
            cellXPositions   = Array(20) { FloatArray(5) { c -> choiceXs[c] } },
            rowSnapped       = BooleanArray(20)
        )
    }

    // ── Debug helpers ─────────────────────────────────────────────────────

    private fun saveAnswerCapturedOverlay(
        context: android.content.Context,
        bgrMat: Mat,
        answerMarkers: List<Point>
    ) {
        val labels = listOf("TM", "TR", "BM", "BR")
        val colors = listOf(
            Scalar(0.0, 255.0, 0.0),    // TM green
            Scalar(0.0, 0.0, 255.0),    // TR red
            Scalar(0.0, 200.0, 255.0),  // BM orange
            Scalar(255.0, 0.0, 255.0)   // BR magenta
        )
        // Opaque 3-channel BGR canvas: a 3-element Scalar on the RGBA capture leaves alpha=0,
        // saving strokes transparent. Convert before drawing.
        val ann = Mat()
        when (bgrMat.channels()) {
            4    -> Imgproc.cvtColor(bgrMat, ann, Imgproc.COLOR_RGBA2BGR)
            1    -> Imgproc.cvtColor(bgrMat, ann, Imgproc.COLOR_GRAY2BGR)
            else -> bgrMat.copyTo(ann)
        }
        for (i in answerMarkers.indices) {
            val pt = answerMarkers[i]
            val color = colors[i]
            Imgproc.drawMarker(ann, pt, color, Imgproc.MARKER_CROSS, 40, 4)
            Imgproc.putText(ann, labels[i], Point(pt.x + 10, pt.y - 15),
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, color, 3)
        }
        DebugImageSaver.saveMat(context, ann, "answer_captured_markers")
        ann.release()
    }

    private fun saveAnswerWarpedBoxes(
        context: android.content.Context,
        warpedGray: Mat,
        subjectBlobs: List<Rect>,
        colSearchRegions: Array<Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>>>,
        colBoxResults: List<BoxFinder.BoxResult?>
    ) {
        val w = warpedGray.cols()
        val h = warpedGray.rows()
        val bgr = Mat()
        Imgproc.cvtColor(warpedGray, bgr, Imgproc.COLOR_GRAY2BGR)

        // Subject search strip: yellow
        val stripY0 = (SheetBlueprint.SUBJECT_STRIP_Y_FRAC_START * h).toInt()
        val stripY1 = (SheetBlueprint.SUBJECT_STRIP_Y_FRAC_END   * h).toInt()
        Imgproc.rectangle(bgr,
            Point(0.0, stripY0.toDouble()), Point(w.toDouble(), stripY1.toDouble()),
            Scalar(0.0, 255.0, 255.0), 2)

        // Subject blob rects: cyan
        for (rect in subjectBlobs) {
            Imgproc.rectangle(bgr, rect, Scalar(255.0, 255.0, 0.0), 2)
        }

        // Column search regions: magenta
        for (i in 0..4) {
            val region = colSearchRegions[i]
            val x0 = (region.first.start           * w).toInt()
            val x1 = (region.first.endInclusive    * w).toInt().coerceAtMost(w)
            val y0 = (region.second.start          * h).toInt()
            val y1 = (region.second.endInclusive   * h).toInt().coerceAtMost(h)
            Imgproc.rectangle(bgr,
                Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()),
                Scalar(255.0, 0.0, 255.0), 2)
        }

        // Column box results: green rect if found, red X across search region if null
        for (i in 0..4) {
            val colBox = colBoxResults.getOrNull(i)
            if (colBox != null) {
                Imgproc.rectangle(bgr, colBox.boxRect, Scalar(0.0, 255.0, 0.0), 3)
            } else {
                val region = colSearchRegions[i]
                val x0 = (region.first.start           * w).toInt()
                val x1 = (region.first.endInclusive    * w).toInt().coerceAtMost(w)
                val y0 = (region.second.start          * h).toInt()
                val y1 = (region.second.endInclusive   * h).toInt().coerceAtMost(h)
                Imgproc.line(bgr,
                    Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()),
                    Scalar(0.0, 0.0, 255.0), 2)
                Imgproc.line(bgr,
                    Point(x1.toDouble(), y0.toDouble()), Point(x0.toDouble(), y1.toDouble()),
                    Scalar(0.0, 0.0, 255.0), 2)
            }
        }

        DebugImageSaver.saveMat(context, bgr, "answer_warped_boxes")
        bgr.release()
    }

    /**
     * Subject strip overlay: red = read as shaded, green = blank (located
     * dynamically), orange = bubble not located (blueprint fallback position).
     * Annotated with interior fill and tint values.
     */
    private fun saveSubjectSubwarpOverlay(
        context: android.content.Context,
        subjectSubWarp: Mat,
        reads: List<SubjectRead>,
        stripY0: Int
    ) {
        DebugImageSaver.saveMat(context, subjectSubWarp, "answer_subject_subwarp")
        val overlay = Mat()
        Imgproc.cvtColor(subjectSubWarp, overlay, Imgproc.COLOR_GRAY2BGR)
        val stripH = subjectSubWarp.rows()
        for (r in reads) {
            val local = Rect(r.rect.x,
                (r.rect.y - stripY0).coerceIn(0, (stripH - r.rect.height).coerceAtLeast(0)),
                r.rect.width, r.rect.height)
            val color = when {
                r.shaded     -> Scalar(0.0, 0.0, 255.0)
                !r.located   -> Scalar(0.0, 165.0, 255.0)
                else         -> Scalar(0.0, 220.0, 0.0)
            }
            Imgproc.rectangle(overlay, local, color, 2)
            Imgproc.putText(overlay, "f%.2f t%.2f".format(r.ifill, r.tint),
                Point(local.x.toDouble(), (local.y - 4).coerceAtLeast(10).toDouble()),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, color, 1)
        }
        DebugImageSaver.saveMat(context, overlay, "answer_subject_subwarp_cells_overlay")
        overlay.release()
    }

    /** Overlay for the curve-rectified subject path: rects are already in sub-warp
     *  coords (no strip offset). Same filenames + color scheme as the strip overlay. */
    private fun saveSubjectRectifiedOverlay(
        context: android.content.Context,
        subWarp: Mat,
        reads: List<SubjectRead>
    ) {
        DebugImageSaver.saveMat(context, subWarp, "answer_subject_subwarp")
        val overlay = Mat()
        Imgproc.cvtColor(subWarp, overlay, Imgproc.COLOR_GRAY2BGR)
        for (r in reads) {
            val color = when {
                r.shaded   -> Scalar(0.0, 0.0, 255.0)
                !r.located -> Scalar(0.0, 165.0, 255.0)
                else       -> Scalar(0.0, 220.0, 0.0)
            }
            Imgproc.rectangle(overlay, r.rect, color, 2)
            Imgproc.putText(overlay, "f%.2f t%.2f".format(r.ifill, r.tint),
                Point(r.rect.x.toDouble(), (r.rect.y - 4).coerceAtLeast(10).toDouble()),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, color, 1)
        }
        DebugImageSaver.saveMat(context, overlay, "answer_subject_subwarp_cells_overlay")
        overlay.release()
    }

    /**
     * Per-column diagnostic overlay on the grayscale sub-warp:
     *  - blue horizontal lines  = detected structural lines (borders/divider)
     *  - green row lines        = row snapped to a detected candidate
     *  - orange row lines       = row interpolated from the grid fit
     *  - blue vertical lines    = choice x-centers
     *  - red cell rect + value  = cell read as shaded (fill ratio annotated)
     *  - thin gray cell rect    = cell read as blank
     * Title bar states whether positions came from detection or blueprint fallback.
     */
    private fun saveColumnSubwarpOverlay(
        context: android.content.Context,
        colSubWarp: Mat,
        colIndex: Int,
        cells: ColumnCells,
        reads: List<CellRead>,
        fromDetection: Boolean
    ) {
        val subW = colSubWarp.cols()
        val subH = colSubWarp.rows()

        val overlay = Mat()
        Imgproc.cvtColor(colSubWarp, overlay, Imgproc.COLOR_GRAY2BGR)

        for (sy in cells.structuralYs) {
            Imgproc.line(overlay, Point(0.0, sy.toDouble()), Point(subW.toDouble(), sy.toDouble()),
                Scalar(255.0, 128.0, 0.0), 2)
        }
        for (row in 0..19) {
            val y = cells.rowYPositions.getOrNull(row)?.toDouble() ?: continue
            val snapped = cells.rowSnapped.getOrNull(row) ?: false
            val color = if (snapped) Scalar(0.0, 220.0, 0.0) else Scalar(0.0, 165.0, 255.0)
            Imgproc.line(overlay, Point(0.0, y), Point(subW.toDouble(), y), color, 1)
        }
        for (choice in 0..4) {
            val x = cells.choiceXPositions.getOrNull(choice)?.toDouble() ?: continue
            Imgproc.line(overlay, Point(x, 0.0), Point(x, subH.toDouble()),
                Scalar(255.0, 0.0, 0.0), 1)
        }
        for (r in reads) {
            // Pre-snap geometric rect (magenta), present only when A2 moved this cell.
            r.geomRect?.let {
                Imgproc.rectangle(overlay, it, Scalar(255.0, 0.0, 255.0), 1)
            }
            when {
                r.shaded -> {
                    Imgproc.rectangle(overlay, r.rect, Scalar(0.0, 0.0, 255.0), 2)
                    Imgproc.putText(overlay, "%.2f".format(r.tdev2),
                        Point((r.rect.x + r.rect.width + 2).toDouble(), (r.rect.y + r.rect.height).toDouble()),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.35, Scalar(0.0, 0.0, 255.0), 1)
                }
                r.rejected -> {
                    // Candidate by mass, rejected as improper mark (X/slash/ellipse) or noise
                    Imgproc.rectangle(overlay, r.rect, Scalar(0.0, 165.0, 255.0), 2)
                    Imgproc.putText(overlay, "t%.2f".format(r.tdev2),
                        Point((r.rect.x + r.rect.width + 2).toDouble(), (r.rect.y + r.rect.height).toDouble()),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.35, Scalar(0.0, 165.0, 255.0), 1)
                }
                else -> Imgproc.rectangle(overlay, r.rect, Scalar(160.0, 160.0, 160.0), 1)
            }
        }
        val title = if (fromDetection) "DETECTED" else "FALLBACK"
        Imgproc.putText(overlay, title, Point(6.0, 22.0),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.6,
            if (fromDetection) Scalar(0.0, 220.0, 0.0) else Scalar(0.0, 165.0, 255.0), 2)

        DebugImageSaver.saveMat(context, overlay, "answer_col${colIndex + 1}_subwarp_overlay")
        overlay.release()
    }

    /**
     * Draws one column's cell decisions onto the accumulated full-warp overlay,
     * mapping sub-warp rects back through the crop/resize. Each question row is
     * annotated with its detected answer ('-' blank, 'A'..'E', '?' multi).
     */
    private fun drawColumnDecisions(
        overlay: Mat,
        col: Int,
        answersSoFar: StringBuilder,
        reads: List<CellRead>,
        dstCorners: List<Point>,
        subWarpW: Int, subWarpH: Int
    ) {
        // Sub-warp → full-warp via the inverse rectifying transform (dstCorners as
        // destination, canonical rect as source), so the overlay sits on the real bubbles.
        val srcQuad = MatOfPoint2f(
            Point(0.0, 0.0), Point(subWarpW.toDouble(), 0.0),
            Point(0.0, subWarpH.toDouble()), Point(subWarpW.toDouble(), subWarpH.toDouble())
        )
        val dstQuad = MatOfPoint2f(dstCorners[0], dstCorners[1], dstCorners[2], dstCorners[3])
        val tf = Imgproc.getPerspectiveTransform(srcQuad, dstQuad)
        srcQuad.release(); dstQuad.release()
        fun mapRect(r: Rect): Rect {
            val pts = MatOfPoint2f(
                Point(r.x.toDouble(), r.y.toDouble()),
                Point((r.x + r.width).toDouble(), r.y.toDouble()),
                Point(r.x.toDouble(), (r.y + r.height).toDouble()),
                Point((r.x + r.width).toDouble(), (r.y + r.height).toDouble())
            )
            val outPts = MatOfPoint2f()
            org.opencv.core.Core.perspectiveTransform(pts, outPts, tf)
            val a = outPts.toArray()
            pts.release(); outPts.release()
            val mxs = a.map { it.x }; val mys = a.map { it.y }
            return Rect(
                mxs.min().toInt(), mys.min().toInt(),
                (mxs.max() - mxs.min()).toInt().coerceAtLeast(1),
                (mys.max() - mys.min()).toInt().coerceAtLeast(1)
            )
        }

        val rowYs = DoubleArray(20)
        val rowX0 = DoubleArray(20) { Double.MAX_VALUE }
        for (r in reads) {
            val m = mapRect(r.rect)
            val color = when {
                r.shaded   -> Scalar(0.0, 0.0, 255.0)     // red: counted answer
                r.rejected -> Scalar(0.0, 165.0, 255.0)   // orange: improper mark rejected
                else       -> Scalar(0.0, 200.0, 0.0)     // green: blank
            }
            Imgproc.rectangle(overlay, m, color, if (r.shaded || r.rejected) 2 else 1)
            rowYs[r.row] = m.y + m.height.toDouble()
            if (m.x.toDouble() < rowX0[r.row]) rowX0[r.row] = m.x.toDouble()
        }
        for (row in 0..19) {
            val q = col * 20 + row
            val ch = answersSoFar.getOrNull(q) ?: continue
            if (rowX0[row] == Double.MAX_VALUE) continue
            val label = "${q + 1}:$ch"
            val color = when (ch) {
                '-'  -> Scalar(160.0, 160.0, 160.0)
                '?'  -> Scalar(0.0, 165.0, 255.0)
                else -> Scalar(0.0, 0.0, 255.0)
            }
            Imgproc.putText(overlay, label, Point(rowX0[row] - 58.0, rowYs[row] - 4.0),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, color, 1)
        }
        tf.release()
    }
}
