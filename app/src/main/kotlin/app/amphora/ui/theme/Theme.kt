package app.amphora.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors =
    darkColorScheme(
        primary = AmphoraDarkPrimary,
        onPrimary = AmphoraDarkOnPrimary,
        primaryContainer = AmphoraDarkPrimaryContainer,
        onPrimaryContainer = AmphoraDarkOnPrimaryContainer,
        secondary = AmphoraDarkSecondary,
        onSecondary = AmphoraDarkOnSecondary,
        secondaryContainer = AmphoraDarkSecondaryContainer,
        onSecondaryContainer = AmphoraDarkOnSecondaryContainer,
        background = AmphoraDarkBackground,
        onBackground = AmphoraDarkOnBackground,
        surface = AmphoraDarkSurface,
        onSurface = AmphoraDarkOnSurface,
        surfaceVariant = AmphoraDarkSurfaceVariant,
        onSurfaceVariant = AmphoraDarkOnSurfaceVariant,
    )

private val LightColors =
    lightColorScheme(
        primary = AmphoraLightPrimary,
        onPrimary = AmphoraLightOnPrimary,
        primaryContainer = AmphoraLightPrimaryContainer,
        onPrimaryContainer = AmphoraLightOnPrimaryContainer,
        secondary = AmphoraLightSecondary,
        onSecondary = AmphoraLightOnSecondary,
        secondaryContainer = AmphoraLightSecondaryContainer,
        onSecondaryContainer = AmphoraLightOnSecondaryContainer,
        background = AmphoraLightBackground,
        onBackground = AmphoraLightOnBackground,
        surface = AmphoraLightSurface,
        onSurface = AmphoraLightOnSurface,
        surfaceVariant = AmphoraLightSurfaceVariant,
        onSurfaceVariant = AmphoraLightOnSurfaceVariant,
    )

@Composable
fun AmphoraTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AmphoraTypography,
        content = content,
    )
}
