package app.amphora.feature.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.amphora.core.engine.GuestStorageAccess
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.COMMON) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? Activity
    val storageActivityResultHandler =
        remember { AtomicReference<(() -> Unit)?>(null) }
    val storageSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            storageActivityResultHandler.get()?.invoke()
        }
    val unknownSourcesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            activity?.let(viewModel::installPendingUpdate)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings")
                        Text(
                            "Changes apply to the next launch",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
                actions = {
                    TextButton(onClick = { showResetConfirmation = true }) {
                        Text("Reset defaults")
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (maxWidth >= 840.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SettingsCategoryPane(
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it },
                        modifier =
                        Modifier
                            .width(240.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    SettingsCategoryContent(
                        category = selectedCategory,
                        state = state,
                        viewModel = viewModel,
                        onOpenStorageSettings = {
                            storageSettingsLauncher.launch(GuestStorageAccess.manageIntent(context))
                        },
                        onRegisterStorageActivityResultHandler = storageActivityResultHandler::set,
                        onOpenSystemInstaller = {
                            if (viewModel.needsInstallPermission()) {
                                unknownSourcesLauncher.launch(viewModel.installPermissionSettingsIntent())
                            } else {
                                activity?.let(viewModel::installPendingUpdate)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    SettingsCategoryTabs(
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it },
                    )
                    SettingsCategoryContent(
                        category = selectedCategory,
                        state = state,
                        viewModel = viewModel,
                        onOpenStorageSettings = {
                            storageSettingsLauncher.launch(GuestStorageAccess.manageIntent(context))
                        },
                        onRegisterStorageActivityResultHandler = storageActivityResultHandler::set,
                        onOpenSystemInstaller = {
                            if (viewModel.needsInstallPermission()) {
                                unknownSourcesLauncher.launch(viewModel.installPermissionSettingsIntent())
                            } else {
                                activity?.let(viewModel::installPendingUpdate)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Restore recommended defaults?") },
            text = {
                Text(
                    "This resets display, graphics, compatibility, Windows components, and " +
                        "advanced runtime settings. Installed programs and runtime files are not removed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetPreferences()
                        showResetConfirmation = false
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private enum class SettingsCategory(val label: String, val description: String) {
    COMMON("Common", "Display, graphics, language, and storage"),
    ADVANCED("Advanced", "Windows components and runtime tuning"),
    SYSTEM("System", "Components, updates, cleanup, and diagnostics"),
}

@Composable
private fun SettingsCategoryPane(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "CATEGORIES",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsCategory.entries.forEach { category ->
            val active = category == selected
            Surface(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(category) },
                color =
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                contentColor =
                if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        category.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                        if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryTabs(selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsCategory.entries.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category.label) },
            )
        }
    }
}

@Composable
private fun SettingsCategoryContent(
    category: SettingsCategory,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenStorageSettings: () -> Unit,
    onRegisterStorageActivityResultHandler: ((() -> Unit)?) -> Unit,
    onOpenSystemInstaller: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(category) {
        scrollState.scrollTo(0)
    }
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            category.label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            category.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (category) {
            SettingsCategory.COMMON ->
                CommonSettings(
                    state = state,
                    viewModel = viewModel,
                    onOpenStorageSettings = onOpenStorageSettings,
                    onRegisterStorageActivityResultHandler = onRegisterStorageActivityResultHandler,
                )
            SettingsCategory.ADVANCED -> {
                WindowsComponentsSection(state = state, viewModel = viewModel)
                AdvancedRuntimeSection(state = state, viewModel = viewModel)
            }
            SettingsCategory.SYSTEM -> {
                SettingsOverview(state)
                StorageUsageSection(
                    state = state,
                    onRefresh = viewModel::refreshStorageUsage,
                    onDeleteUnused = viewModel::deleteUnusedGuestData,
                )
                AppUpdateSection(
                    state = state,
                    viewModel = viewModel,
                    onOpenSystemInstaller = onOpenSystemInstaller,
                )
                SessionCleanupSection(state = state, viewModel = viewModel)
                ComponentSection(state = state, onRefresh = viewModel::refreshComponents)
            }
        }
        state.error?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
