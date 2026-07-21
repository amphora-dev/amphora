package app.amphora.feature.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File

/**
 * MVP launcher (RFC §3 v0.1 / §6): pick a Windows .exe, choose a render
 * resolution, then launch. The picked file is staged app-private (see
 * [LauncherViewModel]) and the resulting path + resolution are forwarded to the
 * game-session route, which builds the [app.amphora.core.engine.model.LaunchSpec].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
    /** Optional test path: stage bundled notepad.exe and launch (wired from app NavHost). */
    onDebugLaunchNotepad: (() -> Unit)? = null,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // SAF document picker -- accepts any file (.exe mime types are unreliable).
    val pickExe = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onExePicked(uri)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Amphora") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("v0.1", style = MaterialTheme.typography.bodyMedium)

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

            // --- launch -------------------------------------------------------
            Button(
                onClick = {
                    val path = uiState.stagedExePath
                    if (path != null) {
                        onLaunch(path, uiState.resolution.width, uiState.resolution.height)
                    }
                },
                enabled = uiState.stagedExePath != null && !uiState.staging,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Launch") }

            uiState.stageError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }

            if (onDebugLaunchNotepad != null) {
                Button(
                    onClick = onDebugLaunchNotepad,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Debug: Notepad") }
            }
        }
    }
}

@Composable
private fun ResolutionSelector(selected: Resolution, onSelect: (Resolution) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Resolution", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
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
