package com.pbec.preboardexamchecker.ui.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pbec.preboardexamchecker.ui.clusters.ClusterViewModel
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar

/**
 * A scan session grades against a whole cluster, not a single subject: the scanned subject bubble
 * routes each paper to its exam (see ScannerViewModel.loadClusterSession). The instructor only
 * picks the cluster and scan mode here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSetupScreen(
    viewModel: ScannerViewModel,
    clusterViewModel: ClusterViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clusters by clusterViewModel.clusters.collectAsState()
    var selectedClusterId by remember { mutableStateOf<Long?>(null) }
    var scanMode by remember { mutableStateOf(ScanSettings.getMode(context)) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { BrandTopAppBar(title = "New Scan Session") },
    ) { innerPadding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text("Scan mode", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = scanMode == ScanMode.SINGLE,
                    onClick = { scanMode = ScanMode.SINGLE; ScanSettings.setMode(context, ScanMode.SINGLE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("1-Capture") }
                SegmentedButton(
                    selected = scanMode == ScanMode.TWO_PHASE,
                    onClick = { scanMode = ScanMode.TWO_PHASE; ScanSettings.setMode(context, ScanMode.TWO_PHASE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("2-Phase") }
            }
        }

        if (clusters.isEmpty()) {
            Text("Select Exam Cluster", style = MaterialTheme.typography.labelLarge)
            Text(
                "No clusters yet. Create one under Subjects → Exam Clusters " +
                    "(one exam each for Math, ESAS, and Prof EE).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.weight(1f))
        } else {
            Text("Select Exam Cluster", style = MaterialTheme.typography.labelLarge)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(clusters, key = { it.id }) { cluster ->
                    val isSelected = selectedClusterId == cluster.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedClusterId = cluster.id },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(cluster.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            cluster.schoolYear?.let {
                                Text("SY $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val id = selectedClusterId ?: return@Button
                viewModel.loadClusterSession(id, scanMode)
            },
            enabled = selectedClusterId != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Load Session")
        }
    }
    }
}
