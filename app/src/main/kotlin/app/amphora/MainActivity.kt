package app.amphora

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.gamesession.SessionActivity
import app.amphora.ui.AmphoraApp
import app.amphora.ui.stageDebugWineExe
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent { AmphoraApp() }
        if (
            savedInstanceState == null &&
            isDebuggable &&
            intent.getBooleanExtra(EXTRA_DEBUG_WINE_SMOKE, false)
        ) {
            stageDebugMaliLeegaoAsset()
            SessionActivity.launch(
                context = this,
                exePath =
                    intent
                        .getStringExtra(EXTRA_DEBUG_WINE_EXE)
                        ?.takeIf { it.isNotBlank() }
                        ?: stageDebugWineExe(this),
                width = intent.getIntExtra(EXTRA_DEBUG_WIDTH, 1280),
                height = intent.getIntExtra(EXTRA_DEBUG_HEIGHT, 720),
                graphicsDiag = intent.getBooleanExtra(EXTRA_DEBUG_GRAPHICS_DIAG, false),
            )
        }
    }

    private fun stageDebugMaliLeegaoAsset() {
        val selected =
            getSharedPreferences(GraphicsDriverIds.PREFS_NAME, MODE_PRIVATE)
                .getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null)
        if (selected != GraphicsDriverIds.MALI_LEEGAO) return

        val destination = File(filesDir, "runtime-assets/$MALI_LEEGAO_ASSET")
        destination.parentFile?.mkdirs()
        assets.open(MALI_LEEGAO_ASSET).use { input ->
            val partial = File(destination.absolutePath + ".part")
            partial.outputStream().use(input::copyTo)
            check(!destination.exists() || destination.delete()) {
                "Cannot replace debug Mali Leegao asset: ${destination.absolutePath}"
            }
            check(partial.renameTo(destination)) {
                "Cannot stage debug Mali Leegao asset: ${destination.absolutePath}"
            }
        }
    }

    private companion object {
        const val MALI_LEEGAO_ASSET = "graphics_driver/wrapper-leegao.tzst"
        const val EXTRA_DEBUG_WINE_SMOKE = "app.amphora.debug.WINE_SMOKE"
        const val EXTRA_DEBUG_WINE_EXE = "app.amphora.debug.WINE_EXE"
        const val EXTRA_DEBUG_WIDTH = "app.amphora.debug.WIDTH"
        const val EXTRA_DEBUG_HEIGHT = "app.amphora.debug.HEIGHT"
        const val EXTRA_DEBUG_GRAPHICS_DIAG = "app.amphora.debug.GRAPHICS_DIAG"
    }
}
