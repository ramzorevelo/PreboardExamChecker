package com.pbec.preboardexamchecker.ui.scanner.processor

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Rectifies a bordered box by following its border curves, traced from the box's filled
 * contour silhouette (captures an outward bulge, never leaks in a neighbour). Stretches
 * between the box's two long edges:
 *
 *  - [vertical] = false: tall box (answer column). Trace left/right per row, stretch each
 *    [Lx(y), Rx(y)] span to full width — undoes a horizontal bow.
 *  - [vertical] = true: wide box (STUDENT ID / TEST SET / SUBJECT). Trace top/bottom per
 *    column, stretch each [Ty(x), By(x)] span to full height. A wide box's left/right edges
 *    are too short to trace, so a full Coons patch shears it; single-axis stretch is robust.
 *
 * Each edge is the smoothed raw silhouette, never a global polynomial — a polynomial
 * extrapolates past the box ends, leaking neighbours / clipping content. Returns the
 * (gray, binary) pair, or null when the edges can't be traced (caller falls back).
 */
class CurveRectifier {

    /**
     * @param srcGray   full source gray (e.g. the answer or info warp)
     * @param srcBinary full source binary (same frame as [srcGray])
     * @param contour   the box's contour in [srcBinary] pixel coords
     * @param outW/outH canonical sub-warp size
     * @param vertical  true = wide box (trace top/bottom), false = tall box (left/right)
     */
    fun rectify(
        srcGray: Mat, srcBinary: Mat, contour: MatOfPoint,
        outW: Int, outH: Int, vertical: Boolean
    ): Pair<Mat, Mat>? {
        val maps = computeMaps(srcBinary.cols(), srcBinary.rows(), contour, outW, outH, vertical)
            ?: return null
        val (mapX, mapY) = maps
        val outGray = Mat(); val outBin = Mat()
        Imgproc.remap(srcGray, outGray, mapX, mapY, Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE, Scalar(255.0))
        Imgproc.remap(srcBinary, outBin, mapX, mapY, Imgproc.INTER_NEAREST,
            Core.BORDER_CONSTANT, Scalar(255.0))
        mapX.release(); mapY.release()
        return Pair(outGray, outBin)
    }

    /** Gray-only variant — for the info boxes, which recompute their own binary after
     *  the warp (so only the gray needs remapping). */
    fun rectifyGray(srcGray: Mat, contour: MatOfPoint, outW: Int, outH: Int, vertical: Boolean): Mat? {
        val maps = computeMaps(srcGray.cols(), srcGray.rows(), contour, outW, outH, vertical)
            ?: return null
        val (mapX, mapY) = maps
        val outGray = Mat()
        Imgproc.remap(srcGray, outGray, mapX, mapY, Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE, Scalar(255.0))
        mapX.release(); mapY.release()
        return outGray
    }

    /**
     * Top-anchored vertical rectify for a wide box whose bottom border can't be traced:
     * the SUBJECT box sits above the answer grid, so its silhouette bottom is contaminated
     * by the column boxes. The top border is clean (empty paper above), so trace it and
     * stretch each column down by the box's median traced height (robust to the few
     * contaminated columns). Gray only. Null when the top edge can't be traced.
     */
    fun rectifyGrayTopAnchored(srcGray: Mat, contour: MatOfPoint, outW: Int, outH: Int): Mat? {
        val w = srcGray.cols(); val h = srcGray.rows()
        val br = Imgproc.boundingRect(contour)
        val bx0 = (br.x - BBOX_PAD).coerceIn(0, w - 1)
        val by0 = (br.y - BBOX_PAD).coerceIn(0, h - 1)
        val bx1 = (br.x + br.width + BBOX_PAD).coerceIn(bx0 + 1, w)
        val by1 = (br.y + br.height + BBOX_PAD).coerceIn(by0 + 1, h)
        val cropW = bx1 - bx0; val cropH = by1 - by0
        if (cropW < 20 || cropH < 10) return null

        val mask = Mat.zeros(cropH, cropW, CvType.CV_8UC1)
        Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1, Imgproc.LINE_8,
            Mat(), Int.MAX_VALUE, Point(-bx0.toDouble(), -by0.toDouble()))
        val px = ByteArray(cropW * cropH); mask.get(0, 0, px); mask.release()

        val topRaw = FloatArray(cropW) { -1f }
        val heights = ArrayList<Float>(cropW)
        var valid = 0
        for (x in 0 until cropW) {
            var ty = -1; var by = -1
            for (y in 0 until cropH) if (px[y * cropW + x].toInt() != 0) { ty = y; break }
            for (y in cropH - 1 downTo 0) if (px[y * cropW + x].toInt() != 0) { by = y; break }
            if (ty >= 0 && by > ty) { topRaw[x] = ty.toFloat(); heights.add((by - ty).toFloat()); valid++ }
        }
        if (valid < MIN_VALID_FRAC * cropW || heights.isEmpty()) return null
        val ct = smoothEdge(topRaw)
        heights.sort()
        val boxH = heights[heights.size / 2].toDouble()  // median height: robust to contamination

        val mapXarr = FloatArray(outW * outH)
        val mapYarr = FloatArray(outW * outH)
        for (u in 0 until outW) {
            val s = if (outW > 1) u.toDouble() / (outW - 1) else 0.0
            val fj = s * (cropW - 1)
            val ty = interpEdge(ct, fj)
            val srcX = (bx0 + fj).toFloat()
            for (v in 0 until outH) {
                val fy = if (outH > 1) v.toDouble() / (outH - 1) else 0.0
                val idx = v * outW + u
                mapXarr[idx] = srcX
                mapYarr[idx] = (by0 + ty + fy * boxH).toFloat()
            }
        }
        val mapX = Mat(outH, outW, CvType.CV_32FC1); mapX.put(0, 0, mapXarr)
        val mapY = Mat(outH, outW, CvType.CV_32FC1); mapY.put(0, 0, mapYarr)
        val outGray = Mat()
        Imgproc.remap(srcGray, outGray, mapX, mapY, Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE, Scalar(255.0))
        mapX.release(); mapY.release()
        return outGray
    }

    /** Builds the (mapX, mapY) remap from the box's filled-silhouette border curves,
     *  or null when the relevant edges can't be traced. [w]×[h] = source frame size. */
    private fun computeMaps(
        w: Int, h: Int, contour: MatOfPoint, outW: Int, outH: Int, vertical: Boolean
    ): Pair<Mat, Mat>? {
        val br = Imgproc.boundingRect(contour)  // includes outward bulges
        val bx0 = (br.x - BBOX_PAD).coerceIn(0, w - 1)
        val by0 = (br.y - BBOX_PAD).coerceIn(0, h - 1)
        val bx1 = (br.x + br.width + BBOX_PAD).coerceIn(bx0 + 1, w)
        val by1 = (br.y + br.height + BBOX_PAD).coerceIn(by0 + 1, h)
        val cropW = bx1 - bx0; val cropH = by1 - by0
        if (cropW < 20 || cropH < 20) return null

        // Fill THIS box's contour into a local mask (offset into crop coords).
        val mask = Mat.zeros(cropH, cropW, CvType.CV_8UC1)
        Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1, Imgproc.LINE_8,
            Mat(), Int.MAX_VALUE, Point(-bx0.toDouble(), -by0.toDouble()))
        val px = ByteArray(cropW * cropH); mask.get(0, 0, px); mask.release()

        val mapXarr = FloatArray(outW * outH)
        val mapYarr = FloatArray(outW * outH)

        if (!vertical) {
            // ── Tall box: per-row horizontal stretch between traced left/right edges ──
            val leftRaw = FloatArray(cropH) { -1f }
            val rightRaw = FloatArray(cropH) { -1f }
            var valid = 0
            for (y in 0 until cropH) {
                val rb = y * cropW; var lx = -1; var rx = -1
                for (x in 0 until cropW) if (px[rb + x].toInt() != 0) { lx = x; break }
                for (x in cropW - 1 downTo 0) if (px[rb + x].toInt() != 0) { rx = x; break }
                if (lx >= 0 && rx > lx) { leftRaw[y] = lx.toFloat(); rightRaw[y] = rx.toFloat(); valid++ }
            }
            if (valid < MIN_VALID_FRAC * cropH) return null
            val cl = smoothEdge(leftRaw); val cr = smoothEdge(rightRaw)
            for (v in 0 until outH) {
                val t = if (outH > 1) v.toDouble() / (outH - 1) else 0.0
                val fi = t * (cropH - 1)
                val lx = interpEdge(cl, fi)
                val rx = interpEdge(cr, fi).coerceAtLeast(lx + 1.0)
                val srcY = (by0 + fi).toFloat(); val span = rx - lx; val rb = v * outW
                for (u in 0 until outW) {
                    val fx = if (outW > 1) u.toDouble() / (outW - 1) else 0.0
                    mapXarr[rb + u] = (bx0 + lx + fx * span).toFloat()
                    mapYarr[rb + u] = srcY
                }
            }
        } else {
            // ── Wide box: per-column vertical stretch between traced top/bottom edges ──
            val topRaw = FloatArray(cropW) { -1f }
            val botRaw = FloatArray(cropW) { -1f }
            var valid = 0
            for (x in 0 until cropW) {
                var ty = -1; var by = -1
                for (y in 0 until cropH) if (px[y * cropW + x].toInt() != 0) { ty = y; break }
                for (y in cropH - 1 downTo 0) if (px[y * cropW + x].toInt() != 0) { by = y; break }
                if (ty >= 0 && by > ty) { topRaw[x] = ty.toFloat(); botRaw[x] = by.toFloat(); valid++ }
            }
            if (valid < MIN_VALID_FRAC * cropW) return null
            val ct = smoothEdge(topRaw); val cb = smoothEdge(botRaw)
            for (u in 0 until outW) {
                val s = if (outW > 1) u.toDouble() / (outW - 1) else 0.0
                val fj = s * (cropW - 1)
                val ty = interpEdge(ct, fj)
                val by = interpEdge(cb, fj).coerceAtLeast(ty + 1.0)
                val srcX = (bx0 + fj).toFloat(); val span = by - ty
                for (v in 0 until outH) {
                    val fy = if (outH > 1) v.toDouble() / (outH - 1) else 0.0
                    val idx = v * outW + u
                    mapXarr[idx] = srcX
                    mapYarr[idx] = (by0 + ty + fy * span).toFloat()
                }
            }
        }

        val mapX = Mat(outH, outW, CvType.CV_32FC1); mapX.put(0, 0, mapXarr)
        val mapY = Mat(outH, outW, CvType.CV_32FC1); mapY.put(0, 0, mapYarr)
        return Pair(mapX, mapY)
    }

    /** Smooths a raw traced edge (entries < 0 are gaps): interpolate gaps, a small
     *  median pass (rejects 1–2px rasterization spikes), then a moving average.
     *  Follows the TRUE box boundary, so it can never extrapolate outside the box. */
    private fun smoothEdge(raw: FloatArray): FloatArray {
        val n = raw.size
        val nextValid = IntArray(n)
        run { var nx = -1; for (i in n - 1 downTo 0) { if (raw[i] >= 0f) nx = i; nextValid[i] = nx } }
        val filled = FloatArray(n)
        var prev = -1
        for (i in 0 until n) {
            if (raw[i] >= 0f) { filled[i] = raw[i]; prev = i } else {
                val nv = nextValid[i]
                filled[i] = when {
                    prev >= 0 && nv >= 0 -> raw[prev] + (i - prev).toFloat() / (nv - prev) * (raw[nv] - raw[prev])
                    prev >= 0 -> raw[prev]
                    nv >= 0 -> raw[nv]
                    else -> 0f
                }
            }
        }
        val med = FloatArray(n); val mbuf = FloatArray(5)
        for (i in 0 until n) {
            for (j in -2..2) mbuf[j + 2] = filled[(i + j).coerceIn(0, n - 1)]
            mbuf.sort(); med[i] = mbuf[2]
        }
        val out = FloatArray(n); val half = EDGE_SMOOTH / 2
        for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (j in -half..half) { s += med[(i + j).coerceIn(0, n - 1)]; c++ }
            out[i] = (s / c).toFloat()
        }
        return out
    }

    /** Linear interpolation of a smoothed edge array at fractional index [fi]. */
    private fun interpEdge(arr: FloatArray, fi: Double): Double {
        val n = arr.size
        if (fi <= 0.0) return arr[0].toDouble()
        if (fi >= n - 1) return arr[n - 1].toDouble()
        val lo = fi.toInt(); val frac = fi - lo
        return arr[lo] * (1 - frac) + arr[lo + 1] * frac
    }

    companion object {
        private const val BBOX_PAD = 6
        private const val EDGE_SMOOTH = 41       // edge moving-average window
        private const val MIN_VALID_FRAC = 0.60f // fraction of rows/cols that must trace
    }
}
