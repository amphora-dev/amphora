package app.amphora.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.amphora.core.engine.GuestStorageAccess
import app.amphora.core.ui.AmphoraSemantic

@Composable
internal fun StorageUsageSection(
    state: SettingsUiState,
    onRefresh: () -> Unit,
    onDeleteUnused: (List<String>) -> Unit,
) {
    val usage = state.storageUsage
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRecoveryDelete by rememberSaveable { mutableStateOf(false) }
    SettingSection(
        title = "Storage usage",
        subtitle = "What Amphora keeps in app-private storage",
    ) {
        if (usage == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Measuring installed components…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingSection
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatStorageSize(usage.totalBytes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${formatStorageSize(usage.freeBytes)} free on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = !state.storageScanning) {
                Text(if (state.storageScanning) "Measuring…" else "Recalculate")
            }
        }
        if (usage.reclaimableBytes > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "${formatStorageSize(usage.reclaimableBytes)} can be reclaimed from caches, " +
                        "temporary data, and superseded components.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.storageMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider()
        usage.entries.forEach { entry ->
            val removable = entry.children.mapNotNull(StorageEntry::removablePath)
            val recoveryBackups = entry.children.mapNotNull(StorageEntry::recoveryPath)
            StorageUsageRow(
                entry = entry,
                totalBytes = usage.totalBytes,
                action =
                when {
                    removable.isNotEmpty() -> {
                        {
                            TextButton(
                                onClick = { confirmDelete = true },
                                enabled = !state.deletingStorage,
                            ) {
                                Text(
                                    if (state.deletingStorage) "Cleaning…" else "Clean up",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    recoveryBackups.isNotEmpty() -> {
                        {
                            TextButton(
                                onClick = { confirmRecoveryDelete = true },
                                enabled = !state.deletingStorage,
                            ) {
                                Text(
                                    if (state.deletingStorage) "Deleting…" else "Delete backup",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    else -> null
                },
            )
        }
    }

    if (confirmDelete && usage != null) {
        val removable =
            usage.entries
                .flatMap(StorageEntry::children)
                .filter { it.removablePath != null }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Clean up reclaimable data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "This removes superseded component versions and old files from interrupted " +
                            "Amphora operations. Current components, containers, and Wine prefix " +
                            "recovery backups are kept.",
                    )
                    removable.forEach {
                        Text(
                            "${it.label} · ${formatStorageSize(it.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteUnused(removable.mapNotNull(StorageEntry::removablePath))
                    },
                ) {
                    Text("Clean up", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmRecoveryDelete && usage != null) {
        val recoveryBackups =
            usage.entries
                .flatMap(StorageEntry::children)
                .filter { it.recoveryPath != null }
        AlertDialog(
            onDismissRequest = { confirmRecoveryDelete = false },
            title = { Text("Delete Wine prefix recovery backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "This permanently removes the previous Wine prefix copy. You will no longer " +
                            "be able to recover saves or configuration from it. The current working " +
                            "prefix is kept.",
                    )
                    recoveryBackups.forEach {
                        Text(
                            "${it.label} · ${formatStorageSize(it.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRecoveryDelete = false
                        onDeleteUnused(recoveryBackups.mapNotNull(StorageEntry::recoveryPath))
                    },
                ) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRecoveryDelete = false }) { Text("Keep backup") }
            },
        )
    }
}

@Composable
private fun StorageUsageRow(entry: StorageEntry, totalBytes: Long, action: (@Composable () -> Unit)? = null) {
    var expanded by rememberSaveable(entry.label) { mutableStateOf(false) }
    val expandable = entry.children.isNotEmpty()
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                entry.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                formatStorageSize(entry.bytes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                entry.detail,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expandable) {
                Text(
                    if (expanded) "Hide" else "Details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        LinearProgressIndicator(
            progress = {
                if (totalBytes <= 0) 0f else (entry.bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            entry.children.forEach { child ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        child.label,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        formatStorageSize(child.bytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        action?.invoke()
    }
}

@Composable
internal fun StorageSection(
    state: SettingsUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onRegisterActivityResultHandler: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(GuestStorageAccess.isGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val refreshAccess = {
            granted = GuestStorageAccess.isGranted(context)
            if (granted) onRefresh()
        }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshAccess()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onRegisterActivityResultHandler(refreshAccess)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onRegisterActivityResultHandler(null)
        }
    }

    SettingSection(
        title = "Storage",
        subtitle = "Windows D: (Downloads), F: (device storage), and removable drives",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(if (granted) HealthTone.GOOD else HealthTone.BAD)
            Column(Modifier.weight(1f)) {
                Text(
                    if (granted) "Storage access granted" else "Storage access required",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (granted) {
                        "Wine can list files on mapped device and removable storage drives."
                    } else {
                        "Without access, Wine may show folders but hide every file inside them."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Text("Guest drive mappings", style = MaterialTheme.typography.labelLarge)
        if (state.guestDrives.isEmpty()) {
            Text(
                if (state.refreshingGuestDrives) {
                    "Detecting storage volumes…"
                } else {
                    "No storage drives are currently available."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.guestDrives.forEach { drive ->
                GuestDriveMappingRow(
                    letter = drive.letter?.let { "$it:" } ?: "—",
                    label = drive.label,
                    path = drive.path,
                    mapped = drive.letter != null,
                    available = drive.available,
                )
            }
        }
        state.guestDriveMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRefresh,
            enabled = !state.refreshingGuestDrives,
        ) {
            Text(if (state.refreshingGuestDrives) "Refreshing…" else "Refresh drive mappings")
        }
        Text(
            "Mounted SD cards are assigned G: and later letters automatically. Wine uses " +
                "direct filesystem links, so Android file access must be granted first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Android may still hide other apps' Android/data and Android/obb folders. " +
                "Downloads, Documents, media, and other shared files remain available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (granted) "Manage Android file access" else "Allow Android file access")
        }
    }
}

@Composable
private fun GuestDriveMappingRow(letter: String, label: String, path: String, mapped: Boolean, available: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(letter, style = MaterialTheme.typography.titleSmall)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when {
                !mapped -> "Not mapped"
                available -> "Ready"
                else -> "Unavailable"
            },
            style = MaterialTheme.typography.labelSmall,
            color =
            if (mapped && available) {
                AmphoraSemantic.successDim
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
