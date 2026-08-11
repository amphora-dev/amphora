package app.amphora.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amphora.core.engine.WindowsComponentPreferences

@Composable
internal fun WindowsComponentsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
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
                    useNative =
                    state.windowsComponents[component]
                        ?: WindowsComponentPreferences.defaultUsesNative(component.id),
                    defaultUseNative = WindowsComponentPreferences.defaultUsesNative(component.id),
                    onChange = { useNative ->
                        viewModel.setWindowsComponentNative(component, useNative)
                    },
                )
                if (index != WindowsComponentSetting.entries.lastIndex) HorizontalDivider()
            }
            Text(
                "Compatibility archives are verified during runtime provisioning. Native extracts " +
                    "private DLLs into the prefix; builtin links the matching Proton DLLs.",
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
    defaultUseNative: Boolean,
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
            ModifiedResetAction(
                modified = useNative != defaultUseNative,
                onReset = { onChange(defaultUseNative) },
            )
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
internal fun AdvancedRuntimeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
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
                impact =
                state.storageUsage?.let {
                    "Scope: Turnip and Zink · ${formatStorageSize(it.shaderCacheBytes)} cached"
                } ?: "Scope: Turnip and Zink · stored in private app storage",
                selected = state.shaderCache,
                defaultValue = true,
                values = listOf(true, false),
                label = { if (it) "Enabled" else "Disabled" },
                onSelect = viewModel::setShaderCache,
                onReset = { viewModel.setShaderCache(true) },
            )
            ChoiceSetting(
                title = "Shader cache limit",
                description = "Maximum disk space Mesa may use for cached shaders.",
                impact = "Environment: MESA_SHADER_CACHE_MAX_SIZE",
                selected = state.shaderCacheSize,
                defaultValue = ShaderCacheSize.MB512,
                values = ShaderCacheSize.entries,
                label = { it.label },
                enabled = state.shaderCache,
                onSelect = viewModel::selectShaderCacheSize,
                onReset = { viewModel.selectShaderCacheSize(ShaderCacheSize.MB512) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = viewModel::clearShaderCache,
                    enabled = !state.clearingShaderCache,
                ) {
                    Text("Clear shader caches")
                }
                Text(
                    if (state.clearingShaderCache) {
                        "Clearing…"
                    } else {
                        state.cacheActionMessage.orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                ModifiedResetAction(
                    modified = state.customEnv.isNotBlank(),
                    onReset = { viewModel.setCustomEnv("") },
                )
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
                    Text(
                        if (state.rejectedEnvNames.isEmpty()) {
                            "Blank lines and lines beginning with # are ignored."
                        } else {
                            "Ignored protected or invalid names: " +
                                state.rejectedEnvNames.distinct().joinToString()
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color =
                        if (state.rejectedEnvNames.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
            )
        }
    }
}
