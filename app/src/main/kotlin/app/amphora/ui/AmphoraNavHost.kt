package app.amphora.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.amphora.feature.launcher.navigation.LAUNCHER_ROUTE
import app.amphora.feature.launcher.navigation.launcherScreen
import app.amphora.feature.settings.navigation.SETTINGS_ROUTE
import app.amphora.feature.settings.navigation.settingsScreen
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.SessionActivity

@Composable
fun AmphoraNavHost(navController: NavHostController) {
    val context = LocalContext.current
    NavHost(navController = navController, startDestination = LAUNCHER_ROUTE) {
        launcherScreen(
            onLaunch = { exePath, width, height ->
                SessionActivity.launch(context, exePath, width, height)
            },
            onOpenExplorer = { width, height ->
                SessionActivity.launch(
                    context = context,
                    exePath = "",
                    width = width,
                    height = height,
                    target = LaunchTarget.EXPLORER,
                )
            },
            onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
        )
        settingsScreen(onBack = { navController.popBackStack() })
    }
}

/** Stage the deterministic PE smoke-test fixture into app-private storage. */
internal fun stageDebugWineExe(context: Context): String = DebugWineFixture.stage(context).absolutePath
