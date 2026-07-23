package com.pbec.preboardexamchecker.ui.theme

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

enum class ThemeMode { LIGHT, DARK, SYSTEM }

// Factory default is SYSTEM. Backed by a process-wide State so a write from Settings
// recomposes the theme at the app root without an Activity restart.
object ThemeSettings {
    private const val PREFS = "app_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    private val state = mutableStateOf<ThemeMode?>(null)

    fun modeState(context: Context): State<ThemeMode> {
        if (state.value == null) state.value = read(context)
        @Suppress("UNCHECKED_CAST")
        return state as State<ThemeMode>
    }

    fun setMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_MODE, mode.name).apply()
        state.value = mode
    }

    private fun read(context: Context): ThemeMode =
        when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME_MODE, null)) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
}
