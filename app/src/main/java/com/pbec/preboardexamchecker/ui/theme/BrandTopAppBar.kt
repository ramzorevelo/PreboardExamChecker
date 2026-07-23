package com.pbec.preboardexamchecker.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Single source of truth for screen headers: solid brand bar, white title + icons.
// `selection` switches to magenta for multi-select bars so they read as a distinct mode.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    selection: Boolean = false,
) {
    val container = if (selection) brand_header_selection else brand_header
    TopAppBar(
        title = { Text(title) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = brandColors(container),
        // Hosting Scaffold already applies the status-bar inset; don't double-pad.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun brandColors(container: Color): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(
        containerColor = container,
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
    )
