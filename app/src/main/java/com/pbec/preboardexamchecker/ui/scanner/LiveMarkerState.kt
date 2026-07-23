package com.pbec.preboardexamchecker.ui.scanner

import android.graphics.PointF

// Each phase uses four of the six corners: Phase 1 (info) TL/TM/BL/BM,
// Phase 2 (answer) TM/TR/BM/BR. Labels are never shown; they exist to track
// each detected corner independently across frames.
enum class MarkerRole { TL, TM, TR, BL, BM, BR }

// SEARCHING/HOLD_STILL come from the ImageAnalysis analyzer; READING/ERROR come
// from ScannerViewModel and override whatever the analyzer last emitted.
enum class OverlayPhase { SEARCHING, HOLD_STILL, READING, ERROR }

data class LiveMarkerState(
    val detectedMarkers: Map<MarkerRole, PointF> = emptyMap(),
    val overlayPhase: OverlayPhase = OverlayPhase.SEARCHING,
    val stabilityProgress: Float = 0f
)

// Maps detector slots [TL, TR, BL, BR] to phase-specific roles. The visual
// corner per slot is fixed; only the label differs between phases.
fun phaseRoles(phase: Int): Array<MarkerRole> =
    if (phase == 1)
        arrayOf(MarkerRole.TL, MarkerRole.TM, MarkerRole.BL, MarkerRole.BM)
    else
        arrayOf(MarkerRole.TM, MarkerRole.TR, MarkerRole.BM, MarkerRole.BR)
