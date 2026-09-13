package app.amphora.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.amphora.core.ui.AmphoraMotion
import app.amphora.desktop.DesktopActivity
import app.amphora.feature.launcher.navigation.LAUNCHER_ROUTE
import app.amphora.feature.launcher.navigation.launcherScreen
import app.amphora.feature.settings.navigation.SETTINGS_ROUTE
import app.amphora.feature.settings.navigation.settingsScreen

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
                SessionLaunch.program(context, exePath, width, height)
            },
            onOpenExplorer = { width, height ->
                SessionLaunch.explorer(context, width, height)
            },
            onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
            onOpenDesktop = { DesktopActivity.launch(context) },
        )
        settingsScreen(onBack = { navController.popBackStack() })
    }
}

/** Stage the deterministic PE smoke-test fixture into app-private storage. */
internal fun stageDebugWineExe(context: Context): String = DebugWineFixture.stage(context).absolutePath
