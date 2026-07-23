package com.pbec.preboardexamchecker.ui.theme

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

// Conservative multipliers: large enough to help readability for older instructors,
// small enough that fixed-dp layouts (the answer-result grid, nav bar) still render.
enum class FontScale(val scale: Float) {
    DEFAULT(1.0f),
    LARGE(1.15f),
    LARGER(1.30f)
}

// Factory default is DEFAULT. Backed by a process-wide State so a write from Settings
// recomposes the app's text at the root without an Activity restart (mirrors ThemeSettings).
object FontScaleSettings {
    private const val PREFS = "app_prefs"
    private const val KEY_FONT_SCALE = "font_scale"

    private val state = mutableStateOf<FontScale?>(null)

    fun scaleState(context: Context): State<FontScale> {
        if (state.value == null) state.value = read(context)
        @Suppress("UNCHECKED_CAST")
        return state as State<FontScale>
    }

    fun setScale(context: Context, scale: FontScale) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FONT_SCALE, scale.name).apply()
        state.value = scale
    }

    private fun read(context: Context): FontScale =
        when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FONT_SCALE, null)) {
            FontScale.LARGE.name -> FontScale.LARGE
            FontScale.LARGER.name -> FontScale.LARGER
            else -> FontScale.DEFAULT
        }
}
