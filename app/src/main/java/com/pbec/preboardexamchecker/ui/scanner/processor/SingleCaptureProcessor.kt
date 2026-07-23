package com.pbec.preboardexamchecker.ui.scanner.processor

import android.graphics.Bitmap
import android.util.Log
import com.pbec.preboardexamchecker.BuildConfig
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringStrategy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Single-capture processor: reads both the info zone and the answer zone from one full-sheet
 * capture containing all 6 markers. Builds the neutralized gray + CLAHE layers once →
 * [MarkerDetector.detectSix] (orientation-agnostic) → reconcile against the 6 live-lock hints
 * → read each zone via the shared readWarpedZone stages → combine. Both reads share the
 * `cellEnhanced` layer, so the neutralize/CLAHE work is done once, not twice.
 */
class SingleCaptureProcessor(
    private val markerDetector: MarkerDetector = MarkerDetector(),
    private val infoProcessor: InfoZoneProcessor = InfoZoneProcessor(),
    private val answerProcessor: AnswerZoneProcessor = AnswerZoneProcessor()
) {

    sealed class Result {
        data class Success(
            val scanResult: ScanResult,
            val answerKey: String,
            val info: ScannedInfo
        ) : Result()
        data class SubjectMismatch(
            val detectedSubject: String,
            val capturedBitmap: Bitmap,
            val info: ScannedInfo
        ) : Result()
        data class Error(val reason: String) : Result()
        /** The 6-marker grid couldn't be confirmed (vs the live lock) — recapture. */
        data class Recapture(val reason: String) : Result()
    }

    companion object {
        private const val TAG = "SingleCaptureProcessor"
        private const val COLOR_SAT_THRESHOLD = 60
        // Per-corner tolerance when reconciling a detected marker against its refined
        // live-lock hint, as a fraction of image width (mirrors MarkerDetector's gate).
        private const val PER_CORNER_HINT_TOL = 0.08
    }

    /**
     * @param cornerHints the 6 locked marker positions from the live guide, as
     *   normalized [0,1] fractions in role order [TL, TM, TR, BL, BM, BR] (entries may
     *   be null). Used to narrow / correct detection, mirroring the 2-phase WYSIWYG gate.
     */
    fun process(
        bitmap: Bitmap,
        context: ScanContext,
        scoringStrategy: ScoringStrategy,
        cornerHints: Array<Point?>? = null,
        bypassBlurGate: Boolean = false,
        skipSubjectCheck: Boolean = false,
        debugContext: android.content.Context? = null
    ): Result {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // Mask colored pixels (blue ink/banners) to white so they don't register as dark
        // marker candidates.
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        hsv.release()
        val colorMask = Mat()
        Imgproc.threshold(hsvCh[1], colorMask, COLOR_SAT_THRESHOLD.toDouble(),
            255.0, Imgproc.THRESH_BINARY)
        hsvCh.forEach { it.release() }

        // Plain gray (cell reading) + masked gray (marker detection + neutral cell read).
        val plainGray = Mat()
        Imgproc.cvtColor(src, plainGray, Imgproc.COLOR_BGR2GRAY)
        src.release()
        val maskedGray = Mat()
        plainGray.copyTo(maskedGray)
        maskedGray.setTo(Scalar(255.0), colorMask)
        colorMask.release()

        // Layer A: CLAHE(3.0, 32×32) on the masked gray → Otsu binary for markers.
        val markerClahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
        val markerEnhanced = Mat()
        markerClahe.apply(maskedGray, markerEnhanced)
        val binary = Mat()
        Imgproc.threshold(markerEnhanced, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        // Layer B: CLAHE(2.0, 8×8) cell-reading grays — plain (shared by both zones)
        // and blue-neutralized (info ID/SET sub-boxes only).
        val cellClahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val cellEnhanced = Mat()
        cellClahe.apply(plainGray, cellEnhanced)
        val cellEnhancedNeutral = Mat()
        cellClahe.apply(maskedGray, cellEnhancedNeutral)
        plainGray.release()
        maskedGray.release()

        val imgW = binary.cols()
        val imgH = binary.rows()

        // Detect all 6 markers (role order [TL,TM,TR,BL,BM,BR]); markerEnhanced doubles
        // as the cornerSubPix grayscale reference.
        val rawSix = markerDetector.detectSix(binary, markerEnhanced)
        binary.release()

        // Refine the 6 live-lock hints onto the real squares, then reconcile per role.
        val hintPx: List<Point?>? = cornerHints?.map { h ->
            h?.let { markerDetector.refineToNearestSquare(
                markerEnhanced, Point(it.x * imgW, it.y * imgH), imgW) }
        }
        markerEnhanced.release()

        val markers6 = reconcileSix(rawSix, hintPx, imgW)
        if (markers6 == null) {
            cellEnhanced.release()
            cellEnhancedNeutral.release()
            return Result.Recapture("Could not find all 6 markers")
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "six markers (TL,TM,TR,BL,BM,BR) = $markers6")
        }

        // Warp order per zone: info [TL,TM,BL,BM], answer [TM,TR,BM,BR].
        val infoMarkers = listOf(markers6[0], markers6[1], markers6[3], markers6[4])
        val answerMarkers = listOf(markers6[1], markers6[2], markers6[4], markers6[5])

        // ── Info zone ─────────────────────────────────────────────────────────
        val infoResult = infoProcessor.readWarpedZone(
            cellEnhanced, cellEnhancedNeutral, infoMarkers, context.studentMap, debugContext
        )
        val scannedInfo = when (infoResult) {
            is InfoZoneProcessor.Result.Success -> infoResult.info
            is InfoZoneProcessor.Result.Recapture -> {
                cellEnhanced.release(); cellEnhancedNeutral.release()
                return Result.Recapture(infoResult.reason)
            }
            is InfoZoneProcessor.Result.Error -> {
                cellEnhanced.release(); cellEnhancedNeutral.release()
                return Result.Error("Info zone: ${infoResult.reason}")
            }
        }

        // ── Answer zone ─────────────────────────────────────────────────────────
        val answerResult = answerProcessor.readWarpedZone(
            cellEnhanced, answerMarkers, context, scannedInfo, scoringStrategy,
            bitmap, bypassBlurGate, skipSubjectCheck, debugContext
        )
        cellEnhanced.release()
        cellEnhancedNeutral.release()

        return when (answerResult) {
            is AnswerZoneProcessor.Result.Success ->
                Result.Success(answerResult.scanResult, answerResult.answerKey, scannedInfo)
            is AnswerZoneProcessor.Result.SubjectMismatch ->
                Result.SubjectMismatch(answerResult.detectedSubject, answerResult.capturedBitmap, scannedInfo)
            is AnswerZoneProcessor.Result.Recapture ->
                Result.Recapture(answerResult.reason)
            is AnswerZoneProcessor.Result.Error ->
                Result.Error("Answer zone: ${answerResult.reason}")
        }
    }

    /**
     * Corrects the 6 detected markers against the refined live-lock [hintPx]: keep a marker
     * that agrees with its hint, replace a mis-picked / missing one. Null only when detection
     * failed and the 6 hints aren't all present.
     */
    private fun reconcileSix(
        detected: List<Point>?, hintPx: List<Point?>?, imgW: Int
    ): List<Point>? {
        if (detected != null && (hintPx == null || imgW <= 0)) return detected
        if (detected == null) {
            return if (hintPx != null && hintPx.size == 6 && hintPx.all { it != null })
                hintPx.map { it!! } else null
        }
        if (detected.size != 6) return null
        var snapped = 0
        val out = ArrayList<Point>(6)
        for (i in 0 until 6) {
            val h = hintPx!!.getOrNull(i)
            if (h != null) {
                val d = kotlin.math.hypot(detected[i].x - h.x, detected[i].y - h.y) / imgW
                if (d > PER_CORNER_HINT_TOL) { out.add(h); snapped++ } else out.add(detected[i])
            } else out.add(detected[i])
        }
        if (snapped > 0) Log.w(TAG, "reconcileSix: snapped $snapped/6 marker(s) to the live lock")
        return out
    }
}
