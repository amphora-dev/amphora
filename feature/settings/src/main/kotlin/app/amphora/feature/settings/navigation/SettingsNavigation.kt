package app.amphora.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.amphora.feature.settings.SettingsScreen

const val SETTINGS_ROUTE = "settings"

fun NavGraphBuilder.settingsScreen(onBack: () -> Unit) {
    composable(route = SETTINGS_ROUTE) {
        SettingsScreen(onBack = onBack)
    }
}
