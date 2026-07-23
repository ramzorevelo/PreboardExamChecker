package com.pbec.preboardexamchecker.ui.scanner.processor

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Info zone uses [readFill] on a global binary Mat. The legacy [fillRatio]/[readGroup]
 * methods stay because the answer zone pipeline relies on a brightness-relative approach.
 */
class BubbleReader {

    companion object {
        const val FILL_THRESHOLD = 0.38f
        const val DOMINANCE_MARGIN = 0.12f
        const val SHADE_RELATIVE_THRESHOLD = 0.25
        const val ABSOLUTE_DARK_THRESHOLD = 155.0
    }

    /**
     * Fill ratio of [cellRect] sampled from [globalBinary] (whole sub-warp thresholded
     * once via CLAHE + Otsu). Per-cell Otsu would see a near-unimodal distribution and
     * count grey printed digits as fill; the global threshold keeps digits white and
     * only pencil marks black. 1-px inset excludes the bracket frame.
     */
    fun readFill(globalBinary: Mat, cellRect: Rect): Float {
        val safeRect = Rect(
            cellRect.x.coerceIn(0, (globalBinary.cols() - 1).coerceAtLeast(0)),
            cellRect.y.coerceIn(0, (globalBinary.rows() - 1).coerceAtLeast(0)),
            cellRect.width.coerceAtMost(globalBinary.cols() - cellRect.x).coerceAtLeast(1),
            cellRect.height.coerceAtMost(globalBinary.rows() - cellRect.y).coerceAtLeast(1)
        )
        if (safeRect.width <= 4 || safeRect.height <= 4) return 0f

        val inset = 1
        val innerW = safeRect.width - inset * 2
        val innerH = safeRect.height - inset * 2
        if (innerW <= 0 || innerH <= 0) return 0f

        val inner = Mat(globalBinary, Rect(safeRect.x + inset, safeRect.y + inset, innerW, innerH))

        val totalPixels = inner.rows() * inner.cols()
        val darkPixels  = totalPixels - Core.countNonZero(inner)

        inner.release()

        return if (totalPixels == 0) 0f else darkPixels.toFloat() / totalPixels
    }

    /**
     * Darkness score 0.0 (white) – 1.0 (black) for [cellRect] in [claheGray], measured
     * before thresholding. Continuous signal unaffected by Otsu's choice, so cells can be
     * compared against column neighbors despite per-capture lighting drift. 1-px inset.
     */
    fun readMeanDarkness(claheGray: Mat, cellRect: Rect): Float {
        val safeRect = Rect(
            cellRect.x.coerceIn(0, (claheGray.cols() - 1).coerceAtLeast(0)),
            cellRect.y.coerceIn(0, (claheGray.rows() - 1).coerceAtLeast(0)),
            cellRect.width.coerceAtMost(claheGray.cols() - cellRect.x).coerceAtLeast(1),
            cellRect.height.coerceAtMost(claheGray.rows() - cellRect.y).coerceAtLeast(1)
        )
        if (safeRect.width <= 4 || safeRect.height <= 4) return 0f

        val inset = 1
        val innerRect = Rect(
            safeRect.x + inset, safeRect.y + inset,
            (safeRect.width - inset * 2).coerceAtLeast(1),
            (safeRect.height - inset * 2).coerceAtLeast(1)
        )
        if (innerRect.width <= 0 || innerRect.height <= 0) return 0f

        val inner = Mat(claheGray, innerRect)
        val mean = Core.mean(inner).`val`[0]
        inner.release()

        return (1.0 - mean / 255.0).toFloat().coerceIn(0f, 1f)
    }

    /** Portrait-aware: separate width/height fractions for the info zone (taller than wide). */
    fun normToRect(
        center: NormPoint,
        cellWFrac: Float,
        cellHFrac: Float,
        canvasW: Int,
        canvasH: Int
    ): Rect {
        val pw = (cellWFrac * canvasW).toInt().coerceAtLeast(1)
        val ph = (cellHFrac * canvasH).toInt().coerceAtLeast(1)
        val px = (center.x * canvasW - pw / 2).toInt().coerceIn(0, (canvasW - pw).coerceAtLeast(0))
        val py = (center.y * canvasH - ph / 2).toInt().coerceIn(0, (canvasH - ph).coerceAtLeast(0))
        return Rect(px, py, pw, ph)
    }

    /** Legacy square cell (answer zone): both axes use [cellSize] * [canvasW]. */
    fun normToRect(center: NormPoint, cellSize: Float, canvasW: Int, canvasH: Int): Rect {
        val pw = (cellSize * canvasW).toInt().coerceAtLeast(1)
        val ph = (cellSize * canvasW).toInt().coerceAtLeast(1) // square: canvasW on both axes
        val px = (center.x * canvasW - pw / 2).toInt().coerceIn(0, (canvasW - pw).coerceAtLeast(0))
        val py = (center.y * canvasH - ph / 2).toInt().coerceIn(0, (canvasH - ph).coerceAtLeast(0))
        return Rect(px, py, pw, ph)
    }

    // Legacy methods (answer zone pipeline)

    fun fillRatio(warpedBinary: Mat, cellRect: Rect): Float {
        val roi = Mat(warpedBinary, cellRect)
        val nonZero = Core.countNonZero(roi)
        val total = cellRect.width * cellRect.height
        roi.release()
        return if (total == 0) 0f else nonZero.toFloat() / total.toFloat()
    }

    fun isShaded(warpedBinary: Mat, cellRect: Rect): Boolean =
        fillRatio(warpedBinary, cellRect) >= FILL_THRESHOLD

    fun fillRatioAdaptive(
        claheGrayMat: Mat,
        cellRect: Rect,
        innerFrac: Float = 0.72f
    ): Float {
        val sx = ((cellRect.width  * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
        val sy = ((cellRect.height * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
        val inner = Rect(
            cellRect.x + sx,
            cellRect.y + sy,
            (cellRect.width  - 2 * sx).coerceAtLeast(2),
            (cellRect.height - 2 * sy).coerceAtLeast(2)
        )
        val safe = Rect(
            inner.x.coerceIn(0, (claheGrayMat.cols() - 1).coerceAtLeast(0)),
            inner.y.coerceIn(0, (claheGrayMat.rows() - 1).coerceAtLeast(0)),
            inner.width .coerceAtMost(claheGrayMat.cols() - inner.x),
            inner.height.coerceAtMost(claheGrayMat.rows() - inner.y)
        )
        if (safe.width <= 0 || safe.height <= 0) return 0f

        val roi = Mat(claheGrayMat, safe)
        val normalized = Mat()
        Core.normalize(roi, normalized, 0.0, 255.0, Core.NORM_MINMAX, -1, Mat())
        val localBinary = Mat()
        Imgproc.threshold(normalized, localBinary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val nonZero = Core.countNonZero(localBinary)
        val total   = safe.width * safe.height
        roi.release(); normalized.release(); localBinary.release()
        return if (total == 0) 0f else nonZero.toFloat() / total
    }

    fun meanBrightness(grayMat: Mat, cellRect: Rect, innerFrac: Float = 0.72f): Double {
        val sx = ((cellRect.width  * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
        val sy = ((cellRect.height * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
        val ix = (cellRect.x + sx).coerceIn(0, grayMat.cols() - 1)
        val iy = (cellRect.y + sy).coerceIn(0, grayMat.rows() - 1)
        val iw = (cellRect.width  - 2 * sx).coerceAtLeast(2).coerceAtMost(grayMat.cols() - ix)
        val ih = (cellRect.height - 2 * sy).coerceAtLeast(2).coerceAtMost(grayMat.rows() - iy)
        if (iw <= 0 || ih <= 0) return 255.0
        val roi  = Mat(grayMat, Rect(ix, iy, iw, ih))
        val mean = Core.mean(roi)
        roi.release()
        return mean.`val`[0]
    }

    fun readGroup(grayMat: Mat, cellRects: List<Rect>, innerFrac: Float = 0.72f): Int {
        val brightnesses = cellRects.map { meanBrightness(grayMat, it, innerFrac) }
        val maxBrightness = brightnesses.maxOrNull() ?: return -1
        if (maxBrightness <= 0.0) return -1
        val candidates = brightnesses.indices.filter { i ->
            val depth = (maxBrightness - brightnesses[i]) / maxBrightness
            depth >= SHADE_RELATIVE_THRESHOLD && brightnesses[i] < ABSOLUTE_DARK_THRESHOLD
        }
        return when (candidates.size) {
            0    -> -1
            1    -> candidates[0]
            else -> -2
        }
    }

    fun readGroupBinary(
        warpedBinary: Mat,
        cellRects: List<Rect>,
        innerFrac: Float = 0.55f,
        fillThreshold: Float = 0.50f
    ): Int {
        val fills = cellRects.map { rect ->
            val sx = ((rect.width  * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
            val sy = ((rect.height * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
            val ix = (rect.x + sx).coerceIn(0, warpedBinary.cols() - 1)
            val iy = (rect.y + sy).coerceIn(0, warpedBinary.rows() - 1)
            val iw = (rect.width  - 2 * sx).coerceAtLeast(2).coerceAtMost(warpedBinary.cols() - ix)
            val ih = (rect.height - 2 * sy).coerceAtLeast(2).coerceAtMost(warpedBinary.rows() - iy)
            if (iw <= 0 || ih <= 0) 0f
            else fillRatio(warpedBinary, Rect(ix, iy, iw, ih))
        }
        Log.d("BubbleReader", "binary fills: ${fills.map { "%.2f".format(it) }}")
        val candidates = fills.indices.filter { fills[it] >= fillThreshold }
        return when (candidates.size) {
            0    -> -1
            1    -> candidates[0]
            else -> -2
        }
    }

    fun debugOverlay(warpedGray: Mat, allCellRects: List<Rect>): Mat {
        val bgr = Mat()
        Imgproc.cvtColor(warpedGray, bgr, Imgproc.COLOR_GRAY2BGR)
        val innerFrac = 0.72f
        for (rect in allCellRects) {
            val brightness = meanBrightness(warpedGray, rect)
            val isShaded = brightness < ABSOLUTE_DARK_THRESHOLD
            val outerColor = if (isShaded) Scalar(0.0, 0.0, 255.0) else Scalar(0.0, 255.0, 0.0)
            Imgproc.rectangle(bgr, rect, outerColor, 2)
            val sx = ((rect.width  * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
            val sy = ((rect.height * (1f - innerFrac)) / 2f).toInt().coerceAtLeast(1)
            val innerRect = Rect(
                rect.x + sx,
                rect.y + sy,
                (rect.width  - 2 * sx).coerceAtLeast(2),
                (rect.height - 2 * sy).coerceAtLeast(2)
            )
            Imgproc.rectangle(bgr, innerRect, Scalar(255.0, 0.0, 0.0), 1)
        }
        return bgr
    }
}
