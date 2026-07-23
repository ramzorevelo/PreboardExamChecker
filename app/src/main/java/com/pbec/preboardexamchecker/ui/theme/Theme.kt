package com.pbec.preboardexamchecker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inverseSurface = md_theme_dark_inverseSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint,
    outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
)

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inverseSurface = md_theme_light_inverseSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = md_theme_light_surfaceTint,
    outlineVariant = md_theme_light_outlineVariant,
    scrim = md_theme_light_scrim,
)

// Semantic scan-result colors. Live outside the M3 scheme so they survive theme swaps intact.
@Immutable
data class ExtendedColors(
    val pass: Color, val passContainer: Color, val onPassContainer: Color,
    val fail: Color, val failContainer: Color, val onFailContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val blankContainer: Color, val onBlankContainer: Color,
)

private val LightExtendedColors = ExtendedColors(
    pass = brand_light_pass, passContainer = brand_light_passContainer, onPassContainer = brand_light_onPassContainer,
    fail = brand_light_fail, failContainer = brand_light_failContainer, onFailContainer = brand_light_onFailContainer,
    warning = brand_light_warning, warningContainer = brand_light_warningContainer, onWarningContainer = brand_light_onWarningContainer,
    blankContainer = brand_light_blankContainer, onBlankContainer = brand_light_onBlankContainer,
)

private val DarkExtendedColors = ExtendedColors(
    pass = brand_dark_pass, passContainer = brand_dark_passContainer, onPassContainer = brand_dark_onPassContainer,
    fail = brand_dark_fail, failContainer = brand_dark_failContainer, onFailContainer = brand_dark_onFailContainer,
    warning = brand_dark_warning, warningContainer = brand_dark_warningContainer, onWarningContainer = brand_dark_onWarningContainer,
    blankContainer = brand_dark_blankContainer, onBlankContainer = brand_dark_onBlankContainer,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

@Composable
fun PreBoardExamCheckerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Material You would override the brand palette on Android 12+.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Match the brand header so the status bar reads as one bar; icons stay light on teal.
            window.statusBarColor = brand_header.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    // App-wide text scaling (Appearance → Font size). Multiplies the device fontScale so all
    // .sp text grows; layouts sized in dp are unaffected. Kept conservative in FontScaleSettings.
    val fontScale = FontScaleSettings.scaleState(LocalContext.current).value
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(baseDensity.density, baseDensity.fontScale * fontScale.scale)

    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
