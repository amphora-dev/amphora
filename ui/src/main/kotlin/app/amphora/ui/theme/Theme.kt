package app.amphora.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** Access to Amphora's tokens inside [AmphoraTheme]. */
public object Amphora {
    public val colors: AmphoraColors
        @Composable @ReadOnlyComposable
        get() = LocalAmphoraColors.current

    public val type: AmphoraType
        @Composable @ReadOnlyComposable
        get() = LocalAmphoraType.current
}

private val AmphoraShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(9.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

@Composable
public fun AmphoraTheme(content: @Composable () -> Unit) {
    val colors = DarkAmphoraColors
    val scheme =
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.accent,
            secondary = colors.ok,
            onSecondary = colors.canvas,
            tertiary = colors.info,
            background = colors.canvas,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textMuted,
            surfaceContainerLowest = colors.surfaceSunken,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surfaceRaised,
            surfaceContainerHighest = colors.surfaceRaised,
            outline = colors.hairlineStrong,
            outlineVariant = colors.hairline,
            error = colors.danger,
            onError = colors.canvas,
            scrim = colors.canvas,
        )
    CompositionLocalProvider(
        LocalAmphoraColors provides colors,
        LocalAmphoraType provides DefaultAmphoraType,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AmphoraTypography, shapes = AmphoraShapes, content = content)
    }
}
