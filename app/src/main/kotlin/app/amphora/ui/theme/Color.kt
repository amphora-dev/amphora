package app.amphora.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Amphora palette: terracotta brand accents (primary) over pure-neutral
 * graphite surfaces, with a warm mauve secondary for chips and selection
 * states. Surfaces carry no hue cast so the warm brand color owns the
 * temperature of the screen; the mauve secondary keeps enough hue distance
 * from the primary for accents to scan instantly. Tertiary and error slots
 * are pinned to the Material 3 baseline dark tokens, declared explicitly so
 * nothing silently falls through if the baseline changes.
 */

// --- Dark (the product scheme; AmphoraTheme pins darkTheme = true) ------------------------
val AmphoraDarkPrimary = Color(0xFFFF8B61)
val AmphoraDarkOnPrimary = Color(0xFF2B0D05)
val AmphoraDarkPrimaryContainer = Color(0xFF4A211A)
val AmphoraDarkOnPrimaryContainer = Color(0xFFFFD8CB)
val AmphoraDarkSecondary = Color(0xFFE5C8ED)
val AmphoraDarkOnSecondary = Color(0xFF461A52)
val AmphoraDarkSecondaryContainer = Color(0xFF532A5E)
val AmphoraDarkOnSecondaryContainer = Color(0xFFF4E5F8)
val AmphoraDarkTertiary = Color(0xFFEFB8C8)
val AmphoraDarkOnTertiary = Color(0xFF492532)
val AmphoraDarkTertiaryContainer = Color(0xFF633B48)
val AmphoraDarkOnTertiaryContainer = Color(0xFFFFD8E4)
val AmphoraDarkError = Color(0xFFF2B8B5)
val AmphoraDarkOnError = Color(0xFF601410)
val AmphoraDarkErrorContainer = Color(0xFF8C1D18)
val AmphoraDarkOnErrorContainer = Color(0xFFF9DEDC)
val AmphoraDarkBackground = Color(0xFF0C0C0C)
val AmphoraDarkOnBackground = Color(0xFFF8F4F1)
val AmphoraDarkSurface = Color(0xFF121212)
val AmphoraDarkOnSurface = Color(0xFFF8F4F1)
val AmphoraDarkSurfaceVariant = Color(0xFF303030)
val AmphoraDarkOnSurfaceVariant = Color(0xFFC6C6C6)
val AmphoraDarkSurfaceContainerLowest = Color(0xFF0F0F0F)
val AmphoraDarkSurfaceContainerLow = Color(0xFF1D1D1D)
val AmphoraDarkSurfaceContainer = Color(0xFF222222)
val AmphoraDarkSurfaceContainerHigh = Color(0xFF2C2C2C)
val AmphoraDarkSurfaceContainerHighest = Color(0xFF373737)
val AmphoraDarkSurfaceDim = Color(0xFF141414)
val AmphoraDarkSurfaceBright = Color(0xFF3B3B3B)
val AmphoraDarkOutline = Color(0xFF949494)
val AmphoraDarkOutlineVariant = Color(0xFF494949)
val AmphoraDarkInverseSurface = Color(0xFFE5E5E5)
val AmphoraDarkInverseOnSurface = Color(0xFF323232)
val AmphoraDarkInversePrimary = Color(0xFF9250A4)
val AmphoraDarkScrim = Color(0xFF000000)

// --- Light (kept complete so a future theme toggle needs no palette work) -----------------
val AmphoraLightPrimary = Color(0xFF9B452E)
val AmphoraLightOnPrimary = Color(0xFFFFFFFF)
val AmphoraLightPrimaryContainer = Color(0xFFFFDAD0)
val AmphoraLightOnPrimaryContainer = Color(0xFF3B0A00)
val AmphoraLightSecondary = Color(0xFF77574F)
val AmphoraLightOnSecondary = Color(0xFFFFFFFF)
val AmphoraLightSecondaryContainer = Color(0xFFFFDAD2)
val AmphoraLightOnSecondaryContainer = Color(0xFF2C1510)
val AmphoraLightTertiary = Color(0xFF456960)
val AmphoraLightOnTertiary = Color(0xFFFFFFFF)
val AmphoraLightTertiaryContainer = Color(0xFFC8F0E6)
val AmphoraLightOnTertiaryContainer = Color(0xFF06201A)
val AmphoraLightError = Color(0xFFB3261E)
val AmphoraLightOnError = Color(0xFFFFFFFF)
val AmphoraLightErrorContainer = Color(0xFFF9DEDC)
val AmphoraLightOnErrorContainer = Color(0xFF410E0B)
val AmphoraLightBackground = Color(0xFFFFF8F6)
val AmphoraLightOnBackground = Color(0xFF241A18)
val AmphoraLightSurface = Color(0xFFFFF8F6)
val AmphoraLightOnSurface = Color(0xFF241A18)
val AmphoraLightSurfaceVariant = Color(0xFFF5DED8)
val AmphoraLightOnSurfaceVariant = Color(0xFF53433F)
val AmphoraLightSurfaceContainerLowest = Color(0xFFFFFFFF)
val AmphoraLightSurfaceContainerLow = Color(0xFFFCEDE7)
val AmphoraLightSurfaceContainer = Color(0xFFF6E7E1)
val AmphoraLightSurfaceContainerHigh = Color(0xFFF0E1DB)
val AmphoraLightSurfaceContainerHighest = Color(0xFFEAE0D6)
val AmphoraLightSurfaceDim = Color(0xFFE7D9D3)
val AmphoraLightSurfaceBright = Color(0xFFFFF8F6)
val AmphoraLightOutline = Color(0xFF82716C)
val AmphoraLightOutlineVariant = Color(0xFFD4C3BD)
val AmphoraLightInverseSurface = Color(0xFF3A2F2C)
val AmphoraLightInverseOnSurface = Color(0xFFFFEDE6)
val AmphoraLightInversePrimary = Color(0xFFFF8B61)
val AmphoraLightScrim = Color(0xFF000000)
