package com.pbec.preboardexamchecker.ui.scanner.processor

import android.util.Log
import com.pbec.preboardexamchecker.BuildConfig
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc

/**
 * Finds the 4 registration marker centers in a binary (Otsu) Mat. Markers are solid black
 * squares (~84×84 px at 300 DPI reference). Area bounds are image-relative so the same
 * physical markers pass at any capture resolution.
 *
 * Output order [topLeft, topRight, bottomLeft, bottomRight] relative to the zone:
 *   Phase 1 (info zone):   TL, TM, BL, BM
 *   Phase 2 (answer zone): TM, TR, BM, BR
 */
class MarkerDetector {

    private data class Candidate(val pt: Point, val area: Double)

    /**
     * @param expectedQuadAspect expected w/h ratio of the 4-marker quad in image coords
     *   (0 = skip). Phase 2 answer zone ≈0.632, Phase 1 info zone ≈0.577. When set, the
     *   detector picks the per-quadrant candidate set whose quad best matches it, so a lone
     *   dark square (ID cell, logo) that wins a quadrant on area but can't form a plausible
     *   rectangle with the others is rejected.
     */
    fun detect(
        binaryMat: Mat,
        grayMat: Mat? = null,
        expectedQuadAspect: Double = 0.0,
        useCalibRois: Boolean = false,
        cornerHints: Array<Point?>? = null,
        roiFrac: Array<FloatArray>? = null
    ): List<Point>? {
        val imgW = binaryMat.cols()
        val imgH = binaryMat.rows()

        // A marker's bbox is ~4.8% of image width. The 0.20×–3.0× band allows perspective
        // foreshortening but excludes answer bubbles (~0.1× expectedArea); the old
        // 0.025×–15× band let four bubbles form a false marker quad.
        val side = imgW * 0.048
        val expectedArea = side * side
        val effectiveMin = (expectedArea * 0.20).toInt().coerceAtLeast(50)
        val effectiveMax = (expectedArea * 3.0).toInt()
        if (BuildConfig.DEBUG) {
            Log.d("MarkerDetector", "detect: imgW=$imgW effectiveMin=$effectiveMin effectiveMax=$effectiveMax")
        }

        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(binaryMat, binaryMat, Imgproc.MORPH_CLOSE, closeKernel)
        closeKernel.release()

        val allCandidates = findCandidates(binaryMat, effectiveMin, effectiveMax)

        // Base regions: calibrated windows (roiFrac selects a layout, e.g.
        // MARKER_ROI_FRAC_PHASE1) or classic midpoint quadrants.
        val calibFrac = roiFrac ?: MARKER_ROI_FRAC
        val baseRois = if (useCalibRois) markerRois(imgW, imgH, calibFrac) else midpointRois(imgW, imgH)

        // Optionally narrow each region around the guide's locked position. cornerHints are
        // [0,1] fractions, so they scale across analysis/still frames implicitly.
        val hintRad = (imgW * HINT_RADIUS_FRAC).toInt()
        val searchRois = Array(4) { q ->
            val base = baseRois[q]
            val hint = cornerHints?.get(q)
            if (hint != null) {
                val hx = (hint.x * imgW).toInt()
                val hy = (hint.y * imgH).toInt()
                val x0 = (hx - hintRad).coerceAtLeast(base.x)
                val y0 = (hy - hintRad).coerceAtLeast(base.y)
                val x1 = (hx + hintRad).coerceAtMost(base.x + base.width)
                val y1 = (hy + hintRad).coerceAtMost(base.y + base.height)
                if (x1 > x0 && y1 > y0) Rect(x0, y0, x1 - x0, y1 - y0) else base
            } else base
        }

        val quadLists = Array(4) { q ->
            val roi = searchRois[q]
            val x0 = roi.x.toDouble(); val y0 = roi.y.toDouble()
            val x1 = (roi.x + roi.width).toDouble(); val y1 = (roi.y + roi.height).toDouble()
            topInQuadrant(allCandidates) {
                it.pt.x >= x0 && it.pt.x < x1 && it.pt.y >= y0 && it.pt.y < y1
            }
        }

        // Staged geometric set selection. Each stage runs only when earlier ones fail, so a
        // capture that already produced a valid set is unaffected; the rest rescue captures
        // that previously returned null:
        //   1. global candidates + expected aspect (fast path)
        //   2. per-quadrant local threshold + aspect (shadow-robust, like the live guide)
        //   3/4. same two sources with the aspect prior dropped — the parallelogram /
        //        side / area-spread / midpoint gates still reject a stray square.
        var best: List<Candidate>? = null
        var stage = 0
        val globalComplete = quadLists.none { it.isEmpty() }
        if (!globalComplete) {
            Log.w("MarkerDetector", "Only ${quadLists.count { it.isNotEmpty() }}/4 quadrants " +
                "have global marker candidates — trying per-quadrant local fallback.")
        }

        if (globalComplete) best = pickBestSet(quadLists, expectedQuadAspect, expectedArea)?.also { stage = 1 }

        var localLists: Array<List<Candidate>>? = null
        if (best == null && grayMat != null) {
            localLists = findCandidatesPerQuadrantLocal(grayMat, effectiveMin, effectiveMax, useCalibRois, calibFrac)
            if (localLists.none { it.isEmpty() }) {
                best = pickBestSet(localLists, expectedQuadAspect, expectedArea)?.also { stage = 2 }
            }
        }
        if (best == null && globalComplete) best = pickBestSet(quadLists, 0.0, expectedArea)?.also { stage = 3 }
        if (best == null && localLists != null && localLists.none { it.isEmpty() }) {
            best = pickBestSet(localLists, 0.0, expectedArea)?.also { stage = 4 }
        }

        if (best == null) {
            Log.w("MarkerDetector", "No 4-candidate combination passed geometric validation " +
                "(expectedQuadAspect=$expectedQuadAspect; tried global + per-quadrant local + relaxed aspect)")
            return null
        }
        if (BuildConfig.DEBUG) {
            Log.d("MarkerDetector", "Accepted 4 markers via stage $stage, areas=" +
                "${best.map { it.area.toInt() }}")
        }

        return refineToSubPixel(grayMat, best.map { it.pt })
    }

    /**
     * Evaluates every combination of the top per-quadrant candidates and returns the
     * [tl, tr, bl, br] set whose quad best matches the expected geometry, or null. With
     * [expectedQuadAspect] ≤ 0, the other gates (parallelogram, side-ratio, area) still apply.
     */
    private fun pickBestSet(
        quadLists: Array<List<Candidate>>,
        expectedQuadAspect: Double,
        expectedArea: Double = 0.0
    ): List<Candidate>? {
        fun dist(a: Point, b: Point): Double {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        var best: List<Candidate>? = null
        var bestErr = Double.MAX_VALUE
        for (tl in quadLists[0]) for (tr in quadLists[1]) for (bl in quadLists[2]) for (br in quadLists[3]) {
            val areas = listOf(tl.area, tr.area, bl.area, br.area)
            if (areas.max() / areas.min() > MAX_PERSPECTIVE_AREA_RATIO) continue

            val wTop = dist(tl.pt, tr.pt)
            val wBot = dist(bl.pt, br.pt)
            val hL = dist(tl.pt, bl.pt)
            val hR = dist(tr.pt, br.pt)
            if (wTop < 1.0 || wBot < 1.0 || hL < 1.0 || hR < 1.0) continue

            // With the perspective-loosened gates below, convexity is the primary guard
            // against a distractor (e.g. an ID square in TM's quadrant) forming a set.
            if (!isConvexQuad(tl.pt, tr.pt, br.pt, bl.pt)) continue

            // Opposite sides near-equal (perspective bounds the spread).
            val sideRatioW = maxOf(wTop, wBot) / minOf(wTop, wBot)
            val sideRatioH = maxOf(hL, hR) / minOf(hL, hR)
            if (sideRatioW > MAX_OPPOSITE_SIDE_RATIO || sideRatioH > MAX_OPPOSITE_SIDE_RATIO) continue

            // Diagonal midpoints coincide for a parallelogram; a wrong corner separates them.
            val mid1x = (tl.pt.x + br.pt.x) / 2; val mid1y = (tl.pt.y + br.pt.y) / 2
            val mid2x = (tr.pt.x + bl.pt.x) / 2; val mid2y = (tr.pt.y + bl.pt.y) / 2
            val diag = (dist(tl.pt, br.pt) + dist(tr.pt, bl.pt)) / 2
            val midErr = kotlin.math.sqrt(
                (mid1x - mid2x) * (mid1x - mid2x) + (mid1y - mid2y) * (mid1y - mid2y)) / diag
            if (midErr > MAX_DIAG_MIDPOINT_FRAC) continue

            var err = (sideRatioW - 1.0) + (sideRatioH - 1.0) + 2.0 * midErr
            if (expectedQuadAspect > 0) {
                val aspect = ((wTop + wBot) / 2) / ((hL + hR) / 2)
                val aspectErr = kotlin.math.abs(kotlin.math.ln(aspect / expectedQuadAspect))
                if (aspectErr > MAX_ASPECT_LOG_ERROR) continue
                err += aspectErr
            }
            // Size preference: among valid sets prefer the one whose smallest member is
            // nearest the expected marker area. A shaded bubble pulled into a cluttered
            // quadrant is far smaller than a real marker, so this penalty keeps the larger
            // real marker winning even when the bubble forms a marginally tidier quad.
            if (expectedArea > 0) {
                val minA = areas.min()
                val areaShortfall = ((expectedArea - minA) / expectedArea).coerceIn(0.0, 1.0)
                err += MARKER_AREA_PREF_WEIGHT * areaShortfall
            }
            if (err < bestErr) {
                bestErr = err
                best = listOf(tl, tr, bl, br)
            }
        }
        return best
    }

    /**
     * True if the 4 points (order TL→TR→BR→BL) form a convex quad (all edge cross-products
     * share one sign). Perspective keeps a real set convex; a wrong corner makes it concave.
     */
    private fun isConvexQuad(tl: Point, tr: Point, br: Point, bl: Point): Boolean {
        val pts = arrayOf(tl, tr, br, bl)
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    // ── Subpixel refinement ───────────────────────────────────────────────

    /**
     * Refines marker centers (bounding-rect centers) to subpixel via cornerSubPix. The
     * rect center shifts a few px between captures as the binarization threshold varies
     * with lighting; cornerSubPix locks onto the gradient saddle, stabilizing the source
     * points fed to getPerspectiveTransform. No-op when [grayMat] is null.
     */
    private fun refineToSubPixel(grayMat: Mat?, points: List<Point>): List<Point> {
        if (grayMat == null) return points

        val corners = MatOfPoint2f(*points.toTypedArray())
        val winSize = Size(7.0, 7.0)
        // zeroZone disabled: a non-trivial one would fill the 7×7 window and cornerSubPix
        // would reject the input.
        val zeroZone = Size(-1.0, -1.0)
        val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 30, 0.001)

        Imgproc.cornerSubPix(grayMat, corners, winSize, zeroZone, criteria)
        val refined = corners.toList()
        corners.release()

        if (BuildConfig.DEBUG) {
            Log.d("MarkerDetector", "Subpixel refinement: raw=$points -> refined=$refined")
        }
        return refined
    }

    // ── Contour search helpers ────────────────────────────────────────────

    // Area + aspect only, no solidity: analysis frames threshold without CLAHE, so contours
    // are coarse and solidity drops below 0.80 even for clear markers.
    private fun findCandidatesForGuide(binaryMat: Mat, minArea: Int, maxArea: Int): List<Candidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binaryMat.clone(), contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()
        val results = mutableListOf<Candidate>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val bboxArea = rect.width * rect.height
            if (bboxArea < minArea || bboxArea > maxArea) { contour.release(); continue }
            val aspect = rect.width.toDouble() / rect.height
            if (aspect < 0.6 || aspect > 1.6) { contour.release(); continue }
            results.add(Candidate(
                pt   = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0),
                area = bboxArea.toDouble()
            ))
            contour.release()
        }
        return results
    }

    /**
     * Shadow-fallback retry for a single quadrant the primary CLAHE+Otsu pass missed.
     * Tries Black Hat morphology first (shadow-invariant — reveals a greyed-out marker as
     * a local dark blob), then adaptive threshold against the local mean for the deepest
     * gradients. Reuses the same area/aspect/solidity gates so the area-ratio check stays
     * meaningful.
     */
    private fun retryQuadrantWithFallback(
        grayMat: Mat,
        qx: Int, qy: Int, qw: Int, qh: Int,
        effectiveMin: Int, effectiveMax: Int
    ): Candidate? {
        val roi = grayMat.submat(qy, qy + qh, qx, qx + qw)

        val markerSide = (qw * 0.095).toInt().coerceIn(20, qw / 2)
        val ks = if (markerSide % 2 == 0) markerSide + 1 else markerSide
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(ks.toDouble(), ks.toDouble())
        )
        val roiClone = roi.clone()
        val blackhat = Mat()
        Imgproc.morphologyEx(roiClone, blackhat, Imgproc.MORPH_BLACKHAT, kernel)
        val bhBinary = Mat()
        Imgproc.threshold(blackhat, bhBinary, 0.0, 255.0,
            Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        var best = findCandidatesInROI(bhBinary, effectiveMin, effectiveMax).maxByOrNull { it.area }
        roiClone.release()
        blackhat.release()
        bhBinary.release()
        kernel.release()

        if (best != null) {
            if (BuildConfig.DEBUG) {
                Log.d("MarkerDetector", "detect retry qx=$qx qy=$qy pass=blackhat pt=${best.pt}")
            }
        } else {
            val clahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
            val enhanced = Mat()
            clahe.apply(roi, enhanced)
            val blockSize = (qw / 3).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(51)
            val adaptBinary = Mat()
            Imgproc.adaptiveThreshold(
                enhanced, adaptBinary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                blockSize, -8.0
            )
            best = findCandidatesInROI(adaptBinary, effectiveMin, effectiveMax).maxByOrNull { it.area }
            enhanced.release()
            adaptBinary.release()
            if (best != null && BuildConfig.DEBUG) {
                Log.d("MarkerDetector", "detect retry qx=$qx qy=$qy pass=adaptive pt=${best.pt}")
            }
        }

        roi.release()
        return best?.let { Candidate(pt = Point(it.pt.x + qx, it.pt.y + qy), area = it.area) }
    }

    /**
     * Shadow-robust fallback when the global Otsu pass yields no valid 4-marker set.
     * Thresholds each quadrant ROI locally (the same CLAHE+Otsu + blackhat/adaptive the
     * live guide uses), recovering a marker the global threshold washed out or a quadrant
     * whose global candidates were all non-markers. Returns up to
     * [MAX_CANDIDATES_PER_QUADRANT] candidates (full-image coords) for [pickBestSet].
     */
    private fun findCandidatesPerQuadrantLocal(
        grayMat: Mat,
        effectiveMin: Int,
        effectiveMax: Int,
        useCalibRois: Boolean = false,
        calibFrac: Array<FloatArray> = MARKER_ROI_FRAC
    ): Array<List<Candidate>> {
        // Downscale for speed: the blackhat fallback needs a kernel larger than a marker
        // (≈190 px on a 12 MP capture), O(kernel)-slow at full res. Detection is
        // image-relative, so a downscaled copy finds the same markers; centres scale back
        // to full-res and detect()'s cornerSubPix refines them there.
        val maxDim = maxOf(grayMat.cols(), grayMat.rows())
        val scale = if (maxDim > FALLBACK_MAX_DIM) FALLBACK_MAX_DIM.toDouble() / maxDim else 1.0
        val work = if (scale < 1.0) Mat().also {
            Imgproc.resize(grayMat, it, Size(), scale, scale, Imgproc.INTER_AREA)
        } else grayMat
        val sMin = (effectiveMin * scale * scale).toInt().coerceAtLeast(20)
        val sMax = (effectiveMax * scale * scale).toInt().coerceAtLeast(sMin + 1)
        val inv = 1.0 / scale

        val imgW = work.cols()
        val imgH = work.rows()
        // quadGeom: (qx, qy, qw, qh) per corner in downscaled-image coords
        val quadGeom: Array<IntArray> = if (useCalibRois) {
            Array(4) { q ->
                val f = calibFrac[q]
                val x0 = (f[0] * imgW).toInt()
                val y0 = (f[2] * imgH).toInt()
                val qw = ((f[1] * imgW).toInt().coerceAtMost(imgW) - x0).coerceAtLeast(1)
                val qh = ((f[3] * imgH).toInt().coerceAtMost(imgH) - y0).coerceAtLeast(1)
                intArrayOf(x0, y0, qw, qh)
            }
        } else {
            val midX = imgW / 2
            val midY = imgH / 2
            arrayOf(
                intArrayOf(0,    0,    midX,        midY),
                intArrayOf(midX, 0,    imgW - midX, midY),
                intArrayOf(0,    midY, midX,        imgH - midY),
                intArrayOf(midX, midY, imgW - midX, imgH - midY)
            )
        }
        val clahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        val out = Array(4) { emptyList<Candidate>() }
        for (q in 0..3) {
            val (qx, qy, qw, qh) = quadGeom[q]
            val roi = work.submat(qy, qy + qh, qx, qx + qw)
            val enhanced = Mat()
            clahe.apply(roi, enhanced)
            val binary = Mat()
            Imgproc.threshold(enhanced, binary, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel)
            enhanced.release()

            // Loose per-ROI filter (solidity ≥0.60), as the live guide uses: the strict
            // findCandidates (≥0.88 + fill gate) rejected ragged/shadowed markers, so this
            // fallback wasn't rescuing the "Only N/4 quadrants" captures. Geometry is the
            // real distractor filter. ROI-local coords → offset to work coords.
            var cands = findCandidatesInROI(binary, sMin, sMax)
                .map { Candidate(Point(it.pt.x + qx, it.pt.y + qy), it.area) }
            binary.release()

            if (cands.isEmpty()) {
                retryQuadrantWithFallback(work, qx, qy, qw, qh, sMin, sMax)
                    ?.let { cands = listOf(it) }
            }
            roi.release()
            // Scale centres + areas back to full-res to match the global set and the refine.
            out[q] = cands.sortedByDescending { it.area }
                .take(MAX_CANDIDATES_PER_QUADRANT)
                .map { Candidate(Point(it.pt.x * inv, it.pt.y * inv), it.area * inv * inv) }
        }
        closeKernel.release()
        if (work !== grayMat) work.release()
        return out
    }

    private fun findCandidates(binaryMat: Mat, minArea: Int, maxArea: Int): List<Candidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binaryMat.clone(), contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()

        val results = mutableListOf<Candidate>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val bboxArea = rect.width * rect.height
            if (bboxArea < minArea || bboxArea > maxArea) { contour.release(); continue }

            val aspect = rect.width.toDouble() / rect.height
            if (aspect < MIN_MARKER_ASPECT || aspect > MAX_MARKER_ASPECT) { contour.release(); continue }

            val contourArea = Imgproc.contourArea(contour)
            val solidity = contourArea / bboxArea
            if (solidity < 0.88) { contour.release(); continue }

            val boundingRectFillRatio = contourArea / (rect.width.toDouble() * rect.height)
            if (boundingRectFillRatio < 0.65) { contour.release(); continue }

            results.add(Candidate(
                pt   = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0),
                area = contourArea
            ))
            contour.release()
        }
        return results
    }

    private fun topInQuadrant(
        candidates: List<Candidate>,
        filter: (Candidate) -> Boolean
    ): List<Candidate> =
        candidates.filter(filter).sortedByDescending { it.area }.take(MAX_CANDIDATES_PER_QUADRANT)

    /**
     * Live-analysis guide detection. Each corner quadrant is thresholded independently
     * (CLAHE + Otsu) so a shadow on one corner can't bleed into an adjacent quadrant.
     * Output order matches detect(): [topLeft, topRight, bottomLeft, bottomRight].
     */
    fun detectForGuide(
        grayMat: Mat,
        expectedQuadAspect: Double = 0.0,
        useCalibRois: Boolean = false,
        roiFrac: Array<FloatArray>? = null
    ): Array<Point?> {
        val calibFrac = roiFrac ?: MARKER_ROI_FRAC
        val imgW = grayMat.cols()
        val imgH = grayMat.rows()

        // Same 0.20×–3.0× band as detect() so guide and still agree on marker size and a
        // marker visible at analysis resolution is also found at still resolution; excludes
        // answer bubbles (~0.1×) that would let the guide lock a false quad.
        val side = imgW * 0.048
        val expectedArea = side * side
        val effectiveMin = (expectedArea * 0.20).toInt().coerceAtLeast(30)
        val effectiveMax = (expectedArea * 3.0).toInt()

        // quadGeom: (ox, oy, qw, qh) per corner in full-image coords
        val quadGeom: Array<IntArray> = if (useCalibRois) {
            Array(4) { q ->
                val f = calibFrac[q]
                val ox = (f[0] * imgW).toInt()
                val oy = (f[2] * imgH).toInt()
                val qw = ((f[1] * imgW).toInt().coerceAtMost(imgW) - ox).coerceAtLeast(1)
                val qh = ((f[3] * imgH).toInt().coerceAtMost(imgH) - oy).coerceAtLeast(1)
                intArrayOf(ox, oy, qw, qh)
            }
        } else {
            val roiW = imgW / 2
            val roiH = imgH / 2
            arrayOf(
                intArrayOf(0,           0,    roiW, roiH),
                intArrayOf(imgW - roiW, 0,    roiW, roiH),
                intArrayOf(0,           imgH - roiH, roiW, roiH),
                intArrayOf(imgW - roiW, imgH - roiH, roiW, roiH)
            )
        }

        val clahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
        // Candidate lists per quadrant, not just the largest, so the geometric selection can
        // pick the real marker when a distractor outranks it by area in a cluttered quadrant.
        val quadCands = Array(4) { emptyList<Candidate>() }

        for (idx in 0..3) {
            val (ox, oy, qw, qh) = quadGeom[idx]
            val roi = grayMat.submat(oy, oy + qh, ox, ox + qw)
            val enhanced = Mat()
            clahe.apply(roi, enhanced)
            val binary = Mat()
            Imgproc.threshold(
                enhanced, binary, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
            )
            val guideCloseKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, guideCloseKernel)
            guideCloseKernel.release()

            var cands = findCandidatesInROI(binary, effectiveMin, effectiveMax)
            binary.release()

            if (cands.isEmpty()) {
                // PASS 2 — Black Hat + Otsu: shadow-invariant, reveals a greyed-out marker
                // as a local dark blob however the primary pass mis-binarised it.
                val markerSide = (qw * 0.095).toInt().coerceIn(20, qw / 2)
                val ks = if (markerSide % 2 == 0) markerSide + 1 else markerSide
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, Size(ks.toDouble(), ks.toDouble())
                )
                val roiClone = roi.clone()
                val blackhat = Mat()
                Imgproc.morphologyEx(roiClone, blackhat, Imgproc.MORPH_BLACKHAT, kernel)
                val bhBinary = Mat()
                Imgproc.threshold(blackhat, bhBinary, 0.0, 255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

                cands = findCandidatesInROI(bhBinary, effectiveMin, effectiveMax)

                roiClone.release()
                blackhat.release()
                bhBinary.release()
                kernel.release()
            }

            if (cands.isEmpty()) {
                // PASS 3 — Adaptive threshold on the CLAHE ROI: compares each pixel to its
                // local mean, flagging a marker only slightly darker than its surroundings.
                val blockSize = (qw / 3).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(51)
                val adaptBinary = Mat()
                Imgproc.adaptiveThreshold(
                    enhanced, adaptBinary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                    blockSize, -8.0
                )

                cands = findCandidatesInROI(adaptBinary, effectiveMin, effectiveMax)
                adaptBinary.release()
            }

            enhanced.release()
            roi.release()

            quadCands[idx] = cands.map { Candidate(Point(it.pt.x + ox, it.pt.y + oy), it.area) }
        }

        // Prefer the 4 candidates that form a valid quad, so a distractor near a cluttered
        // corner can't win the lock and the corners stop jittering between blobs.
        if (quadCands.none { it.isEmpty() }) {
            pickBestSet(quadCands, expectedQuadAspect, expectedArea)?.let { best ->
                return arrayOf(best[0].pt, best[1].pt, best[2].pt, best[3].pt)
            }
        }
        // Fallback: per-quadrant largest, so the guide never regresses to "nothing found".
        return Array(4) { quadCands[it].maxByOrNull { c -> c.area }?.pt }
    }

    /**
     * Up to [MAX_CANDIDATES_PER_QUADRANT] candidates in a per-quadrant ROI binary, largest
     * first. A light solidity check rejects text/shadow edges that pass area/aspect.
     */
    private fun findCandidatesInROI(binaryMat: Mat, minArea: Int, maxArea: Int): List<Candidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binaryMat.clone(), contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()

        val results = ArrayList<Candidate>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val bboxArea = rect.width * rect.height
            val aspect = rect.width.toDouble() / rect.height
            val contourArea = Imgproc.contourArea(contour)
            val solidity = if (bboxArea > 0) contourArea / bboxArea else 0.0
            contour.release()

            if (bboxArea < minArea || bboxArea > maxArea) continue
            if (aspect < 0.60 || aspect > 1.67) continue
            if (solidity < 0.60) continue

            results.add(Candidate(
                pt = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0),
                area = contourArea
            ))
        }
        return results.sortedByDescending { it.area }.take(MAX_CANDIDATES_PER_QUADRANT)
    }

    /** Single best marker candidate, for per-quadrant live-analysis detection. */
    fun detectBest(binaryMat: Mat, minArea: Int = 150, maxArea: Int = 12_000): Point? =
        findCandidates(binaryMat, minArea, maxArea).maxByOrNull { it.area }?.pt

    // ── Single-capture: all 6 markers in one frame ────────────────────────
    // detect()/detectForGuide() see one zone (4 markers) at a known orientation;
    // single-capture sees the whole landscape sheet at any rotation. Keep the largest solid
    // squares (the 6 markers are the biggest), pick the 4 forming the marker rectangle
    // (long:short ≈ 2.16), then locate the 2 edge-mid markers and label all six by geometry:
    //   • the mid splits its long edge 920:2524 (~0.267 from the INFO end), fixing
    //     left/right + rotation;
    //   • quad winding (shoelace sign, rotation-invariant) fixes top/bottom — so the
    //     labeling is unique for any of the 4 rotations.

    /**
     * Detects all 6 markers in a full-sheet [binaryMat] (Otsu-inverted, markers = white)
     * and returns centres in role order [TL, TM, TR, BL, BM, BR], sub-pixel refined on
     * [grayMat]. Null when a valid 6-marker grid can't be formed.
     */
    fun detectSix(binaryMat: Mat, grayMat: Mat? = null): List<Point>? {
        val imgW = binaryMat.cols()
        val minSide = imgW * SIX_MIN_SIDE_FRAC
        val maxSide = imgW * SIX_MAX_SIDE_FRAC
        val minArea = (minSide * minSide).toInt().coerceAtLeast(20)
        val maxArea = (maxSide * maxSide).toInt().coerceAtLeast(minArea + 1)
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(binaryMat, binaryMat, Imgproc.MORPH_CLOSE, closeKernel)
        closeKernel.release()
        val cands = findSquareCandidates(binaryMat, minArea, maxArea, minSolidity = 0.80)
        val six = selectSixGrid(cands, imgW) ?: return null
        return refineToSubPixel(grayMat, six)
    }

    /**
     * Live-analysis counterpart of [detectSix]. Whole-frame CLAHE + Otsu (no
     * orientation-specific ROIs — the sheet can be at any rotation), then the same grid
     * selection. Returns [TL, TM, TR, BL, BM, BR]; all null when the grid isn't found (the
     * lock is all-or-nothing, so the overlay shows 6 dots only once the full grid is found).
     */
    fun detectSixForGuide(grayMat: Mat): Array<Point?> {
        val imgW = grayMat.cols()
        val clahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
        val enhanced = Mat()
        clahe.apply(grayMat, enhanced)
        val binary = Mat()
        Imgproc.threshold(enhanced, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        enhanced.release()
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel)
        closeKernel.release()
        val minSide = imgW * SIX_MIN_SIDE_FRAC
        val maxSide = imgW * SIX_MAX_SIDE_FRAC
        val minArea = (minSide * minSide).toInt().coerceAtLeast(12)
        val maxArea = (maxSide * maxSide).toInt().coerceAtLeast(minArea + 1)
        // Looser solidity for the coarse CLAHE-only analysis threshold (as the 4-marker guide).
        val cands = findSquareCandidates(binary, minArea, maxArea, minSolidity = 0.60)
        binary.release()
        val six = selectSixGrid(cands, imgW)
        return if (six == null) arrayOfNulls(6) else Array(6) { six[it] }
    }

    /**
     * Selects and labels the 6 markers from the square [candidates]. Returns
     * [TL, TM, TR, BL, BM, BR] or null. See [detectSix] for the geometric rationale.
     */
    private fun selectSixGrid(candidates: List<Candidate>, imgW: Int): List<Point>? {
        if (candidates.size < 6) return null
        // Top-K by area keeps the O(K⁴) corner search cheap while still holding the 6 markers.
        val top = candidates.sortedByDescending { it.area }.take(SIX_TOP_K)
        val corners = bestCornerQuad(top) ?: run {
            if (BuildConfig.DEBUG) Log.d("MarkerDetector", "detectSix: no corner quad among ${top.size} candidates")
            return null
        }
        return labelSix(corners, top)
    }

    /**
     * Picks the 4 candidates best forming the marker rectangle (convex, opposite sides
     * near-equal, long:short ≈ [MARKER_RECT_ASPECT]). The 2 edge-mid markers are excluded:
     * swapping a corner for a mid fails convexity (3 collinear) or the side-ratio gate.
     * Returns the corners in convex order, or null.
     */
    private fun bestCornerQuad(cands: List<Candidate>): List<Point>? {
        val n = cands.size
        if (n < 4) return null
        var best: List<Point>? = null
        var bestErr = Double.MAX_VALUE
        for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) for (l in k + 1 until n) {
            val quad = orderConvex(listOf(cands[i].pt, cands[j].pt, cands[k].pt, cands[l].pt)) ?: continue
            val areas = listOf(cands[i].area, cands[j].area, cands[k].area, cands[l].area)
            if (areas.max() / areas.min() > MAX_PERSPECTIVE_AREA_RATIO) continue
            // Side lengths in convex order; e0/e2 and e1/e3 are the opposite pairs.
            val s = DoubleArray(4) { dist(quad[it], quad[(it + 1) % 4]) }
            if (s.any { it < 1.0 }) continue
            val pairA = (s[0] + s[2]) / 2.0
            val pairB = (s[1] + s[3]) / 2.0
            val longSide = maxOf(pairA, pairB)
            val shortSide = minOf(pairA, pairB)
            // Opposite sides near-equal (perspective-bounded).
            if (maxOf(s[0], s[2]) / minOf(s[0], s[2]) > MAX_OPPOSITE_SIDE_RATIO) continue
            if (maxOf(s[1], s[3]) / minOf(s[1], s[3]) > MAX_OPPOSITE_SIDE_RATIO) continue
            val aspect = longSide / shortSide
            val aspectErr = kotlin.math.abs(kotlin.math.ln(aspect / MARKER_RECT_ASPECT))
            if (aspectErr > SIX_ASPECT_LOG_ERR) continue
            val sideErr = maxOf(s[0], s[2]) / minOf(s[0], s[2]) - 1.0 +
                maxOf(s[1], s[3]) / minOf(s[1], s[3]) - 1.0
            val err = aspectErr + sideErr
            if (err < bestErr) { bestErr = err; best = quad }
        }
        return best
    }

    /**
     * Labels the 6 markers from the 4 [corners] (convex order) and all [cands]: finds the
     * mid on each long edge, derives the info/answer ends from its 920:2524 split, and
     * resolves top/bottom by winding. Returns [TL, TM, TR, BL, BM, BR] or null.
     */
    private fun labelSix(corners: List<Point>, cands: List<Candidate>): List<Point>? {
        val s = DoubleArray(4) { dist(corners[it], corners[(it + 1) % 4]) }
        // Long edges = the opposite pair with the greater mean length.
        val longIsE0E2 = (s[0] + s[2]) >= (s[1] + s[3])
        val shortLen = if (longIsE0E2) (s[1] + s[3]) / 2.0 else (s[0] + s[2]) / 2.0
        val edgeAIdx = if (longIsE0E2) 0 else 1
        val edgeCIdx = if (longIsE0E2) 2 else 3
        val a0 = corners[edgeAIdx]; val a1 = corners[(edgeAIdx + 1) % 4]
        val c0 = corners[edgeCIdx]; val c1 = corners[(edgeCIdx + 1) % 4]
        val cornerPts = corners
        val midA = findMidMarker(a0, a1, shortLen, cands, cornerPts) ?: return null
        val midC = findMidMarker(c0, c1, shortLen, cands, cornerPts) ?: return null

        // Info end = the corner the mid is nearest (mid is ~0.267 along edge from it).
        val infoA = if (midA.t < 0.5) a0 else a1
        val answerA = if (midA.t < 0.5) a1 else a0
        val infoC = if (midC.t < 0.5) c0 else c1
        val answerC = if (midC.t < 0.5) c1 else c0

        // Two top/bottom assignments; pick the one whose TL→TR→BR→BL winds positive
        // (matches the reference sheet — preserved under any in-plane rotation).
        val opt1 = shoelace(infoA, answerA, answerC, infoC)
        return if (opt1 > 0)
            listOf(infoA, midA.pt, answerA, infoC, midC.pt, answerC)   // A=top, C=bottom
        else
            listOf(infoC, midC.pt, answerC, infoA, midA.pt, answerA)   // C=top, A=bottom
    }

    private class MidHit(val pt: Point, val t: Double)

    /**
     * Finds the edge-mid marker on long edge [p0]→[p1]: small perpendicular distance,
     * projection fraction near 0.267/0.733, not one of [corners]. Returns point + t, or null.
     */
    private fun findMidMarker(
        p0: Point, p1: Point, shortLen: Double, cands: List<Candidate>, corners: List<Point>
    ): MidHit? {
        val ex = p1.x - p0.x; val ey = p1.y - p0.y
        val len2 = ex * ex + ey * ey
        if (len2 < 1.0) return null
        val maxPerp = MID_PERP_DIST_FRAC * shortLen
        var best: MidHit? = null
        var bestErr = Double.MAX_VALUE
        for (c in cands) {
            if (corners.any { kotlin.math.hypot(it.x - c.pt.x, it.y - c.pt.y) < shortLen * 0.25 }) continue
            val t = ((c.pt.x - p0.x) * ex + (c.pt.y - p0.y) * ey) / len2
            if (t <= 0.05 || t >= 0.95) continue
            val projX = p0.x + t * ex; val projY = p0.y + t * ey
            val perp = kotlin.math.hypot(c.pt.x - projX, c.pt.y - projY)
            if (perp > maxPerp) continue
            val tErr = minOf(kotlin.math.abs(t - 0.267), kotlin.math.abs(t - 0.733))
            if (tErr > MID_T_TOL) continue
            val err = tErr + perp / maxPerp
            if (err < bestErr) { bestErr = err; best = MidHit(c.pt, t) }
        }
        return best
    }

    private fun dist(a: Point, b: Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    /** Shoelace signed-area sum of the 4 points in order (sign = winding). */
    private fun shoelace(p0: Point, p1: Point, p2: Point, p3: Point): Double {
        val p = listOf(p0, p1, p2, p3)
        var sum = 0.0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return sum
    }

    /** Orders 4 points into convex (angle-sorted) order, or null if 3 are collinear. */
    private fun orderConvex(pts: List<Point>): List<Point>? {
        val cx = pts.sumOf { it.x } / 4.0
        val cy = pts.sumOf { it.y } / 4.0
        val ordered = pts.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        // Reject if any 3 consecutive are (near-)collinear — that means a mid was
        // mistaken for a corner.
        for (i in 0 until 4) {
            val a = ordered[i]; val b = ordered[(i + 1) % 4]; val c = ordered[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (kotlin.math.abs(cross) < 1e-3) return null
        }
        return ordered
    }

    /**
     * Whole-image square-candidate search for the 6-marker detectors. [minSolidity] is
     * relaxed for the coarse live-analysis threshold.
     */
    private fun findSquareCandidates(
        binaryMat: Mat, minArea: Int, maxArea: Int, minSolidity: Double
    ): List<Candidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binaryMat.clone(), contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()
        val results = ArrayList<Candidate>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val bboxArea = rect.width * rect.height
            val aspect = if (rect.height > 0) rect.width.toDouble() / rect.height else 0.0
            val contourArea = Imgproc.contourArea(contour)
            val solidity = if (bboxArea > 0) contourArea / bboxArea else 0.0
            contour.release()
            if (bboxArea < minArea || bboxArea > maxArea) continue
            if (aspect < 0.62 || aspect > 1.6) continue
            if (solidity < minSolidity) continue
            results.add(Candidate(
                pt = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0),
                area = contourArea
            ))
        }
        return results
    }

    companion object {
        // Max area spread among the 4 chosen markers. Real markers measure ≤1.3×, perspective
        // ~2–3×; 3.0 rejects mixed false sets (real marker + bubbles, ratio ~5–6) the old 8.0 let pass.
        private const val MAX_PERSPECTIVE_AREA_RATIO = 3.0
        // Size-preference weight in pickBestSet: scales the area-shortfall fraction into the
        // geometric error, above the ~0.1–0.3 spread of valid sets so a larger real marker
        // outscores a shaded bubble, but below the hard convex/aspect/side gates.
        private const val MARKER_AREA_PREF_WEIGHT = 1.5
        private const val MIN_MARKER_ASPECT = 0.72
        private const val MAX_MARKER_ASPECT = 1.38
        // Was 3, but a cluttered quadrant can rank several non-markers above the real marker
        // by area, excluding it before geometry runs. Keep more; pickBestSet is cheap O(K⁴).
        private const val MAX_CANDIDATES_PER_QUADRANT = 8
        // 4 coplanar markers under tilt form a general convex quad, not a parallelogram.
        // Widened from 1.40 / 0.12, which rejected valid angled captures; isConvexQuad
        // compensates for the looser bounds.
        private const val MAX_OPPOSITE_SIDE_RATIO = 1.75
        private const val MAX_DIAG_MIDPOINT_FRAC = 0.22
        // Quad aspect may deviate ±35% under tilt/curve.
        private val MAX_ASPECT_LOG_ERROR = kotlin.math.ln(1.35)
        // Max image dim for the local fallback search. A 12 MP capture downscaled here still
        // shows markers at ~90+ px while making the large blackhat kernel tractable; corners
        // are cornerSubPix-refined on full-res afterwards.
        private const val FALLBACK_MAX_DIM = 2000

        // ── Single-capture (6-marker) geometry ────────────────────────────────
        // Marker side ×image width. The whole landscape sheet is in frame, so a marker
        // (≈2.15% of a 3900 px sheet) is smaller than in zone captures and shrinks more when
        // the sheet doesn't fill the frame — hence a wider band. Calibrate on-device.
        private const val SIX_MIN_SIDE_FRAC = 0.008
        private const val SIX_MAX_SIDE_FRAC = 0.060
        // Top-K largest solid squares fed to the O(K⁴) corner search.
        private const val SIX_TOP_K = 14
        // The marker rectangle is 3444×1596 → long:short.
        private val MARKER_RECT_ASPECT = 3444.0 / 1596.0
        // Quad aspect may deviate ±50% under tilt/curve.
        private val SIX_ASPECT_LOG_ERR = kotlin.math.ln(1.5)
        // Mid marker's perpendicular distance to its long edge (×short side); and the
        // tolerance on its 0.267/0.733 projection fraction.
        private const val MID_PERP_DIST_FRAC = 0.18
        private const val MID_T_TOL = 0.18

        // Padded per-corner ROI fractions (tools/calibrate_phase2_rois.py): ground-truth mean
        // ± 2.5σ + 0.12 pad, clamped to quadrant bounds. [cx_lo, cx_hi, cy_lo, cy_hi]; order [TL, TR, BL, BR].
        private val MARKER_ROI_FRAC = arrayOf(
            floatArrayOf(0.00f, 0.41f, 0.00f, 0.42f),  // TL (Phase 2 TM)
            floatArrayOf(0.64f, 1.00f, 0.00f, 0.45f),  // TR (Phase 2 TR)
            floatArrayOf(0.00f, 0.40f, 0.60f, 1.00f),  // BL (Phase 2 BM)
            floatArrayOf(0.67f, 1.00f, 0.58f, 1.00f),  // BR (Phase 2 BR)
        )

        // Phase-1 (info zone) calibrated ROIs from 44 hand-annotated captures
        // (tools/annotate_phase1_markers.py): per-corner mean ± 2.5σ, padded for framing
        // tolerance generously toward the edges/centre gap but only ~0.04 toward the answer
        // grid on TM/BM's right so a shaded answer bubble can't enter. Order [TL, TM, BL, BM].
        val MARKER_ROI_FRAC_PHASE1 = arrayOf(
            floatArrayOf(0.079f, 0.426f, 0.040f, 0.386f),  // TL
            // TM cx_lo raised 0.573 → 0.640: the shaded SET "B" bubble at cx ≈ 0.57 read as
            // a marker and was intermittently picked over the real TM at cx ≈ 0.735. TM mean
            // is 0.735 (σ≈0.025–0.04), so 0.640 still admits it while keeping the SET box out.
            floatArrayOf(0.640f, 0.837f, 0.035f, 0.371f),  // TM
            floatArrayOf(0.038f, 0.450f, 0.656f, 1.000f),  // BL
            floatArrayOf(0.581f, 0.868f, 0.665f, 1.000f),  // BM
        )
        private const val HINT_RADIUS_FRAC = 0.10
        // Post-capture WYSIWYG gate: reject the still if any marker sits farther than this
        // from its locked hint (it locked onto a different square than the dots showed).
        // Tighter than HINT_RADIUS_FRAC to catch the drift, loose enough to absorb
        // preview-vs-still jitter (a mis-pick lands ≫0.1 away). Tune on-device.
        const val MARKER_HINT_MAX_DIVERGENCE_FRAC = 0.06
        // reconcileWithHints snap tolerance: good detections sit ≤~0.03 from their hint,
        // mis-picks ≥~0.10.
        private const val PER_CORNER_HINT_TOL = 0.08
    }

    /**
     * Max distance between each detected marker and its hint, ×image width, for the
     * post-capture WYSIWYG gate. [detected] is detect()'s output; [hints] are [0,1]
     * fractions. Null hints/entries are skipped; 0.0 when nothing can be compared.
     */
    fun maxHintDivergenceFrac(
        detected: List<Point>, hints: Array<Point?>?, imgW: Int, imgH: Int
    ): Double {
        if (hints == null || imgW <= 0) return 0.0
        var maxD = 0.0
        for (q in detected.indices) {
            val h = hints.getOrNull(q) ?: continue
            val d = kotlin.math.hypot(detected[q].x - h.x * imgW, detected[q].y - h.y * imgH) / imgW
            if (d > maxD) maxD = d
        }
        return maxD
    }

    /**
     * Corrects the still's re-detected markers against the stable live-lock hints [hintPx]
     * (still-pixel coords, refined by [refineToNearestSquare]). The still re-detection is
     * the flaky part (global Otsu often finds candidates in only 0–2 quadrants). Per corner,
     * keep the detected point when it agrees with its hint, else (diverged past
     * [PER_CORNER_HINT_TOL]) snap to the hint; when detection failed entirely but all 4
     * hints exist, use the hints. Null only when nothing is usable; no hints → [detected].
     */
    fun reconcileWithHints(
        detected: List<Point>?, hintPx: List<Point?>?, imgW: Int
    ): List<Point>? {
        if (hintPx == null || imgW <= 0) return detected
        if (detected == null) {
            return if (hintPx.size == 4 && hintPx.all { it != null }) hintPx.map { it!! } else null
        }
        var snapped = 0
        val out = ArrayList<Point>(detected.size)
        for (q in detected.indices) {
            val h = hintPx.getOrNull(q)
            if (h != null) {
                val d = kotlin.math.hypot(detected[q].x - h.x, detected[q].y - h.y) / imgW
                if (d > PER_CORNER_HINT_TOL) { out.add(h); snapped++ } else out.add(detected[q])
            } else out.add(detected[q])
        }
        if (snapped > 0) Log.w("MarkerDetector",
            "reconcileWithHints: snapped $snapped/${detected.size} mis-picked corner(s) to the live lock")
        return out
    }

    /**
     * Refines a live-lock hint onto the centre of the nearby black registration square. The
     * guide's centroid can sit a few px off, biasing any corner snapped to it. Searches
     * ±~one marker side around [p] (small enough to exclude the next marker), Otsu-thresholds
     * (paper vs the lone square is bimodal), and returns the bounding-rect centre of the
     * nearest marker-sized solid square. Falls back to [p] when none is found.
     */
    fun refineToNearestSquare(gray: Mat, p: Point, imgW: Int): Point {
        val side = imgW * 0.048
        val expectedArea = side * side
        val half = side.toInt().coerceAtLeast(8)
        val x0 = (p.x - half).toInt().coerceAtLeast(0)
        val y0 = (p.y - half).toInt().coerceAtLeast(0)
        val x1 = (p.x + half).toInt().coerceAtMost(gray.cols())
        val y1 = (p.y + half).toInt().coerceAtMost(gray.rows())
        if (x1 - x0 < 8 || y1 - y0 < 8) return p

        val roi = Mat(gray, Rect(x0, y0, x1 - x0, y1 - y0))
        val bin = Mat()
        Imgproc.threshold(roi, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        roi.release()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, k)
        k.release()

        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        bin.release(); hier.release()

        var best: Point? = null
        var bestDist = Double.MAX_VALUE
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            val br = Imgproc.boundingRect(c)
            c.release()
            if (area < 0.18 * expectedArea || area > 3.0 * expectedArea) continue
            val aspect = if (br.height > 0) br.width.toDouble() / br.height else 0.0
            if (aspect < 0.6 || aspect > 1.7) continue
            val solidity = if (br.width * br.height > 0) area / (br.width.toDouble() * br.height) else 0.0
            if (solidity < 0.80) continue
            val cx = x0 + br.x + br.width / 2.0
            val cy = y0 + br.y + br.height / 2.0
            val d = kotlin.math.hypot(cx - p.x, cy - p.y)
            if (d < bestDist) { bestDist = d; best = Point(cx, cy) }
        }
        return best ?: p
    }

    private fun markerRois(imgW: Int, imgH: Int, frac: Array<FloatArray> = MARKER_ROI_FRAC): Array<Rect> =
        Array(4) { q ->
            val f = frac[q]
            val x0 = (f[0] * imgW).toInt()
            val y0 = (f[2] * imgH).toInt()
            val x1 = (f[1] * imgW).toInt().coerceAtMost(imgW)
            val y1 = (f[3] * imgH).toInt().coerceAtMost(imgH)
            Rect(x0, y0, x1 - x0, y1 - y0)
        }

    private fun midpointRois(imgW: Int, imgH: Int): Array<Rect> {
        val midX = imgW / 2
        val midY = imgH / 2
        return arrayOf(
            Rect(0,    0,    midX,        midY),
            Rect(midX, 0,    imgW - midX, midY),
            Rect(0,    midY, midX,        imgH - midY),
            Rect(midX, midY, imgW - midX, imgH - midY)
        )
    }
}
