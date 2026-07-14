package app.amphora.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.amphora.feature.launcher.navigation.LauncherRoute
import app.amphora.feature.launcher.navigation.launcherScreen
import app.amphora.feature.settings.navigation.SettingsRoute
import app.amphora.feature.settings.navigation.settingsScreen
import app.amphora.gamesession.gameSessionRoute
import app.amphora.gamesession.gameSessionScreen

@Composable
fun AmphoraNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = LauncherRoute) {
        launcherScreen(
            onLaunch = { exePath, width, height ->
                navController.navigate(gameSessionRoute(exePath, width, height))
            },
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
        settingsScreen(onBack = { navController.popBackStack() })
        gameSessionScreen(onExit = { navController.popBackStack() })
    }
}
