package app.amphora.feature.launcher.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.amphora.feature.launcher.LauncherScreen

const val LauncherRoute = "launcher"

fun NavGraphBuilder.launcherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable(route = LauncherRoute) {
        LauncherScreen(
            onLaunch = onLaunch,
            onOpenSettings = onOpenSettings,
        )
    }
}
