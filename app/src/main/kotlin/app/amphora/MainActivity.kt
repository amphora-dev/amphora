package app.amphora

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amphora.gamesession.gameSessionRoute
import app.amphora.ui.AmphoraApp
import app.amphora.ui.stageDebugWineExe
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val debugStartRoute =
            if (isDebuggable && intent.getBooleanExtra(EXTRA_DEBUG_WINE_SMOKE, false)) {
                gameSessionRoute(
                    exePath = stageDebugWineExe(this),
                    width = intent.getIntExtra(EXTRA_DEBUG_WIDTH, 1280),
                    height = intent.getIntExtra(EXTRA_DEBUG_HEIGHT, 720),
                )
            } else {
                null
            }
        setContent { AmphoraApp(startRouteOverride = debugStartRoute) }
    }

    private companion object {
        const val EXTRA_DEBUG_WINE_SMOKE = "app.amphora.debug.WINE_SMOKE"
        const val EXTRA_DEBUG_WIDTH = "app.amphora.debug.WIDTH"
        const val EXTRA_DEBUG_HEIGHT = "app.amphora.debug.HEIGHT"
    }
}
