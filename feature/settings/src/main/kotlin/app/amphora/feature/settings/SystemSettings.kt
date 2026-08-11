package app.amphora.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amphora.core.engine.ShizukuCleanupStatus

@Composable
internal fun SettingsOverview(state: SettingsUiState) {
    val healthy =
        state.manifestReady &&
            state.unhealthyComponents == 0 &&
            state.unhealthyAssets == 0 &&
            !state.imagefsResidue
    Card(
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (healthy) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(if (healthy) HealthTone.GOOD else HealthTone.NEUTRAL)
            Column(Modifier.weight(1f)) {
                Text(
                    if (healthy) "Runtime ready" else "Runtime overview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        state.refreshing -> "Checking installed components…"
                        !state.manifestReady -> "Remote component information is unavailable"
                        state.unhealthyComponents + state.unhealthyAssets > 0 ->
                            "${state.unhealthyComponents} components and " +
                                "${state.unhealthyAssets} files need attention"
                        else -> "All required components match the published versions"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SessionCleanupSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var confirmEmergencyStop by rememberSaveable { mutableStateOf(false) }
    SettingSection(
        title = "Session cleanup",
        subtitle = "Normal teardown is built in; Shizuku is an emergency fallback",
    ) {
        Text(
            "Exit first stops environment components, then waits for Wine/Box64, sends SIGKILL " +
                "when needed, and reaps child processes. No elevated permission is required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when (state.shizukuCleanupStatus) {
                ShizukuCleanupStatus.UNAVAILABLE -> "Shizuku: unavailable or not running"
                ShizukuCleanupStatus.PERMISSION_REQUIRED -> "Shizuku: permission required"
                ShizukuCleanupStatus.READY -> "Shizuku: ready for emergency force-stop"
            },
            style = MaterialTheme.typography.labelMedium,
        )
        if (state.shizukuCleanupStatus != ShizukuCleanupStatus.UNAVAILABLE) {
            TextButton(
                onClick = {
                    if (state.shizukuCleanupStatus == ShizukuCleanupStatus.READY) {
                        confirmEmergencyStop = true
                    } else {
                        viewModel.requestShizukuPermission()
                    }
                },
            ) {
                Text(
                    if (state.shizukuCleanupStatus == ShizukuCleanupStatus.READY) {
                        "Emergency force-stop Amphora"
                    } else {
                        "Grant Shizuku access"
                    },
                )
            }
        }
    }

    if (confirmEmergencyStop) {
        AlertDialog(
            onDismissRequest = { confirmEmergencyStop = false },
            title = { Text("Force-stop Amphora?") },
            text = {
                Text(
                    "This asks Shizuku to stop the entire app and every Wine/Box64 process. " +
                        "Use it only if normal Exit did not finish.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEmergencyStop = false
                        viewModel.emergencyForceStop()
                    },
                ) {
                    Text("Force stop")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmergencyStop = false }) { Text("Cancel") }
            },
        )
    }
}
