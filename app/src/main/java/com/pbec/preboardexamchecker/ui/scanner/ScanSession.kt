package com.pbec.preboardexamchecker.ui.scanner

import com.pbec.preboardexamchecker.ui.scanner.processor.ScanContext
import com.pbec.preboardexamchecker.ui.scanner.processor.ScannedInfo

data class ScanSession(
    val context: ScanContext,
    val lastScannedInfo: ScannedInfo? = null
)
