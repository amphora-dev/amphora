package app.amphora.ui

import android.content.Context
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

/**
 * Flip to `true` for the "open app → Wine session" iteration loop (bypasses SAF).
 * Normal builds leave this `false` and start at [LauncherRoute]; the launcher still
 * exposes a **Debug: Wine smoke test** button that uses the same staging helper.
 */
private const val DEBUG_AUTO_LAUNCH_WINE = false

private const val DEBUG_WIDTH = 1280
private const val DEBUG_HEIGHT = 720

@Composable
fun AmphoraNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val startRoute = remember {
        if (DEBUG_AUTO_LAUNCH_WINE) {
            gameSessionRoute(stageDebugWineExe(context), DEBUG_WIDTH, DEBUG_HEIGHT)
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
            onDebugLaunchWine = {
                navController.navigate(
                    gameSessionRoute(stageDebugWineExe(context), DEBUG_WIDTH, DEBUG_HEIGHT),
                )
            },
        )
        settingsScreen(onBack = { navController.popBackStack() })
        gameSessionScreen(onExit = { navController.popBackStack() })
    }
}

/** Stage the deterministic PE smoke-test fixture into app-private storage. */
internal fun stageDebugWineExe(context: Context): String =
    DebugWineFixture.stage(context).absolutePath
