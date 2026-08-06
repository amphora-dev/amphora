package app.amphora.feature.launcher.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.amphora.feature.launcher.LauncherScreen

const val LAUNCHER_ROUTE = "launcher"

fun NavGraphBuilder.launcherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
    onDebugLaunchWine: ((width: Int, height: Int) -> Unit)? = null,
    onDebugLaunchWineDiag: ((width: Int, height: Int) -> Unit)? = null,
) {
    composable(route = LAUNCHER_ROUTE) {
        LauncherScreen(
            onLaunch = onLaunch,
            onOpenSettings = onOpenSettings,
            onDebugLaunchWine = onDebugLaunchWine,
            onDebugLaunchWineDiag = onDebugLaunchWineDiag,
        )
    }
}
