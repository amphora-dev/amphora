package app.amphora.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AppUpdateSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSystemInstaller: () -> Unit,
) {
    SettingSection(
        title = "App update",
        subtitle = "Install the latest CI debug APK (SHA-pinned via content_manifest)",
    ) {
        Text(
            "Installed: ${state.installedVersionName} (${state.installedVersionCode})",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            state.availableUpdate?.let {
                "Available: ${it.versionName} (${it.versionCode}) · ${it.channel}"
            } ?: "Available: check for the latest published build",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.availableUpdate?.notes?.let { notes ->
            Text(
                notes,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.updateMessage?.let { message ->
            Text(
                message,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.updateBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            TextButton(
                onClick = viewModel::checkForUpdate,
                enabled = !state.updateBusy,
            ) {
                Text("Check")
            }
            if (state.availableUpdate != null && state.pendingApk == null) {
                TextButton(
                    onClick = viewModel::installUpdate,
                    enabled = !state.updateBusy,
                ) {
                    Text("Install update")
                }
            }
            if (state.pendingApk != null) {
                TextButton(
                    onClick = onOpenSystemInstaller,
                    enabled = !state.updateBusy,
                ) {
                    Text("Open system installer")
                }
            }
        }
    }
}
