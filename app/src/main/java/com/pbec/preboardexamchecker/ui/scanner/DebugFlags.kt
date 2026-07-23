package com.pbec.preboardexamchecker.ui.scanner

import com.pbec.preboardexamchecker.BuildConfig

object DebugFlags {
    // Off in release: even async saving costs disk + memory.
    @Volatile
    var SAVE_SCAN_DEBUG: Boolean = BuildConfig.DEBUG
}
