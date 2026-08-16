package app.amphora.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Every scheme slot is overridden; none may fall through to the Material baseline. */
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
        tertiary = AmphoraDarkTertiary,
        onTertiary = AmphoraDarkOnTertiary,
        tertiaryContainer = AmphoraDarkTertiaryContainer,
        onTertiaryContainer = AmphoraDarkOnTertiaryContainer,
        error = AmphoraDarkError,
        onError = AmphoraDarkOnError,
        errorContainer = AmphoraDarkErrorContainer,
        onErrorContainer = AmphoraDarkOnErrorContainer,
        background = AmphoraDarkBackground,
        onBackground = AmphoraDarkOnBackground,
        surface = AmphoraDarkSurface,
        onSurface = AmphoraDarkOnSurface,
        surfaceVariant = AmphoraDarkSurfaceVariant,
        onSurfaceVariant = AmphoraDarkOnSurfaceVariant,
        surfaceContainerLowest = AmphoraDarkSurfaceContainerLowest,
        surfaceContainerLow = AmphoraDarkSurfaceContainerLow,
        surfaceContainer = AmphoraDarkSurfaceContainer,
        surfaceContainerHigh = AmphoraDarkSurfaceContainerHigh,
        surfaceContainerHighest = AmphoraDarkSurfaceContainerHighest,
        surfaceDim = AmphoraDarkSurfaceDim,
        surfaceBright = AmphoraDarkSurfaceBright,
        surfaceTint = AmphoraDarkPrimary,
        outline = AmphoraDarkOutline,
        outlineVariant = AmphoraDarkOutlineVariant,
        inverseSurface = AmphoraDarkInverseSurface,
        inverseOnSurface = AmphoraDarkInverseOnSurface,
        inversePrimary = AmphoraDarkInversePrimary,
        scrim = AmphoraDarkScrim,
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
        tertiary = AmphoraLightTertiary,
        onTertiary = AmphoraLightOnTertiary,
        tertiaryContainer = AmphoraLightTertiaryContainer,
        onTertiaryContainer = AmphoraLightOnTertiaryContainer,
        error = AmphoraLightError,
        onError = AmphoraLightOnError,
        errorContainer = AmphoraLightErrorContainer,
        onErrorContainer = AmphoraLightOnErrorContainer,
        background = AmphoraLightBackground,
        onBackground = AmphoraLightOnBackground,
        surface = AmphoraLightSurface,
        onSurface = AmphoraLightOnSurface,
        surfaceVariant = AmphoraLightSurfaceVariant,
        onSurfaceVariant = AmphoraLightOnSurfaceVariant,
        surfaceContainerLowest = AmphoraLightSurfaceContainerLowest,
        surfaceContainerLow = AmphoraLightSurfaceContainerLow,
        surfaceContainer = AmphoraLightSurfaceContainer,
        surfaceContainerHigh = AmphoraLightSurfaceContainerHigh,
        surfaceContainerHighest = AmphoraLightSurfaceContainerHighest,
        surfaceDim = AmphoraLightSurfaceDim,
        surfaceBright = AmphoraLightSurfaceBright,
        surfaceTint = AmphoraLightPrimary,
        outline = AmphoraLightOutline,
        outlineVariant = AmphoraLightOutlineVariant,
        inverseSurface = AmphoraLightInverseSurface,
        inverseOnSurface = AmphoraLightInverseOnSurface,
        inversePrimary = AmphoraLightInversePrimary,
        scrim = AmphoraLightScrim,
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
