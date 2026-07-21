package app.amphora.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.amphora.feature.launcher.navigation.LauncherRoute
import app.amphora.feature.launcher.navigation.launcherScreen
import app.amphora.feature.settings.navigation.SettingsRoute
import app.amphora.feature.settings.navigation.settingsScreen
import app.amphora.gamesession.gameSessionRoute
import app.amphora.gamesession.gameSessionScreen
import java.io.File

/**
 * Flip to `true` for the "open app → Wine session" iteration loop (bypasses SAF).
 * Normal builds leave this `false` and start at [LauncherRoute]; the launcher still
 * exposes a **Debug: Notepad** button that uses the same staging helper.
 */
private const val DEBUG_AUTO_LAUNCH_NOTEPAD = false

private const val TAG = "AmphoraNavHost"
private const val NOTEPAD_ASSET = "exe/notepad.exe"
private const val NOTEPAD_WIDTH = 1280
private const val NOTEPAD_HEIGHT = 720

@Composable
fun AmphoraNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val startRoute = remember {
        if (DEBUG_AUTO_LAUNCH_NOTEPAD) {
            stageNotepadExe(context)?.let { gameSessionRoute(it, NOTEPAD_WIDTH, NOTEPAD_HEIGHT) }
                ?: LauncherRoute
        } else {
            LauncherRoute
        }
    }
    NavHost(navController = navController, startDestination = startRoute) {
        launcherScreen(
            onLaunch = { exePath, width, height ->
                navController.navigate(gameSessionRoute(exePath, width, height))
            },
            onOpenSettings = { navController.navigate(SettingsRoute) },
            onDebugLaunchNotepad = {
                stageNotepadExe(context)?.let { path ->
                    navController.navigate(gameSessionRoute(path, NOTEPAD_WIDTH, NOTEPAD_HEIGHT))
                }
            },
        )
        settingsScreen(onBack = { navController.popBackStack() })
        gameSessionScreen(onExit = { navController.popBackStack() })
    }
}

/** Stage `assets/exe/notepad.exe` into app-private storage; null if the asset is absent. */
internal fun stageNotepadExe(context: Context): String? {
    return try {
        val exe = File(context.filesDir, "exe/notepad.exe").apply { parentFile?.mkdirs() }
        if (!exe.exists() || exe.length() == 0L) {
            context.assets.open(NOTEPAD_ASSET).use { input ->
                exe.outputStream().use { input.copyTo(it) }
            }
        }
        exe.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "notepad asset missing — run :app:stageBundledContent (needs exe/notepad.exe)", e)
        null
    }
}
