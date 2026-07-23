package com.pbec.preboardexamchecker.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import com.pbec.preboardexamchecker.ui.theme.FontScale
import com.pbec.preboardexamchecker.ui.theme.FontScaleSettings
import com.pbec.preboardexamchecker.ui.theme.ThemeMode
import com.pbec.preboardexamchecker.ui.theme.ThemeSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(navController: NavController) {
    val context = LocalContext.current
    val themeMode by ThemeSettings.modeState(context)
    val fontScale by FontScaleSettings.scaleState(context)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrandTopAppBar(
                title = "Appearance",
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SettingsSectionLabel("Theme")
            SettingRadioOption(
                title = "Light",
                subtitle = "Always use the light theme.",
                selected = themeMode == ThemeMode.LIGHT,
                onSelect = { ThemeSettings.setMode(context, ThemeMode.LIGHT) }
            )
            Spacer(Modifier.height(8.dp))
            SettingRadioOption(
                title = "Dark",
                subtitle = "Always use the dark theme.",
                selected = themeMode == ThemeMode.DARK,
                onSelect = { ThemeSettings.setMode(context, ThemeMode.DARK) }
            )
            Spacer(Modifier.height(8.dp))
            SettingRadioOption(
                title = "System",
                subtitle = "Follow the device's light/dark setting.",
                selected = themeMode == ThemeMode.SYSTEM,
                onSelect = { ThemeSettings.setMode(context, ThemeMode.SYSTEM) }
            )

            Spacer(Modifier.height(24.dp))
            SettingsSectionLabel("Font size")
            SettingsSectionCaption("Scales text across the app for easier reading.")
            SettingRadioOption(
                title = "Default",
                subtitle = "Standard text size.",
                selected = fontScale == FontScale.DEFAULT,
                onSelect = { FontScaleSettings.setScale(context, FontScale.DEFAULT) }
            )
            Spacer(Modifier.height(8.dp))
            SettingRadioOption(
                title = "Large",
                subtitle = "Slightly larger text.",
                selected = fontScale == FontScale.LARGE,
                onSelect = { FontScaleSettings.setScale(context, FontScale.LARGE) }
            )
            Spacer(Modifier.height(8.dp))
            SettingRadioOption(
                title = "Larger",
                subtitle = "Largest text size.",
                selected = fontScale == FontScale.LARGER,
                onSelect = { FontScaleSettings.setScale(context, FontScale.LARGER) }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
