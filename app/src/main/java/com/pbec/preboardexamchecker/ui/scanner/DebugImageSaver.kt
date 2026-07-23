package com.pbec.preboardexamchecker.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object DebugImageSaver {

    // Callers must snapshot synchronously: the source Mat/Bitmap is usually
    // released right after the call returns. Single-thread FIFO keeps save
    // order, so timestamp filenames stay monotonic.
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DebugImageSaver").apply { priority = Thread.MIN_PRIORITY }
    }

    private fun debugDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "scanner_debug")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun timestamp(): String =
        SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())

    fun saveBitmap(context: Context, bitmap: Bitmap, label: String) {
        val copy = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            android.util.Log.e("DebugSaver", "Failed to copy $label", e)
            return
        }
        val dir = debugDir(context)
        val name = "${label}_${timestamp()}.jpg"
        ioExecutor.execute {
            try {
                File(dir, name).outputStream().use { out ->
                    copy.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (e: Exception) {
                android.util.Log.e("DebugSaver", "Failed to save $label", e)
            } finally {
                copy.recycle()
            }
        }
    }

    fun saveMat(context: Context, mat: Mat, label: String) {
        val clone = mat.clone()
        val dir = debugDir(context)
        val name = "${label}_${timestamp()}.png"
        ioExecutor.execute {
            try {
                Imgcodecs.imwrite(File(dir, name).absolutePath, clone)
            } catch (e: Exception) {
                android.util.Log.e("DebugSaver", "Failed to save $label", e)
            } finally {
                clone.release()
            }
        }
    }
}
