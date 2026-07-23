package com.pbec.preboardexamchecker.ui.scanner.processor

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Laplacian variance; higher = sharper. Thresholds still need on-device calibration. */
class SharpnessChecker {

    companion object {
        const val BLUR_THRESHOLD    = 80.0
        const val SHARP_THRESHOLD   = 150.0
    }

    fun variance(bitmap: Bitmap): Double {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return varianceMat(mat).also { mat.release() }
    }

    fun varianceMat(mat: Mat): Double {
        val gray = Mat()
        if (mat.channels() > 1) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            mat.copyTo(gray)
        }

        // Center crop (middle 50%) to skip border noise.
        val cx = gray.cols() / 2
        val cy = gray.rows() / 2
        val w  = gray.cols() / 2
        val h  = gray.rows() / 2
        val roi = Mat(gray, org.opencv.core.Rect(cx - w / 2, cy - h / 2, w, h))

        val laplacian = Mat()
        Imgproc.Laplacian(roi, laplacian, org.opencv.core.CvType.CV_64F)

        val mean = org.opencv.core.MatOfDouble()
        val stddev = org.opencv.core.MatOfDouble()
        org.opencv.core.Core.meanStdDev(laplacian, mean, stddev)
        val std = stddev.toArray()[0]

        roi.release()
        laplacian.release()
        gray.release()
        mean.release()
        stddev.release()

        return std * std
    }

    fun isSharp(bitmap: Bitmap): Boolean = variance(bitmap) >= SHARP_THRESHOLD
    fun isBlurry(bitmap: Bitmap): Boolean = variance(bitmap) < BLUR_THRESHOLD

    /**
     * Variance over the whole [mat], no center crop: the content-aware blur gate already
     * passes a cropped, content-dense region and wants all of it measured.
     */
    fun varianceNoCrop(mat: Mat): Double {
        val gray = Mat()
        if (mat.channels() > 1) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            mat.copyTo(gray)
        }
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, org.opencv.core.CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val stddev = org.opencv.core.MatOfDouble()
        org.opencv.core.Core.meanStdDev(laplacian, mean, stddev)
        val std = stddev.toArray()[0]
        gray.release(); laplacian.release(); mean.release(); stddev.release()
        return std * std
    }
}
