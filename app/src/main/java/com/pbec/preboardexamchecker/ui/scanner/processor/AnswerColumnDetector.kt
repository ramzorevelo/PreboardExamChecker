package com.pbec.preboardexamchecker.ui.scanner.processor

import android.util.Log
import com.pbec.preboardexamchecker.BuildConfig
import org.opencv.core.Mat
import kotlin.math.abs

/**
 * Locates the 20 row y-centers and 5 choice x-centers in one answer-column sub-warp.
 * [binarySubWarp] must be binary (background 255, content 0), sized [subWarpW]×[subWarpH].
 * Returns null if either axis fails its sanity gates — caller treats the column as
 * unreadable rather than guess.
 *
 * Detection is per-strip, not a full-width projection: the full-width approach counted
 * the box borders and subgroup divider as bracket bars (shifting sequential pairing by
 * one), and paper curvature spread a row's bars across y values until the global
 * projection fragmented. Inside each narrow x-strip curvature is negligible and bars
 * stay crisp.
 */
data class ColumnCells(
    val rowYPositions: FloatArray,
    val choiceXPositions: FloatArray,
    /** Per-cell [row][choice], for curved rows; falls back to rowYPositions[row]. */
    val cellYPositions: Array<FloatArray>,
    /** Per-row choice x [row][choice]. Curvature shifts bubble x 10–20 px down the
     *  column, so one straight x per choice would land rects on bubble edges. */
    val cellXPositions: Array<FloatArray>,
    /** True where the row y was snapped to a detected candidate (vs interpolated). */
    val rowSnapped: BooleanArray,
    val structuralYs: List<Int> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColumnCells) return false
        return rowYPositions.contentEquals(other.rowYPositions) &&
               choiceXPositions.contentEquals(other.choiceXPositions)
    }

    override fun hashCode() =
        31 * rowYPositions.contentHashCode() + choiceXPositions.contentHashCode()
}

class AnswerColumnDetector {

    companion object {
        private const val TAG = "AnswerColumnDetector"

        // Fraction of the choice band a row must darken to be a structural line
        private const val STRUCTURAL_ROW_FRAC = 0.80f
        // Fraction of kept rows a column must darken to be a structural vertical
        private const val STRUCTURAL_COL_FRAC = 0.70f
        // Fraction of a strip's width a row must darken to be a bracket bar
        private const val STRIP_BAR_FRAC = 0.45f
        // Cluster extent ≥ this × cellH = shaded-cell blob (center = row center)
        private const val THICK_CLUSTER_FRAC = 0.55f
        // Thin-bar pair separation range, × cellH
        private const val PAIR_SEP_MIN = 0.50f
        private const val PAIR_SEP_MAX = 1.35f
        // Cross-strip clustering distance, × row stride
        private const val ROW_CLUSTER_FRAC = 0.35f
        // Per-cell y may deviate from the row center by at most this × cellH …
        private const val CELL_Y_CLAMP_FRAC = 0.20f
        // … but never more than this × the inter-row gap (b−cellH), so a cell can't
        // ride into the neighbour row's bracket when rows are packed tight.
        private const val CELL_Y_GAP_FRAC = 0.40f
        // Minimum strips that must agree for a strong row candidate
        private const val MIN_STRIP_SUPPORT = 2
        // Grid-fit parameters (sub-warp px)
        private const val OFFSET_SEARCH_PX = 60f
        private const val OFFSET_STEP_PX = 2f
        private const val MATCH_RADIUS_PX = 14f
        private const val SNAP_RADIUS_PX = 12f
        private const val MIN_MATCHED_PER_SUBGROUP = 6
        // Stride/pitch sanity gates vs blueprint
        private const val STRIDE_GATE_MIN = 0.85f
        private const val STRIDE_GATE_MAX = 1.15f
        // Choice comb-fit search
        private val COMB_SCALES = floatArrayOf(0.95f, 0.97f, 0.99f, 1.0f, 1.01f, 1.03f, 1.05f)
        private const val COMB_OFFSET_FRAC = 0.07f   // × subWarpW
        private const val COMB_WINDOW_FRAC = 0.45f   // × pitch (half-window)
        private const val CENTROID_MAX_DRIFT_FRAC = 0.25f  // × pitch
        private const val REFIT_SNAP_FRAC = 0.12f    // × pitch
        private const val MAX_CHOICE_STRIDE_CV = 0.12f
    }

    fun detect(binarySubWarp: Mat, subWarpW: Int, subWarpH: Int): ColumnCells? {
        if (binarySubWarp.cols() != subWarpW || binarySubWarp.rows() != subWarpH) {
            Log.w(TAG, "detect: Mat ${binarySubWarp.cols()}x${binarySubWarp.rows()} " +
                "!= expected ${subWarpW}x$subWarpH")
            return null
        }
        // Single bulk copy: per-row JNI calls (Core.sumElems) are far slower.
        val px = ByteArray(subWarpW * subWarpH)
        binarySubWarp.get(0, 0, px)
        fun isDark(x: Int, y: Int) = (px[y * subWarpW + x].toInt() and 0xFF) < 128

        val w = subWarpW
        val h = subWarpH
        val cellH = SheetBlueprint.ANSWER_CELL_H_FRAC * h
        val cellW = SheetBlueprint.ANSWER_CELL_W_FRAC * w
        val stride = SheetBlueprint.ANSWER_ROW_STRIDE_FRAC * h
        val xFracs = SheetBlueprint.answerChoiceXFracs

        // ── Choice band + structural rows ─────────────────────────────────
        val bandX0 = ((xFracs[0] - 0.075f) * w).toInt().coerceAtLeast(0)
        val bandX1 = ((xFracs[4] + 0.075f) * w).toInt().coerceAtMost(w)
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
        val structuralYs = structuralClusters.map { (s, e) -> (s + e) / 2 }

        // ── 1. Choice x-centers ───────────────────────────────────────────
        val choiceXs = detectChoices(::isDark, w, h, structuralMask, cellH) ?: run {
            Log.d(TAG, "detect: choice comb fit failed")
            return null
        }

        // ── 2–4. Row y-centers ────────────────────────────────────────────
        val rowResult = detectRows(
            ::isDark, w, h, choiceXs, structuralClusters, cellH, cellW, stride
        ) ?: run {
            Log.d(TAG, "detect: row grid fit failed")
            return null
        }

        val cellXs = computeRowChoiceX(::isDark, w, h, choiceXs, rowResult, cellH)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "detect OK: choiceXs=${choiceXs.map { it.toInt() }} " +
                "rows[0]=${rowResult.rowYs[0].toInt()} rows[19]=${rowResult.rowYs[19].toInt()} " +
                "snapped=${rowResult.snapped.count { it }}/20 structural=$structuralYs " +
                "xDrift(A)=${(cellXs[0][0] - cellXs[19][0]).toInt()}px")
        }

        return ColumnCells(
            rowYPositions    = rowResult.rowYs,
            choiceXPositions = choiceXs,
            cellYPositions   = rowResult.cellYs,
            cellXPositions   = cellXs,
            rowSnapped       = rowResult.snapped,
            structuralYs     = structuralYs
        )
    }

    /**
     * Per-row x-center per choice. A cell's dark mass is x-symmetric about its center, so
     * the centroid locates it. The median over ±2 neighbour rows follows S-curves a single
     * line fit cannot, while resisting per-cell outliers (asymmetric shades, X marks); a
     * Theil-Sen line backs up rows with too few neighbours.
     */
    private fun computeRowChoiceX(
        isDark: (Int, Int) -> Boolean,
        w: Int,
        h: Int,
        choiceXs: FloatArray,
        rowResult: RowResult,
        cellH: Float
    ): Array<FloatArray> {
        val pitch = choiceXs[1] - choiceXs[0]
        // On curved captures a bubble drifts up to ~0.45×pitch off column-mean X; the
        // window must reach that far but stay under 0.5 so it can't cross into the
        // neighbouring choice.
        val win = pitch * 0.48f
        val out = Array(20) { FloatArray(5) }
        for (si in 0 until 5) {
            val centroid = arrayOfNulls<Float>(20)
            for (ri in 0 until 20) {
                val yc = rowResult.cellYs[ri][si]
                val y0 = (yc - cellH / 2).toInt().coerceAtLeast(0)
                val y1 = (yc + cellH / 2).toInt().coerceAtMost(h)
                val x0 = (choiceXs[si] - win).toInt().coerceAtLeast(0)
                val x1 = (choiceXs[si] + win).toInt().coerceAtMost(w)
                var mass = 0
                var massX = 0L
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        if (isDark(x, y)) { mass++; massX += x }
                    }
                }
                if (mass >= 30) centroid[ri] = massX.toFloat() / mass
            }
            val valid = (0 until 20).filter { centroid[it] != null }
            if (valid.size >= 8) {
                val slopes = ArrayList<Float>()
                for (i in valid.indices) {
                    for (j in i + 1 until valid.size) {
                        val dy = rowResult.rowYs[valid[j]] - rowResult.rowYs[valid[i]]
                        if (dy > 100f) {
                            slopes.add((centroid[valid[j]]!! - centroid[valid[i]]!!) / dy)
                        }
                    }
                }
                val b = if (slopes.isNotEmpty()) median(slopes).coerceIn(-0.05f, 0.05f) else 0f
                val intercepts = valid.map { centroid[it]!! - b * rowResult.rowYs[it] }
                val a = median(intercepts)
                for (ri in 0 until 20) {
                    var neigh = ((ri - 2).coerceAtLeast(0)..(ri + 2).coerceAtMost(19))
                        .mapNotNull { centroid[it] }
                    if (neigh.size < 3) {
                        neigh = ((ri - 4).coerceAtLeast(0)..(ri + 4).coerceAtMost(19))
                            .mapNotNull { centroid[it] }
                    }
                    val x = if (neigh.size >= 3) median(neigh) else a + b * rowResult.rowYs[ri]
                    out[ri][si] = x.coerceIn(
                        choiceXs[si] - 0.48f * pitch, choiceXs[si] + 0.48f * pitch)
                }
            } else {
                for (ri in 0 until 20) out[ri][si] = choiceXs[si]
            }
        }
        return out
    }

    // ── Choice detection ──────────────────────────────────────────────────

    private fun detectChoices(
        isDark: (Int, Int) -> Boolean,
        w: Int, h: Int,
        structuralMask: BooleanArray,
        cellH: Float
    ): FloatArray? {
        val xFracs = SheetBlueprint.answerChoiceXFracs
        var nKept = 0
        for (y in 0 until h) if (!structuralMask[y]) nKept++
        if (nKept == 0) return null

        val v = FloatArray(w)
        for (y in 0 until h) {
            if (structuralMask[y]) continue
            for (x in 0 until w) if (isDark(x, y)) v[x] += 1f
        }
        // Drop structural verticals (box borders) so they don't outscore choice columns.
        val colCap = STRUCTURAL_COL_FRAC * nKept
        for (x in 0 until w) if (v[x] >= colCap) v[x] = 0f

        val sm = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (k in -4..4) {
                val j = x + k
                if (j in 0 until w) { s += v[j]; n++ }
            }
            sm[x] = if (n > 0) s / n else 0f
        }
        // Prefix sums: O(1) window queries.
        val ps = DoubleArray(w + 1)
        val psx = DoubleArray(w + 1)
        for (x in 0 until w) {
            ps[x + 1] = ps[x] + sm[x]
            psx[x + 1] = psx[x] + sm[x] * x
        }
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
        val base = FloatArray(5) { xFracs[it] * w }

        var bestOff = 0f
        var bestScale = 1f
        var bestScore = -1.0
        val maxOff = COMB_OFFSET_FRAC * w
        for (scale in COMB_SCALES) {
            var off = -maxOff
            while (off <= maxOff) {
                var score = 0.0
                var valid = true
                for (i in 0 until 5) {
                    val c = base[i] * scale + off
                    if (c - win < 0 || c + win > w) { valid = false; break }
                    score += windowSum((c - win).toInt(), (c + win).toInt())
                }
                if (valid && score > bestScore) {
                    bestScore = score; bestOff = off; bestScale = scale
                }
                off += 2f
            }
        }
        if (bestScore < 0) return null

        val xs = FloatArray(5)
        for (i in 0 until 5) {
            val c = base[i] * bestScale + bestOff
            val centroid = windowCentroid((c - win).toInt(), (c + win).toInt())
            xs[i] = if (centroid != null && abs(centroid - c) <= pitch * CENTROID_MAX_DRIFT_FRAC)
                centroid.toFloat() else c
        }

        // Theil-Sen uniform-pitch re-fit tolerates 1–2 outlier columns.
        val slopes = ArrayList<Float>(10)
        for (i in 0 until 5) for (j in i + 1 until 5) slopes.add((xs[j] - xs[i]) / (j - i))
        val bMed = median(slopes)
        val intercepts = ArrayList<Float>(5)
        for (i in 0 until 5) intercepts.add(xs[i] - bMed * i)
        val aMed = median(intercepts)
        for (i in 0 until 5) {
            val fitted = aMed + bMed * i
            if (abs(xs[i] - fitted) > pitch * REFIT_SNAP_FRAC) xs[i] = fitted
        }

        // Sanity gates
        val strides = FloatArray(4) { xs[it + 1] - xs[it] }
        val meanStride = strides.average().toFloat()
        if (meanStride <= 0f) return null
        val cv = strides.map { abs(it - meanStride) }.average().toFloat() / meanStride
        if (cv > MAX_CHOICE_STRIDE_CV) {
            Log.d(TAG, "detectChoices: stride CV $cv > $MAX_CHOICE_STRIDE_CV")
            return null
        }
        if (bMed < pitch * STRIDE_GATE_MIN || bMed > pitch * STRIDE_GATE_MAX) {
            Log.d(TAG, "detectChoices: pitch $bMed out of [${pitch * STRIDE_GATE_MIN}, " +
                "${pitch * STRIDE_GATE_MAX}]")
            return null
        }
        return xs
    }

    // ── Row detection ─────────────────────────────────────────────────────

    private class RowResult(
        val rowYs: FloatArray,
        val cellYs: Array<FloatArray>,
        val snapped: BooleanArray
    )

    /** A row-center candidate produced by one choice strip. */
    private class StripCandidate(val y: Float, val strip: Int)

    /** A cross-strip cluster: median y + the per-strip y values that formed it. */
    private class RowCandidate(val y: Float, val stripYs: Map<Int, Float>) {
        val support get() = stripYs.size
    }

    private fun detectRows(
        isDark: (Int, Int) -> Boolean,
        w: Int, h: Int,
        choiceXs: FloatArray,
        structuralClusters: List<Pair<Int, Int>>,
        cellH: Float, cellW: Float, stride: Float
    ): RowResult? {
        fun overlapsStructural(s: Int, e: Int): Boolean {
            for ((cs, ce) in structuralClusters) {
                if (!(e < cs - 2 || s > ce + 2)) return true
            }
            return false
        }

        val stripCands = ArrayList<StripCandidate>(120)
        val half = (cellW * 0.60f).toInt()
        for (si in 0 until 5) {
            val x0 = (choiceXs[si].toInt() - half).coerceAtLeast(0)
            val x1 = (choiceXs[si].toInt() + half).coerceAtMost(w)
            val sw = x1 - x0
            if (sw <= 0) continue

            val barMask = BooleanArray(h)
            for (y in 0 until h) {
                var c = 0
                for (x in x0 until x1) if (isDark(x, y)) c++
                barMask[y] = c >= STRIP_BAR_FRAC * sw
            }
            val clusters = clusterExtents(barMask, maxGap = 4)
                .filter { (s, e) -> !overlapsStructural(s, e) }

            val thin = ArrayList<Float>()
            for ((s, e) in clusters) {
                val extent = e - s + 1
                val center = (s + e) / 2f
                if (extent >= THICK_CLUSTER_FRAC * cellH) {
                    // Shaded cell: one thick blob whose center is the row center.
                    stripCands.add(StripCandidate(center, si))
                } else {
                    thin.add(center)
                }
            }
            // A row's two bracket bars sit ≈cellH apart; their midpoint is the row center.
            var i = 0
            while (i < thin.size - 1) {
                val sep = thin[i + 1] - thin[i]
                if (sep >= PAIR_SEP_MIN * cellH && sep <= PAIR_SEP_MAX * cellH) {
                    stripCands.add(StripCandidate((thin[i] + thin[i + 1]) / 2f, si))
                    i += 2
                } else {
                    i += 1
                }
            }
        }
        stripCands.sortBy { it.y }

        val rowCands = ArrayList<RowCandidate>()
        var cur = ArrayList<StripCandidate>()
        for (c in stripCands) {
            if (cur.isEmpty() || c.y - cur.last().y <= ROW_CLUSTER_FRAC * stride) {
                cur.add(c)
            } else {
                rowCands.add(buildRowCandidate(cur))
                cur = arrayListOf(c)
            }
        }
        if (cur.isNotEmpty()) rowCands.add(buildRowCandidate(cur))
        val strong = rowCands.filter { it.support >= MIN_STRIP_SUPPORT }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "detectRows: stripCands=${stripCands.size} clusters=${rowCands.size} " +
                "strong=${strong.size}")
        }
        if (strong.size < 12) return null

        val rowYs = FloatArray(20)
        val snapped = BooleanArray(20)
        val cellYs = Array(20) { FloatArray(5) }
        for (sg in 0 until 2) {
            val pred = FloatArray(10) { i ->
                val gap = if (sg == 1) SheetBlueprint.ANSWER_SUBGROUP_GAP_FRAC * h else 0f
                val base = if (sg == 1) 10 * stride else 0f
                SheetBlueprint.ANSWER_ROW_FIRST_Y_FRAC * h + base + i * stride + gap
            }
            var bestOff = 0f
            var bestScore = -1
            var off = -OFFSET_SEARCH_PX
            while (off <= OFFSET_SEARCH_PX) {
                var score = 0
                for (p in pred) {
                    if (strong.any { abs(it.y - (p + off)) <= MATCH_RADIUS_PX }) score++
                }
                if (score > bestScore) { bestScore = score; bestOff = off }
                off += OFFSET_STEP_PX
            }
            val matchedIdx = ArrayList<Int>(10)
            val matchedY = ArrayList<Float>(10)
            for (i in 0 until 10) {
                val target = pred[i] + bestOff
                val near = strong.filter { abs(it.y - target) <= MATCH_RADIUS_PX }
                if (near.isNotEmpty()) {
                    matchedIdx.add(i)
                    matchedY.add(near.minByOrNull { abs(it.y - target) }!!.y)
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "detectRows: sg${sg + 1} off=$bestOff matched=${matchedIdx.size}/10")
            }
            if (matchedIdx.size < MIN_MATCHED_PER_SUBGROUP) return null

            // Least squares y = a + b·i over matched rows.
            val n = matchedIdx.size
            val meanI = matchedIdx.average()
            val meanYv = matchedY.average()
            var cov = 0.0
            var varI = 0.0
            for (k in 0 until n) {
                cov += (matchedIdx[k] - meanI) * (matchedY[k] - meanYv)
                varI += (matchedIdx[k] - meanI) * (matchedIdx[k] - meanI)
            }
            if (varI <= 0.0) return null
            val b = (cov / varI).toFloat()
            val a = (meanYv - b * meanI).toFloat()
            if (b < stride * STRIDE_GATE_MIN || b > stride * STRIDE_GATE_MAX) {
                Log.d(TAG, "detectRows: sg${sg + 1} fitted stride $b out of range vs $stride")
                return null
            }

            // Snap fitted rows to nearby candidates to follow curvature.
            for (i in 0 until 10) {
                val row = sg * 10 + i
                val fitted = a + b * i
                val near = strong.filter { abs(it.y - fitted) <= SNAP_RADIUS_PX }
                if (near.isNotEmpty()) {
                    val cand = near.minByOrNull { abs(it.y - fitted) }!!
                    rowYs[row] = cand.y
                    snapped[row] = true
                    // A shaded blob's center is the mark, not the bracket; a high/low
                    // mark pulls the cell rect off the row line. Cap deviation to a
                    // fraction of the inter-row gap (b−cellH) so a cell can't ride into
                    // the neighbour row's bracket when rows pack tight.
                    val gap = (b - cellH).coerceAtLeast(0f)
                    val clamp = minOf(CELL_Y_CLAMP_FRAC * cellH, CELL_Y_GAP_FRAC * gap)
                    for (si in 0 until 5) {
                        cellYs[row][si] = (cand.stripYs[si] ?: cand.y)
                            .coerceIn(cand.y - clamp, cand.y + clamp)
                    }
                } else {
                    rowYs[row] = fitted
                    snapped[row] = false
                    for (si in 0 until 5) cellYs[row][si] = fitted
                }
            }
        }
        return RowResult(rowYs, cellYs, snapped)
    }

    private fun buildRowCandidate(cluster: List<StripCandidate>): RowCandidate {
        val ys = cluster.map { it.y }.sorted()
        val medianY = if (ys.size % 2 == 1) ys[ys.size / 2]
                      else (ys[ys.size / 2 - 1] + ys[ys.size / 2]) / 2f
        // One y per strip; if a strip contributed twice keep the one nearest the median.
        val byStrip = HashMap<Int, Float>()
        for (c in cluster) {
            val existing = byStrip[c.strip]
            if (existing == null || abs(c.y - medianY) < abs(existing - medianY)) {
                byStrip[c.strip] = c.y
            }
        }
        return RowCandidate(medianY, byStrip)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** True runs of [mask], bridging gaps ≤ [maxGap], as inclusive (start, end) extents. */
    private fun clusterExtents(mask: BooleanArray, maxGap: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        var prev = -1
        for (y in mask.indices) {
            if (!mask[y]) continue
            if (start < 0) {
                start = y
            } else if (y - prev > maxGap) {
                out.add(start to prev)
                start = y
            }
            prev = y
        }
        if (start >= 0) out.add(start to prev)
        return out
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}
