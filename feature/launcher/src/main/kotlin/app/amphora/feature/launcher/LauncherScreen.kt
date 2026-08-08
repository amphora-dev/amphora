package app.amphora.feature.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ProvisionProgress
import java.io.File

/**
 * Dev/test inject marker. Deliberately outside the theme palette: it must not
 * read as normal body text (fine) nor as an error (broken) — this is a build
 * that intentionally ignores the published pin.
 */
private val LOCAL_BUILD_COLOR = Color(0xFF00629E)

/**
 * MVP launcher (RFC §3 v0.1 / §6): pick a Windows .exe, choose a render
 * resolution, then launch. Shows app / imagefs versions and live download
 * progress when remote content is being fetched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
    /** Optional test path: stage a deterministic PE fixture and launch Wine. */
    onDebugLaunchWine: ((width: Int, height: Int) -> Unit)? = null,
    /** Same smoke PE with DXVK HUD + file logs (AIO DX9 black-screen triage). */
    onDebugLaunchWineDiag: ((width: Int, height: Int) -> Unit)? = null,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // SAF document picker -- accepts any file (.exe mime types are unreliable).
    val pickExe =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onExePicked(uri)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Amphora")
                        Text(
                            "Windows runtime for Android",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        // Landscape (and short viewports) overflow the chip/button stack — must scroll.
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            VersionBlock(uiState = uiState, onRefresh = viewModel::refreshContentInfo)

            uiState.provisionProgress?.let { progress ->
                ProvisionProgressBlock(progress)
            }

            StorageAccessBlock()

            Text(
                "Launch a program",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        uiState.stagedExePath?.let { File(it).name } ?: "No program selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (uiState.stagedExePath == null) {
                            "Choose a Windows executable. Amphora copies it into private storage " +
                                "before Wine starts."
                        } else {
                            "Ready to run in a ${uiState.resolution.label} virtual desktop."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { pickExe.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (uiState.staging) "Preparing…" else "Choose .exe")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("Active configuration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${uiState.resolution.label} · ${uiState.graphicsDriver.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${uiState.directDrawWrapper.label} for legacy DirectDraw",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text("Review settings and dependencies")
                    }
                }
            }

            Button(
                onClick = {
                    val path = uiState.stagedExePath
                    if (path != null) {
                        onLaunch(path, uiState.resolution.width, uiState.resolution.height)
                    }
                },
                enabled =
                uiState.stagedExePath != null &&
                    !uiState.staging &&
                    !uiState.driverBusy &&
                    uiState.catalogStatus is ContentCatalog.Status.Ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (uiState.catalogStatus is ContentCatalog.Status.Ready) {
                        "Launch"
                    } else {
                        "Waiting for component information…"
                    },
                )
            }

            uiState.stageError?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (onDebugLaunchWine != null) {
                Text("Diagnostics", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = {
                        onDebugLaunchWine(
                            uiState.resolution.width,
                            uiState.resolution.height,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Debug: Wine smoke test") }
            }

            if (onDebugLaunchWineDiag != null) {
                OutlinedButton(
                    onClick = {
                        onDebugLaunchWineDiag(
                            uiState.resolution.width,
                            uiState.resolution.height,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Debug: Wine + DXVK diag") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Storage access for the guest's `D:` / `F:` drives.
 *
 * [com.winlator.cmod.runtime.container.Container.DEFAULT_DRIVES] maps `D:` to
 * Downloads and `F:` to the external storage root, and `createDosdevicesSymlinks`
 * symlinks them into the prefix. Those symlinks resolve without any permission,
 * but Android's FUSE view then hands the app *directories only* — every file
 * entry is filtered out. In-guest that reads as "D: has folders and nothing
 * else", which looks like a Wine bug and is not one.
 *
 * targetSdk 28 keeps legacy external storage, so the runtime READ/WRITE pair is
 * enough to lift the filter; all-files access is the fallback for when the user
 * denies that dialog (Android stops offering it after two refusals). Upstream
 * WinNative asks the same two ways from its setup wizard.
 */
@Composable
private fun StorageAccessBlock() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasExternalStorageAccess(context)) }

    val requestLegacy =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted = hasExternalStorageAccess(context) }
    val openSettings =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { granted = hasExternalStorageAccess(context) }

    // All-files access is toggled in Settings and can also be revoked from
    // outside the app, so re-read it whenever the launcher is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) granted = hasExternalStorageAccess(context)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Storage access not granted — inside Wine, D: (Downloads) and " +
                "F: (internal storage) list folders but no files.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                requestLegacy.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant storage access") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            TextButton(onClick = { openSettings.launch(allFilesAccessIntent(context)) }) {
                Text("Use all-files access instead")
            }
        }
    }
}

private fun hasExternalStorageAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
        return true
    }
    return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
}

@RequiresApi(Build.VERSION_CODES.R)
private fun allFilesAccessIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    "package:${context.packageName}".toUri(),
)

@Composable
private fun VersionBlock(uiState: LauncherUiState, onRefresh: () -> Unit) {
    val unhealthyComponents =
        uiState.components.count {
            it.pinned == null || it.installed == null || !it.matchesPin
        }
    val unhealthyAssets = uiState.runtimeAssets.count { !it.healthy }
    val healthy =
        uiState.catalogStatus is ContentCatalog.Status.Ready &&
            unhealthyComponents == 0 &&
            unhealthyAssets == 0 &&
            !uiState.imagefsResidue
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (healthy) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .background(
                        if (healthy) Color(0xFF2E7D5B) else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (healthy) "Runtime ready" else "Runtime status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (val status = uiState.catalogStatus) {
                        is ContentCatalog.Status.Idle -> "Component information has not loaded"
                        is ContentCatalog.Status.Loading -> "Checking published components…"
                        is ContentCatalog.Status.Failed -> "Could not check components: ${status.error}"
                        is ContentCatalog.Status.Ready ->
                            if (healthy) {
                                "Proton, Box64 and graphics layers are ready"
                            } else {
                                "$unhealthyComponents components and $unhealthyAssets files need attention"
                            }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "App ${uiState.appVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = !uiState.contentBusy) {
                Text(if (uiState.contentBusy) "Checking…" else "Refresh")
            }
        }
    }
}

/**
 * `runtimeAssets[]` — the bulk of the manifest (wincomponents, ddrawrapper,
 * metadata, …). Collapsed by default so the launcher stays usable; the whole
 * screen scrolls, so expanding just makes the page longer.
 */
@Composable
private fun RuntimeAssetBlock(assets: List<RuntimeAssetStatus>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val unhealthy = assets.count { !it.healthy }
    val overrides = assets.count { it.state == RuntimeAssetStatus.State.LOCAL_OVERRIDE }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val summary =
            buildString {
                append("Runtime assets (${assets.size}")
                if (unhealthy > 0) append(", $unhealthy need attention")
                if (overrides > 0) append(", $overrides local")
                append(')')
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show all")
            }
        }
        if (expanded) {
            assets.forEach { asset ->
                val suffix =
                    when (asset.state) {
                        RuntimeAssetStatus.State.OK -> ""
                        RuntimeAssetStatus.State.MISSING -> " (missing)"
                        RuntimeAssetStatus.State.MISMATCH -> " (mismatch)"
                        RuntimeAssetStatus.State.UNVERIFIED -> " (unverified)"
                        RuntimeAssetStatus.State.LOCAL_OVERRIDE -> " (local)"
                    }
                val sha = asset.installedSha?.take(12)?.plus("…") ?: "—"
                val local = asset.state == RuntimeAssetStatus.State.LOCAL_OVERRIDE
                Text(
                    "${asset.assetPath}: $sha$suffix",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                    when {
                        local -> LOCAL_BUILD_COLOR
                        asset.state == RuntimeAssetStatus.State.OK -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.error
                    },
                    fontWeight = if (local) FontWeight.Bold else null,
                )
            }
        }
    }
}

@Composable
private fun ProvisionProgressBlock(progress: ProvisionProgress) {
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

@Composable
private fun ResolutionSelector(selected: Resolution, onSelect: (Resolution) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Resolution", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            Resolution.entries.forEach { res ->
                FilterChip(
                    selected = res == selected,
                    onClick = { onSelect(res) },
                    label = { Text(res.label) },
                )
            }
        }
    }
}

@Composable
private fun GraphicsDriverSelector(
    selected: GraphicsDriverOption,
    enabled: Boolean,
    onSelect: (GraphicsDriverOption) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("GPU driver", style = MaterialTheme.typography.labelLarge)
        Text(
            "Wrapper = system Adreno (default). Turnip = optional Mesa freedreno.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            GraphicsDriverOption.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun DirectDrawWrapperSelector(
    selected: DirectDrawWrapperOption,
    enabled: Boolean,
    onSelect: (DirectDrawWrapperOption) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("DirectDraw compatibility", style = MaterialTheme.typography.labelLarge)
        Text(
            "DxWrapper covers DirectDraw/D3D1–7 through DXVK. d7vk translates D3D3–7 " +
                "directly to Vulkan. cnc-ddraw targets software-rendered 2D games.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            DirectDrawWrapperOption.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
    }
}
