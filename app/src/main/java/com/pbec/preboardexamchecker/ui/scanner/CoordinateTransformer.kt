package com.pbec.preboardexamchecker.ui.scanner

import android.graphics.PointF

// ImageAnalysis delivers frames in the sensor's natural orientation; rotate by
// rotationDegrees into upright space, then FILL_CENTER-scale onto the preview
// surface (matching PreviewView's default scale type). Rebuilt by ScanPhaseScreen
// whenever preview size or frame rotation changes.
class CoordinateTransformer(
    private val analysisWidth: Int,
    private val analysisHeight: Int,
    private val previewWidth: Int,
    private val previewHeight: Int,
    private val rotationDegrees: Int
) {
    private val uprightWidth: Int =
        if (rotationDegrees == 90 || rotationDegrees == 270) analysisHeight else analysisWidth
    private val uprightHeight: Int =
        if (rotationDegrees == 90 || rotationDegrees == 270) analysisWidth else analysisHeight

    fun transform(point: PointF): PointF {
        val ux: Float
        val uy: Float
        when (rotationDegrees) {
            90 -> { ux = analysisHeight - 1 - point.y; uy = point.x }
            180 -> { ux = analysisWidth - 1 - point.x; uy = analysisHeight - 1 - point.y }
            270 -> { ux = point.y; uy = analysisWidth - 1 - point.x }
            else -> { ux = point.x; uy = point.y }
        }

        if (uprightWidth <= 0 || uprightHeight <= 0) return PointF(ux, uy)

        val scale = maxOf(
            previewWidth.toFloat() / uprightWidth,
            previewHeight.toFloat() / uprightHeight
        )
        val offsetX = (previewWidth - uprightWidth * scale) / 2f
        val offsetY = (previewHeight - uprightHeight * scale) / 2f
        return PointF(ux * scale + offsetX, uy * scale + offsetY)
    }
}
