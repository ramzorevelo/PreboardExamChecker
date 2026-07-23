package com.pbec.preboardexamchecker.ui.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

fun applyExifRotation(jpegBytes: ByteArray, src: Bitmap): Bitmap {
    val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90  -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else                                 -> 0f
    }
    if (degrees == 0f) return src
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
