package com.pbec.preboardexamchecker.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    /**
     * Saves an OpenCV Mat object as a PNG file to the app's external files directory.
     *
     * @param context The application context.
     * @param mat The Mat object to save.
     * @param filename The name of the file to save (e.g., "my_image.png").
     */
    fun saveMatToPng(context: Context, mat: Mat, filename: String) {
        if (mat.empty()) {
            Log.e("ImageUtils", "Attempted to save an empty Mat: $filename. Skipping save.")
            return
        }

        val tempMat = when (mat.channels()) {
            1 -> Mat().also { Imgproc.cvtColor(mat, it, Imgproc.COLOR_GRAY2BGRA) }
            3 -> Mat().also { Imgproc.cvtColor(mat, it, Imgproc.COLOR_BGR2BGRA) }
            else -> mat
        }

        var bitmap: Bitmap? = null
        try {
            bitmap = createBitmap(tempMat.cols(), tempMat.rows())
            Utils.matToBitmap(tempMat, bitmap)

            val picturesDir = context.getExternalFilesDir(null)
            if (picturesDir == null) {
                Log.e("ImageUtils", "External files directory is null. Cannot save image.")
                return
            }

            val file = File(picturesDir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("ImageUtils", "Saved image to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ImageUtils", "Could not save image $filename: ${e.message}", e)
        } finally {
            bitmap?.recycle()
            if (tempMat !== mat) {
                tempMat.release()
            }
        }
    }
}