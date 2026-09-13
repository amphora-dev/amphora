package app.amphora.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.ui.AmphoraMotion
import app.amphora.feature.launcher.navigation.LAUNCHER_ROUTE
import app.amphora.feature.launcher.navigation.launcherScreen
import app.amphora.feature.settings.navigation.SETTINGS_ROUTE
import app.amphora.feature.settings.navigation.settingsScreen
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.gamesession.SessionActivity
import app.amphora.gamesession.wineandroid.WineAndroidSessionActivity

@Composable
fun AmphoraNavHost(navController: NavHostController) {
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = LAUNCHER_ROUTE,
        enterTransition = { AmphoraMotion.navEnter() },
        exitTransition = { AmphoraMotion.navExit() },
        popEnterTransition = { AmphoraMotion.navPopEnter() },
        popExitTransition = { AmphoraMotion.navPopExit() },
    ) {
        launcherScreen(
            onLaunch = { exePath, width, height ->
                if (WineAndroidLaunchGate.FORCE_WINEANDROID_HOST) {
                    WineAndroidSessionActivity.launch(context, exePath, width, height)
                } else {
                    SessionActivity.launch(
                        context,
                        exePath,
                        width,
                        height,
                        displayBackend = DisplayBackend.X11,
                    )
                }
            },
            onOpenExplorer = { width, height ->
                if (WineAndroidLaunchGate.FORCE_WINEANDROID_HOST) {
                    WineAndroidSessionActivity.launch(
                        context = context,
                        exePath = "",
                        width = width,
                        height = height,
                        target = LaunchTarget.EXPLORER,
                    )
                } else {
                    SessionActivity.launch(
                        context = context,
                        exePath = "",
                        width = width,
                        height = height,
                        target = LaunchTarget.EXPLORER,
                        displayBackend = DisplayBackend.X11,
                    )
                }
            },
            onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
        )
        settingsScreen(onBack = { navController.popBackStack() })
    }
}

/** Stage the deterministic PE smoke-test fixture into app-private storage. */
internal fun stageDebugWineExe(context: Context): String = DebugWineFixture.stage(context).absolutePath
