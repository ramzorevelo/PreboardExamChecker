package com.pbec.preboardexamchecker.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.ui.scanner.AutoAdvanceSpeed
import com.pbec.preboardexamchecker.ui.scanner.ScanMode
import com.pbec.preboardexamchecker.ui.scanner.ScanSettings
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSettingsScreen(navController: NavController) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(ScanSettings.getMode(context)) }
    var saveRaw by remember { mutableStateOf(ScanSettings.isSaveRawImages(context)) }
    var treeUri by remember { mutableStateOf(ScanSettings.getRawImageTreeUri(context)) }
    var autoAdvance by remember { mutableStateOf(ScanSettings.isAutoAdvance(context)) }
    var advanceSpeed by remember { mutableStateOf(ScanSettings.getAutoAdvanceSpeed(context)) }
    var boostBrightness by remember { mutableStateOf(ScanSettings.isBoostBrightness(context)) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist access across reboots so future scans can keep writing here.
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ScanSettings.setRawImageTreeUri(context, uri.toString())
            treeUri = uri.toString()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrandTopAppBar(
                title = "Scanning",
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
            SettingsSectionLabel("Scan mode")
            SettingsSectionCaption("Default capture flow for new scan sessions.")
            SettingRadioOption(
                title = "1-Capture (recommended)",
                subtitle = "One photo per paper: detects all 6 markers and reads info + answers at once. Best on modern phones.",
                selected = mode == ScanMode.SINGLE,
                onSelect = { mode = ScanMode.SINGLE; ScanSettings.setMode(context, ScanMode.SINGLE) }
            )
            Spacer(Modifier.height(8.dp))
            SettingRadioOption(
                title = "2-Phase",
                subtitle = "Two photos per paper: info panel, then answer grid. Easier for low-resolution / older cameras.",
                selected = mode == ScanMode.TWO_PHASE,
                onSelect = { mode = ScanMode.TWO_PHASE; ScanSettings.setMode(context, ScanMode.TWO_PHASE) }
            )

            Spacer(Modifier.height(24.dp))
            SettingsSectionLabel("Capture behavior")

            SettingToggleRow(
                title = "Save raw scan images",
                subtitle = "Keep a copy of each successfully-recorded sheet in a folder you choose.",
                checked = saveRaw,
                onCheckedChange = { saveRaw = it; ScanSettings.setSaveRawImages(context, it) }
            )
            if (saveRaw) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Folder", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                folderLabel(treeUri),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (treeUri == null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = { folderPicker.launch(null) }) {
                            Text(if (treeUri == null) "Choose" else "Change")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingToggleRow(
                title = "Auto-advance after scan",
                subtitle = "After a clean result, return to capture automatically. Duplicate warnings still pause for your decision.",
                checked = autoAdvance,
                onCheckedChange = { autoAdvance = it; ScanSettings.setAutoAdvance(context, it) }
            )
            if (autoAdvance) {
                Spacer(Modifier.height(8.dp))
                AutoAdvanceSpeed.values().forEachIndexed { index, speed ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    SettingRadioOption(
                        title = speed.label,
                        subtitle = speed.caption,
                        selected = advanceSpeed == speed,
                        onSelect = { advanceSpeed = speed; ScanSettings.setAutoAdvanceSpeed(context, speed) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingToggleRow(
                title = "Boost brightness while scanning",
                subtitle = "Force the screen to full brightness during a scan session so the capture view stays easy to see.",
                checked = boostBrightness,
                onCheckedChange = { boostBrightness = it; ScanSettings.setBoostBrightness(context, it) }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

// Decodes the SAF tree document id into a readable trailing folder name, e.g. "…:Pictures/Scans".
private fun folderLabel(treeUriString: String?): String {
    if (treeUriString == null) return "No folder selected"
    return try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(
            android.net.Uri.parse(treeUriString)
        )
        docId.substringAfter(':', docId).ifBlank { docId }
    } catch (e: Exception) {
        "Selected folder"
    }
}

private val AutoAdvanceSpeed.label: String
    get() = when (this) {
        AutoAdvanceSpeed.QUICK -> "Quick"
        AutoAdvanceSpeed.NORMAL -> "Normal"
        AutoAdvanceSpeed.SLOW -> "Slow"
    }

private val AutoAdvanceSpeed.caption: String
    get() = when (this) {
        AutoAdvanceSpeed.QUICK -> "Advance almost immediately (0.8s)."
        AutoAdvanceSpeed.NORMAL -> "Brief pause to glance at the score (1.5s)."
        AutoAdvanceSpeed.SLOW -> "Longer pause to read the result (3s)."
    }

@Composable
internal fun SettingsSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun SettingsSectionCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
}
