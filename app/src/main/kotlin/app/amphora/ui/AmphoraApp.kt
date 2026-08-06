package app.amphora.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import app.amphora.ui.theme.AmphoraTheme

@Composable
fun AmphoraApp(startRouteOverride: String? = null) {
    AmphoraTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            AmphoraNavHost(
                navController = navController,
                startRouteOverride = startRouteOverride,
            )
        }
    }
}
