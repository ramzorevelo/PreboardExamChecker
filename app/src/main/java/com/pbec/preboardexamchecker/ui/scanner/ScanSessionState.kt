package com.pbec.preboardexamchecker.ui.scanner

import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.ui.scanner.processor.ScanContext
import com.pbec.preboardexamchecker.ui.scanner.processor.ScannedInfo

sealed class ScanSessionState {
    object Setup : ScanSessionState()
    object Loading : ScanSessionState()
    data class Phase1(val context: ScanContext) : ScanSessionState()
    data class Phase1Review(val info: ScannedInfo) : ScanSessionState()
    object Phase2 : ScanSessionState()
    data class SingleCapture(val context: ScanContext) : ScanSessionState()
    data class SubjectMismatch(
        val detectedSubject: String,
        val context: ScanContext
    ) : ScanSessionState()
    data class ResultDisplay(
        val result: ScanResult,
        val answerKey: String,
        // >0: existing active records for this student+exam; card warns and offers Replace.
        val duplicateCount: Int = 0
    ) : ScanSessionState()
    data class Error(val reason: String, val phase: Int) : ScanSessionState()
}
