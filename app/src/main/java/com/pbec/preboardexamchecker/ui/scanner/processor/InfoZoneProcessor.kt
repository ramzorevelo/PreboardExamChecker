package com.pbec.preboardexamchecker.ui.scanner.processor

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.pbec.preboardexamchecker.BuildConfig
import com.pbec.preboardexamchecker.ui.scanner.DebugImageSaver
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Processes a Phase 1 (info zone) bitmap.
 *
 * Pipeline: BGR → gray → two CLAHE layers. Layer A (clip 3.0, tile 32×32) aggressively
 * normalizes wide shadow gradients for marker detection; Layer B (clip 2.0, tile 8×8)
 * preserves local contrast for cell reading. Layer B is warped to the 800×1300 info canvas,
 * then BoxFinder locates the STUDENT ID and TEST QUESTION SET boxes and a second warp
 * normalizes each into a fixed sub-canvas (see SheetBlueprint's two-level system), so cell
 * positions never drift. One adaptive binary per sub-canvas feeds BubbleReader.
 *
 * ID/TestSet reading uses the answer grid's fully-vs-not-fully rule: measure binary fill,
 * interior fill, tint (p25 interior darkness — graphite tints the paper between strokes, a
 * printed glyph or X/ø/slash mark doesn't) and background-mid, normalize against a per-group
 * blank baseline (column median for ID digits, the other cell for SET), then gate by mass,
 * tint, and a stroke veto. See readStudentId / readTestSet / measureCell.
 */
class InfoZoneProcessor(
    private val markerDetector: MarkerDetector = MarkerDetector(),
    private val perspectiveCorrector: PerspectiveCorrector = PerspectiveCorrector(),
    private val bubbleReader: BubbleReader = BubbleReader(),
    private val boxFinder: BoxFinder = BoxFinder(),
    private val dynamicCellDetector: DynamicCellDetector = DynamicCellDetector(),
    private val curveRectifier: CurveRectifier = CurveRectifier()
) {

    companion object {
        private const val TAG = "InfoZoneProcessor"
        // Rectify the ID / TEST SET boxes by following their border curves instead of a
        // 4-corner warp. Default OFF: on noisy info captures BoxFinder's contour
        // over-includes whitespace/shadow, mapping the box thinner and distorting the grid
        // non-uniformly — worse than the perspective warp's uniform over-framing. Re-enable
        // once the rectify keys on a tight printed-border silhouette.
        const val ENABLE_INFO_CURVE_RECTIFY = false

        // Fully-vs-not-fully shading constants, ported from the answer grid. Seeded from
        // Phase 2; tune on-device from the logged per-cell fdev/tdev/bdev.
        // Candidate by mass: fill deviation above the group baseline …
        private const val FDEV_MIN = 0.05f
        // … and within this fraction of the group's strongest fill deviation.
        private const val DOMINANCE = 0.60f
        // Proper shade: tint deviation (graphite between strokes) above baseline.
        private const val TINT_MIN = 0.08f
        // Stroke veto: a candidate whose background shows no tint is a drawn stroke
        // (X/slash), not a shade — unless the interior is near-solid (no background to sample).
        private const val VETO_BG = 0.04f
        private const val VETO_IFILL_MAX = 0.72f
        // Interior inset (per side) excluding the bracket frame; tint percentile.
        private const val INTERIOR_INSET = 0.24f
        private const val TINT_PERCENTILE = 25f
        // Coverage: interior IQR (cover − tint) above COVER_SPREAD_MAX is a printed glyph
        // fattened by local darkening, not a shade that uniformly covers the cell.
        // Lighting-invariant (uniform darkening shifts both percentiles together).
        private const val COVER_PERCENTILE = 75f
        private const val COVER_SPREAD_MAX = 0.30f
        // Blue-neutralization: pixels above this saturation (blue banners/borders) are
        // blanked to white in the ID/SET reading gray; black brackets and graphite are kept.
        private const val COLOR_SAT_THRESHOLD = 60

        // STUDENT ID box: tall grid in the lower two-thirds of the info zone.
        private val ID_BOX_X_RANGE = 0.15..0.85
        private val ID_BOX_Y_RANGE = 0.18..0.80

        // TEST QUESTION SET box: wide banner near the top.
        private val TESTSET_BOX_X_RANGE = 0.05..0.95
        private val TESTSET_BOX_Y_RANGE = 0.03..0.20

        // Edge-snap radius for a blueprint-fallback box (no border found). Wider than the
        // 14 px used when BoxFinder located the box, so the centered guess can still be
        // pulled onto a detectable border, but tight enough not to grab a neighbour.
        private const val FALLBACK_SNAP_RADIUS = 28

        // Outward padding before the second warp (see padBoxCorners). Bottom-weighted: the
        // bottom border is weakest and crops most often. Just enough to recover a cropped
        // last row without pulling in much below (the row fit anchors on digit 9).
        private const val BOX_PAD_TOP_FRAC = 0.03
        private const val BOX_PAD_BOTTOM_FRAC = 0.06
        private const val BOX_PAD_SIDE_FRAC = 0.03
    }

    sealed class Result {
        data class Success(val info: ScannedInfo) : Result()
        data class Error(val reason: String) : Result()
        /** The still's re-detected markers diverged from the live lock (WYSIWYG
         *  gate) — the caller should re-capture. See [process]'s validateMarkers. */
        data class Recapture(val reason: String) : Result()
    }

    fun process(
        bitmap: Bitmap,
        studentMap: Map<String, com.pbec.preboardexamchecker.data.models.Student>,
        cornerHints: Array<Point?>? = null,
        debugContext: android.content.Context? = null
    ): Result {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // Raw-BGR copy for debug overlays + the calibration corpus (the raw capture, not the
        // CLAHE marker layer, is what the overlays show and tools/annotate_phase2_markers.py
        // ROI calibration needs).
        val debugSrc = if (debugContext != null) src.clone() else null
        if (debugContext != null) DebugImageSaver.saveMat(debugContext, src, "info_captured")

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

        // Blue-neutralized gray for the ID + TEST SET sub-box reading. The banners and box
        // fills are printed solid blue, dark enough in plain gray to survive the adaptive
        // threshold, where DynamicCellDetector then clusters the banner as a bracket bar
        // (reading it as the Set-B bubble). Masking high-saturation pixels to white removes
        // them while keeping black brackets and graphite. Marker detection and BoxFinder keep
        // the unmodified gray, so the blue box border is still found.
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        hsv.release()
        val colorMask = Mat()
        Imgproc.threshold(hsvCh[1], colorMask, COLOR_SAT_THRESHOLD.toDouble(),
            255.0, Imgproc.THRESH_BINARY)
        hsvCh.forEach { it.release() }
        val grayNeutral = Mat()
        gray.copyTo(grayNeutral)
        grayNeutral.setTo(Scalar(255.0), colorMask)
        colorMask.release()
        src.release()

        // ── Layer A: CLAHE(3.0, 32×32) for marker detection ────────────────
        val markerClahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
        val markerEnhanced = Mat()
        markerClahe.apply(gray, markerEnhanced)

        // Invert so dark markers → white blobs.
        val markerBinary = Mat()
        Imgproc.threshold(markerEnhanced, markerBinary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        // ── Layer B: CLAHE(2.0, 8×8) for cell reading ────────────────────────
        val cellClahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val cellEnhanced = Mat()
        cellClahe.apply(gray, cellEnhanced)
        // Same CLAHE on the blue-neutralized gray, used only for the ID/SET sub-box content.
        val cellEnhancedNeutral = Mat()
        cellClahe.apply(grayNeutral, cellEnhancedNeutral)
        gray.release()
        grayNeutral.release()

        // markerEnhanced doubles as the cornerSubPix reference (aligned with markerBinary,
        // CLAHE-sharpened for clean gradients). Calibrated info-zone ROIs restrict the search
        // to the left/centre block, excluding the answer grid on the right — the source of
        // wrong-region warps when a shaded answer bubble won TM under the old midpoint
        // quadrants. cornerHints are NOT passed to detect(); we keep the robust ROI detection
        // then reconcile against the live lock below, since the still re-detection is flaky.
        val rawMarkers = markerDetector.detect(markerBinary, markerEnhanced,
            expectedQuadAspect = 920.0 / 1596.0,
            useCalibRois = true,
            roiFrac = MarkerDetector.MARKER_ROI_FRAC_PHASE1)
        val markerImgW = markerBinary.cols()
        val markerImgH = markerBinary.rows()
        markerBinary.release()

        // Live-lock hints → still pixels, each refined onto the actual black square's centre
        // (the guide centroid can sit a few px off).
        val hintPx: List<Point?>? = cornerHints?.map { h ->
            h?.let { markerDetector.refineToNearestSquare(
                markerEnhanced, Point(it.x * markerImgW, it.y * markerImgH), markerImgW) }
        }
        if (hintPx != null && rawMarkers != null) {
            var maxDiv = 0.0
            for (q in rawMarkers.indices) hintPx.getOrNull(q)?.let {
                maxDiv = maxOf(maxDiv, Math.hypot(rawMarkers[q].x - it.x, rawMarkers[q].y - it.y) / markerImgW)
            }
            Log.w(TAG, "Marker WYSIWYG raw divergence=%.3f (gate %.3f)".format(
                maxDiv, MarkerDetector.MARKER_HINT_MAX_DIVERGENCE_FRAC))
        }
        // Per-corner correction against the refined live lock: keep good detected corners,
        // replace mis-picked / missing ones. Valid even when detect() returned null, as long
        // as all 4 hints are present.
        val markers = markerDetector.reconcileWithHints(rawMarkers, hintPx, markerImgW)

        // Draw the picks on the raw BGR capture (not the contrast-stretched CLAHE marker
        // layer) so a marker mis-pick is diagnosable.
        if (debugSrc != null && debugContext != null) {
            saveInfoCapturedMarkers(debugContext, debugSrc, markers, hintPx)
            debugSrc.release()
        }
        markerEnhanced.release()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "PROCESSING: detect() markers = ${if (markers == null) "NULL (not found)" else "${markers.size} points: $markers"}")
        }

        if (markers == null) {
            cellEnhanced.release()
            cellEnhancedNeutral.release()
            return Result.Error("Could not find registration markers in info zone")
        }

        val result = readWarpedZone(cellEnhanced, cellEnhancedNeutral, markers, studentMap, debugContext)
        cellEnhanced.release()
        cellEnhancedNeutral.release()
        return result
    }

    /**
     * Warps [cellEnhanced] (plain CLAHE gray) and [cellEnhancedNeutral] (blue-neutralized)
     * to the canonical info zone using [markers] ([TL, TM, BL, BM]), then locates and reads
     * the STUDENT ID and TEST SET boxes. Shared by [process] and SingleCaptureProcessor.
     * Input Mats are borrowed — the caller releases them.
     */
    fun readWarpedZone(
        cellEnhanced: Mat,
        cellEnhancedNeutral: Mat,
        markers: List<Point>,
        studentMap: Map<String, com.pbec.preboardexamchecker.data.models.Student>,
        debugContext: android.content.Context? = null
    ): Result {
        val warpedGray = perspectiveCorrector.warp(
            cellEnhanced, markers, SheetBlueprint.INFO_W, SheetBlueprint.INFO_H
        )
        // Blue-neutralized counterpart; the ID/SET sub-box warps read from this, BoxFinder +
        // edge-snap stay on warpedGray.
        val warpedGrayNeutral = perspectiveCorrector.warp(
            cellEnhancedNeutral, markers, SheetBlueprint.INFO_W, SheetBlueprint.INFO_H
        )

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "PROCESSING: warpedGray dimensions = ${warpedGray.cols()}x${warpedGray.rows()} (target ${SheetBlueprint.INFO_W}x${SheetBlueprint.INFO_H})")
        }

        // Sanity check: a correctly warped image should have some bright pixels
        val totalPixels = SheetBlueprint.INFO_W * SheetBlueprint.INFO_H
        val brightPixels = Core.countNonZero(warpedGray)
        val lightFrac = brightPixels.toFloat() / totalPixels
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Warped grayscale bright-pixel fraction: ${"%.3f".format(lightFrac)}")
        }
        if (lightFrac < 0.10f) {
            warpedGray.release()
            warpedGrayNeutral.release()
            return Result.Error("BLURRY or underexposed")
        }

        // ── Locate STUDENT ID box, then sub-warp it to a fixed canonical size ──
        val idBox = boxFinder.findBox(
            warpedGray, label = "id", xFrac = ID_BOX_X_RANGE, yFrac = ID_BOX_Y_RANGE, tall = true,
            expectedW = SheetBlueprint.ID_BOX_TEMPLATE_W,
            expectedH = SheetBlueprint.ID_BOX_TEMPLATE_H,
            maxScaleFactor = 1.50,
            minScaleFactor = 0.50
        )
        val idCornersRaw = idBox?.corners ?: run {
            Log.w(TAG, "PROCESSING: STUDENT ID box not found by BoxFinder (xRange=$ID_BOX_X_RANGE " +
                "yRange=$ID_BOX_Y_RANGE) — using blueprint fallback position; shadow/curve likely " +
                "broke the border contour. Read is best-effort; verify on the review screen.")
            blueprintBoxCorners(warpedGray, ID_BOX_X_RANGE, ID_BOX_Y_RANGE,
                SheetBlueprint.ID_BOX_TEMPLATE_W, SheetBlueprint.ID_BOX_TEMPLATE_H)
        }
        if (BuildConfig.DEBUG && idBox != null) {
            Log.d(TAG, "PROCESSING: STUDENT ID box found = corners=${idBox.corners} boxRect=${idBox.boxRect} searchRoi=${idBox.searchRoi}")
        }
        val idBoxSnapped = snapCornersToEdges(idCornersRaw, warpedGray,
            snapRadius = if (idBox != null) 14 else FALLBACK_SNAP_RADIUS)
        // Pad outward (bottom-weighted): the weak bottom border otherwise snaps onto row 9's
        // bracket and crops it, stretching the sub-warp so the comb locks onto the
        // handwriting row instead of digit 0. The grid-fit re-finds exact rows inside.
        val idBoxRefinedCorners = padBoxCorners(idBoxSnapped, warpedGray.cols(), warpedGray.rows())
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EdgeSnap idBox: TL y ${idCornersRaw[0].y.toInt()} → ${idBoxRefinedCorners[0].y.toInt()}, " +
                "BR y ${idCornersRaw[3].y.toInt()} → ${idBoxRefinedCorners[3].y.toInt()} (padded)")
        }
        // Prefer a curve-following rectify from the border silhouette (undoes a bow); fall
        // back to the 4-corner warp when there's no contour or the edges can't be traced.
        val idBoxWarp = (if (ENABLE_INFO_CURVE_RECTIFY) idBox?.contour?.let {
            curveRectifier.rectifyGray(warpedGrayNeutral, it, SheetBlueprint.ID_BOX_W,
                SheetBlueprint.ID_BOX_H, vertical = true)
        } else null) ?: perspectiveCorrector.warp(
            warpedGrayNeutral, idBoxRefinedCorners, SheetBlueprint.ID_BOX_W, SheetBlueprint.ID_BOX_H
        )
        idBox?.contour?.release()
        val idBoxBinary = computeGlobalBinary(idBoxWarp, "id", debugContext)

        // Blueprint fractions drift when the sub-warp framing shifts or the paper bows;
        // DynamicCellDetector finds the actual cell centers per capture, nulls fall back.
        val dynamicIdCells = dynamicCellDetector.detectIdCells(idBoxWarp, SheetBlueprint.ID_BOX_W, SheetBlueprint.ID_BOX_H)
        if (dynamicIdCells != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "DynamicCellDetector: ID grid detected successfully — using dynamic centers")
            }
        } else {
            Log.w(TAG, "DynamicCellDetector: ID grid detection failed — falling back to SheetBlueprint constants")
        }
        if (BuildConfig.DEBUG && debugContext != null && dynamicIdCells != null) {
            saveDynamicIdCellsDebug(debugContext, idBoxBinary, dynamicIdCells)
        }

        // ── Locate TEST QUESTION SET box, then sub-warp it to a fixed canonical size ──
        val testBox = boxFinder.findBox(
            warpedGray, label = "testset", xFrac = TESTSET_BOX_X_RANGE, yFrac = TESTSET_BOX_Y_RANGE, tall = false,
            expectedW = SheetBlueprint.TESTSET_BOX_TEMPLATE_W,
            expectedH = SheetBlueprint.TESTSET_BOX_TEMPLATE_H,
            maxScaleFactor = 1.60,
            minScaleFactor = 0.50
        )
        val testCornersRaw = testBox?.corners ?: run {
            Log.w(TAG, "PROCESSING: TEST QUESTION SET box not found by BoxFinder — using blueprint " +
                "fallback position; top-of-sheet curl/shadow likely broke the border. SET read is " +
                "best-effort; verify on the review screen.")
            blueprintBoxCorners(warpedGray, TESTSET_BOX_X_RANGE, TESTSET_BOX_Y_RANGE,
                SheetBlueprint.TESTSET_BOX_TEMPLATE_W, SheetBlueprint.TESTSET_BOX_TEMPLATE_H)
        }
        val testBoxRefinedCorners = padBoxCorners(
            snapCornersToEdges(testCornersRaw, warpedGray,
                snapRadius = if (testBox != null) 14 else FALLBACK_SNAP_RADIUS),
            warpedGray.cols(), warpedGray.rows()
        )
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EdgeSnap testset: TL y ${testCornersRaw[0].y.toInt()} → ${testBoxRefinedCorners[0].y.toInt()}, " +
                "BR y ${testCornersRaw[3].y.toInt()} → ${testBoxRefinedCorners[3].y.toInt()}")
        }
        val testBoxWarp = (if (ENABLE_INFO_CURVE_RECTIFY) testBox?.contour?.let {
            curveRectifier.rectifyGray(warpedGrayNeutral, it, SheetBlueprint.TESTSET_BOX_W,
                SheetBlueprint.TESTSET_BOX_H, vertical = true)
        } else null) ?: perspectiveCorrector.warp(
            warpedGrayNeutral, testBoxRefinedCorners, SheetBlueprint.TESTSET_BOX_W, SheetBlueprint.TESTSET_BOX_H
        )
        testBox?.contour?.release()
        val testBoxBinary = computeGlobalBinary(testBoxWarp, "testset", debugContext)

        // ── Dynamically locate TestSet cell centers (blob detection) ─────────
        val dynamicTestSetCells = dynamicCellDetector.detectTestSetCells(testBoxWarp, SheetBlueprint.TESTSET_BOX_W, SheetBlueprint.TESTSET_BOX_H)
        if (dynamicTestSetCells != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "DynamicCellDetector: TestSet cells detected successfully — using dynamic centers")
            }
        } else {
            Log.w(TAG, "DynamicCellDetector: TestSet cell detection failed — falling back to SheetBlueprint constants")
        }
        if (BuildConfig.DEBUG && debugContext != null && dynamicTestSetCells != null) {
            saveDynamicTestSetCellsDebug(debugContext, testBoxBinary, dynamicTestSetCells)
        }

        if (BuildConfig.DEBUG && debugContext != null) {
            saveBubbleDebugCrops(idBoxWarp, debugContext)
        }

        // ── Read test set ────────────────────────────────────────────────────
        val (testSet, testSetDebugStates) = readTestSet(testBoxWarp, testBoxBinary, dynamicTestSetCells)
        Log.d(TAG, "Test set detected: $testSet")

        // ── Read student ID (column-relative) ────────────────────────────────
        val (studentId, idDebugStates) = readStudentId(idBoxWarp, idBoxBinary, dynamicIdCells)
        Log.d(TAG, "Student ID detected: $studentId")

        // Debug overlays. The cell overlays render the final gate decision, so reading must
        // happen first to produce idDebugStates / testSetDebugStates.
        if (debugContext != null) {
            DebugImageSaver.saveMat(debugContext, warpedGray, "info_warped")
            // Box-framing overlay: the final sub-warp corners, green when BoxFinder located
            // the border and orange on blueprint fallback, so a weak border is visible even
            // when saveBoxDetectionOverlay's both-found path doesn't run.
            saveBoxCornersOverlay(
                debugContext, warpedGray,
                idBoxRefinedCorners, idBox != null,
                testBoxRefinedCorners, testBox != null
            )
            if (idBox != null && testBox != null) {
                saveBoxDetectionOverlay(
                    debugContext, warpedGray, idBox, idBoxRefinedCorners, testBox, testBoxRefinedCorners
                )
            }
            if (idDebugStates != null) {
                saveGateOverlay(debugContext, idBoxWarp, "id_box_subwarp", idCellRects(dynamicIdCells), idDebugStates)
            } else {
                saveCellOverlay(debugContext, idBoxWarp, "id_box_subwarp", idCellRects(dynamicIdCells))
            }
            if (testSetDebugStates != null) {
                saveGateOverlay(debugContext, testBoxWarp, "testset_box_subwarp", testSetCellRects(dynamicTestSetCells), testSetDebugStates)
            } else {
                saveCellOverlay(debugContext, testBoxWarp, "testset_box_subwarp", testSetCellRects(dynamicTestSetCells))
            }
            logIdColumnFills(idBoxBinary)
        }

        warpedGray.release()
        warpedGrayNeutral.release()
        idBoxWarp.release()
        idBoxBinary.release()
        testBoxWarp.release()
        testBoxBinary.release()

        val resolvedStudent = studentMap[studentId]
        return Result.Success(ScannedInfo(
            testSet = testSet,
            studentId = studentId,
            resolvedStudent = resolvedStudent
        ))
    }

    // ── Global binary for cell reading (replaces per-cell Otsu) ───────────

    /**
     * Binarizes the whole [subWarp]: CLAHE, an adaptive Gaussian threshold, then a light 3×3
     * close. BubbleReader crops cells from this shared Mat rather than re-thresholding each.
     * Adaptive (vs global Otsu) compares each pixel to its local mean, so a shadow gradient
     * across the box no longer pushes a region past one global threshold. Printed digit
     * glyphs survive but are rejected downstream by the tint gate, not erased here.
     */
    private fun computeGlobalBinary(subWarp: Mat, label: String, debugContext: android.content.Context?): Mat {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(subWarp, enhanced)
        val binary = Mat()
        val block = minOf(51, (minOf(subWarp.cols(), subWarp.rows()) / 2) or 1).coerceAtLeast(3)
        Imgproc.adaptiveThreshold(enhanced, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 8.0)
        enhanced.release()

        // Save the pre-close binary too, to compare the raw adaptive result (thin/disconnected
        // borders) against the answer-warp pipeline when investigating noisy box detection.
        if (BuildConfig.DEBUG && debugContext != null) {
            DebugImageSaver.saveMat(debugContext, binary, "binary_before_morph_close_$label")
        }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
        binary.release()
        kernel.release()

        if (BuildConfig.DEBUG && debugContext != null) {
            DebugImageSaver.saveMat(debugContext, closed, "binary_after_morph_close_$label")
        }
        return closed
    }

    // ── Edge-snap corner refinement (post-processes BoxFinder's output) ────

    /**
     * Best-effort box rectangle ([TL, TR, BL, BR]) when [BoxFinder] can't find the printed
     * border. The first warp already places the info zone canonically, so the box sits at a
     * known location: centre the reference-sized template in its search ROI. The caller's
     * [snapCornersToEdges] (wider radius) + dynamic cell detector still correct a detectable
     * border on top; otherwise the read is approximate and verified on the review screen.
     */
    private fun blueprintBoxCorners(
        warpedGray: Mat,
        xFrac: ClosedFloatingPointRange<Double>,
        yFrac: ClosedFloatingPointRange<Double>,
        templateW: Int,
        templateH: Int
    ): List<Point> {
        val canvasW = warpedGray.cols()
        val canvasH = warpedGray.rows()
        val cx = (xFrac.start + xFrac.endInclusive) / 2.0 * canvasW
        val cy = (yFrac.start + yFrac.endInclusive) / 2.0 * canvasH
        val left   = (cx - templateW / 2.0).coerceIn(0.0, (canvasW - 1).toDouble())
        val right  = (cx + templateW / 2.0).coerceIn(0.0, (canvasW - 1).toDouble())
        val top    = (cy - templateH / 2.0).coerceIn(0.0, (canvasH - 1).toDouble())
        val bottom = (cy + templateH / 2.0).coerceIn(0.0, (canvasH - 1).toDouble())
        return listOf(
            Point(left, top), Point(right, top),
            Point(left, bottom), Point(right, bottom)
        )
    }

    /**
     * Expands a box quad [TL, TR, BL, BR] outward along its local axes by BOX_PAD_*_FRAC,
     * clamped to the canvas. Bottom-weighted: the bottom border carries the least ink, so
     * [snapCornersToEdges] most often pulls it onto the row-9 bracket and crops the last
     * digit row. The grid-fit re-locates exact cells, absorbing the framing shift.
     */
    private fun padBoxCorners(corners: List<Point>, canvasW: Int, canvasH: Int): List<Point> {
        if (corners.size != 4) return corners
        val tl = corners[0]; val tr = corners[1]; val bl = corners[2]; val br = corners[3]
        // Local vertical (top→bottom) and horizontal (left→right) unit axes.
        var vdx = (bl.x + br.x) / 2 - (tl.x + tr.x) / 2
        var vdy = (bl.y + br.y) / 2 - (tl.y + tr.y) / 2
        val vlen = Math.hypot(vdx, vdy).coerceAtLeast(1.0); vdx /= vlen; vdy /= vlen
        var hdx = (tr.x + br.x) / 2 - (tl.x + bl.x) / 2
        var hdy = (tr.y + br.y) / 2 - (tl.y + bl.y) / 2
        val hlen = Math.hypot(hdx, hdy).coerceAtLeast(1.0); hdx /= hlen; hdy /= hlen

        val padT = BOX_PAD_TOP_FRAC * vlen
        val padB = BOX_PAD_BOTTOM_FRAC * vlen
        val padL = BOX_PAD_SIDE_FRAC * hlen
        val padR = BOX_PAD_SIDE_FRAC * hlen
        fun mk(p: Point, sx: Double, sy: Double) = Point(
            (p.x + sx).coerceIn(0.0, (canvasW - 1).toDouble()),
            (p.y + sy).coerceIn(0.0, (canvasH - 1).toDouble())
        )
        return listOf(
            mk(tl, -hdx * padL - vdx * padT, -hdy * padL - vdy * padT),
            mk(tr,  hdx * padR - vdx * padT,  hdy * padR - vdy * padT),
            mk(bl, -hdx * padL + vdx * padB, -hdy * padL + vdy * padB),
            mk(br,  hdx * padR + vdx * padB,  hdy * padR + vdy * padB)
        )
    }

    private enum class EdgeAxis { HORIZONTAL, VERTICAL }

    /**
     * Snaps each of a box's 4 edges [TL, TR, BL, BR] onto the nearest strong gradient within
     * ±[snapRadius]. The first warp's fiducials sit at the page corners, so residual bow
     * accumulates toward the page centre where these boxes live, and BoxFinder's contour can
     * snap to a shadow or the border's outer edge. Summing the Sobel response over the full
     * edge averages out noise so even a thin line registers, correcting onto the real border.
     *
     * @param corners detected corners [TL, TR, BL, BR] in [canvasGray] pixels.
     * @param canvasGray the warped CLAHE gray the corners were detected in.
     * @param snapRadius ± search window from the detected edge.
     * @param minEdgeStrength minimum summed |Sobel| to count as an edge; else keep original.
     * @return refined corners, each shifted only perpendicular to its edge, clamped and
     *         never crossing the opposite edge.
     */
    private fun snapCornersToEdges(
        corners: List<Point>,
        canvasGray: Mat,
        snapRadius: Int = 14,
        minEdgeStrength: Double = 30.0
    ): List<Point> {
        require(corners.size == 4) { "Exactly 4 corners required [TL, TR, BL, BR]" }
        val tl = corners[0]
        val tr = corners[1]
        val bl = corners[2]
        val br = corners[3]

        val canvasW = canvasGray.cols()
        val canvasH = canvasGray.rows()

        val boxLeft = minOf(tl.x, bl.x)
        val boxRight = maxOf(tr.x, br.x)
        val boxTop = minOf(tl.y, tr.y)
        val boxBottom = maxOf(bl.y, br.y)
        val boxWidth = (boxRight - boxLeft).toInt().coerceAtLeast(1)
        val boxHeight = (boxBottom - boxTop).toInt().coerceAtLeast(1)

        val topShift = bestEdgeShift(canvasGray, EdgeAxis.HORIZONTAL, boxLeft.toInt(), boxWidth,
            (tl.y + tr.y) / 2.0, snapRadius, minEdgeStrength) ?: 0
        val bottomShift = bestEdgeShift(canvasGray, EdgeAxis.HORIZONTAL, boxLeft.toInt(), boxWidth,
            (bl.y + br.y) / 2.0, snapRadius, minEdgeStrength) ?: 0
        val leftShift = bestEdgeShift(canvasGray, EdgeAxis.VERTICAL, boxTop.toInt(), boxHeight,
            (tl.x + bl.x) / 2.0, snapRadius, minEdgeStrength) ?: 0
        val rightShift = bestEdgeShift(canvasGray, EdgeAxis.VERTICAL, boxTop.toInt(), boxHeight,
            (tr.x + br.x) / 2.0, snapRadius, minEdgeStrength) ?: 0

        var newTlX = tl.x + leftShift
        var newTlY = tl.y + topShift
        var newTrX = tr.x + rightShift
        var newTrY = tr.y + topShift
        var newBlX = bl.x + leftShift
        var newBlY = bl.y + bottomShift
        var newBrX = br.x + rightShift
        var newBrY = br.y + bottomShift

        // Clamp to canvas bounds
        newTlX = newTlX.coerceIn(0.0, (canvasW - 1).toDouble())
        newTrX = newTrX.coerceIn(0.0, (canvasW - 1).toDouble())
        newBlX = newBlX.coerceIn(0.0, (canvasW - 1).toDouble())
        newBrX = newBrX.coerceIn(0.0, (canvasW - 1).toDouble())
        newTlY = newTlY.coerceIn(0.0, (canvasH - 1).toDouble())
        newTrY = newTrY.coerceIn(0.0, (canvasH - 1).toDouble())
        newBlY = newBlY.coerceIn(0.0, (canvasH - 1).toDouble())
        newBrY = newBrY.coerceIn(0.0, (canvasH - 1).toDouble())

        // Critical: never let a snapped edge cross the opposite edge — fall back
        // to the original (unsnapped) corner pair for that axis if it would.
        if (newTlY >= newBlY || newTrY >= newBrY) {
            newTlY = tl.y; newTrY = tr.y; newBlY = bl.y; newBrY = br.y
        }
        if (newTlX >= newTrX || newBlX >= newBrX) {
            newTlX = tl.x; newTrX = tr.x; newBlX = bl.x; newBrX = br.x
        }

        return listOf(
            Point(newTlX, newTlY),
            Point(newTrX, newTrY),
            Point(newBlX, newBlY),
            Point(newBrX, newBrY)
        )
    }

    /**
     * Searches ±[snapRadius] around [detectedPos] for the strongest edge gradient. Extracts
     * a strip spanning the edge ([stripExtent] from [stripStart]) by snapRadius*2+1 thick,
     * applies a perpendicular Sobel (HORIZONTAL → Sobel-Y, VERTICAL → Sobel-X), and sums
     * |gradient| per row/column. The highest-sum line clearing [minEdgeStrength] wins.
     *
     * @return integer shift from [detectedPos], or null if nothing clears [minEdgeStrength].
     */
    private fun bestEdgeShift(
        canvasGray: Mat,
        axis: EdgeAxis,
        stripStart: Int,
        stripExtent: Int,
        detectedPos: Double,
        snapRadius: Int,
        minEdgeStrength: Double
    ): Int? {
        val canvasW = canvasGray.cols()
        val canvasH = canvasGray.rows()
        val thickness = snapRadius * 2 + 1

        val stripRect = if (axis == EdgeAxis.HORIZONTAL) {
            val x0 = stripStart.coerceIn(0, (canvasW - stripExtent).coerceAtLeast(0))
            val w = stripExtent.coerceAtMost(canvasW - x0)
            val y0 = (detectedPos - snapRadius).toInt().coerceIn(0, (canvasH - thickness).coerceAtLeast(0))
            val h = thickness.coerceAtMost(canvasH - y0)
            Rect(x0, y0, w, h)
        } else {
            val y0 = stripStart.coerceIn(0, (canvasH - stripExtent).coerceAtLeast(0))
            val h = stripExtent.coerceAtMost(canvasH - y0)
            val x0 = (detectedPos - snapRadius).toInt().coerceIn(0, (canvasW - thickness).coerceAtLeast(0))
            val w = thickness.coerceAtMost(canvasW - x0)
            Rect(x0, y0, w, h)
        }
        if (stripRect.width <= 0 || stripRect.height <= 0) return null

        val strip = Mat(canvasGray, stripRect)
        val sobel = Mat()
        if (axis == EdgeAxis.HORIZONTAL) {
            // Top/bottom edges are horizontal lines — their gradient is along Y.
            Imgproc.Sobel(strip, sobel, CvType.CV_32F, 0, 1, 3)
        } else {
            // Left/right edges are vertical lines — their gradient is along X.
            Imgproc.Sobel(strip, sobel, CvType.CV_32F, 1, 0, 3)
        }
        strip.release()

        val absSobel = Mat()
        Core.absdiff(sobel, Scalar(0.0), absSobel)
        sobel.release()

        // For HORIZONTAL edges, candidate positions are rows (Y varies);
        // for VERTICAL edges, candidate positions are columns (X varies).
        var bestIndex = -1
        var bestStrength = -1.0
        val candidateCount = if (axis == EdgeAxis.HORIZONTAL) absSobel.rows() else absSobel.cols()
        for (i in 0 until candidateCount) {
            val line = if (axis == EdgeAxis.HORIZONTAL) absSobel.row(i) else absSobel.col(i)
            val strength = Core.sumElems(line).`val`[0]
            if (strength > bestStrength) {
                bestStrength = strength
                bestIndex = i
            }
        }
        absSobel.release()

        if (bestIndex < 0 || bestStrength < minEdgeStrength) return null

        val stripOrigin = if (axis == EdgeAxis.HORIZONTAL) stripRect.y else stripRect.x
        val snappedPos = (stripOrigin + bestIndex).toDouble()
        return Math.round(snappedPos - detectedPos).toInt().coerceIn(-snapRadius, snapRadius)
    }

    // ── Cell-rect resolution: dynamic center (if detected) else SheetBlueprint ──

    /** Builds a cell Rect around a pixel-space center, mirroring [BubbleReader.normToRect]. */
    private fun rectFromPixelCenter(
        cx: Float, cy: Float,
        cellWFrac: Float, cellHFrac: Float,
        canvasW: Int, canvasH: Int
    ): Rect {
        val pw = (cellWFrac * canvasW).toInt().coerceAtLeast(1)
        val ph = (cellHFrac * canvasH).toInt().coerceAtLeast(1)
        val px = (cx - pw / 2f).toInt().coerceIn(0, (canvasW - pw).coerceAtLeast(0))
        val py = (cy - ph / 2f).toInt().coerceIn(0, (canvasH - ph).coerceAtLeast(0))
        return Rect(px, py, pw, ph)
    }

    private fun idCellRect(col: Int, digit: Int, dynamicCells: Array<Array<PointF?>>?): Rect {
        val center = dynamicCells?.get(digit)?.get(col)
        return if (center != null) {
            rectFromPixelCenter(
                center.x, center.y,
                SheetBlueprint.ID_CELL_W_FRAC, SheetBlueprint.ID_CELL_H_FRAC,
                SheetBlueprint.ID_BOX_W, SheetBlueprint.ID_BOX_H
            )
        } else {
            bubbleReader.normToRect(
                SheetBlueprint.idGrid[col][digit],
                SheetBlueprint.ID_CELL_W_FRAC, SheetBlueprint.ID_CELL_H_FRAC,
                SheetBlueprint.ID_BOX_W, SheetBlueprint.ID_BOX_H
            )
        }
    }

    private fun testSetCellRect(index: Int, dynamicCells: Array<PointF?>?): Rect {
        val center = dynamicCells?.get(index)
        return if (center != null) {
            rectFromPixelCenter(
                center.x, center.y,
                SheetBlueprint.TESTSET_CELL_W_FRAC, SheetBlueprint.TESTSET_CELL_H_FRAC,
                SheetBlueprint.TESTSET_BOX_W, SheetBlueprint.TESTSET_BOX_H
            )
        } else {
            bubbleReader.normToRect(
                if (index == 0) SheetBlueprint.testSetA else SheetBlueprint.testSetB,
                SheetBlueprint.TESTSET_CELL_W_FRAC, SheetBlueprint.TESTSET_CELL_H_FRAC,
                SheetBlueprint.TESTSET_BOX_W, SheetBlueprint.TESTSET_BOX_H
            )
        }
    }

    /**
     * Per-cell gate outcome, threaded out of the readers in debug builds so
     * [saveGateOverlay] can render the 3-color decision. Null in release builds.
     */
    private data class CellDebugState(
        val col: Int,
        val digit: Int,
        val fill: Float,
        val tint: Float,
        val fdev: Float,
        val tdev: Float,
        /** Passed the mass (fill-deviation + dominance) candidate gate. */
        val candidate: Boolean,
        /** Emitted as the column's selected shade (candidate ∧ tint ∧ ¬veto ∧ unique). */
        val selected: Boolean
    )

    /** Per-cell measures, mirroring AnswerZoneProcessor.CellRead. */
    private class CellMeasure(
        val fill: Float,    // binary dark fraction, full rect (1px inset)
        val ifill: Float,   // binary dark fraction, interior (INTERIOR_INSET inset)
        val tint: Float,    // p25 interior darkness on the CLAHE gray
        val bgmid: Float,   // darkness at the middle of the background fraction
        val cover: Float    // p75 interior darkness — with tint gives the spread
    ) {
        /** Interior darkness IQR (p75−p25). Small = uniform fill (a real shade); large =
         *  bimodal strokes-on-paper (a printed glyph). Lighting-invariant. */
        val spread: Float get() = cover - tint
    }

    // ── Test set reading ──────────────────────────────────────────────────

    /**
     * Reads the A/B bracket with the answer grid's fully-vs-not-fully rule: the heavier-fill
     * cell is the candidate, the other the blank baseline. It's the set only if the candidate
     * clears FDEV_MIN (more mass), TINT_MIN (a graphite shade, not the printed glyph or an
     * X/ø/slash mark), and the stroke veto; else "-".
     */
    private fun readTestSet(testBoxGray: Mat, testBoxBinary: Mat, dynamicCells: Array<PointF?>?): Pair<String, List<CellDebugState>?> {
        val w = testBoxBinary.cols()
        val h = testBoxBinary.rows()
        val binPx = ByteArray(w * h); testBoxBinary.get(0, 0, binPx)
        val grayPx = ByteArray(w * h); testBoxGray.get(0, 0, grayPx)

        // testSetCellRect resolves each cell independently (dynamic centre, else blueprint).
        val rectA = testSetCellRect(0, dynamicCells)
        val rectB = testSetCellRect(1, dynamicCells)
        val mA = measureCell(binPx, grayPx, w, rectA)
        val mB = measureCell(binPx, grayPx, w, rectB)

        val aIsCand = mA.fill >= mB.fill
        val cand = if (aIsCand) mA else mB
        val other = if (aIsCand) mB else mA
        val fdev = cand.fill - other.fill
        val tdev = cand.tint - other.tint
        val bdev = cand.bgmid - other.bgmid
        val candidate = fdev >= FDEV_MIN
        val tinted = tdev >= TINT_MIN
        val veto = cand.ifill < VETO_IFILL_MAX && bdev < VETO_BG
        // Coverage: a real shade covers the cell uniformly (small interior IQR); a
        // fattened printed A/B glyph stays bimodal (dark strokes over lighter paper).
        val covered = cand.spread <= COVER_SPREAD_MAX
        val selected = candidate && tinted && !veto && covered

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "TestSet A(fill=%.3f tint=%.3f spread=%.3f) B(fill=%.3f tint=%.3f spread=%.3f) cand=${if (aIsCand) "A" else "B"} "
                .format(mA.fill, mA.tint, mA.spread, mB.fill, mB.tint, mB.spread) +
                "fdev=%.3f tdev=%.3f bdev=%.3f ifill=%.2f covered=$covered -> ".format(fdev, tdev, bdev, cand.ifill) +
                if (selected) (if (aIsCand) "A" else "B") else "-")
        }

        val result = if (selected) (if (aIsCand) "A" else "B") else "-"

        val debugStates = if (BuildConfig.DEBUG) {
            listOf(
                CellDebugState(
                    col = 0, digit = 0, fill = mA.fill, tint = mA.tint,
                    fdev = if (aIsCand) fdev else 0f, tdev = if (aIsCand) tdev else 0f,
                    candidate = aIsCand && candidate, selected = aIsCand && selected
                ),
                CellDebugState(
                    col = 0, digit = 1, fill = mB.fill, tint = mB.tint,
                    fdev = if (!aIsCand) fdev else 0f, tdev = if (!aIsCand) tdev else 0f,
                    candidate = !aIsCand && candidate, selected = !aIsCand && selected
                )
            )
        } else null

        return result to debugStates
    }

    // ── Student ID reading — three-gate column-relative selection ─────────

    /**
     * For each of the 6 ID columns, picks the shaded digit with the answer grid's
     * fully-vs-not-fully rule. Since ≥9 of 10 cells are blank, the column median of each
     * measure is the per-capture blank baseline, and deviations are taken from it.
     *   candidate    — fill deviation ≥ FDEV_MIN and within DOMINANCE× of the column max.
     *   shade (tint) — tint deviation ≥ TINT_MIN. Graphite tints the paper; a printed glyph
     *                  or X/ø/slash mark leaves the interior white and fails here.
     *   stroke veto  — a drawn stroke has no background tint; rejected unless near-solid.
     * Exactly one cell passing all three → that digit; none or several → '?'.
     */
    private fun readStudentId(idBoxGray: Mat, idBoxBinary: Mat, dynamicCells: Array<Array<PointF?>>?): Pair<String, List<CellDebugState>?> {
        val w = idBoxBinary.cols()
        val h = idBoxBinary.rows()
        val binPx = ByteArray(w * h); idBoxBinary.get(0, 0, binPx)
        val grayPx = ByteArray(w * h); idBoxGray.get(0, 0, grayPx)

        val sb = StringBuilder()
        val debugStates = if (BuildConfig.DEBUG) ArrayList<CellDebugState>() else null
        for (col in 0..5) {
            val rects = Array(10) { digit -> idCellRect(col, digit, dynamicCells) }
            val m = Array(10) { digit -> measureCell(binPx, grayPx, w, rects[digit]) }

            val fillBase = median(m.map { it.fill })
            val tintBase = median(m.map { it.tint })
            val bgBase   = median(m.map { it.bgmid })
            val fdev = FloatArray(10) { m[it].fill - fillBase }
            val tdev = FloatArray(10) { m[it].tint - tintBase }
            val bdev = FloatArray(10) { m[it].bgmid - bgBase }
            val maxFdev = fdev.maxOrNull() ?: 0f

            val candidate = BooleanArray(10) { fdev[it] >= FDEV_MIN && fdev[it] >= DOMINANCE * maxFdev }
            val passed = BooleanArray(10) { digit ->
                candidate[digit] &&
                    tdev[digit] >= TINT_MIN &&
                    !(m[digit].ifill < VETO_IFILL_MAX && bdev[digit] < VETO_BG) &&
                    // Coverage: reject a fattened printed digit (bimodal interior); a shade
                    // covers the cell uniformly.
                    m[digit].spread <= COVER_SPREAD_MAX
            }
            val winners = (0..9).filter { passed[it] }
            sb.append(if (winners.size == 1) ('0' + winners[0]) else '?')

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "ID col$col base(f=%.2f t=%.2f) ".format(fillBase, tintBase) +
                    "fdev=${fdev.map { "%.2f".format(it) }} tdev=${tdev.map { "%.2f".format(it) }} " +
                    "spread=${m.map { "%.2f".format(it.spread) }} " +
                    "-> ${if (winners.size == 1) ('0' + winners[0]) else '?'}")
            }
            if (debugStates != null) {
                for (digit in 0..9) {
                    debugStates.add(CellDebugState(
                        col = col, digit = digit,
                        fill = m[digit].fill, tint = m[digit].tint,
                        fdev = fdev[digit], tdev = tdev[digit],
                        candidate = candidate[digit],
                        selected = winners.size == 1 && passed[digit]
                    ))
                }
            }
        }
        return sb.toString() to debugStates
    }

    // ── Per-cell measurement (ported from AnswerZoneProcessor.measureCell) ────

    /**
     * Measures one cell from the bulk pixel arrays (binary + CLAHE gray, [stride] wide).
     *  - fill : binary dark fraction, full rect (1px inset)
     *  - ifill: binary dark fraction, interior (INTERIOR_INSET per side)
     *  - tint : p25 interior darkness on the CLAHE gray
     *  - bgmid: interior darkness at the middle of the background fraction
     *           (q = clamp((1−ifill)·50, 5, 40)) — paper-level for strokes, tinted for shades
     */
    private fun measureCell(binPx: ByteArray, grayPx: ByteArray, stride: Int, rect: Rect): CellMeasure {
        fun darkFrac(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            var dark = 0; var total = 0
            for (y in y0 until y1) {
                val base = y * stride
                for (x in x0 until x1) {
                    if ((binPx[base + x].toInt() and 0xFF) < 128) dark++
                    total++
                }
            }
            return if (total > 0) dark.toFloat() / total else 0f
        }

        val fill = darkFrac(rect.x + 1, rect.y + 1, rect.x + rect.width - 1, rect.y + rect.height - 1)

        val ix = (rect.width * INTERIOR_INSET).toInt()
        val iy = (rect.height * INTERIOR_INSET).toInt()
        val x0 = rect.x + ix; val y0 = rect.y + iy
        val x1 = rect.x + rect.width - ix; val y1 = rect.y + rect.height - iy
        if (x1 - x0 < 4 || y1 - y0 < 4) return CellMeasure(fill, 0f, 0f, 0f, 0f)

        val ifill = darkFrac(x0, y0, x1, y1)
        val darkness = FloatArray((x1 - x0) * (y1 - y0))
        var i = 0
        for (y in y0 until y1) {
            val base = y * stride
            for (x in x0 until x1) darkness[i++] = 1f - (grayPx[base + x].toInt() and 0xFF) / 255f
        }
        darkness.sort()
        val sorted = darkness.toList()
        val tint = percentile(sorted, TINT_PERCENTILE)
        val bgQ = ((1f - ifill) * 50f).coerceIn(5f, 40f)
        val bgmid = percentile(sorted, bgQ)
        val cover = percentile(sorted, COVER_PERCENTILE)
        return CellMeasure(fill, ifill, tint, bgmid, cover)
    }

    /** Percentile [q] (0–100) of a pre-sorted list, linear interpolation. */
    private fun percentile(sorted: List<Float>, q: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val pos = q / 100f * (sorted.size - 1)
        val lo = pos.toInt().coerceIn(0, sorted.size - 1)
        val frac = pos - lo
        return if (lo + 1 < sorted.size) sorted[lo] * (1 - frac) + sorted[lo + 1] * frac else sorted[lo]
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    // ── Debug helpers ──────────────────────────────────────────────────────

    /** Cell rects in the same col-major order as [readStudentId]'s debug states —
     *  dynamic centres when available so the overlay matches what was measured. */
    private fun idCellRects(dynamicCells: Array<Array<PointF?>>?): List<Rect> =
        (0..5).flatMap { col -> (0..9).map { digit -> idCellRect(col, digit, dynamicCells) } }

    /**
     * Mirrors [testSetCellRect] so the overlay draws the same rects [readTestSet] measured
     * from; independent blueprint rects would misalign whenever dynamic detection succeeds.
     */
    private fun testSetCellRects(dynamicCells: Array<PointF?>?): List<Rect> =
        listOf(0, 1).map { index -> testSetCellRect(index, dynamicCells) }

    private fun logIdColumnFills(idBoxBinary: Mat) {
        for (col in 0..5) {
            val fills = (0..9).map { digit ->
                val rect = bubbleReader.normToRect(
                    SheetBlueprint.idGrid[col][digit],
                    SheetBlueprint.ID_CELL_W_FRAC, SheetBlueprint.ID_CELL_H_FRAC,
                    SheetBlueprint.ID_BOX_W, SheetBlueprint.ID_BOX_H
                )
                bubbleReader.readFill(idBoxBinary, rect)
            }
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "ID col$col fills: ${fills.map { "%.2f".format(it) }}")
            }
        }
    }

    /**
     * Draws the search ROI, detected box outline, and corner-refinement comparison: raw
     * BoxFinder corners as cyan crosses, [snapCornersToEdges] output as yellow crosses.
     */
    private fun saveBoxDetectionOverlay(
        context: android.content.Context,
        warpedGray: Mat,
        idBox: BoxFinder.BoxResult,
        idRefinedCorners: List<Point>,
        testBox: BoxFinder.BoxResult,
        testRefinedCorners: List<Point>
    ) {
        val bgr = Mat()
        Imgproc.cvtColor(warpedGray, bgr, Imgproc.COLOR_GRAY2BGR)
        Imgproc.rectangle(bgr, idBox.searchRoi, Scalar(255.0, 255.0, 0.0), 2)
        Imgproc.rectangle(bgr, idBox.boxRect, Scalar(0.0, 255.0, 0.0), 3)
        Imgproc.rectangle(bgr, testBox.searchRoi, Scalar(255.0, 0.0, 255.0), 2)
        Imgproc.rectangle(bgr, testBox.boxRect, Scalar(0.0, 0.0, 255.0), 3)
        drawCornerCrosses(bgr, idBox.corners, Scalar(255.0, 255.0, 0.0), size = 8)      // cyan, raw
        drawCornerCrosses(bgr, idRefinedCorners, Scalar(0.0, 255.0, 255.0), size = 12)  // yellow, snapped
        drawCornerCrosses(bgr, testBox.corners, Scalar(255.0, 255.0, 0.0), size = 8)
        drawCornerCrosses(bgr, testRefinedCorners, Scalar(0.0, 255.0, 255.0), size = 12)
        DebugImageSaver.saveMat(context, bgr, "info_box_detection")
        bgr.release()
    }

    private fun drawCornerCrosses(bgr: Mat, corners: List<Point>, color: Scalar, size: Int) {
        for (corner in corners) {
            Imgproc.drawMarker(bgr, corner, color, Imgproc.MARKER_CROSS, size, 2)
        }
    }

    /**
     * Draws the 4 markers (TL, TM, BL, BM) on the raw BGR capture as labelled crosses. Saves
     * the frame even when [markers] is null so the reviewer sees what the detector saw.
     * [bgrFrame] is left intact. Saved as "info_captured_markers".
     */
    private fun saveInfoCapturedMarkers(
        context: android.content.Context,
        bgrFrame: Mat,
        markers: List<Point>?,
        lockPx: List<Point?>? = null
    ) {
        // Force an opaque 3-channel BGR canvas: Utils.bitmapToMat yields RGBA, and drawing a
        // 3-element Scalar on that leaves alpha=0, so strokes save transparent (look black).
        val bgr = Mat()
        when (bgrFrame.channels()) {
            4    -> Imgproc.cvtColor(bgrFrame, bgr, Imgproc.COLOR_RGBA2BGR)
            1    -> Imgproc.cvtColor(bgrFrame, bgr, Imgproc.COLOR_GRAY2BGR)
            else -> bgrFrame.copyTo(bgr)
        }
        val imgW = bgr.cols()

        // Refined live-lock positions first, as hollow cyan circles, so any gap to the solid
        // detected crosses (the markers actually used to warp) is visible.
        val cyan = Scalar(255.0, 255.0, 0.0)
        if (lockPx != null) {
            for (i in lockPx.indices) {
                val hp = lockPx[i] ?: continue
                Imgproc.circle(bgr, hp, 30, cyan, 4)
                Imgproc.putText(bgr, "lock$i", Point(hp.x + 10, hp.y + 34),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, cyan, 2)
            }
        }
        if (markers != null) {
            val labels = listOf("TL", "TM", "BL", "BM")
            val colors = listOf(
                Scalar(0.0, 255.0, 0.0),    // TL green
                Scalar(0.0, 0.0, 255.0),    // TM red
                Scalar(0.0, 200.0, 255.0),  // BL orange
                Scalar(255.0, 0.0, 255.0)   // BM magenta
            )
            for (i in markers.indices) {
                val pt = markers[i]
                val color = colors.getOrElse(i) { Scalar(255.0, 255.0, 255.0) }
                Imgproc.drawMarker(bgr, pt, color, Imgproc.MARKER_CROSS, 40, 4)
                Imgproc.putText(bgr, labels.getOrElse(i) { "$i" }, Point(pt.x + 10, pt.y - 14),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, color, 3)
            }
            // Cross-vs-lock distance per corner after reconcile: ~0 where snapped to the
            // lock, small where the detection was kept.
            if (lockPx != null && imgW > 0) {
                val perCorner = markers.indices.map { i ->
                    val h = lockPx.getOrNull(i)
                    if (h == null) "-" else "%.3f".format(
                        kotlin.math.hypot(markers[i].x - h.x, markers[i].y - h.y) / imgW)
                }
                Imgproc.putText(bgr, "cross-vs-lock  ${perCorner.joinToString(",")}",
                    Point(20.0, 50.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 220.0, 0.0), 3)
            }
        }
        DebugImageSaver.saveMat(context, bgr, "info_captured_markers")
        bgr.release()
    }

    /**
     * Draws each box's final corners and quad — green when BoxFinder located the border,
     * orange on blueprint fallback — so the reviewer sees if the sub-warps frame the real
     * boxes. Saved as "info_box_corners".
     */
    private fun saveBoxCornersOverlay(
        context: android.content.Context,
        warpedGray: Mat,
        idCorners: List<Point>,
        idFound: Boolean,
        testCorners: List<Point>,
        testFound: Boolean
    ) {
        val bgr = Mat()
        Imgproc.cvtColor(warpedGray, bgr, Imgproc.COLOR_GRAY2BGR)
        val green = Scalar(0.0, 220.0, 0.0)
        val orange = Scalar(0.0, 165.0, 255.0)
        fun drawQuad(corners: List<Point>, found: Boolean, tag: String) {
            if (corners.size != 4) return
            val color = if (found) green else orange
            // [TL, TR, BL, BR] → perimeter TL→TR→BR→BL.
            val ring = listOf(corners[0], corners[1], corners[3], corners[2])
            for (i in 0 until 4) Imgproc.line(bgr, ring[i], ring[(i + 1) % 4], color, 2)
            drawCornerCrosses(bgr, corners, color, size = 12)
            val label = if (found) "$tag FOUND" else "$tag FALLBACK"
            Imgproc.putText(bgr, label, Point(corners[0].x, corners[0].y - 8),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        }
        drawQuad(idCorners, idFound, "ID")
        drawQuad(testCorners, testFound, "SET")
        DebugImageSaver.saveMat(context, bgr, "info_box_corners")
        bgr.release()
    }

    /** Saves a sub-canvas plus a cell-rect overlay for visual alignment verification. */
    private fun saveCellOverlay(
        context: android.content.Context,
        subWarp: Mat,
        name: String,
        cellRects: List<Rect>
    ) {
        DebugImageSaver.saveMat(context, subWarp, name)
        val overlay = bubbleReader.debugOverlay(subWarp, cellRects)
        DebugImageSaver.saveMat(context, overlay, "${name}_cells_overlay")
        overlay.release()
    }

    /**
     * Saves a sub-canvas plus a 3-color overlay of the final shading decision per cell:
     *   green  — not a candidate (blank / below FDEV_MIN)
     *   orange — a mass candidate rejected as a mark/glyph or non-unique winner
     *   red    — selected (passed mass + tint + veto)
     * [cellRects] and [states] must be the same length and order.
     */
    private fun saveGateOverlay(
        context: android.content.Context,
        subWarp: Mat,
        name: String,
        cellRects: List<Rect>,
        states: List<CellDebugState>
    ) {
        DebugImageSaver.saveMat(context, subWarp, name)
        val overlay = Mat()
        Imgproc.cvtColor(subWarp, overlay, Imgproc.COLOR_GRAY2BGR)
        for ((rect, state) in cellRects.zip(states)) {
            val color = when {
                !state.candidate -> Scalar(0.0, 200.0, 0.0)    // green
                !state.selected  -> Scalar(0.0, 165.0, 255.0)  // orange
                else             -> Scalar(0.0, 0.0, 255.0)     // red
            }
            Imgproc.rectangle(overlay, rect, color, 2)
        }
        DebugImageSaver.saveMat(context, overlay, "${name}_cells_overlay")
        overlay.release()
    }

    /**
     * Draws green (col 0) / red (other cols) crosses at each detected ID cell center, to
     * compare against the static blueprint overlay.
     */
    private fun saveDynamicIdCellsDebug(
        context: android.content.Context,
        idBinary: Mat,
        dynamicIdCells: Array<Array<PointF?>>
    ) {
        val ann = Mat()
        Imgproc.cvtColor(idBinary, ann, Imgproc.COLOR_GRAY2BGR)
        for (row in 0 until 10) {
            for (col in 0 until 6) {
                val pt = dynamicIdCells[row][col]
                if (pt != null) {
                    Imgproc.drawMarker(
                        ann,
                        Point(pt.x.toDouble(), pt.y.toDouble()),
                        if (col == 0) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0),
                        Imgproc.MARKER_CROSS, 12, 2
                    )
                }
            }
        }
        DebugImageSaver.saveMat(context, ann, "id_box_dynamic_cells")
        ann.release()
    }

    /** Draws crosses at the detected Set-A (green) / Set-B (red) cell centers. */
    private fun saveDynamicTestSetCellsDebug(
        context: android.content.Context,
        testSetBinary: Mat,
        dynamicTestSetCells: Array<PointF?>
    ) {
        val ann = Mat()
        Imgproc.cvtColor(testSetBinary, ann, Imgproc.COLOR_GRAY2BGR)
        dynamicTestSetCells.forEachIndexed { idx, pt ->
            if (pt != null) {
                Imgproc.drawMarker(
                    ann,
                    Point(pt.x.toDouble(), pt.y.toDouble()),
                    if (idx == 0) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0),
                    Imgproc.MARKER_CROSS, 12, 2
                )
            }
        }
        DebugImageSaver.saveMat(context, ann, "testset_box_dynamic_cells")
        ann.release()
    }

    // ── BubbleDebug: save cell crops for visual inspection ────────────────

    /**
     * Saves cell crops (col 0–5, rows 0–1) to verify blueprint alignment: for the test
     * captures (col=0, row=1) should show the pencil-shaded "1" bubble interior.
     */
    private fun saveBubbleDebugCrops(idBoxWarp: Mat, context: android.content.Context) {
        for (col in 0..5) {
            for (row in 0..1) {
                val rect = bubbleReader.normToRect(
                    SheetBlueprint.idGrid[col][row],
                    SheetBlueprint.ID_CELL_W_FRAC, SheetBlueprint.ID_CELL_H_FRAC,
                    SheetBlueprint.ID_BOX_W, SheetBlueprint.ID_BOX_H
                )
                if (rect.width <= 0 || rect.height <= 0 ||
                    rect.x + rect.width > idBoxWarp.cols() ||
                    rect.y + rect.height > idBoxWarp.rows()) continue

                val crop = Mat(idBoxWarp, rect)
                DebugImageSaver.saveMat(context, crop, "cell_col${col}_row${row}")
                crop.release()
            }
        }
    }
}
