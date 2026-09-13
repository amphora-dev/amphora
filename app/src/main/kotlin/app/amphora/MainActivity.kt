package app.amphora

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.gamesession.SessionActivity
import app.amphora.gamesession.wineandroid.WineAndroidSessionActivity
import app.amphora.ui.AmphoraApp
import app.amphora.ui.WineAndroidLaunchGate
import app.amphora.ui.stageDebugWineExe
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent { AmphoraApp() }
        if (savedInstanceState == null && isDebuggable) {
            val useWineAndroid =
                intent.getBooleanExtra(EXTRA_DEBUG_WINEANDROID, false) ||
                    WineAndroidLaunchGate.FORCE_WINEANDROID_HOST
            val smoke = intent.getBooleanExtra(EXTRA_DEBUG_WINE_SMOKE, false)
            if (smoke || useWineAndroid) {
                val exePath =
                    intent
                        .getStringExtra(EXTRA_DEBUG_WINE_EXE)
                        ?.takeIf { it.isNotBlank() }
                        ?: stageDebugWineExe(this)
                val width = intent.getIntExtra(EXTRA_DEBUG_WIDTH, 1280)
                val height = intent.getIntExtra(EXTRA_DEBUG_HEIGHT, 720)
                val graphicsDiag = intent.getBooleanExtra(EXTRA_DEBUG_GRAPHICS_DIAG, false)
                if (useWineAndroid) {
                    WineAndroidSessionActivity.launch(
                        context = this,
                        exePath = exePath,
                        width = width,
                        height = height,
                        graphicsDiag = graphicsDiag,
                    )
                } else {
                    SessionActivity.launch(
                        context = this,
                        exePath = exePath,
                        width = width,
                        height = height,
                        graphicsDiag = graphicsDiag,
                        displayBackend = DisplayBackend.X11,
                    )
                }
            }
        }
    }

    private companion object {
        const val EXTRA_DEBUG_WINE_SMOKE = "app.amphora.debug.WINE_SMOKE"
        const val EXTRA_DEBUG_WINE_EXE = "app.amphora.debug.WINE_EXE"
        const val EXTRA_DEBUG_WIDTH = "app.amphora.debug.WIDTH"
        const val EXTRA_DEBUG_HEIGHT = "app.amphora.debug.HEIGHT"
        const val EXTRA_DEBUG_GRAPHICS_DIAG = "app.amphora.debug.GRAPHICS_DIAG"
        /** Debug-only: open [WineAndroidSessionActivity] instead of the X11 SessionActivity. */
        const val EXTRA_DEBUG_WINEANDROID = "app.amphora.debug.WINEANDROID"
    }
}
