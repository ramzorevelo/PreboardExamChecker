package com.pbec.preboardexamchecker.ui.scanner.processor

import android.graphics.PointF
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Locates bracket-cell centers inside a sub-warped grayscale box (STUDENT ID or TEST
 * QUESTION SET). Blueprint fractional centers drift when the sub-warp's framing shifts or
 * the paper bows, so cells are detected directly. Returns sub-canvas pixel centers; the
 * caller falls back to [SheetBlueprint] for any cell not found.
 */
class DynamicCellDetector {

    private data class Blob(val cx: Double, val cy: Double, val rect: Rect)

    /**
     * ID-grid cell centers as [10 digit rows 0–9][6 columns]; a null entry means that
     * cell wasn't located (caller falls back to SheetBlueprint). Tries [detectIdGridFit]
     * first (strip-local, so it tracks curvature and shadow), then the older whole-box
     * [detectIdCellsByBlobs] when the grid fit misses its gates.
     */
    fun detectIdCells(subGray: Mat, subW: Int, subH: Int): Array<Array<PointF?>>? {
        detectIdGridFit(subGray, subW, subH)?.let { return it }
        Log.w(TAG, "detectIdCells: grid-fit failed its gates — falling back to blob detection")
        return detectIdCellsByBlobs(subGray, subW, subH)
    }

    /** Blob-detection fallback for the ID grid. Null if < 40 valid blobs found. */
    private fun detectIdCellsByBlobs(subGray: Mat, subW: Int, subH: Int): Array<Array<PointF?>>? {
        val totalArea = subW.toDouble() * subH
        val minArea = totalArea * 0.0008
        val maxArea = totalArea * 0.015
        val headerCutoffY = subH * 0.08

        val blobs = findBlobs(subGray) { area, rect ->
            val aspect = if (rect.width > 0) rect.height.toDouble() / rect.width else 0.0
            area in minArea..maxArea &&
                rect.height > rect.width &&
                aspect in 1.2..4.0 &&
                solidity(area, rect) > 0.30 &&
                rect.y >= headerCutoffY
        }

        Log.d(TAG, "detectIdCells: ${blobs.size} candidate blobs after filtering (need ~60)")
        if (blobs.size < 40) {
            Log.w(TAG, "detectIdCells: too few blobs (${blobs.size} < 40) — detection failed")
            return null
        }

        val rowClusters = clusterByGap(blobs.sortedBy { it.cy }) { it.cy }
        Log.d(TAG, "detectIdCells: clustered into ${rowClusters.size} row groups (need 8-12)")
        if (rowClusters.size !in 8..12) {
            Log.w(TAG, "detectIdCells: row cluster count ${rowClusters.size} not in [8,12] — detection failed")
            return null
        }

        val rowsByDigit = assignClustersToDigitRows(rowClusters)

        // Full rows (exactly 6 blobs) give expected per-column x to recover rows where a
        // blob was missed or a spurious one crept in.
        val referenceRows = rowsByDigit.filterNotNull().filter { it.size == 6 }
        val medianColX: DoubleArray? = if (referenceRows.isNotEmpty()) {
            DoubleArray(6) { col ->
                median(referenceRows.map { row -> row.sortedBy { b -> b.cx }[col].cx })
            }
        } else null

        return Array(10) { digit ->
            val row = rowsByDigit[digit]
            if (row == null) {
                Log.w(TAG, "detectIdCells: digit row $digit has no detected blob cluster — using nulls")
                arrayOfNulls(6)
            } else {
                resolveRowColumns(row, medianColX)
            }
        }
    }

    /**
     * The 2 TEST QUESTION SET cell centers (0 = Set A, 1 = Set B) in sub-canvas pixels.
     * Each cell is located independently by searching a ±window around its blueprint x for
     * bracket bars / a shaded blob, then refining x by dark-mass centroid. A cell that
     * can't be located keeps its blueprint position, so curl/shadow degrades gracefully;
     * both entries are always filled. Null only if the binary couldn't be built.
     */
    fun detectTestSetCells(subGray: Mat, subW: Int, subH: Int): Array<PointF?>? {
        val px = buildDarkBuffer(subGray, subW, subH) ?: return null
        fun isDark(x: Int, y: Int) = (px[y * subW + x].toInt() and 0xFF) < 128

        val w = subW
        val h = subH

        // Re-normalize to the box's own printed border. BoxFinder can over-frame the SET
        // box on curl/shadow, leaving whitespace around it; blueprint fractions assume a
        // tight warp, so on a loose one they point off the bubbles. Mapping every fraction
        // into the detected border rect makes placement independent of that whitespace
        // (no-op on an already-tight warp). Each axis falls back to the full canvas.
        val box = detectTestSetBoxBounds(::isDark, w, h)
        val boxW = box.right - box.left
        val boxH = box.bottom - box.top
        val cellW = SheetBlueprint.TESTSET_CELL_W_FRAC * boxW
        val cellH = SheetBlueprint.TESTSET_CELL_H_FRAC * boxH

        // The A/B bubble row sits at a reliable fraction of box height; use it as a tight
        // prior, searching only ±TESTSET_MAX_Y_DEV·cellH around it so the header-text band
        // above can't be paired as a bracket bar. (The earlier "SET"-label centroid anchor
        // averaged in that header text and dragged cells upward.)
        val rowPriorY = box.top + SheetBlueprint.TESTSET_FALLBACK_Y_FRAC * boxH
        val band = TESTSET_MAX_Y_DEV * cellH
        fun inBand(y: Int) = abs(y - rowPriorY) <= band

        // Structural rows = the box's top/bottom borders: full-width lines read as a high
        // dark fraction, bracket bars (short islands) don't. Excluded from every cell's bar
        // search, dilated ±2 px for border thickness, so the edge isn't paired as a bar.
        val structural = BooleanArray(h)
        for (y in 0 until h) {
            var c = 0
            for (x in 0 until w) if (isDark(x, y)) c++
            structural[y] = c >= TESTSET_STRUCTURAL_ROW_FRAC * w
        }
        val structuralDil = BooleanArray(h)
        for (y in 0 until h) if (structural[y]) {
            for (dy in -2..2) { val yy = y + dy; if (yy in 0 until h) structuralDil[yy] = true }
        }

        // Candidate row centers, found only within the bubble-row band so a header cluster
        // is never considered. Shaded bubble = one thick cluster; unshaded = bracket pair.
        val candPerCell = Array(2) { i ->
            val fx = box.left + SheetBlueprint.testSetXFracs[i] * boxW
            val x0 = (fx - cellW).toInt().coerceAtLeast(0)
            val x1 = (fx + cellW).toInt().coerceAtMost(w)
            val winW = x1 - x0
            val candidates = ArrayList<Float>()
            if (winW > 0) {
                val barMask = BooleanArray(h)
                for (y in 0 until h) {
                    if (structuralDil[y] || !inBand(y)) { barMask[y] = false; continue }
                    var c = 0
                    for (x in x0 until x1) if (isDark(x, y)) c++
                    barMask[y] = c >= TESTSET_BAR_MIN_PX && c < 0.90f * winW
                }
                val clusters = clusterExtents(barMask, maxGap = 4)
                val thin = ArrayList<Float>()
                for ((s, e) in clusters) {
                    val center = (s + e) / 2f
                    if (e - s + 1 >= 0.60f * cellH) candidates.add(center)   // thick = shaded blob
                    else thin.add(center)
                }
                for (j in 0 until thin.size - 1) {
                    val sep = thin[j + 1] - thin[j]
                    if (sep >= 0.70f * cellH && sep <= 1.30f * cellH) {
                        candidates.add((thin[j] + thin[j + 1]) / 2f)
                    }
                }
            }
            candidates
        }

        // Both bubbles share one physical row. Seed with the candidate nearest the prior,
        // then average all candidates within half a cell of it: a shaded bubble pins the
        // row for its blank partner, and one stray can't drag both cells off the row.
        val allCand = candPerCell[0] + candPerCell[1]
        val sharedY = if (allCand.isEmpty()) rowPriorY else {
            val seed = allCand.minByOrNull { abs(it - rowPriorY) }!!
            allCand.filter { abs(it - seed) <= 0.5f * cellH }.average().toFloat()
        }
        Log.d(TAG, "detectTestSetCells: box=[${box.left.toInt()},${box.top.toInt()},${box.right.toInt()},${box.bottom.toInt()}] " +
            "sharedY=${sharedY.toInt()} priorY=${rowPriorY.toInt()} " +
            "candsA=${candPerCell[0].map { it.toInt() }} candsB=${candPerCell[1].map { it.toInt() }}")

        val out = arrayOfNulls<PointF>(2)
        for (i in 0..1) {
            val fx = box.left + SheetBlueprint.testSetXFracs[i] * boxW
            val x0 = (fx - cellW).toInt().coerceAtLeast(0)
            val x1 = (fx + cellW).toInt().coerceAtMost(w)

            // Per-cell y: this cell's own bracket near the shared row (refines residual
            // tilt), else the shared row — so A and B can't land on different bands.
            val own = candPerCell[i].filter { abs(it - sharedY) <= 0.5f * cellH }
                .minByOrNull { abs(it - sharedY) }
            var cy = own ?: sharedY

            var cx = fx
            if (x1 > x0) {
                val yy0 = (cy - cellH / 2).toInt().coerceAtLeast(0)
                val yy1 = (cy + cellH / 2).toInt().coerceAtMost(h)
                var mass = 0
                var massX = 0L
                for (y in yy0 until yy1) {
                    for (x in x0 until x1) if (isDark(x, y)) { mass++; massX += x }
                }
                if (mass >= 15) cx = (massX.toFloat() / mass).coerceIn(fx - cellW / 2f, fx + cellW / 2f)
            }
            cy = cy.coerceIn(cellH / 2f, h - cellH / 2f)
            out[i] = PointF(cx, cy)
        }
        Log.d(TAG, "detectTestSetCells: A=(${out[0]?.x?.toInt()},${out[0]?.y?.toInt()}) " +
            "B=(${out[1]?.x?.toInt()},${out[1]?.y?.toInt()})")
        return out
    }

    /** The SET box's printed-border rectangle in sub-warp pixels (canvas edges where a side isn't found). */
    private class BoxBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The SET box's printed border rect, used to re-normalize cell fractions to the actual
     * box (see [detectTestSetCells]). A border is a continuous line — near-full-width dark
     * row or near-full-height dark column — which the broken header glyphs never reach. An
     * axis is trusted only when both borders are found and span a plausible canvas
     * fraction; otherwise it falls back to the canvas edges.
     */
    private fun detectTestSetBoxBounds(isDark: (Int, Int) -> Boolean, w: Int, h: Int): BoxBounds {
        var top = 0f; var bottom = h.toFloat()
        val rowDark = IntArray(h) { y -> var c = 0; for (x in 0 until w) if (isDark(x, y)) c++; c }
        val hBorder = (0 until h).filter { rowDark[it] >= TESTSET_BORDER_LINE_FRAC * w }
        val topB = hBorder.firstOrNull { it < 0.55f * h }?.toFloat()
        val botB = hBorder.lastOrNull { it > 0.45f * h }?.toFloat()
        if (topB != null && botB != null && botB - topB >= TESTSET_BORDER_MIN_SPAN * h) {
            top = topB; bottom = botB
        }

        var left = 0f; var right = w.toFloat()
        val bt = top.toInt().coerceIn(0, h - 1)
        val bb = bottom.toInt().coerceIn(bt + 1, h)
        val bh = bb - bt
        val colDark = IntArray(w) { x -> var c = 0; for (y in bt until bb) if (isDark(x, y)) c++; c }
        val vBorder = (0 until w).filter { colDark[it] >= TESTSET_BORDER_LINE_FRAC * bh }
        val leftB = vBorder.firstOrNull { it < TESTSET_VBORDER_EDGE_FRAC * w }?.toFloat()
        val rightB = vBorder.lastOrNull { it > (1f - TESTSET_VBORDER_EDGE_FRAC) * w }?.toFloat()
        if (leftB != null && rightB != null && rightB - leftB >= TESTSET_BORDER_MIN_SPAN * w) {
            left = leftB; right = rightB
        }
        return BoxBounds(left, top, right, bottom)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Grid-fit ID detection (curvature/shadow-robust; mirrors AnswerColumnDetector)
    // ══════════════════════════════════════════════════════════════════════

    private class RowGrid(val cellYs: Array<FloatArray>)              // [10][6] per-cell y
    private class StripCand(val y: Float, val strip: Int)
    private class RowCand(val y: Float, val stripYs: Map<Int, Float>) { val support get() = stripYs.size }

    /**
     * Locates the 6×10 ID grid by per-strip bar detection plus a comb fit per axis, like
     * [AnswerColumnDetector]. Strip-local measures track bow and shadow better than
     * whole-box blobs. Returns [10 rows][6 columns], or null if an axis fails its gates.
     */
    private fun detectIdGridFit(subGray: Mat, subW: Int, subH: Int): Array<Array<PointF?>>? {
        val px = buildDarkBuffer(subGray, subW, subH) ?: return null
        fun isDark(x: Int, y: Int) = (px[y * subW + x].toInt() and 0xFF) < 128

        val w = subW
        val h = subH
        val cellW = SheetBlueprint.ID_CELL_W_FRAC * w
        val cellH = SheetBlueprint.ID_CELL_H_FRAC * h
        val rowStride = SheetBlueprint.ID_ROW_STRIDE_FRAC * h
        val xFracs = SheetBlueprint.idColXFracs

        // Column band + structural rows (box borders / "STUDENT ID" header bar)
        val bandX0 = ((xFracs[0]) * w - cellW).toInt().coerceAtLeast(0)
        val bandX1 = ((xFracs[5]) * w + cellW).toInt().coerceAtMost(w)
        val bandW = bandX1 - bandX0
        if (bandW <= 0) return null
        val rowDark = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            for (x in bandX0 until bandX1) if (isDark(x, y)) c++
            rowDark[y] = c
        }
        val structuralMask = BooleanArray(h) { rowDark[it] >= STRUCTURAL_ROW_FRAC * bandW }
        val structuralClusters = clusterExtents(structuralMask, maxGap = 5)

        val colXs = detectIdColumns(::isDark, w, h, structuralMask, xFracs) ?: run {
            Log.d(TAG, "detectIdGridFit: column comb fit failed"); return null
        }
        val rowGrid = detectIdRows(::isDark, w, h, colXs, structuralClusters, cellH, cellW, rowStride) ?: run {
            Log.d(TAG, "detectIdGridFit: row grid fit failed"); return null
        }
        val cellXs = computeIdCellX(::isDark, w, h, colXs, rowGrid, cellH)

        Log.d(TAG, "detectIdGridFit OK: cols=${colXs.map { it.toInt() }} " +
            "rows[0]=${rowGrid.cellYs[0][0].toInt()} rows[9]=${rowGrid.cellYs[9][0].toInt()}")

        return Array(10) { digit ->
            Array(6) { col -> PointF(cellXs[digit][col], rowGrid.cellYs[digit][col]) }
        }
    }

    /** 6-column comb fit on the vertical dark projection (ports AnswerColumnDetector.detectChoices). */
    private fun detectIdColumns(
        isDark: (Int, Int) -> Boolean, w: Int, h: Int,
        structuralMask: BooleanArray, xFracs: FloatArray
    ): FloatArray? {
        var nKept = 0
        for (y in 0 until h) if (!structuralMask[y]) nKept++
        if (nKept == 0) return null

        val v = FloatArray(w)
        for (y in 0 until h) {
            if (structuralMask[y]) continue
            for (x in 0 until w) if (isDark(x, y)) v[x] += 1f
        }
        val colCap = STRUCTURAL_COL_FRAC * nKept
        for (x in 0 until w) if (v[x] >= colCap) v[x] = 0f

        val sm = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in -4..4) { val j = x + k; if (j in 0 until w) { s += v[j]; n++ } }
            sm[x] = if (n > 0) s / n else 0f
        }
        val ps = DoubleArray(w + 1)
        val psx = DoubleArray(w + 1)
        for (x in 0 until w) { ps[x + 1] = ps[x] + sm[x]; psx[x + 1] = psx[x] + sm[x] * x }
        fun windowSum(lo: Int, hi: Int): Double {
            val l = lo.coerceIn(0, w); val r = hi.coerceIn(0, w)
            return if (r > l) ps[r] - ps[l] else 0.0
        }
        fun windowCentroid(lo: Int, hi: Int): Double? {
            val l = lo.coerceIn(0, w); val r = hi.coerceIn(0, w)
            if (r <= l) return null
            val mass = ps[r] - ps[l]
            if (mass <= 0.0) return null
            return (psx[r] - psx[l]) / mass
        }

        val pitch = (xFracs[1] - xFracs[0]) * w
        val win = (pitch * COMB_WINDOW_FRAC).toInt()
        val base = FloatArray(6) { xFracs[it] * w }
        var bestOff = 0f; var bestScale = 1f; var bestScore = -1.0
        val maxOff = COMB_OFFSET_FRAC * w
        for (scale in COMB_SCALES) {
            var off = -maxOff
            while (off <= maxOff) {
                var score = 0.0; var valid = true
                for (i in 0 until 6) {
                    val c = base[i] * scale + off
                    if (c - win < 0 || c + win > w) { valid = false; break }
                    score += windowSum((c - win).toInt(), (c + win).toInt())
                }
                if (valid && score > bestScore) { bestScore = score; bestOff = off; bestScale = scale }
                off += 2f
            }
        }
        if (bestScore < 0) return null

        val xs = FloatArray(6)
        for (i in 0 until 6) {
            val c = base[i] * bestScale + bestOff
            val centroid = windowCentroid((c - win).toInt(), (c + win).toInt())
            xs[i] = if (centroid != null && abs(centroid - c) <= pitch * CENTROID_MAX_DRIFT_FRAC)
                centroid.toFloat() else c
        }
        // Theil-Sen uniform-pitch re-fit
        val slopes = ArrayList<Float>()
        for (i in 0 until 6) for (j in i + 1 until 6) slopes.add((xs[j] - xs[i]) / (j - i))
        val bMed = medianF(slopes)
        val intercepts = ArrayList<Float>()
        for (i in 0 until 6) intercepts.add(xs[i] - bMed * i)
        val aMed = medianF(intercepts)
        for (i in 0 until 6) {
            val fitted = aMed + bMed * i
            if (abs(xs[i] - fitted) > pitch * REFIT_SNAP_FRAC) xs[i] = fitted
        }
        // Sanity gates
        val strides = FloatArray(5) { xs[it + 1] - xs[it] }
        val meanStride = strides.average().toFloat()
        if (meanStride <= 0f) return null
        val cv = strides.map { abs(it - meanStride) }.average().toFloat() / meanStride
        if (cv > MAX_COL_STRIDE_CV) { Log.d(TAG, "detectIdColumns: stride CV $cv > $MAX_COL_STRIDE_CV"); return null }
        if (bMed < pitch * STRIDE_GATE_MIN || bMed > pitch * STRIDE_GATE_MAX) {
            Log.d(TAG, "detectIdColumns: pitch $bMed out of range vs $pitch"); return null
        }
        return xs
    }

    /** Per-strip bar detection + single-subgroup 10-row grid fit (ports AnswerColumnDetector.detectRows). */
    private fun detectIdRows(
        isDark: (Int, Int) -> Boolean, w: Int, h: Int,
        colXs: FloatArray, structuralClusters: List<Pair<Int, Int>>,
        cellH: Float, cellW: Float, stride: Float
    ): RowGrid? {
        fun overlapsStructural(s: Int, e: Int): Boolean {
            for ((cs, ce) in structuralClusters) if (!(e < cs - 2 || s > ce + 2)) return true
            return false
        }
        val stripCands = ArrayList<StripCand>(120)
        val half = (cellW * 0.60f).toInt()
        for (si in 0 until 6) {
            val x0 = (colXs[si].toInt() - half).coerceAtLeast(0)
            val x1 = (colXs[si].toInt() + half).coerceAtMost(w)
            val sw = x1 - x0
            if (sw <= 0) continue
            val barMask = BooleanArray(h)
            for (y in 0 until h) {
                var c = 0
                for (x in x0 until x1) if (isDark(x, y)) c++
                barMask[y] = c >= STRIP_BAR_FRAC * sw
            }
            val clusters = clusterExtents(barMask, maxGap = 4).filter { (s, e) -> !overlapsStructural(s, e) }
            val thin = ArrayList<Float>()
            for ((s, e) in clusters) {
                val extent = e - s + 1
                val center = (s + e) / 2f
                if (extent >= THICK_CLUSTER_FRAC * cellH) stripCands.add(StripCand(center, si))
                else thin.add(center)
            }
            var i = 0
            while (i < thin.size - 1) {
                val sep = thin[i + 1] - thin[i]
                if (sep >= PAIR_SEP_MIN * cellH && sep <= PAIR_SEP_MAX * cellH) {
                    stripCands.add(StripCand((thin[i] + thin[i + 1]) / 2f, si)); i += 2
                } else i += 1
            }
        }
        stripCands.sortBy { it.y }

        val rowCands = ArrayList<RowCand>()
        var cur = ArrayList<StripCand>()
        for (c in stripCands) {
            if (cur.isEmpty() || c.y - cur.last().y <= ROW_CLUSTER_FRAC * stride) cur.add(c)
            else { rowCands.add(buildRowCand(cur)); cur = arrayListOf(c) }
        }
        if (cur.isNotEmpty()) rowCands.add(buildRowCand(cur))
        val strong = rowCands.filter { it.support >= MIN_STRIP_SUPPORT }
        Log.d(TAG, "detectIdRows: stripCands=${stripCands.size} clusters=${rowCands.size} strong=${strong.size}")
        if (strong.size < MIN_STRONG_ROWS) return null

        // Bottom-anchored consistent-stride fit. The ID box has 11 bands: a handwriting
        // row ~1 stride above digit 0 plus the 10 digit rows. A blueprint-origin search can
        // mis-lock onto the handwriting row (inflated, uneven stride). The bottom-most
        // candidate is reliably digit 9, so anchor row 9 there and search the stride going
        // up; a single linear model then gives uniform spacing (no per-row snap).
        val candY = strong.map { it.y }.sorted()
        if (candY.isEmpty()) return null
        val ymax = candY.last()
        val bMin = stride * STRIDE_GATE_MIN
        val bMax = stride * STRIDE_GATE_MAX
        var bestScore = -1f
        var bestMatched: List<Pair<Int, Float>> = emptyList()
        var b = bMin
        while (b <= bMax) {
            // Float the anchor within a sub-stride window so a slightly-low stray can't
            // lock it, but never far enough up to skip digit 9.
            var r9 = ymax - 0.5f * b
            val r9Hi = ymax + 0.25f * b
            val r9Step = maxOf(1f, b * 0.05f)
            while (r9 <= r9Hi) {
                val origin = r9 - 9f * b
                var inliers = 0
                val matched = ArrayList<Pair<Int, Float>>(10)
                for (k in 0..9) {
                    val predY = origin + k * b
                    val near = candY.minByOrNull { abs(it - predY) }
                    if (near != null && abs(near - predY) <= MATCH_RADIUS_PX) {
                        inliers++; matched.add(k to near)
                    }
                }
                // Maximize inliers; tie-break toward row 9 nearest ymax so we pick
                // digit0..9, not handwriting..8.
                val score = inliers - 0.01f * abs(r9 - ymax) / stride
                if (score > bestScore) { bestScore = score; bestMatched = matched }
                r9 += r9Step
            }
            b += stride * 0.01f
        }
        Log.d(TAG, "detectIdRows: bottom-anchor ymax=${ymax.toInt()} matched=${bestMatched.size}/10")
        if (bestMatched.size < MIN_MATCHED_ROWS) return null

        // Least squares y = a + b·k over matched rows: one stride for all 10, no snap.
        val meanK = bestMatched.map { it.first.toDouble() }.average()
        val meanY = bestMatched.map { it.second.toDouble() }.average()
        var cov = 0.0; var varK = 0.0
        for ((k, y) in bestMatched) {
            cov += (k - meanK) * (y - meanY); varK += (k - meanK) * (k - meanK)
        }
        if (varK <= 0.0) return null
        val bb = (cov / varK).toFloat()
        val aa = (meanY - bb * meanK).toFloat()
        if (bb < bMin || bb > bMax) {
            Log.d(TAG, "detectIdRows: fitted stride $bb out of range vs $stride"); return null
        }
        return RowGrid(Array(10) { i -> FloatArray(6) { aa + bb * i } })
    }

    private fun buildRowCand(cluster: List<StripCand>): RowCand {
        val ys = cluster.map { it.y }.sorted()
        val medianY = if (ys.size % 2 == 1) ys[ys.size / 2] else (ys[ys.size / 2 - 1] + ys[ys.size / 2]) / 2f
        val byStrip = HashMap<Int, Float>()
        for (c in cluster) {
            val existing = byStrip[c.strip]
            if (existing == null || abs(c.y - medianY) < abs(existing - medianY)) byStrip[c.strip] = c.y
        }
        return RowCand(medianY, byStrip)
    }

    /** Per-cell x via dark-mass centroid + ±2 moving median (ports computeRowChoiceX). */
    private fun computeIdCellX(
        isDark: (Int, Int) -> Boolean, w: Int, h: Int,
        colXs: FloatArray, rowGrid: RowGrid, cellH: Float
    ): Array<FloatArray> {
        val pitch = colXs[1] - colXs[0]
        val win = pitch * 0.48f
        val out = Array(10) { FloatArray(6) }
        for (si in 0 until 6) {
            val centroid = arrayOfNulls<Float>(10)
            for (ri in 0 until 10) {
                val yc = rowGrid.cellYs[ri][si]
                val y0 = (yc - cellH / 2).toInt().coerceAtLeast(0)
                val y1 = (yc + cellH / 2).toInt().coerceAtMost(h)
                val x0 = (colXs[si] - win).toInt().coerceAtLeast(0)
                val x1 = (colXs[si] + win).toInt().coerceAtMost(w)
                var mass = 0; var massX = 0L
                for (y in y0 until y1) for (x in x0 until x1) if (isDark(x, y)) { mass++; massX += x }
                if (mass >= 20) centroid[ri] = massX.toFloat() / mass
            }
            for (ri in 0 until 10) {
                val neigh = ((ri - 2).coerceAtLeast(0)..(ri + 2).coerceAtMost(9)).mapNotNull { centroid[it] }
                val x = if (neigh.size >= 3) medianF(neigh) else colXs[si]
                out[ri][si] = x.coerceIn(colXs[si] - 0.48f * pitch, colXs[si] + 0.48f * pitch)
            }
        }
        return out
    }

    // ── Shared helpers for the grid-fit / window paths ────────────────────

    /**
     * CLAHE → adaptive threshold → 3×3 close, as a row-major byte buffer for fast
     * per-pixel access. Adaptive (not global Otsu) is what keeps the strip detectors
     * robust to a shadow gradient across the box. Null only if the Mat read fails.
     */
    private fun buildDarkBuffer(subGray: Mat, subW: Int, subH: Int): ByteArray? {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(subGray, enhanced)
        val binary = Mat()
        val block = minOf(51, (minOf(subW, subH) / 2) or 1).coerceAtLeast(3)
        Imgproc.adaptiveThreshold(enhanced, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 8.0)
        enhanced.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
        binary.release()
        kernel.release()
        return try {
            val buf = ByteArray(subW * subH)
            closed.get(0, 0, buf)
            buf
        } catch (t: Throwable) {
            Log.w(TAG, "buildDarkBuffer: Mat read failed", t); null
        } finally {
            closed.release()
        }
    }

    /** Clusters consecutive true runs of [mask] (bridging gaps ≤ [maxGap]) into inclusive extents. */
    private fun clusterExtents(mask: BooleanArray, maxGap: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1; var prev = -1
        for (y in mask.indices) {
            if (!mask[y]) continue
            if (start < 0) start = y
            else if (y - prev > maxGap) { out.add(start to prev); start = y }
            prev = y
        }
        if (start >= 0) out.add(start to prev)
        return out
    }

    private fun medianF(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    // ── Shared blob pipeline ──────────────────────────────────────────────

    /**
     * CLAHE → inverted Otsu binary (bracket borders become white blobs) → close → external
     * contours → bounding-rect center of each contour passing [accept].
     */
    private fun findBlobs(subGray: Mat, accept: (area: Double, rect: Rect) -> Boolean): List<Blob> {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(subGray, enhanced)

        val binary = Mat()
        Imgproc.threshold(enhanced, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        enhanced.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 1)
        binary.release()
        kernel.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        closed.release()
        hierarchy.release()

        val blobs = ArrayList<Blob>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)
            if (accept(area, rect)) {
                // Bounding-rect center, not dark-mass centroid: bracket mass is symmetric
                // but the glyph inside isn't (e.g. "A" concentrates stroke low), so a
                // moments centroid drifts ~1-2 px. The rect spans the full cell regardless.
                blobs.add(Blob(cx = rect.x + rect.width / 2.0, cy = rect.y + rect.height / 2.0, rect = rect))
            }
            contour.release()
        }
        return blobs
    }

    private fun solidity(contourArea: Double, boundingRect: Rect): Double {
        val bboxArea = boundingRect.width.toDouble() * boundingRect.height
        return if (bboxArea > 0) contourArea / bboxArea else 0.0
    }

    // ── Row clustering (by y-gap) ─────────────────────────────────────────

    /** Splits [sorted] into clusters, cutting where the gap exceeds 1.5× the median gap. */
    private fun <T> clusterByGap(sorted: List<T>, value: (T) -> Double): List<List<T>> {
        if (sorted.size < 2) return listOf(sorted)
        val gaps = DoubleArray(sorted.size - 1) { value(sorted[it + 1]) - value(sorted[it]) }
        val threshold = median(gaps.toList()) * 1.5

        val clusters = ArrayList<MutableList<T>>()
        var current = mutableListOf(sorted[0])
        for (i in gaps.indices) {
            if (gaps[i] > threshold) {
                clusters.add(current)
                current = mutableListOf()
            }
            current.add(sorted[i + 1])
        }
        clusters.add(current)
        return clusters
    }

    /**
     * Maps row clusters (top-to-bottom) onto digit rows 0–9. Exactly 10 clusters map 1:1;
     * a merged/split count (8–12) assigns each cluster to the nearest of 10 evenly-spaced
     * slots by relative y, leaving unmatched slots null.
     */
    private fun assignClustersToDigitRows(rowClusters: List<List<Blob>>): Array<List<Blob>?> {
        val assignment = arrayOfNulls<List<Blob>>(10)
        if (rowClusters.size == 10) {
            for (i in 0 until 10) assignment[i] = rowClusters[i]
            return assignment
        }

        val centers = rowClusters.map { cluster -> cluster.sumOf { it.cy } / cluster.size }
        val span = (centers.last() - centers.first()).takeIf { it > 0.0 } ?: 1.0
        for (i in rowClusters.indices) {
            val slot = (((centers[i] - centers.first()) / span) * 9.0)
                .let { Math.round(it).toInt() }
                .coerceIn(0, 9)
            if (assignment[slot] == null) {
                assignment[slot] = rowClusters[i]
            } else {
                Log.w(TAG, "detectIdCells: digit-row slot $slot already filled — dropping extra cluster at y=${centers[i]}")
            }
        }
        return assignment
    }

    // ── Column resolution within a row ────────────────────────────────────

    /**
     * Resolves a row's blobs to 6 columns (null per missing cell): 6 → use directly;
     * 5 → match to nearest expected column from [medianColX]; 7+ → drop farthest outliers
     * to 6; otherwise → whole row null.
     */
    private fun resolveRowColumns(row: List<Blob>, medianColX: DoubleArray?): Array<PointF?> {
        val sorted = row.sortedBy { it.cx }
        return when {
            sorted.size == 6 -> Array(6) { toPointF(sorted[it]) }

            sorted.size == 5 && medianColX != null -> matchToExpectedColumns(sorted, medianColX)

            sorted.size >= 7 -> {
                val trimmed = dropFarthestOutliers(sorted, target = 6)
                if (trimmed.size == 6) Array(6) { toPointF(trimmed[it]) } else arrayOfNulls(6)
            }

            else -> arrayOfNulls(6)
        }
    }

    private fun toPointF(b: Blob) = PointF(b.cx.toFloat(), b.cy.toFloat())

    /** Greedily assigns each blob to its closest not-yet-filled expected column slot. */
    private fun matchToExpectedColumns(blobs: List<Blob>, expectedColX: DoubleArray): Array<PointF?> {
        val result = arrayOfNulls<PointF>(6)
        for (blob in blobs) {
            var bestCol = -1
            var bestDist = Double.MAX_VALUE
            for (col in 0 until 6) {
                if (result[col] != null) continue
                val dist = abs(blob.cx - expectedColX[col])
                if (dist < bestDist) {
                    bestDist = dist
                    bestCol = col
                }
            }
            if (bestCol >= 0) result[bestCol] = toPointF(blob)
        }
        return result
    }

    /** Repeatedly removes the blob with the largest gap to its nearest x-neighbor. */
    private fun dropFarthestOutliers(sortedByX: List<Blob>, target: Int): List<Blob> {
        var current = sortedByX
        while (current.size > target) {
            var worstIdx = 0
            var worstNearestGap = -1.0
            for (i in current.indices) {
                val left = if (i > 0) current[i].cx - current[i - 1].cx else Double.MAX_VALUE
                val right = if (i < current.size - 1) current[i + 1].cx - current[i].cx else Double.MAX_VALUE
                val nearest = minOf(left, right)
                if (nearest > worstNearestGap) {
                    worstNearestGap = nearest
                    worstIdx = i
                }
            }
            current = current.filterIndexed { idx, _ -> idx != worstIdx }
        }
        return current
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    companion object {
        private const val TAG = "DynamicCellDetector"

        // ── Grid-fit tuning (mirrors AnswerColumnDetector; values per the smaller
        //    340×600 ID sub-canvas — cellH≈40px, rowStride≈48px, colStride≈44px) ──
        private const val STRUCTURAL_ROW_FRAC = 0.80f   // row dark-frac over band → structural line
        private const val STRUCTURAL_COL_FRAC = 0.70f   // col dark-frac over kept rows → vertical line
        private const val STRIP_BAR_FRAC = 0.45f        // strip-width dark-frac → bracket bar
        private const val THICK_CLUSTER_FRAC = 0.55f    // cluster ≥ this×cellH → shaded blob (row center)
        private const val PAIR_SEP_MIN = 0.50f          // thin-bar pair separation range, ×cellH
        private const val PAIR_SEP_MAX = 1.35f
        private const val ROW_CLUSTER_FRAC = 0.35f      // cross-strip clustering distance, ×rowStride
        private const val MIN_STRIP_SUPPORT = 2         // strips that must agree for a strong row
        private const val MIN_STRONG_ROWS = 6           // strong row candidates required (of 10)
        private const val MIN_MATCHED_ROWS = 5          // predicted rows that must match in the bottom-anchored fit
        private const val MATCH_RADIUS_PX = 12f         // candidate↔predicted-row match tolerance
        private const val STRIDE_GATE_MIN = 0.80f       // fitted stride/pitch sanity vs blueprint
        private const val STRIDE_GATE_MAX = 1.20f
        private val COMB_SCALES = floatArrayOf(0.95f, 0.97f, 0.99f, 1.0f, 1.01f, 1.03f, 1.05f)
        private const val COMB_OFFSET_FRAC = 0.07f      // ×subWarpW
        private const val COMB_WINDOW_FRAC = 0.45f      // ×pitch (half-window)
        private const val CENTROID_MAX_DRIFT_FRAC = 0.25f
        private const val REFIT_SNAP_FRAC = 0.12f
        private const val MAX_COL_STRIDE_CV = 0.15f
        // Min dark px for a bracket-bar row inside a SET search window
        private const val TESTSET_BAR_MIN_PX = 6
        // Half-height (×cellH) of the bubble-row search band: wide enough for warp drift,
        // tight enough to exclude the header band ≈1.5·cellH above the bubbles.
        private const val TESTSET_MAX_Y_DEV = 0.9f
        // Full-width dark fraction above which a row is a box border, not a bracket bar.
        private const val TESTSET_STRUCTURAL_ROW_FRAC = 0.80f
        // Continuous-line dark fraction for the box's printed border. Must be high: a
        // pentagon arrow, shaded bubble, and header text can stack at one x and fake a low
        // bar (0.55 once picked such a stack as the left border, drifting every cell right).
        private const val TESTSET_BORDER_LINE_FRAC = 0.85f
        // Side borders sit in the outer canvas edge; restrict the vertical search there so
        // an interior full-height feature (pentagon base) can't pass as a side border.
        private const val TESTSET_VBORDER_EDGE_FRAC = 0.18f
        // Min span (×canvas) between a found border pair for the axis to be trusted.
        private const val TESTSET_BORDER_MIN_SPAN = 0.5f
    }
}
