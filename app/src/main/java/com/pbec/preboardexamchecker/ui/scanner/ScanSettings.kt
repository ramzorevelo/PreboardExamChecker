package com.pbec.preboardexamchecker.ui.scanner

import android.content.Context

enum class ScanMode {
    // One capture: all 6 markers, both zones at once.
    SINGLE,
    // Two captures: Phase 1 (info) then Phase 2 (answers), 4 markers each.
    TWO_PHASE
}

// How long the result card lingers before auto-advance returns to capture.
enum class AutoAdvanceSpeed(val delayMs: Long) {
    QUICK(800),
    NORMAL(1500),
    SLOW(3000)
}

// Factory default is SINGLE.
object ScanSettings {
    private const val PREFS = "app_prefs"
    private const val KEY_SCAN_MODE = "scan_mode"
    private const val VALUE_SINGLE = "single"
    private const val VALUE_TWO_PHASE = "two_phase"

    private const val KEY_SAVE_RAW = "scan_save_raw"
    private const val KEY_RAW_TREE_URI = "scan_raw_tree_uri"
    private const val KEY_AUTO_ADVANCE = "scan_auto_advance"
    private const val KEY_AUTO_ADVANCE_SPEED = "scan_auto_advance_speed"
    private const val KEY_BOOST_BRIGHTNESS = "scan_boost_brightness"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(context: Context): ScanMode {
        val v = prefs(context).getString(KEY_SCAN_MODE, VALUE_SINGLE)
        return if (v == VALUE_TWO_PHASE) ScanMode.TWO_PHASE else ScanMode.SINGLE
    }

    fun setMode(context: Context, mode: ScanMode) {
        prefs(context).edit()
            .putString(KEY_SCAN_MODE, if (mode == ScanMode.TWO_PHASE) VALUE_TWO_PHASE else VALUE_SINGLE)
            .apply()
    }

    // Save the raw frame of each successfully-recorded scan to a user-picked folder.
    fun isSaveRawImages(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVE_RAW, false)

    fun setSaveRawImages(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_RAW, enabled).apply()
    }

    // SAF tree URI (as String) where raw scans are written; null until the user picks one.
    fun getRawImageTreeUri(context: Context): String? =
        prefs(context).getString(KEY_RAW_TREE_URI, null)

    fun setRawImageTreeUri(context: Context, uri: String?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_RAW_TREE_URI) else putString(KEY_RAW_TREE_URI, uri)
        }.apply()
    }

    // After a clean (non-duplicate) result, auto-return to capture without tapping Next.
    fun isAutoAdvance(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ADVANCE, false)

    fun setAutoAdvance(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ADVANCE, enabled).apply()
    }

    // How long the result lingers before auto-advance fires (default NORMAL).
    fun getAutoAdvanceSpeed(context: Context): AutoAdvanceSpeed =
        when (prefs(context).getString(KEY_AUTO_ADVANCE_SPEED, null)) {
            AutoAdvanceSpeed.QUICK.name -> AutoAdvanceSpeed.QUICK
            AutoAdvanceSpeed.SLOW.name -> AutoAdvanceSpeed.SLOW
            else -> AutoAdvanceSpeed.NORMAL
        }

    fun setAutoAdvanceSpeed(context: Context, speed: AutoAdvanceSpeed) {
        prefs(context).edit().putString(KEY_AUTO_ADVANCE_SPEED, speed.name).apply()
    }

    // Force full screen brightness for the duration of a scan session.
    fun isBoostBrightness(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOST_BRIGHTNESS, false)

    fun setBoostBrightness(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOST_BRIGHTNESS, enabled).apply()
    }
}
