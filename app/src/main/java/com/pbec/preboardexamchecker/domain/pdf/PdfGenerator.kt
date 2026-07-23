package com.pbec.preboardexamchecker.domain.pdf

import android.content.Context
import java.io.File

interface PdfGenerator<T> {
    suspend fun generate(context: Context, data: T, label: String = ""): File
}
