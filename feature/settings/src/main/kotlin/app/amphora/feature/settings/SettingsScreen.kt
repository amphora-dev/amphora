package app.amphora.feature.settings

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.WineLocaleOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.COMMON) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
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
            SettingsCategory.COMMON -> CommonSettings(state = state, viewModel = viewModel)
            SettingsCategory.ADVANCED -> {
                WindowsComponentsSection(state = state, viewModel = viewModel)
                AdvancedRuntimeSection(state = state, viewModel = viewModel)
            }
            SettingsCategory.SYSTEM -> {
                SettingsOverview(state)
                AppUpdateSection(state = state, viewModel = viewModel)
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

@Composable
private fun CommonSettings(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingSection(
        title = "Display",
        subtitle = "Virtual desktop size",
    ) {
        ChoiceSetting(
            title = "Resolution",
            description =
            "Controls the Wine desktop size. Lower values reduce GPU work; higher values " +
                "provide more space and sharper UI.",
            impact = "Global default · applies on next launch",
            selected = state.resolution,
            defaultValue = DisplayResolution.DEFAULT,
            values = DisplayResolution.entries,
            label = { it.label },
            onSelect = viewModel::selectResolution,
            onReset = { viewModel.selectResolution(DisplayResolution.DEFAULT) },
        )
    }
    SettingSection(
        title = "Graphics",
        subtitle = "Native Vulkan backend used by DXVK, VKD3D and Zink",
    ) {
        ChoiceSetting(
            title = "GPU driver",
            description =
            "System driver uses the device's Adreno Vulkan implementation. Turnip uses " +
                "Mesa Freedreno and is downloaded when first selected.",
            impact = "Global default · Direct3D 9–12 and OpenGL/Zink · next launch",
            selected = state.graphicsDriver,
            defaultValue = GraphicsDriverSetting.WRAPPER,
            values = GraphicsDriverSetting.entries,
            label = { it.label },
            enabled = !state.applyingDriver,
            onSelect = viewModel::selectGraphicsDriver,
            onReset = { viewModel.selectGraphicsDriver(GraphicsDriverSetting.WRAPPER) },
        )
        if (state.applyingDriver) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Installing Turnip and verifying its package…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SettingSection(
        title = "Compatibility",
        subtitle = "Regional and translation behavior for older Windows games",
    ) {
        ChoiceSetting(
            title = "Language for non-Unicode programs",
            description =
            "Selects Wine's ANSI codepage for legacy applications. Automatic follows " +
                "the Android device language.",
            impact = "Global default · legacy ANSI text only · next launch",
            selected = state.wineLocale,
            defaultValue = WineLocaleOption.AUTO,
            values = WineLocaleOption.entries,
            label = { it.label },
            onSelect = viewModel::selectWineLocale,
            onReset = { viewModel.selectWineLocale(WineLocaleOption.AUTO) },
        )
        ChoiceSetting(
            title = "DirectDraw layer",
            description =
            "DxWrapper translates DirectDraw and Direct3D 1–7 to D3D9/DXVK. " +
                "d7vk translates Direct3D 3–7 directly to Vulkan. " +
                "cnc-ddraw is tuned for classic software-rendered 2D games.",
            impact = "Global default · 32-bit DirectDraw titles · next launch",
            selected = state.directDrawWrapper,
            defaultValue = DirectDrawSetting.DXWRAPPER,
            values = DirectDrawSetting.entries,
            label = { it.label },
            onSelect = viewModel::selectDirectDraw,
            onReset = { viewModel.selectDirectDraw(DirectDrawSetting.DXWRAPPER) },
        )
    }
    StorageSection()
}

@Composable
private fun WindowsComponentsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val nativeCount = state.windowsComponents.count { it.value }
    SettingSection(
        title = "Windows components",
        subtitle = "Wine builtin or Microsoft-compatible DLLs",
    ) {
        Text(
            "Matches WinNative's component model. Native can improve game compatibility; " +
                "Builtin keeps Proton's implementation and is often sufficient.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$nativeCount native · ${state.windowsComponents.size - nativeCount} builtin",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Changes update the shared Wine prefix on next launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "Hide" else "Configure", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (expanded) {
            HorizontalDivider()
            WindowsComponentSetting.entries.forEachIndexed { index, component ->
                WindowsComponentChoice(
                    component = component,
                    useNative = state.windowsComponents[component] ?: true,
                    onChange = { useNative ->
                        viewModel.setWindowsComponentNative(component, useNative)
                    },
                )
                if (index != WindowsComponentSetting.entries.lastIndex) HorizontalDivider()
            }
            Text(
                "Compatibility archives are verified during runtime provisioning. Native links " +
                    "them into the prefix; switching back restores Proton's DLLs and overrides.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WindowsComponentChoice(
    component: WindowsComponentSetting,
    useNative: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                component.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!useNative) {
                ModifiedResetAction { onChange(true) }
            }
        }
        Text(
            component.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useNative,
                onClick = { onChange(false) },
                label = { Text("Wine builtin") },
            )
            FilterChip(
                selected = useNative,
                onClick = { onChange(true) },
                label = { Text("Native") },
            )
        }
    }
}

@Composable
private fun AdvancedRuntimeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingSection(
        title = "Advanced runtime",
        subtitle = "Box64, DXVK, Vulkan and Wine overrides",
    ) {
        Text(
            "Automatic defaults are recommended. These values override container defaults " +
                "for every program and take effect on the next launch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${state.box64Mode.label} · ${state.presentMode.label}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "DXVK async ${if (state.dxvkAsync) "on" else "off"} · " +
                            "limit ${state.frameLimit.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "Hide" else "Configure", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (expanded) {
            HorizontalDivider()
            ChoiceSetting(
                title = "Box64 translation profile",
                description =
                "Performance is fastest. Compatibility and Stability progressively disable " +
                    "aggressive dynamic recompilation for difficult games.",
                impact = "Scope: x86_64 Wine and all Windows processes · next launch",
                selected = state.box64Mode,
                defaultValue = Box64Mode.PERFORMANCE,
                values = Box64Mode.entries,
                label = { it.label },
                onSelect = viewModel::selectBox64Mode,
                onReset = { viewModel.selectBox64Mode(Box64Mode.PERFORMANCE) },
            )
            HorizontalDivider()
            ChoiceSetting(
                title = "DXVK asynchronous pipelines",
                description =
                "Can reduce shader stutter with compatible DXVK builds, but may introduce " +
                    "rendering glitches in some games.",
                impact = "Scope: Direct3D through DXVK · next launch",
                selected = state.dxvkAsync,
                defaultValue = false,
                values = listOf(false, true),
                label = { if (it) "Enabled" else "Disabled" },
                onSelect = viewModel::setDxvkAsync,
                onReset = { viewModel.setDxvkAsync(false) },
            )
            ChoiceSetting(
                title = "Frame limit",
                description =
                "Limits DXVK presentation rate. This can reduce heat and power use; it does " +
                    "not affect WineD3D or software renderers.",
                impact = "Environment: DXVK_FRAME_RATE · DXVK only",
                selected = state.frameLimit,
                defaultValue = FrameLimit.OFF,
                values = FrameLimit.entries,
                label = { it.label },
                onSelect = viewModel::selectFrameLimit,
                onReset = { viewModel.selectFrameLimit(FrameLimit.OFF) },
            )
            HorizontalDivider()
            ChoiceSetting(
                title = "Direct3D 12 feature level",
                description =
                "Limits the D3D feature level VKD3D reports. Automatic is recommended; " +
                    "forcing a higher level cannot add missing GPU driver features.",
                impact = "Environment: VKD3D_FEATURE_LEVEL · Direct3D 12 only",
                selected = state.vkd3dFeatureLevel,
                defaultValue = Vkd3dFeatureLevel.AUTO,
                values = Vkd3dFeatureLevel.entries,
                label = { it.label },
                onSelect = viewModel::selectVkd3dFeatureLevel,
                onReset = { viewModel.selectVkd3dFeatureLevel(Vkd3dFeatureLevel.AUTO) },
            )
            ChoiceSetting(
                title = "Direct3D 12 shader model",
                description =
                "Controls the maximum shader model exposed by VKD3D-Proton. Values unsupported " +
                    "by the driver may prevent a game from starting.",
                impact = "Environment: VKD3D_SHADER_MODEL · Direct3D 12 only",
                selected = state.vkd3dShaderModel,
                defaultValue = Vkd3dShaderModel.AUTO,
                values = Vkd3dShaderModel.entries,
                label = { it.label },
                onSelect = viewModel::selectVkd3dShaderModel,
                onReset = { viewModel.selectVkd3dShaderModel(Vkd3dShaderModel.AUTO) },
            )
            ChoiceSetting(
                title = "DirectX Raytracing",
                description =
                "Automatic lets VKD3D detect safe DXR support. Force can bypass its safety " +
                    "checks; DXR 1.2 is experimental and requires opacity micromap support.",
                impact = "Environment: VKD3D_CONFIG · Direct3D 12 only",
                selected = state.vkd3dDxr,
                defaultValue = Vkd3dDxrMode.AUTO,
                values = Vkd3dDxrMode.entries,
                label = { it.label },
                onSelect = viewModel::selectVkd3dDxr,
                onReset = { viewModel.selectVkd3dDxr(Vkd3dDxrMode.AUTO) },
            )
            HorizontalDivider()
            ChoiceSetting(
                title = "Vulkan present mode",
                description =
                "Automatic follows the driver. Mailbox favors low-latency tear-free output; " +
                    "VSync is conservative; Immediate may tear.",
                impact = "Dependency: wrapper → Vulkan WSI · all Vulkan renderers",
                selected = state.presentMode,
                defaultValue = PresentMode.AUTO,
                values = PresentMode.entries,
                label = { it.label },
                onSelect = viewModel::selectPresentMode,
                onReset = { viewModel.selectPresentMode(PresentMode.AUTO) },
            )
            ChoiceSetting(
                title = "BC texture handling",
                description =
                "Controls fallback emulation when the selected GPU driver lacks BC texture " +
                    "support. Full emulation improves compatibility at a performance cost.",
                impact = "Scope: Vulkan wrapper texture formats · next launch",
                selected = state.bcnMode,
                defaultValue = BcnMode.DEFAULT,
                values = BcnMode.entries,
                label = { it.label },
                onSelect = viewModel::selectBcnMode,
                onReset = { viewModel.selectBcnMode(BcnMode.DEFAULT) },
            )
            HorizontalDivider()
            ChoiceSetting(
                title = "Host performance overlay",
                description =
                "Shows X-present FPS, app CPU, GPU load, RAM and temperature above the game. " +
                    "It works with DXVK, VKD3D/DX12, OpenGL/Zink and software rendering.",
                impact = "Android overlay · all graphics APIs · next launch",
                selected = state.hostPerformanceHud,
                defaultValue = false,
                values = listOf(false, true),
                label = { if (it) "Visible" else "Hidden" },
                onSelect = viewModel::setHostPerformanceHud,
                onReset = { viewModel.setHostPerformanceHud(false) },
            )
            ChoiceSetting(
                title = "DXVK in-game HUD",
                description =
                "Shows FPS, API, GPU and memory information over Direct3D games. " +
                    "This is display-only and does not enable verbose log files.",
                impact = "Environment: DXVK_HUD · Direct3D 8–11 only",
                selected = state.dxvkHud,
                defaultValue = false,
                values = listOf(false, true),
                label = { if (it) "Visible" else "Hidden" },
                onSelect = viewModel::setDxvkHud,
                onReset = { viewModel.setDxvkHud(false) },
            )
            ChoiceSetting(
                title = "Mesa shader cache",
                description =
                "Reuses compiled shaders to reduce stutter on later launches. Disable only " +
                    "when investigating corrupt-cache rendering problems.",
                impact = "Scope: Turnip and Zink · stored in private app storage",
                selected = state.shaderCache,
                defaultValue = true,
                values = listOf(true, false),
                label = { if (it) "Enabled" else "Disabled" },
                onSelect = viewModel::setShaderCache,
                onReset = { viewModel.setShaderCache(true) },
            )
            if (state.shaderCache) {
                ChoiceSetting(
                    title = "Shader cache limit",
                    description = "Maximum disk space Mesa may use for cached shaders.",
                    impact = "Environment: MESA_SHADER_CACHE_MAX_SIZE",
                    selected = state.shaderCacheSize,
                    defaultValue = ShaderCacheSize.MB512,
                    values = ShaderCacheSize.entries,
                    label = { it.label },
                    onSelect = viewModel::selectShaderCacheSize,
                    onReset = { viewModel.selectShaderCacheSize(ShaderCacheSize.MB512) },
                )
            }
            TextButton(
                onClick = viewModel::clearShaderCache,
                enabled = !state.clearingShaderCache,
            ) {
                Text(if (state.clearingShaderCache) "Clearing cache…" else "Clear shader caches")
            }
            state.cacheActionMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider()
            ChoiceSetting(
                title = "Wine logging",
                description =
                "Enable only while troubleshooting. Warning logs can be large and may reduce " +
                    "performance.",
                impact = "Environment: WINEDEBUG · written to the session log",
                selected = state.wineLog,
                defaultValue = WineLogMode.OFF,
                values = WineLogMode.entries,
                label = { it.label },
                onSelect = viewModel::selectWineLog,
                onReset = { viewModel.selectWineLog(WineLogMode.OFF) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Custom environment",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.customEnv.isNotBlank()) {
                    ModifiedResetAction { viewModel.setCustomEnv("") }
                }
            }
            Text(
                "One KEY=VALUE per line. Engine-owned paths, sockets, loader variables and " +
                    "the Vulkan ICD cannot be overridden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.customEnv,
                onValueChange = viewModel::setCustomEnv,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Environment overrides") },
                placeholder = { Text("MESA_SHADER_CACHE_MAX_SIZE=2G") },
                supportingText = {
                    if (state.rejectedEnvNames.isEmpty()) {
                        Text("Blank lines and lines beginning with # are ignored.")
                    } else {
                        Text(
                            "Ignored protected or invalid names: " +
                                state.rejectedEnvNames.distinct().joinToString(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsOverview(state: SettingsUiState) {
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
private fun SettingSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun <T> ChoiceSetting(
    title: String,
    description: String,
    impact: String,
    selected: T,
    defaultValue: T,
    values: List<T>,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (selected != defaultValue) ModifiedResetAction(onReset)
    }
    Text(
        description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label(value)) },
            )
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            impact,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModifiedResetAction(onReset: () -> Unit) {
    TextButton(
        onClick = onReset,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text("Modified · Reset", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ComponentSection(state: SettingsUiState, onRefresh: () -> Unit) {
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
private fun SessionCleanupSection(state: SettingsUiState, viewModel: SettingsViewModel) {
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
        when (state.shizukuCleanupStatus) {
            ShizukuCleanupStatus.UNAVAILABLE -> Unit
            ShizukuCleanupStatus.PERMISSION_REQUIRED ->
                TextButton(onClick = viewModel::requestShizukuPermission) {
                    Text("Grant Shizuku access")
                }
            ShizukuCleanupStatus.READY ->
                TextButton(onClick = { confirmEmergencyStop = true }) {
                    Text("Emergency force-stop Amphora")
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

@Composable
private fun StatusDot(tone: HealthTone, size: Int = 10) {
    Box(
        Modifier
            .padding(top = 4.dp)
            .size(size.dp)
            .background(tone.color(), CircleShape),
    )
}

private enum class HealthTone {
    GOOD,
    BAD,
    NEUTRAL,
    LOCAL,
}

@Composable
private fun HealthTone.color(): Color = when (this) {
    HealthTone.GOOD -> Color(0xFF2E7D5B)
    HealthTone.BAD -> MaterialTheme.colorScheme.error
    HealthTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    HealthTone.LOCAL -> Color(0xFF3976A8)
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

@Composable
private fun AppUpdateSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val unknownSourcesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            activity?.let(viewModel::installPendingUpdate)
        }

    SettingSection(
        title = "App update",
        subtitle = "Install the latest CI debug APK (SHA-pinned via content_manifest)",
    ) {
        Text(
            "Installed: ${state.installedVersionName} (${state.installedVersionCode})",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.availableUpdate?.let { update ->
            Text(
                "Available: ${update.versionName} (${update.versionCode}) · ${update.channel}",
                style = MaterialTheme.typography.bodyMedium,
            )
            update.notes?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.updateMessage?.let {
            Text(
                it,
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
            if (state.availableUpdate != null && !state.installReady) {
                TextButton(
                    onClick = viewModel::downloadAndPrepareInstall,
                    enabled = !state.updateBusy,
                ) {
                    Text("Download")
                }
            }
            if (state.installReady && state.pendingApk != null) {
                TextButton(
                    onClick = {
                        if (viewModel.needsInstallPermission()) {
                            unknownSourcesLauncher.launch(viewModel.installPermissionSettingsIntent())
                        } else {
                            activity?.let(viewModel::installPendingUpdate)
                        }
                    },
                    enabled = !state.updateBusy,
                ) {
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun StorageSection() {
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) granted = hasExternalStorageAccess(context)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingSection(
        title = "Storage",
        subtitle = "Windows D: (Downloads) and F: (device storage)",
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
                        "Wine can list files on the mapped D: and F: drives."
                    } else {
                        "Without access, Wine may show folders but hide every file inside them."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!granted) {
            TextButton(
                onClick = {
                    requestLegacy.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ),
                    )
                },
            ) { Text("Grant storage access") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                TextButton(onClick = { openSettings.launch(allFilesAccessIntent(context)) }) {
                    Text("Open all-files access settings")
                }
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
