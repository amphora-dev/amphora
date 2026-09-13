package app.amphora.desktop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amphora.MainActivity
import app.amphora.ui.SessionLaunch
import app.amphora.ui.theme.AmphoraTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * P3 v1 Amphora PC-mode desktop: wallpaper + icon grid + bottom taskbar.
 *
 * Not ZUI Work / ZuiLauncherPC. Not SECONDARY_HOME (would steal tablet home).
 * Icons come from [app.amphora.feature.launcher.LauncherProgramLibrary] only.
 * Session launch uses [SessionLaunch] (default wineandroid; X11 via displayBackend=X11).
 */
@AndroidEntryPoint
class DesktopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AmphoraTheme {
                DesktopScreen(
                    onLaunchProgram = { exePath, width, height, _ ->
                        SessionLaunch.program(this@DesktopActivity, exePath, width, height)
                    },
                    onOpenExplorer = { width, height ->
                        SessionLaunch.explorer(this@DesktopActivity, width, height)
                    },
                    onOpenSettings = {
                        startActivity(
                            Intent(this@DesktopActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                            },
                        )
                    },
                    onCloseDesktop = ::finish,
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DesktopActivity::class.java)

        fun launch(context: Context) {
            context.startActivity(intent(context))
        }
    }
}
