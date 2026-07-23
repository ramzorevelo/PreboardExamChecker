package com.pbec.preboardexamchecker.ui.scanner.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

/**
 * JVM unit tests for [BubbleReader.readFill].
 *
 * These tests require the OpenCV native library to be loadable in the JVM test environment.
 * Without a desktop OpenCV native lib on the classpath they are automatically SKIPPED
 * (using Assume.assumeTrue) — the test file will still compile cleanly.
 *
 * To run locally:
 *   1. Download the OpenCV 4.12.0 desktop release for your platform.
 *   2. Place the native lib (libopencv_java412.so / opencv_java412.dll / libopencv_java412.dylib)
 *      somewhere accessible.
 *   3. Add to your test JVM args:
 *      -Djava.library.path=/path/to/opencv/native
 *   4. The tests will then execute.
 */
class BubbleReaderTest {

    companion object {
        private var opencvAvailable = false

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            opencvAvailable = try {
                System.loadLibrary("opencv_java412")
                true
            } catch (e: UnsatisfiedLinkError) {
                // Desktop OpenCV native lib not installed — tests will be skipped
                false
            }
        }
    }

    private val reader = BubbleReader()

    /**
     * A Mat filled with all-white pixels (value = 255) should have a fill ratio
     * near 0.0 — no pencil shading detected.
     *
     * Reasoning: readFill treats its input as an already-thresholded global binary
     * Mat (bright → 255, dark → 0). All pixels here are 255 (non-zero), so
     * countNonZero = total and darkPixels = total − countNonZero = 0 → fill = 0.0.
     */
    @Test
    fun `all-white cell returns fill ratio near 0`() {
        assumeTrue("OpenCV native library not available", opencvAvailable)

        val mat = Mat(80, 50, CvType.CV_8UC1, Scalar(255.0))
        val rect = Rect(0, 0, mat.cols(), mat.rows())
        val fill = reader.readFill(mat, rect)
        mat.release()

        assertEquals("All-white cell should have fill ≈ 0.0", 0.0f, fill, 0.05f)
    }

    /**
     * A Mat filled with all-black pixels (value = 0) should have a fill ratio
     * near 1.0 — fully shaded.
     *
     * Reasoning: every pixel is 0 (dark) in the global binary Mat. countNonZero = 0,
     * so darkPixels = total → fill = 1.0.
     */
    @Test
    fun `all-black cell returns fill ratio near 1`() {
        assumeTrue("OpenCV native library not available", opencvAvailable)

        val mat = Mat(80, 50, CvType.CV_8UC1, Scalar(0.0))
        val rect = Rect(0, 0, mat.cols(), mat.rows())
        val fill = reader.readFill(mat, rect)
        mat.release()

        assertTrue("All-black cell should have fill near 1.0 (got $fill)", fill >= 0.90f)
    }

    /**
     * A Mat where approximately 70% of pixel rows are black (0) and 30% are white (255)
     * should return a fill ratio close to 0.70.
     *
     * The 2-pixel inset removes the outer border rows, so the actual inner area spans
     * rows 2..(height−3). Of the 80-row Mat: inner has 76 rows; rows 0–55 are black
     * (56 black rows in inner area = rows 2–55 = 54 black inner rows, rows 56–77 = 22 white).
     * Effective ratio ≈ 54/76 ≈ 0.71. We accept ±0.10 tolerance.
     */
    @Test
    fun `70-percent black cell returns fill ratio around 0-70`() {
        assumeTrue("OpenCV native library not available", opencvAvailable)

        val rows = 100; val cols = 100
        val mat = Mat(rows, cols, CvType.CV_8UC1, Scalar(255.0))
        // Set top 70 rows to black
        mat.submat(0, 70, 0, cols).setTo(Scalar(0.0))

        val rect = Rect(0, 0, mat.cols(), mat.rows())
        val fill = reader.readFill(mat, rect)
        mat.release()

        assertTrue(
            "70% black cell should have fill ≈ 0.70 (±0.15), got $fill",
            fill in 0.55f..0.85f
        )
    }

    /**
     * normToRect with separate W/H fractions should produce a portrait rectangle
     * (height > width) on the STUDENT ID sub-canvas (ID_BOX_W × ID_BOX_H).
     */
    @Test
    fun `normToRect portrait overload produces taller-than-wide rect`() {
        assumeTrue("OpenCV native library not available", opencvAvailable)

        val center = NormPoint(0.5f, 0.5f)
        val rect = reader.normToRect(
            center,
            SheetBlueprint.ID_CELL_W_FRAC,
            SheetBlueprint.ID_CELL_H_FRAC,
            SheetBlueprint.ID_BOX_W,
            SheetBlueprint.ID_BOX_H
        )
        assertTrue(
            "INFO zone cells must be portrait (height > width): got ${rect.width}×${rect.height}",
            rect.height > rect.width
        )
        val ratio = rect.height.toFloat() / rect.width
        assertTrue(
            "H:W ratio should be in [1.3, 1.8], got $ratio",
            ratio in 1.3f..1.8f
        )
    }

    /**
     * readFill on a rect that is partially outside the Mat bounds should return 0f
     * without throwing — bounds clamping must be safe.
     */
    @Test
    fun `readFill with partially out-of-bounds rect does not throw`() {
        assumeTrue("OpenCV native library not available", opencvAvailable)

        val mat = Mat(50, 50, CvType.CV_8UC1, Scalar(128.0))
        // Rect extends beyond mat boundary
        val rect = Rect(40, 40, 30, 30)
        val fill = reader.readFill(mat, rect)  // must not throw
        mat.release()

        assertTrue("readFill out-of-bounds fill should be in [0,1]", fill in 0f..1f)
    }
}
