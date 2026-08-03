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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
    onDebugLaunchWine: (() -> Unit)? = null,
    /** Same smoke PE with DXVK HUD + file logs (AIO DX9 black-screen triage). */
    onDebugLaunchWineDiag: (() -> Unit)? = null,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // SAF document picker -- accepts any file (.exe mime types are unreliable).
    val pickExe =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onExePicked(uri)
        }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Amphora") }) },
    ) { padding ->
        // Landscape (and short viewports) overflow the chip/button stack — must scroll.
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VersionBlock(uiState = uiState, onRefresh = viewModel::refreshContentInfo)

            uiState.provisionProgress?.let { progress ->
                ProvisionProgressBlock(progress)
            }

            StorageAccessBlock()

            // --- exe picker ---------------------------------------------------
            Button(
                onClick = { pickExe.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    uiState.stagedExePath?.let { "Exe: ${File(it).name}" }
                        ?: if (uiState.staging) "Staging…" else "Pick a Windows .exe",
                )
            }

            // --- resolution selector -----------------------------------------
            ResolutionSelector(
                selected = uiState.resolution,
                onSelect = viewModel::selectResolution,
            )

            GraphicsDriverSelector(
                selected = uiState.graphicsDriver,
                enabled = !uiState.driverBusy && !uiState.staging,
                onSelect = viewModel::selectGraphicsDriver,
            )
            if (uiState.driverBusy) {
                Text("Installing Turnip…", style = MaterialTheme.typography.bodySmall)
            }

            DirectDrawWrapperSelector(
                selected = uiState.directDrawWrapper,
                enabled = !uiState.staging,
                onSelect = viewModel::selectDirectDrawWrapper,
            )

            // --- launch -------------------------------------------------------
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
            ) { Text("Launch") }

            uiState.stageError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }

            if (onDebugLaunchWine != null) {
                Button(
                    onClick = onDebugLaunchWine,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Debug: Wine smoke test") }
            }

            if (onDebugLaunchWineDiag != null) {
                Button(
                    onClick = onDebugLaunchWineDiag,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Debug: Wine + DXVK diag") }
            }
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("App ${uiState.appVersion}", style = MaterialTheme.typography.titleMedium)
        val catalogLine =
            when (val status = uiState.catalogStatus) {
                is ContentCatalog.Status.Idle -> "Manifest: not loaded"
                is ContentCatalog.Status.Loading -> "Manifest: loading…"
                is ContentCatalog.Status.Ready -> "Manifest: remote OK (${status.manifest.all().size} components)"
                is ContentCatalog.Status.Failed -> "Manifest: ${status.error}"
            }
        Text(
            catalogLine,
            style = MaterialTheme.typography.bodySmall,
            color =
            if (uiState.catalogStatus is ContentCatalog.Status.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (uiState.components.isNotEmpty()) {
            Text("Components", style = MaterialTheme.typography.labelLarge)
            uiState.components.forEach { row ->
                val installed = row.installed ?: "—"
                val pinned = row.pinned ?: "…"
                // A local inject deliberately diverges from the remote pin, so it
                // is a warning (not an error) and must not read as "stale".
                val suffix =
                    when {
                        row.localOverride -> " (local)"
                        row.pinned == null -> " (no pin)"
                        row.installed == null -> " (missing)"
                        !row.matchesPin -> " (stale)"
                        else -> ""
                    }
                Text(
                    "${row.label}: installed $installed · pin $pinned$suffix",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                    when {
                        row.localOverride -> LOCAL_BUILD_COLOR
                        suffix.isNotEmpty() -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (row.localOverride) FontWeight.Bold else null,
                )
            }
        }
        if (uiState.runtimeAssets.isNotEmpty()) {
            RuntimeAssetBlock(uiState.runtimeAssets)
        }
        if (uiState.imagefsResidue) {
            Text(
                "residue: imagefs.olddead (unusable husk)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (uiState.contentBusy) {
            Text("Refreshing content pins…", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRefresh, enabled = !uiState.contentBusy) {
            Text("Refresh manifest")
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
            "DxWrapper covers DirectDraw/D3D1–7. cnc-ddraw targets software-rendered 2D games.",
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
