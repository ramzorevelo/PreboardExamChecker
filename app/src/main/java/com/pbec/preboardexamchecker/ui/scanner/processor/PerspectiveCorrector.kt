package com.pbec.preboardexamchecker.ui.scanner.processor

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Corner order: [topLeft, topRight, bottomLeft, bottomRight]. */
class PerspectiveCorrector {

    fun warp(
        source: Mat,
        corners: List<Point>,
        targetWidth: Int,
        targetHeight: Int,
        interpolation: Int = Imgproc.INTER_LINEAR
    ): Mat {
        require(corners.size == 4) { "Exactly 4 corner points required" }

        val srcPoints = MatOfPoint2f(
            corners[0],
            corners[1],
            corners[2],
            corners[3]
        )

        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(targetWidth.toDouble(), 0.0),
            Point(0.0, targetHeight.toDouble()),
            Point(targetWidth.toDouble(), targetHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val warped = Mat()
        // Binary callers pass INTER_NEAREST to avoid interpolation graying the edges.
        Imgproc.warpPerspective(source, warped, transform,
            Size(targetWidth.toDouble(), targetHeight.toDouble()), interpolation)

        srcPoints.release()
        dstPoints.release()
        transform.release()

        return warped
    }
}
