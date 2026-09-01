package app.amphora.feature.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.engine.GuestStorageAccess

/**
 * Re-reads all-files access on resume so a grant (or revoke) from the system
 * settings page is reflected as soon as the launcher comes back.
 */
@Composable
internal fun rememberGuestStorageGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(GuestStorageAccess.isGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = GuestStorageAccess.isGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/**
 * Prompts for the all-files-access grant that backs the Wine D:/F: drives.
 *
 * Shown only while access is missing. Once granted, management lives in
 * Settings — keeping a "Manage" button on the launcher looks like the app is
 * still asking for permission.
 *
 * [com.winlator.cmod.runtime.container.Container.DEFAULT_DRIVES] maps `D:` to
 * Downloads and `F:` to the external storage root, and `createDosdevicesSymlinks`
 * symlinks them into the prefix. Those symlinks resolve without any permission,
 * but Android's FUSE view then hands the app *directories only* — every file
 * entry is filtered out. In-guest that reads as "D: has folders and nothing
 * else", which looks like a Wine bug and is not one.
 *
 * Android 11+ requires all-files access for these real filesystem links. A SAF
 * document-tree grant cannot be used because Wine cannot resolve content URIs.
 */
@Composable
internal fun StorageAccessBlock() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Android files", style = MaterialTheme.typography.titleSmall)
            Text(
                "Access is required before Wine can browse files on D: and F:.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { context.startActivity(GuestStorageAccess.manageIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow Android file access")
            }
        }
    }
}

@Composable
internal fun ProvisionProgressBlock(progress: ProvisionProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            listOfNotNull(progress.stage, progress.detail.takeIf { it.isNotBlank() })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            val total = progress.totalBytes
            if (total != null) {
                Text(
                    "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(total)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
