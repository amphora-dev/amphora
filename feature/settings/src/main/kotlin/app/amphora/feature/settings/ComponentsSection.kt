package app.amphora.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amphora.core.content.model.ContentComponent

@Composable
internal fun ComponentSection(state: SettingsUiState, onRefresh: () -> Unit) {
    var showAssets by rememberSaveable { mutableStateOf(false) }
    SettingSection(
        title = "Components",
        subtitle = "Installed runtime and published versions",
    ) {
        DependencyChain()
        HorizontalDivider()
        if (state.refreshing && state.components.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.components.forEach { component ->
            ComponentRow(component)
        }
        if (state.imagefsResidue) {
            NoticeRow("Old imagefs residue found", "It is not used, but cleanup is recommended.")
        }
        HorizontalDivider()
        val assetSummary =
            buildString {
                append("${state.runtimeAssets.size} supporting files")
                if (state.unhealthyAssets > 0) append(" · ${state.unhealthyAssets} need attention")
                if (state.localAssets > 0) append(" · ${state.localAssets} local")
            }
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showAssets = !showAssets }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Runtime assets", style = MaterialTheme.typography.titleSmall)
                Text(
                    assetSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (showAssets) "Hide" else "Details", color = MaterialTheme.colorScheme.primary)
        }
        if (showAssets) {
            state.runtimeAssets.forEach { asset ->
                RuntimeAssetRow(asset)
            }
        }
        TextButton(onClick = onRefresh, enabled = !state.refreshing) {
            Text(if (state.refreshing) "Refreshing…" else "Refresh component status")
        }
    }
}

@Composable
private fun DependencyChain() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Launch dependency chain", style = MaterialTheme.typography.labelLarge)
            Text(
                "ImageFS → Proton + Box64 → DXVK / VKD3D → game",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "A missing earlier item prevents everything after it from starting.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun ComponentRow(component: ComponentStatus) {
    val tone =
        when (component.health) {
            ComponentHealth.READY -> HealthTone.GOOD
            ComponentHealth.MISSING, ComponentHealth.UPDATE -> HealthTone.BAD
            ComponentHealth.NO_PIN -> HealthTone.NEUTRAL
        }
    val title =
        when (component.component) {
            ContentComponent.ROOTFS -> "ImageFS"
            ContentComponent.WINE -> "Proton"
            ContentComponent.BOX64 -> "Box64"
            ContentComponent.DXVK -> "DXVK"
            ContentComponent.VKD3D -> "VKD3D"
        }
    val role =
        when (component.component) {
            ContentComponent.ROOTFS -> "Linux libraries and filesystem"
            ContentComponent.WINE -> "Windows compatibility layer"
            ContentComponent.BOX64 -> "Runs x86_64 Wine on ARM"
            ContentComponent.DXVK -> "Direct3D 8–11 to Vulkan"
            ContentComponent.VKD3D -> "Direct3D 12 to Vulkan"
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(tone)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    component.health.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tone.color(),
                )
            }
            Text(
                role,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Installed: ${component.installed ?: "Not installed"}  ·  " +
                    "Published: ${component.pinned ?: "No pin"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuntimeAssetRow(asset: RuntimeAssetHealth) {
    val tone =
        when (asset.health) {
            AssetHealth.READY -> HealthTone.GOOD
            AssetHealth.LOCAL -> HealthTone.LOCAL
            AssetHealth.MISSING, AssetHealth.MISMATCH, AssetHealth.UNVERIFIED -> HealthTone.BAD
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone, 7)
        Text(
            asset.path,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Text(asset.health.label, style = MaterialTheme.typography.labelSmall, color = tone.color())
    }
}

@Composable
private fun NoticeRow(title: String, detail: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val ComponentHealth.label: String
    get() =
        when (this) {
            ComponentHealth.READY -> "Ready"
            ComponentHealth.MISSING -> "Missing"
            ComponentHealth.UPDATE -> "Update needed"
            ComponentHealth.NO_PIN -> "No published version"
        }

private val AssetHealth.label: String
    get() =
        when (this) {
            AssetHealth.READY -> "Ready"
            AssetHealth.MISSING -> "Missing"
            AssetHealth.MISMATCH -> "Mismatch"
            AssetHealth.UNVERIFIED -> "Unverified"
            AssetHealth.LOCAL -> "Local override"
        }
