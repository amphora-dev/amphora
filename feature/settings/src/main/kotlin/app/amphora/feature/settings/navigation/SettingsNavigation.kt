package app.amphora.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.amphora.feature.settings.SettingsScreen

const val SettingsRoute = "settings"

fun NavGraphBuilder.settingsScreen(onBack: () -> Unit) {
    composable(route = SettingsRoute) {
        SettingsScreen(onBack = onBack)
    }
}
