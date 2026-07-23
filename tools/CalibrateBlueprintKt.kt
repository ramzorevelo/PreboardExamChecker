/**
 * CalibrateBlueprintKt.kt — Standalone JVM calibration script for SheetBlueprint.
 *
 * HOW TO RUN:
 *   1. Download the OpenCV 4.12.0 Java desktop release:
 *      https://sourceforge.net/projects/opencvlibrary/files/4.12.0/
 *      (or build from source). You need opencv-412.jar and the platform native lib
 *      (libopencv_java412.so on Linux, opencv_java412.dll on Windows, libopencv_java412.dylib on macOS).
 *
 *   2. Render the answer sheet PDF at 300 DPI (requires poppler-utils):
 *      pdftoppm -r 300 reference/Catc__answer_sheetV4.pdf reference/sheet -png
 *      This produces reference/sheet-1.png (~3900×2550 px).
 *
 *   3. Compile and run (from the project root directory):
 *      kotlinc -classpath /path/to/opencv-412.jar tools/CalibrateBlueprintKt.kt \
 *              -include-runtime -d calibrate.jar
 *      java -Djava.library.path=/path/to/opencv/native \
 *           -jar calibrate.jar [path/to/sheet-1.png]
 *
 *   4. Inspect the printed output and warped_annotated.png.
 *      Green boxes = detected cell boundaries. If they align with the printed cells,
 *      paste the printed fractions into SheetBlueprint.kt.
 *
 * OUTPUT: Normalized fractions for INFO_W=800, INFO_H=1300 canonical warp space.
 */

import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)

    val inputPath = args.getOrElse(0) { "reference/sheet-1.png" }
    val src = Imgcodecs.imread(inputPath)
    if (src.empty()) {
        println("ERROR: Could not load '$inputPath'. Render the PDF first.")
        return
    }
    println("Loaded: $inputPath  (${src.cols()}×${src.rows()} px)")

    // ── Convert to grayscale ────────────────────────────────────────────────
    val gray = Mat()
    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
    src.release()

    // ── CLAHE (3.0, 32×32) for robust marker detection ─────────────────────
    val clahe = Imgproc.createCLAHE(3.0, Size(32.0, 32.0))
    val enhanced = Mat()
    clahe.apply(gray, enhanced)

    // ── Otsu threshold — inverted so dark markers → white blobs ────────────
    val binary = Mat()
    Imgproc.threshold(enhanced, binary, 0.0, 255.0,
        Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

    // ── Find marker candidates ──────────────────────────────────────────────
    val imgW = binary.cols()
    val imgH = binary.rows()
    val expectedSide = (imgW * 0.012).toInt()
    val expectedArea = expectedSide * expectedSide
    val minArea = (expectedArea * 0.5).toInt()
    val maxArea = (expectedArea * 3.0).toInt()
    println("Marker detection: expectedArea=$expectedArea px² (side ~${expectedSide}px), range $minArea–$maxArea")

    val candidates = findMarkerCandidates(binary, minArea, maxArea)
    println("Candidates after filtering: ${candidates.size}")
    binary.release()

    // ── Assign to quadrants (for Phase 1: TL, TM, BL, BM) ─────────────────
    val midX = imgW / 2.0
    val midY = imgH / 2.0

    val tl = candidates.filter { it.center.x <= midX && it.center.y <= midY }.maxByOrNull { it.area }
    val tm = candidates.filter { it.center.x >  midX && it.center.y <= midY }.maxByOrNull { it.area }
    val bl = candidates.filter { it.center.x <= midX && it.center.y >  midY }.maxByOrNull { it.area }
    val bm = candidates.filter { it.center.x >  midX && it.center.y >  midY }.maxByOrNull { it.area }

    if (tl == null || tm == null || bl == null || bm == null) {
        println("ERROR: Missing marker — TL=$tl TM=$tm BL=$bl BM=$bm")
        println("Try adjusting the marker area range or check reference/sheet-1.png")
        enhanced.release(); gray.release()
        return
    }

    println("Markers found (in ${imgW}×${imgH} reference image):")
    println("  TL: (${tl.center.x.toInt()}, ${tl.center.y.toInt()})  area=${tl.area.toInt()}")
    println("  TM: (${tm.center.x.toInt()}, ${tm.center.y.toInt()})  area=${tm.area.toInt()}")
    println("  BL: (${bl.center.x.toInt()}, ${bl.center.y.toInt()})  area=${bl.area.toInt()}")
    println("  BM: (${bm.center.x.toInt()}, ${bm.center.y.toInt()})  area=${bm.area.toInt()}")

    // ── Warp to Phase 1 canonical size (800×1300) ───────────────────────────
    val warpW = 800; val warpH = 1300
    val srcPts = MatOfPoint2f(tl.center, tm.center, bl.center, bm.center)
    val dstPts = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(warpW.toDouble(), 0.0),
        Point(0.0, warpH.toDouble()),
        Point(warpW.toDouble(), warpH.toDouble())
    )
    val H = Imgproc.getPerspectiveTransform(srcPts, dstPts)
    val warpedGray = Mat()
    Imgproc.warpPerspective(gray, warpedGray, H, Size(warpW.toDouble(), warpH.toDouble()))
    H.release(); srcPts.release(); dstPts.release()
    gray.release(); enhanced.release()
    println("\nWarped info zone: ${warpedGray.cols()}×${warpedGray.rows()}")

    // ── Apply light CLAHE on warped for projection analysis ─────────────────
    val claheLight = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    val warpedEnhanced = Mat()
    claheLight.apply(warpedGray, warpedEnhanced)

    // ── Build binary for projection (dark = bracket frames) ─────────────────
    val warpedBinary = Mat()
    Imgproc.threshold(warpedEnhanced, warpedBinary, 100.0, 255.0, Imgproc.THRESH_BINARY_INV)

    // ── Horizontal projection: dark-pixel count per row ─────────────────────
    // ID grid occupies roughly x=25%–75% of canvas. Scan that band.
    val gx0 = (warpW * 0.25).toInt()
    val gx1 = (warpW * 0.75).toInt()
    val gxW = gx1 - gx0

    val hProj = DoubleArray(warpH)
    for (y in 0 until warpH) {
        val row = warpedBinary.submat(y, y + 1, gx0, gx1)
        hProj[y] = Core.sumElems(row).`val`[0] / 255.0 / gxW  // fraction of dark pixels
        row.release()
    }
    warpedBinary.release()

    // ── Find ID-grid rows by locating rhythmic peaks in hProj ───────────────
    // Bracket cell frames produce dark horizontal stripes at top and bottom of each cell.
    // We look for peaks in the dark-pixel fraction, then cluster them.
    val smoothedH = smoothProfile(hProj, 3)
    // Search range: skip the top ~12% (test-set area) and bottom ~10%
    val hy0 = (warpH * 0.12).toInt()
    val hy1 = (warpH * 0.90).toInt()
    val hPeaks = findLocalMaxima(smoothedH, hy0, hy1, minSeparation = 15, minValue = 0.02)
    println("Horizontal dark-stripe peaks (y, fraction): ${hPeaks.map { "$it (${String.format("%.3f", smoothedH[it])})" }}")

    // Cluster consecutive peaks to find cell midpoints (each cell = 2 frame peaks)
    val rowCenters = clusterPeakPairs(hPeaks, smoothedH)
    println("Estimated row centers (${rowCenters.size}): $rowCenters")

    // ── Vertical projection: dark-pixel count per column ────────────────────
    // Search within the estimated ID-grid y-range
    val vy0 = if (rowCenters.size >= 2) rowCenters.first() - 20 else (warpH * 0.30).toInt()
    val vy1 = if (rowCenters.size >= 2) rowCenters.last()  + 20 else (warpH * 0.75).toInt()
    val vyH = (vy1 - vy0).coerceAtLeast(1)

    val vProj = DoubleArray(warpW)
    val gridBand = warpedEnhanced.submat(vy0, vy1, 0, warpW)
    val gridBandBin = Mat()
    Imgproc.threshold(gridBand, gridBandBin, 100.0, 255.0, Imgproc.THRESH_BINARY_INV)
    gridBand.release()
    for (x in 0 until warpW) {
        val col = gridBandBin.submat(0, vyH, x, x + 1)
        vProj[x] = Core.sumElems(col).`val`[0] / 255.0 / vyH
        col.release()
    }
    gridBandBin.release()

    val smoothedV = smoothProfile(vProj, 3)
    val vPeaks = findLocalMaxima(smoothedV, (warpW * 0.15).toInt(), (warpW * 0.85).toInt(),
        minSeparation = 15, minValue = 0.02)
    println("Vertical dark-stripe peaks (x, fraction): ${vPeaks.map { "$it (${String.format("%.3f", smoothedV[it])})" }}")

    val colCenters = clusterPeakPairs(vPeaks, smoothedV)
    println("Estimated column centers (${colCenters.size}): $colCenters")

    // ── Also locate test-set cells ──────────────────────────────────────────
    // Scan top 15% of canvas for A/B cells
    val testBand = warpedEnhanced.submat(0, (warpH * 0.20).toInt(), 0, warpW)
    val testBandBin = Mat()
    Imgproc.threshold(testBand, testBandBin, 100.0, 255.0, Imgproc.THRESH_BINARY_INV)
    testBand.release()

    val testHProj = DoubleArray((warpH * 0.20).toInt())
    for (y in 0 until testHProj.size) {
        val row = testBandBin.submat(y, y + 1, (warpW * 0.30).toInt(), (warpW * 0.80).toInt())
        testHProj[y] = Core.sumElems(row).`val`[0] / 255.0 / (warpW * 0.50).toInt()
        row.release()
    }
    testBandBin.release()

    val testSmoothedH = smoothProfile(testHProj, 3)
    val testPeaks = findLocalMaxima(testSmoothedH, 20, testHProj.size - 5, 10, 0.01)
    val testSetYCenter = testPeaks.firstOrNull() ?: 168
    println("Test-set row y-center estimate: $testSetYCenter")

    // ── Print normalized fractions ───────────────────────────────────────────
    println("\n══════════════════════════════════════════════════════════════════")
    println("Paste into SheetBlueprint.kt (INFO_W=$warpW, INFO_H=$warpH):")
    println()

    val useRows = rowCenters.takeLast(10)
    val useCols = colCenters.takeLast(6)

    if (useRows.size >= 2) {
        val rowStride = (useRows.last() - useRows.first()) / (useRows.size - 1)
        val rowOrigin = useRows.first()
        val cellH = (rowStride * 0.88).toInt()
        println("  // Row positions (10 rows, digits 0–9)")
        println("  // idGrid row origin y: $rowOrigin  (= ${String.format("%.4f", rowOrigin.toFloat()/warpH)}f of INFO_H)")
        println("  // row stride: $rowStride px  (= ${String.format("%.4f", rowStride.toFloat()/warpH)}f of INFO_H)")
        println("  // cell height estimate: $cellH px  (= ${String.format("%.4f", cellH.toFloat()/warpH)}f of INFO_H)")
    }

    if (useCols.size >= 2) {
        val colStride = (useCols.last() - useCols.first()) / (useCols.size - 1)
        val colOrigin = useCols.first()
        val cellW = (colStride * 0.85).toInt()
        println("  // Column positions (6 columns)")
        println("  // idGrid col origin x: $colOrigin  (= ${String.format("%.4f", colOrigin.toFloat()/warpW)}f of INFO_W)")
        println("  // col stride: $colStride px  (= ${String.format("%.4f", colStride.toFloat()/warpW)}f of INFO_W)")
        println("  // cell width estimate: $cellW px  (= ${String.format("%.4f", cellW.toFloat()/warpW)}f of INFO_W)")
        if (useRows.size >= 2 && useCols.size >= 2) {
            val rowStride = (useRows.last() - useRows.first()) / (useRows.size - 1)
            val cellH = (rowStride * 0.88).toInt()
            println("  // H:W portrait ratio: ${String.format("%.2f", cellH.toFloat() / cellW)}")
        }
    }

    println()
    println("  const val INFO_CELL_W_FRAC = ${if (useCols.size >= 2) {
        val s = (useCols.last() - useCols.first()) / (useCols.size - 1)
        String.format("%.4f", (s * 0.85).toFloat() / warpW) } else "0.0550"}f")
    println("  const val INFO_CELL_H_FRAC = ${if (useRows.size >= 2) {
        val s = (useRows.last() - useRows.first()) / (useRows.size - 1)
        String.format("%.4f", (s * 0.88).toFloat() / warpH) } else "0.0546"}f")
    println()
    println("  val idGrid = Array(6) { col ->")
    val colOriginStr = if (useCols.size >= 2) "${useCols.first()}f + col * ${(useCols.last()-useCols.first())/(useCols.size-1)}f" else "279f + col * 53f"
    val rowOriginStr = if (useRows.size >= 2) "${useRows.first()}f + digit * ${(useRows.last()-useRows.first())/(useRows.size-1)}f" else "360f + digit * 82f"
    println("      val cx = ($colOriginStr) / INFO_W")
    println("      Array(10) { digit ->")
    println("          val cy = ($rowOriginStr) / INFO_H")
    println("          NormPoint(cx, cy)")
    println("      }")
    println("  }")
    println()
    println("  val testSetA = NormPoint(___f / INFO_W, ${testSetYCenter}f / INFO_H)")
    println("  val testSetB = NormPoint(___f / INFO_W, ${testSetYCenter}f / INFO_H)")
    println("  // (measure test-set A and B x-centers from warped_annotated.png)")
    println("══════════════════════════════════════════════════════════════════")

    // ── Save annotated warped image ──────────────────────────────────────────
    val annotated = Mat()
    Imgproc.cvtColor(warpedGray, annotated, Imgproc.COLOR_GRAY2BGR)
    warpedGray.release()

    val green = Scalar(0.0, 255.0, 0.0)
    val blue  = Scalar(255.0, 0.0, 0.0)
    val red   = Scalar(0.0, 0.0, 255.0)
    val cyan  = Scalar(255.0, 255.0, 0.0)

    // Draw column lines
    for (cx in colCenters) {
        Imgproc.line(annotated, Point(cx.toDouble(), 0.0),
            Point(cx.toDouble(), warpH.toDouble()), blue, 1)
    }
    // Draw row lines
    for (ry in rowCenters) {
        Imgproc.line(annotated, Point(0.0, ry.toDouble()),
            Point(warpW.toDouble(), ry.toDouble()), red, 1)
    }
    // Draw green cell rectangles
    if (rowCenters.size >= 2 && colCenters.size >= 2) {
        val rS = (rowCenters.last() - rowCenters.first()) / (rowCenters.size - 1)
        val cS = (colCenters.last() - colCenters.first()) / (colCenters.size - 1)
        val cH = (rS * 0.88).toInt()
        val cW = (cS * 0.85).toInt()
        for (ry in rowCenters) for (cx in colCenters) {
            Imgproc.rectangle(annotated,
                Rect((cx - cW/2).coerceAtLeast(0), (ry - cH/2).coerceAtLeast(0), cW, cH),
                green, 2)
        }
    }
    // Draw test-set row line in cyan
    Imgproc.line(annotated, Point(0.0, testSetYCenter.toDouble()),
        Point(warpW.toDouble(), testSetYCenter.toDouble()), cyan, 1)

    Imgcodecs.imwrite("warped_annotated.png", annotated)
    println("\nSaved: warped_annotated.png")
    println("Inspect the image: green boxes should align with printed bracket cells.")
    println("If they do not, the reference/sheet-1.png may need re-rendering at 300 DPI.")

    annotated.release()
    warpedEnhanced.release()
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private data class MarkerCandidate(val center: Point, val area: Double)

private fun findMarkerCandidates(
    binary: Mat, minArea: Int, maxArea: Int
): List<MarkerCandidate> {
    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(binary.clone(), contours, hierarchy,
        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    hierarchy.release()

    val results = mutableListOf<MarkerCandidate>()
    for (contour in contours) {
        val rect = Imgproc.boundingRect(contour)
        val bboxArea = rect.width * rect.height
        if (bboxArea !in minArea..maxArea) { contour.release(); continue }
        val aspect = rect.width.toDouble() / rect.height
        if (aspect < 0.6 || aspect > 1.6) { contour.release(); continue }
        val cArea = Imgproc.contourArea(contour)
        if (cArea / bboxArea < 0.80) { contour.release(); continue }
        results.add(MarkerCandidate(
            center = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0),
            area = cArea
        ))
        contour.release()
    }
    return results
}

private fun smoothProfile(profile: DoubleArray, window: Int): DoubleArray {
    val result = DoubleArray(profile.size)
    val half = window / 2
    for (i in profile.indices) {
        var sum = 0.0; var count = 0
        for (j in maxOf(0, i - half)..minOf(profile.size - 1, i + half)) {
            sum += profile[j]; count++
        }
        result[i] = if (count > 0) sum / count else 0.0
    }
    return result
}

private fun findLocalMaxima(
    profile: DoubleArray, start: Int, end: Int,
    minSeparation: Int, minValue: Double
): List<Int> {
    val clampedStart = start.coerceIn(1, profile.size - 2)
    val clampedEnd = end.coerceIn(1, profile.size - 2)
    val maxima = mutableListOf<Int>()
    for (i in clampedStart..clampedEnd) {
        if (profile[i] >= minValue &&
            profile[i] > profile[i - 1] &&
            profile[i] > profile[i + 1]) {
            if (maxima.isEmpty() || i - maxima.last() >= minSeparation) {
                maxima.add(i)
            } else if (profile[i] > profile[maxima.last()]) {
                maxima[maxima.size - 1] = i
            }
        }
    }
    return maxima
}

/**
 * Given a list of dark-stripe peaks (which appear at top AND bottom of each cell frame),
 * cluster consecutive pairs to estimate cell center y-positions.
 * If peaks can't be paired, returns peaks directly as center estimates.
 */
private fun clusterPeakPairs(peaks: List<Int>, profile: DoubleArray): List<Int> {
    if (peaks.size < 2) return peaks
    // Compute gaps between consecutive peaks
    val gaps = peaks.zipWithNext { a, b -> b - a }
    val medianGap = gaps.sorted()[gaps.size / 2]
    // If median gap is small (< 20px), peaks come in pairs (top+bottom of each cell frame)
    // Midpoint of each pair = cell center
    if (medianGap < 20) {
        val centers = mutableListOf<Int>()
        var i = 0
        while (i < peaks.size - 1) {
            if (peaks[i + 1] - peaks[i] < 25) {
                centers.add((peaks[i] + peaks[i + 1]) / 2)
                i += 2
            } else {
                centers.add(peaks[i])
                i++
            }
        }
        if (i < peaks.size) centers.add(peaks.last())
        return centers
    }
    // Otherwise peaks are already at cell centers
    return peaks
}
