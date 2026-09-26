package app.amphora

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amphora.core.engine.RuntimeSettingsStore
import app.amphora.core.engine.WineAndroidGuestResolution
import app.amphora.desktop.DesktopActivity
import app.amphora.ui.AmphoraApp
import app.amphora.ui.SessionLaunch
import app.amphora.ui.stageDebugWineExe
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var runtimeSettings: RuntimeSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        setContent {
            AmphoraApp(initialOpenSettings = openSettings)
        }
        if (savedInstanceState == null && isDebuggable) {
            when {
                intent.getBooleanExtra(EXTRA_DEBUG_DESKTOP, false) -> {
                    DesktopActivity.launch(this)
                }
                intent.getBooleanExtra(EXTRA_DEBUG_WINEANDROID, false) ||
                    intent.getBooleanExtra(EXTRA_DEBUG_WINE_SMOKE, false) -> {
                    val resolutionName = runtimeSettings.settings.value.resolutionName
                    val configuredResolution = WineAndroidGuestResolution.fromPreference(resolutionName)
                    val widthOverride = if (intent.hasExtra(EXTRA_DEBUG_WIDTH)) {
                        intent.getIntExtra(EXTRA_DEBUG_WIDTH, configuredResolution.width)
                    } else {
                        null
                    }
                    val heightOverride = if (intent.hasExtra(EXTRA_DEBUG_HEIGHT)) {
                        intent.getIntExtra(EXTRA_DEBUG_HEIGHT, configuredResolution.height)
                    } else {
                        null
                    }
                    val (width, height) = WineAndroidGuestResolution.resolveDebugDimensions(
                        preferenceName = resolutionName,
                        widthOverride = widthOverride,
                        heightOverride = heightOverride,
                    )
                    val source = WineAndroidGuestResolution.describeDebugDimensionSource(
                        preferenceName = resolutionName,
                        widthOverride = widthOverride,
                        heightOverride = heightOverride,
                    )
                    Log.i(
                        TAG,
                        "guest resolution resolve source=$source " +
                            "pref=${resolutionName ?: "(none)"} " +
                            "configured=${configuredResolution.width}x${configuredResolution.height} " +
                            "WxH=${width}x$height",
                    )
                    SessionLaunch.program(
                        context = this,
                        exePath = debugExePath(),
                        width = width,
                        height = height,
                        graphicsDiag = intent.getBooleanExtra(EXTRA_DEBUG_GRAPHICS_DIAG, false),
                        exeArgs = debugExeArgs(),
                        debugImeUnicodeText =
                        intent.getStringExtra(EXTRA_DEBUG_IME_UNICODE_TEXT),
                        debugImeComposingText =
                        if (intent.hasExtra(EXTRA_DEBUG_IME_COMPOSING_TEXT)) {
                            intent.getStringExtra(EXTRA_DEBUG_IME_COMPOSING_TEXT) ?: ""
                        } else {
                            null
                        },
                        debugImeShow =
                        if (intent.hasExtra(EXTRA_DEBUG_IME_SHOW)) {
                            intent.getBooleanExtra(EXTRA_DEBUG_IME_SHOW, false)
                        } else {
                            null
                        },
                        debugCaptureHwnd =
                        if (intent.hasExtra(EXTRA_DEBUG_CAPTURE_HWND)) {
                            intent.getIntExtra(EXTRA_DEBUG_CAPTURE_HWND, 0)
                        } else {
                            null
                        },
                        debugDumpZOrder =
                        if (intent.hasExtra(EXTRA_DEBUG_DUMP_ZORDER)) {
                            intent.getBooleanExtra(EXTRA_DEBUG_DUMP_ZORDER, false)
                        } else {
                            null
                        },
                        debugZOrderTopHwnd =
                        if (intent.hasExtra(EXTRA_DEBUG_ZORDER_TOP_HWND)) {
                            intent.getIntExtra(EXTRA_DEBUG_ZORDER_TOP_HWND, 0)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    private fun debugExePath(): String = intent
        .getStringExtra(EXTRA_DEBUG_WINE_EXE)
        ?.takeIf { it.isNotBlank() }
        ?: stageDebugWineExe(this)

    private fun debugExeArgs(): String = intent.getStringExtra(EXTRA_DEBUG_WINE_ARGS).orEmpty()

    companion object {
        private const val TAG = "MainActivity"

        const val EXTRA_OPEN_SETTINGS = "app.amphora.desktop.OPEN_SETTINGS"
        private const val EXTRA_DEBUG_WINE_SMOKE = "app.amphora.debug.WINE_SMOKE"
        private const val EXTRA_DEBUG_WINE_EXE = "app.amphora.debug.WINE_EXE"

        /** Debug-only: trailing Wine program CLI args (e.g. `--cube vk --bench 8`). */
        private const val EXTRA_DEBUG_WINE_ARGS = "app.amphora.debug.WINE_ARGS"
        private const val EXTRA_DEBUG_WIDTH = "app.amphora.debug.WIDTH"
        private const val EXTRA_DEBUG_HEIGHT = "app.amphora.debug.HEIGHT"
        private const val EXTRA_DEBUG_GRAPHICS_DIAG = "app.amphora.debug.GRAPHICS_DIAG"

        /** Debug-only: force wineandroid (redundant with product default; kept for scripts). */
        private const val EXTRA_DEBUG_WINEANDROID = "app.amphora.debug.WINEANDROID"

        /** Debug-only: inject IME commit text (CJK) after wineandroid desktop ready. */
        private const val EXTRA_DEBUG_IME_UNICODE_TEXT = "app.amphora.debug.IME_UNICODE_TEXT"

        /** Debug-only: host composing chip text (empty clears); not sent to guest. */
        private const val EXTRA_DEBUG_IME_COMPOSING_TEXT = "app.amphora.debug.IME_COMPOSING_TEXT"

        /** Debug-only: explicit soft IME show/hide (`--ez … true|false`). */
        private const val EXTRA_DEBUG_IME_SHOW = "app.amphora.debug.IME_SHOW"

        /**
         * Debug-only: force host SetCapture hwnd (`--ei … N`).
         * `0` releases; `-1` = desktop hwnd sentinel.
         */
        private const val EXTRA_DEBUG_CAPTURE_HWND = "app.amphora.debug.CAPTURE_HWND"

        /** Debug-only: dump sibling z-order stacks (`--ez … true`). */
        private const val EXTRA_DEBUG_DUMP_ZORDER = "app.amphora.debug.DUMP_ZORDER"

        /** Debug-only: force hwnd to HWND_TOP then dump (`--ei … N`). */
        private const val EXTRA_DEBUG_ZORDER_TOP_HWND = "app.amphora.debug.ZORDER_TOP_HWND"

        /** Debug-only: open [DesktopActivity]. Default home stays the phone/tablet launcher. */
        private const val EXTRA_DEBUG_DESKTOP = "app.amphora.debug.DESKTOP"
    }
}
