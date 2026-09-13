package app.amphora

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.desktop.DesktopActivity
import app.amphora.ui.AmphoraApp
import app.amphora.ui.SessionLaunch
import app.amphora.ui.stageDebugWineExe
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
                intent.getBooleanExtra(EXTRA_DEBUG_X11, false) -> {
                    SessionLaunch.program(
                        context = this,
                        exePath = debugExePath(),
                        width = intent.getIntExtra(EXTRA_DEBUG_WIDTH, 1280),
                        height = intent.getIntExtra(EXTRA_DEBUG_HEIGHT, 720),
                        graphicsDiag = intent.getBooleanExtra(EXTRA_DEBUG_GRAPHICS_DIAG, false),
                        displayBackend = DisplayBackend.X11,
                    )
                }
                intent.getBooleanExtra(EXTRA_DEBUG_WINEANDROID, false) ||
                    intent.getBooleanExtra(EXTRA_DEBUG_WINE_SMOKE, false) -> {
                    SessionLaunch.program(
                        context = this,
                        exePath = debugExePath(),
                        width = intent.getIntExtra(EXTRA_DEBUG_WIDTH, 1280),
                        height = intent.getIntExtra(EXTRA_DEBUG_HEIGHT, 720),
                        graphicsDiag = intent.getBooleanExtra(EXTRA_DEBUG_GRAPHICS_DIAG, false),
                    )
                }
            }
        }
    }

    private fun debugExePath(): String = intent
        .getStringExtra(EXTRA_DEBUG_WINE_EXE)
        ?.takeIf { it.isNotBlank() }
        ?: stageDebugWineExe(this)

    companion object {
        const val EXTRA_OPEN_SETTINGS = "app.amphora.desktop.OPEN_SETTINGS"
        private const val EXTRA_DEBUG_WINE_SMOKE = "app.amphora.debug.WINE_SMOKE"
        private const val EXTRA_DEBUG_WINE_EXE = "app.amphora.debug.WINE_EXE"
        private const val EXTRA_DEBUG_WIDTH = "app.amphora.debug.WIDTH"
        private const val EXTRA_DEBUG_HEIGHT = "app.amphora.debug.HEIGHT"
        private const val EXTRA_DEBUG_GRAPHICS_DIAG = "app.amphora.debug.GRAPHICS_DIAG"

        /** Debug-only: force wineandroid (redundant with product default; kept for scripts). */
        private const val EXTRA_DEBUG_WINEANDROID = "app.amphora.debug.WINEANDROID"

        /** Debug-only: force legacy Java X11 SessionActivity. */
        private const val EXTRA_DEBUG_X11 = "app.amphora.debug.X11"

        /** Debug-only: open [DesktopActivity]. Default home stays the phone/tablet launcher. */
        private const val EXTRA_DEBUG_DESKTOP = "app.amphora.debug.DESKTOP"
    }
}
