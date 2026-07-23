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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds the 4 corners of a bordered rectangular box (STUDENT ID or TEST QUESTION SET)
 * within a region of an already-warped grayscale Mat. The caller warps the box into its
 * own canonical sub-canvas using these corners, removing per-capture origin drift and
 * residual perspective in one step, so cell coordinates inside it never shift.
 */
class BoxFinder {

    /**
     * @param corners   box corners [topLeft, topRight, bottomLeft, bottomRight] in
     *                  full-canvas pixels, ready for [PerspectiveCorrector.warp].
     * @param searchRoi searched ROI, full-canvas coords (debug).
     * @param boxRect   box bounding rect, full-canvas coords (debug).
     */
    data class BoxResult(
        val corners: List<Point>,
        val searchRoi: Rect,
        val boxRect: Rect,
        /** Winning contour, full-canvas coords (clone — caller releases). CurveRectifier
         *  rectifies from this border silhouette rather than a 4-corner warp. */
        val contour: MatOfPoint? = null
    )

    /**
     * Searches [xFrac]×[yFrac] of [warpedGray] for the largest near-rectangular contour of
     * the expected orientation and confirms 4 corners via approxPolyDP.
     *
     * @param label tags log lines so rejections trace back to the calling search.
     * @param tall true → portrait box (STUDENT ID); false → landscape (TEST QUESTION SET).
     * @param expectedW/expectedH reference box bounding-rect size in canvas pixels.
     * @param areaFracRange acceptable contour area as a fraction of the ROI area.
     * @param maxBottomFracOfRoi upper bound on the contour's bottom edge (×ROI height),
     *                  rejecting candidates that snap to the "IMPORTANT" section below.
     * @param maxScaleFactor/minScaleFactor reject detections out of expectedW/H × factor.
     *                  Paper can only foreshorten a box, never enlarge it, so oversized is
     *                  always a false contour (border merged with a neighbor).
     * @return box corners + debug rects, or null if no matching 4-corner box was found.
     */
    fun findBox(
        warpedGray: Mat,
        label: String,
        xFrac: ClosedFloatingPointRange<Double>,
        yFrac: ClosedFloatingPointRange<Double>,
        tall: Boolean,
        expectedW: Int,
        expectedH: Int,
        areaFracRange: ClosedFloatingPointRange<Double> = 0.03..0.85,
        maxBottomFracOfRoi: Double? = null,
        maxScaleFactor: Double = 1.20,
        minScaleFactor: Double = 0.50
    ): BoxResult? {
        val canvasW = warpedGray.cols()
        val canvasH = warpedGray.rows()

        val x0 = (xFrac.start * canvasW).toInt()
        val x1 = (xFrac.endInclusive * canvasW).toInt()
        val y0 = (yFrac.start * canvasH).toInt()
        val y1 = (yFrac.endInclusive * canvasH).toInt()
        val searchRoi = Rect(x0, y0, x1 - x0, y1 - y0)

        val roiMat = Mat(warpedGray, searchRoi)
        val binary = Mat()
        Imgproc.adaptiveThreshold(roiMat, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 21, 8.0)
        roiMat.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
        binary.release()
        kernel.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        closed.release()
        hierarchy.release()

        val roiArea = searchRoi.width.toDouble() * searchRoi.height
        val minArea = roiArea * areaFracRange.start
        val maxArea = roiArea * areaFracRange.endInclusive

        // Top 5 contours by area, pre-filter, to diagnose why none pass.
        if (BuildConfig.DEBUG) {
            contours
                .map { c ->
                    val a = Imgproc.contourArea(c)
                    val br = Imgproc.boundingRect(c)
                    val aspect = if (br.height > 0) br.width.toDouble() / br.height else 0.0
                    Triple(a, br, aspect)
                }
                .sortedByDescending { it.first }
                .take(5)
                .forEachIndexed { i, (a, br, aspect) ->
                    Log.v(TAG, "findBox[$label]: top contour #$i area=${"%.0f".format(a)} bbox=$br aspect=${"%.3f".format(aspect)} (roiArea=${"%.0f".format(roiArea)} minArea=${"%.0f".format(minArea)} maxArea=${"%.0f".format(maxArea)} tall=$tall)")
                }
        }

        var bestCorners: List<Point>? = null
        var bestBoxRect: Rect? = null
        var bestArea = 0.0
        var bestTier = 0
        var bestContour: MatOfPoint? = null

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) { contour.release(); continue }

            val br = Imgproc.boundingRect(contour)
            val aspect = if (br.height > 0) br.width.toDouble() / br.height else 0.0
            val aspectOk = if (tall) aspect < 0.95 else aspect > 1.05
            if (!aspectOk) { contour.release(); continue }

            if (maxBottomFracOfRoi != null) {
                val bottomLimit = maxBottomFracOfRoi * searchRoi.height
                if (br.y + br.height >= bottomLimit) {
                    if (BuildConfig.DEBUG) {
                        Log.v(TAG, "findBox[$label]: REJECTED contour bbox=$br — bottom=${br.y + br.height} " +
                            "exceeds maxBottomFracOfRoi limit=${bottomLimit.toInt()} (roiHeight=${searchRoi.height}, frac=$maxBottomFracOfRoi)")
                    }
                    contour.release(); continue
                }
            }

            if (area > bestArea) {
                val resolved = resolveCornersWithinSizeGate(
                    warpedGray, contour, br, x0, y0, label,
                    expectedW, expectedH, maxScaleFactor, minScaleFactor
                )
                if (resolved != null) {
                    val (corners, tier) = resolved
                    bestArea = area
                    bestBoxRect = Rect(br.x + x0, br.y + y0, br.width, br.height)
                    bestCorners = corners
                    bestTier = tier
                    // Clone the winner, translated ROI→full-canvas, for the curve rectifier.
                    bestContour?.release()
                    bestContour = MatOfPoint(*contour.toArray().map { Point(it.x + x0, it.y + y0) }.toTypedArray())
                }
            }
            contour.release()
        }

        if (bestCorners == null || bestBoxRect == null) {
            bestContour?.release()
            Log.w(TAG, "findBox[$label]: no contour produced corners within the size gate — " +
                "returning null (searchRoi=$searchRoi, tall=$tall, contours=${contours.size})")
            return null
        }

        val xs = bestCorners.map { it.x }
        val ys = bestCorners.map { it.y }
        val detW = xs.max() - xs.min()
        val detH = ys.max() - ys.min()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "findBox[$label]: accepted tier=$bestTier ${detW.toInt()}×${detH.toInt()} (expected ~${expectedW}×${expectedH})")
        }

        val refinedCorners = refineCornersSubPix(warpedGray, bestCorners)

        if (BuildConfig.DEBUG) {
            Log.v(TAG, "findBox[$label]: found box=$bestBoxRect corners=$refinedCorners")
        }
        return BoxResult(refinedCorners, searchRoi, bestBoxRect, bestContour)
    }

    /**
     * Resolves a contour's 4 corners by trying tiers in turn — approxPolyDP, Hough line
     * intersection, then bounding rect — each validated against the size gate (a box can
     * only foreshorten, so out-of-range is a false contour for any tier).
     *
     * A degenerate early tier (e.g. approxPolyDP collapsing a bowed outline to a sliver)
     * must not short-circuit the search; a later tier may still recover the box. Tier 3's
     * bounding rect matches the already area+aspect-filtered contour, so it passes the gate
     * whenever any tier could.
     *
     * @return accepted corners + tier (1/2/3), or null if every tier failed the gate.
     */
    private fun resolveCornersWithinSizeGate(
        warpedGray: Mat,
        contour: MatOfPoint,
        br: Rect,
        x0: Int,
        y0: Int,
        label: String,
        expectedW: Int,
        expectedH: Int,
        maxScaleFactor: Double,
        minScaleFactor: Double
    ): Pair<List<Point>, Int>? {
        val maxW = expectedW * maxScaleFactor
        val maxH = expectedH * maxScaleFactor
        val minW = expectedW * minScaleFactor
        val minH = expectedH * minScaleFactor

        fun passesSizeGate(corners: List<Point>, tier: Int): Boolean {
            val xs = corners.map { it.x }
            val ys = corners.map { it.y }
            val detW = xs.max() - xs.min()
            val detH = ys.max() - ys.min()
            val ok = detW in minW..maxW && detH in minH..maxH
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "findBox[$label]: tier$tier candidate detected=${detW.toInt()}×${detH.toInt()} " +
                    "allowed=[${minW.toInt()}–${maxW.toInt()}] × [${minH.toInt()}–${maxH.toInt()}] " +
                    if (ok) "— passes" else "— REJECTED, trying next tier")
            }
            return ok
        }

        // Tier 1: approxPolyDP reduces the contour to 4 corners (clean, flat paper).
        val contour2f = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(contour2f, true)
        val approx2f = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx2f, 0.02 * peri, true)
        val approxPoints = approx2f.toArray()
        contour2f.release()
        approx2f.release()

        if (approxPoints.size == 4) {
            val corners = orderCorners(approxPoints).map { Point(it.x + x0, it.y + y0) }
            if (passesSizeGate(corners, 1)) return corners to 1
        }

        // Tier 2: approxPolyDP failed (curved/creased paper or merged interior printing);
        // try Hough line intersection before falling back to the bounding rect.
        val hough = findCornersByHoughIntersection(warpedGray, br, x0, y0)
        if (hough != null && passesSizeGate(hough, 2)) return hough to 2

        // Tier 3 (last resort): the bounding rect is a valid 4-corner box and matches the
        // already-filtered contour, so it clears the gate when earlier tiers went degenerate.
        val bboxCorners = listOf(
            Point((br.x + x0).toDouble(), (br.y + y0).toDouble()),
            Point((br.x + br.width + x0).toDouble(), (br.y + y0).toDouble()),
            Point((br.x + x0).toDouble(), (br.y + br.height + y0).toDouble()),
            Point((br.x + br.width + x0).toDouble(), (br.y + br.height + y0).toDouble())
        )
        if (passesSizeGate(bboxCorners, 3)) return bboxCorners to 3

        return null
    }

    /**
     * Tier 2: when approxPolyDP can't reduce a contour to 4 points (bowed paper adds
     * vertices), find the box's 4 edges as dominant Hough lines and intersect them,
     * recovering a perspective-correct quad instead of a skew-ignoring bounding rect.
     *
     * @param boundingRect contour bounding rect, ROI-local coords.
     * @param roiOffsetX/Y searchRoi offset, to translate results to full-canvas space.
     * @return [topLeft, topRight, bottomLeft, bottomRight] full-canvas, or null.
     */
    private fun findCornersByHoughIntersection(
        warpedGray: Mat,
        boundingRect: Rect,
        roiOffsetX: Int,
        roiOffsetY: Int
    ): List<Point>? {
        val pad = 4
        val canvasW = warpedGray.cols()
        val canvasH = warpedGray.rows()

        val fullX = boundingRect.x + roiOffsetX
        val fullY = boundingRect.y + roiOffsetY
        val left = (fullX - pad).coerceAtLeast(0)
        val top = (fullY - pad).coerceAtLeast(0)
        val right = (fullX + boundingRect.width + pad).coerceAtMost(canvasW)
        val bottom = (fullY + boundingRect.height + pad).coerceAtMost(canvasH)
        val localRoi = Rect(left, top, right - left, bottom - top)
        if (localRoi.width <= 0 || localRoi.height <= 0) return null

        val roiMat = Mat(warpedGray, localRoi)
        val edges = Mat()
        Imgproc.Canny(roiMat, edges, 40.0, 120.0)
        roiMat.release()

        val lines = Mat()
        val houghThreshold = (minOf(localRoi.width, localRoi.height) / 3).coerceAtLeast(1)
        Imgproc.HoughLines(edges, lines, 1.0, PI / 180.0, houghThreshold)
        edges.release()

        if (lines.empty()) {
            lines.release()
            return null
        }

        val allLines = ArrayList<HoughLine>(lines.rows())
        for (i in 0 until lines.rows()) {
            val row = lines.get(i, 0)
            allLines.add(HoughLine(rho = row[0], theta = row[1]))
        }
        lines.release()

        val horizontal = allLines.filter { abs(it.theta - PI / 2.0) < PI / 6.0 }
        val vertical = allLines.filter { it.theta < PI / 6.0 || it.theta > 5.0 * PI / 6.0 }
        if (horizontal.size < 2 || vertical.size < 2) return null

        val top1 = clusterAndPick(horizontal, pickSmallestRho = true) ?: return null
        val bottom1 = clusterAndPick(horizontal, pickSmallestRho = false) ?: return null
        val left1 = clusterAndPick(vertical, pickSmallestRho = true) ?: return null
        val right1 = clusterAndPick(vertical, pickSmallestRho = false) ?: return null

        val tl = intersectLines(top1, left1, localRoi.x, localRoi.y) ?: return null
        val tr = intersectLines(top1, right1, localRoi.x, localRoi.y) ?: return null
        val bl = intersectLines(bottom1, left1, localRoi.x, localRoi.y) ?: return null
        val brPt = intersectLines(bottom1, right1, localRoi.x, localRoi.y) ?: return null

        return listOf(tl, tr, bl, brPt)
    }

    /** A line in Hough normal-form: x·cos(theta) + y·sin(theta) = rho. */
    private data class HoughLine(val rho: Double, val theta: Double)

    /**
     * Clusters [lines] by rho proximity (≤15px), takes each cluster's median rho, and
     * returns the smallest- or largest-rho cluster — the box's outermost edge.
     */
    private fun clusterAndPick(lines: List<HoughLine>, pickSmallestRho: Boolean): HoughLine? {
        if (lines.isEmpty()) return null
        val sorted = lines.sortedBy { it.rho }
        val clusters = ArrayList<MutableList<HoughLine>>()
        var current = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            if (abs(sorted[i].rho - sorted[i - 1].rho) <= 15.0) {
                current.add(sorted[i])
            } else {
                clusters.add(current)
                current = mutableListOf(sorted[i])
            }
        }
        clusters.add(current)

        val merged = clusters.map { cluster ->
            val rhos = cluster.map { it.rho }.sorted()
            val medianRho = if (rhos.size % 2 == 1) rhos[rhos.size / 2]
            else (rhos[rhos.size / 2 - 1] + rhos[rhos.size / 2]) / 2.0
            HoughLine(rho = medianRho, theta = cluster.map { it.theta }.average())
        }
        return if (pickSmallestRho) merged.minByOrNull { it.rho } else merged.maxByOrNull { it.rho }
    }

    /** Intersects two Hough lines and offsets the result ROI-local → full-canvas. */
    private fun intersectLines(l1: HoughLine, l2: HoughLine, offsetX: Int, offsetY: Int): Point? {
        val denom = sin(l2.theta - l1.theta)
        if (abs(denom) < 1e-6) return null
        val x = (l1.rho * sin(l2.theta) - l2.rho * sin(l1.theta)) / denom
        val y = (l2.rho * cos(l1.theta) - l1.rho * cos(l2.theta)) / denom
        return Point(x + offsetX, y + offsetY)
    }

    /**
     * Sub-pixel refines each corner to the nearest gradient extremum, correcting the
     * few-pixel error the tier estimators leave on curved paper.
     */
    private fun refineCornersSubPix(warpedGray: Mat, corners: List<Point>): List<Point> {
        val corners2f = MatOfPoint2f(*corners.toTypedArray())
        Imgproc.cornerSubPix(
            warpedGray,
            corners2f,
            Size(7.0, 7.0),
            Size(-1.0, -1.0),
            TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.01)
        )
        val refined = corners2f.toList()
        corners2f.release()
        return refined
    }

    /** Orders 4 arbitrary corner points as [topLeft, topRight, bottomLeft, bottomRight]. */
    private fun orderCorners(points: Array<Point>): List<Point> {
        val bySum = points.sortedBy { it.x + it.y }
        val tl = bySum.first()
        val br = bySum.last()
        val byDiff = points.sortedBy { it.x - it.y }
        val bl = byDiff.first()
        val tr = byDiff.last()
        return listOf(tl, tr, bl, br)
    }

    /**
     * Searches all of [gray] for a tall bordered rectangle (the drawn column box) and
     * returns its bounding [Rect] in [gray]-local coords, or null. Loose scale bounds:
     * box occupies 40–105% per axis, area 40–99% of the crop.
     */
    fun findBoxRect(gray: Mat, tall: Boolean): Rect? {
        val corners = findBoxQuad(gray, tall) ?: return null
        val xs = corners.map { it.x.toInt() }
        val ys = corners.map { it.y.toInt() }
        val x0 = xs.min().coerceAtLeast(0)
        val y0 = ys.min().coerceAtLeast(0)
        val x1 = xs.max().coerceAtMost(gray.cols())
        val y1 = ys.max().coerceAtMost(gray.rows())
        if (x1 <= x0 || y1 <= y0) return null
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Like [findBoxRect] but returns the box's 4 perspective-correct corners instead of an
     * axis-aligned bounding rect, ready for [PerspectiveCorrector.warp].
     *
     * The answer-column pipeline warps each column with these corners. On angled/bowed
     * paper the column box is a slanted quad; a bounding-rect crop + resize preserves that
     * skew, leaving bubbles on a tilted locus so the per-strip fit drifts off them (worst
     * at the column bottom). Warping the quad removes the skew. Null on the same partial-box
     * condition as [findBoxRect].
     */
    fun findBoxQuad(gray: Mat, tall: Boolean, strict: Boolean = true): List<Point>? {
        // A bowed border can fall short of the strict extent (e.g. an outer column whose far
        // edge bends from the camera). The relaxed pass accepts a smaller-but-mostly-complete
        // box so the caller can still deskew it instead of resizing skew in.
        val minWFrac = if (strict) COL_BOX_MIN_W_FRAC else COL_BOX_MIN_W_FRAC_RELAXED
        val minHFrac = if (strict) COL_BOX_MIN_H_FRAC else COL_BOX_MIN_H_FRAC_RELAXED
        val result = findBox(
            warpedGray    = gray,
            label         = if (strict) "col_box" else "col_box_relaxed",
            xFrac         = 0.0..1.0,
            yFrac         = 0.0..1.0,
            tall          = tall,
            expectedW     = gray.cols(),
            expectedH     = gray.rows(),
            areaFracRange = if (strict) 0.40..0.99 else 0.25..0.99,
            maxScaleFactor = 1.05,
            minScaleFactor = if (strict) 0.40 else 0.30
        ) ?: return null
        val corners = result.corners
        val xs = corners.map { it.x }
        val ys = corners.map { it.y }
        // The real column box fills ≥~90% of the crop both axes; a markedly smaller box is a
        // broken/merged contour that would crop off question numbers or choice columns.
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        if (w < minWFrac * gray.cols() || h < minHFrac * gray.rows()) {
            Log.w(TAG, "findBoxQuad(strict=$strict): rejecting partial box ${w.toInt()}x${h.toInt()} of " +
                "${gray.cols()}x${gray.rows()} (min ${minWFrac}/${minHFrac})")
            return null
        }
        // The relaxed box must be a convex quad or the deskew warp scrambles the column;
        // the strict gate already implies this, so only check when relaxed.
        if (!strict && !isConvexQuad(corners)) {
            Log.w(TAG, "findBoxQuad(relaxed): rejecting non-convex quad $corners")
            return null
        }
        return corners
    }

    /** True if the ordered quad [TL, TR, BL, BR] is convex; guards the deskew warp. */
    private fun isConvexQuad(c: List<Point>): Boolean {
        if (c.size != 4) return false
        // Boundary order TL → TR → BR → BL; convex iff all cross products keep one sign.
        val poly = listOf(c[0], c[1], c[3], c[2])
        var sign = 0.0
        for (i in 0 until 4) {
            val a = poly[i]
            val b = poly[(i + 1) % 4]
            val d = poly[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            if (abs(cross) < 1e-6) continue
            if (sign == 0.0) sign = cross else if (sign * cross < 0) return false
        }
        return true
    }

    companion object {
        private const val TAG = "BoxFinder"
        // Min column-box size (×crop); reference box ≈0.91-0.95 W, ≈0.96 H.
        private const val COL_BOX_MIN_W_FRAC = 0.85
        private const val COL_BOX_MIN_H_FRAC = 0.88
        // Relaxed gate for the deskew fallback: accept a bow-foreshortened but mostly
        // complete box. Still above the noise floor, so it only rescues near-misses.
        private const val COL_BOX_MIN_W_FRAC_RELAXED = 0.72
        private const val COL_BOX_MIN_H_FRAC_RELAXED = 0.78
    }
}
