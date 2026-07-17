package app.amphora.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.amphora.feature.launcher.navigation.launcherScreen
import app.amphora.feature.settings.navigation.SettingsRoute
import app.amphora.feature.settings.navigation.settingsScreen
import app.amphora.gamesession.gameSessionRoute
import app.amphora.gamesession.gameSessionScreen
import java.io.File

@Composable
fun AmphoraNavHost(navController: NavHostController) {
    val context = LocalContext.current
    // DEBUG AUTO-LAUNCH: stage notepad.exe from app assets and start directly in a
    // session, bypassing the launcher/SAF picker so each test cycle is just "open app".
    // Revert startDestination to LauncherRoute for the normal picker flow.
    val startRoute = remember {
        val exe = File(context.filesDir, "exe/notepad.exe").apply { parentFile?.mkdirs() }
        if (!exe.exists() || exe.length() == 0L) {
            context.assets.open("exe/notepad.exe").use { input ->
                exe.outputStream().use { input.copyTo(it) }
            }
        }
        gameSessionRoute(exe.absolutePath, 1280, 720)
    }
    NavHost(navController = navController, startDestination = startRoute) {
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
