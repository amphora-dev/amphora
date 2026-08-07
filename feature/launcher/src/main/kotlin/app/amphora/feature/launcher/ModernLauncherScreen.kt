package app.amphora.feature.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.amphora.core.content.ContentCatalog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernLauncherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenExplorer: (width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pickExe =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onExePicked(uri)
        }
    val runtimeReady =
        state.catalogStatus is ContentCatalog.Status.Ready &&
            !state.staging &&
            !state.driverBusy

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Amphora", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Windows games, made portable",
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
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Open the Windows desktop or launch one of your programs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.provisionProgress?.let { ProvisionProgressBlock(it) }
            StorageAccessBlock()

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 840.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.45f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ExplorerCard(
                                enabled = runtimeReady,
                                busy = state.contentBusy,
                                onOpen = {
                                    onOpenExplorer(
                                        state.resolution.width,
                                        state.resolution.height,
                                    )
                                },
                            )
                            ProgramCard(
                                state = state,
                                runtimeReady = runtimeReady,
                                onChoose = { pickExe.launch(arrayOf("*/*")) },
                                onLaunch = {
                                    state.stagedExePath?.let {
                                        onLaunch(
                                            it,
                                            state.resolution.width,
                                            state.resolution.height,
                                        )
                                    }
                                },
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            VersionBlock(
                                uiState = state,
                                onRefresh = viewModel::refreshContentInfo,
                            )
                            ConfigurationCard(state, onOpenSettings)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ExplorerCard(
                            enabled = runtimeReady,
                            busy = state.contentBusy,
                            onOpen = {
                                onOpenExplorer(
                                    state.resolution.width,
                                    state.resolution.height,
                                )
                            },
                        )
                        ProgramCard(
                            state = state,
                            runtimeReady = runtimeReady,
                            onChoose = { pickExe.launch(arrayOf("*/*")) },
                            onLaunch = {
                                state.stagedExePath?.let {
                                    onLaunch(
                                        it,
                                        state.resolution.width,
                                        state.resolution.height,
                                    )
                                }
                            },
                        )
                        VersionBlock(uiState = state, onRefresh = viewModel::refreshContentInfo)
                        ConfigurationCard(state, onOpenSettings)
                    }
                }
            }

            state.stageError?.let { ErrorNotice(it) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ExplorerCard(enabled: Boolean, busy: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "W",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Wine Explorer",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Browse Windows drives, installers and programs in the full Wine desktop.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpen, enabled = enabled) {
                    Text(
                        when {
                            busy -> "Checking runtime…"
                            enabled -> "Open Explorer"
                            else -> "Runtime unavailable"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramCard(
    state: LauncherUiState,
    runtimeReady: Boolean,
    onChoose: () -> Unit,
    onLaunch: () -> Unit,
) {
    val selectedName = state.stagedExePath?.let { File(it).name }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (selectedName == null) "Add a Windows program" else "Ready to launch",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                selectedName ?: "Choose an .exe from your device to run it with the active profile.",
                style =
                if (selectedName == null) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(
                onClick = onChoose,
                enabled = !state.staging,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.staging) "Preparing…" else "Choose .exe")
            }
            if (selectedName != null) {
                Button(
                    onClick = onLaunch,
                    enabled = runtimeReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Launch $selectedName")
                }
            }
        }
    }
}

@Composable
private fun ConfigurationCard(state: LauncherUiState, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Active profile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${state.resolution.label} · ${state.graphicsDriver.label}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${state.directDrawWrapper.label} for legacy DirectDraw",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text("Customize profile") }
        }
    }
}

@Composable
private fun ErrorNotice(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Something needs attention", fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
