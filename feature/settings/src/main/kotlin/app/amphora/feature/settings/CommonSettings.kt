package app.amphora.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.amphora.core.engine.WineLocaleOption

@Composable
internal fun CommonSettings(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenStorageSettings: () -> Unit,
    onRegisterStorageActivityResultHandler: ((() -> Unit)?) -> Unit,
) {
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
            title = "Windows language profile",
            description =
            "Simulates the Windows system locale for legacy ANSI text and locale-aware " +
                "font aliases. Automatic follows the Android device language.",
            impact = "Global default · ANSI codepage and Windows fonts · next launch",
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
    StorageSection(
        state = state,
        onRefresh = viewModel::refreshGuestDrives,
        onOpenSettings = onOpenStorageSettings,
        onRegisterActivityResultHandler = onRegisterStorageActivityResultHandler,
    )
}
