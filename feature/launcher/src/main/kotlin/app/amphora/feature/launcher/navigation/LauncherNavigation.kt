package app.amphora.feature.launcher.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.amphora.feature.launcher.ModernLauncherScreen

const val LAUNCHER_ROUTE = "launcher"

fun NavGraphBuilder.launcherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenExplorer: (width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable(route = LAUNCHER_ROUTE) {
        ModernLauncherScreen(
            onLaunch = onLaunch,
            onOpenExplorer = onOpenExplorer,
            onOpenSettings = onOpenSettings,
        )
    }
}
