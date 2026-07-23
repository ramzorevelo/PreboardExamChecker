package com.pbec.preboardexamchecker.ui.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves the raw camera frame of a successfully-recorded scan to a user-picked SAF folder.
 *
 * Unlike [DebugImageSaver] (internal app storage, for diagnostics), this writes only frames
 * that produced a saved record, into a tree the instructor chose via OpenDocumentTree. Uses
 * [DocumentsContract] directly so no extra dependency is needed. Callers pass already-encoded
 * JPEG bytes, so no full-res bitmaps are ever queued here behind a slow folder write.
 */
object ScanImageStore {

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ScanImageStore").apply { priority = Thread.MIN_PRIORITY }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())

    private fun sanitize(part: String): String =
        part.trim().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Writes the [jpegBytes] to the SAF tree off-thread. */
    fun save(
        context: Context,
        jpegBytes: ByteArray,
        treeUriString: String,
        studentId: String,
        subject: String,
        testSet: String
    ) {
        val appContext = context.applicationContext
        val name = "${sanitize(studentId)}_${sanitize(subject)}_Set${sanitize(testSet)}_${timestamp()}.jpg"
        ioExecutor.execute {
            try {
                val tree = Uri.parse(treeUriString)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                val child = DocumentsContract.createDocument(
                    appContext.contentResolver, parentDocUri, "image/jpeg", name
                ) ?: run {
                    android.util.Log.e("ScanImageStore", "createDocument returned null for $name")
                    return@execute
                }
                appContext.contentResolver.openOutputStream(child)?.use { out ->
                    out.write(jpegBytes)
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanImageStore", "Failed to save $name", e)
            }
        }
    }
}
