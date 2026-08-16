package app.amphora.core.ui

import androidx.compose.ui.graphics.Color

/**
 * Shared design tokens usable from every UI-bearing module (app + features).
 * The full Material scheme lives in the app theme; these are the brand
 * semantics and layout constants that widgets previously hand-picked as
 * scattered hex literals (two different greens for one meaning was the
 * motivating bug).
 */
object AmphoraSemantic {
    /** Healthy / installed / verified. */
    val success = Color(0xFF58D6A5)

    /** Success on dim surfaces (dot borders, disabled chips). */
    val successDim = Color(0xFF2E7D5B)

    /** Degraded / fallback / caution. */
    val warning = Color(0xFFF5A97F)

    /** Broken / error emphasis beyond the scheme error slot. */
    val danger = Color(0xFFFF867C)

    /** Neutral informational accent (HUD primary metric, IME preview). */
    val info = Color(0xFF80CBC4)

    /** Muted metric text on HUD-style near-black panels. */
    val metricMuted = Color(0xFFCFD8DC)

    /** Locally-built content badge. */
    val localBuild = Color(0xFF3976A8)
}

/**
 * Window-size breakpoints shared across screens. COMPACT covers phone-portrait
 * ergonomics (collapsed top bars); EXPANDED is the two-pane / navigation-rail
 * threshold. Screens must not invent their own cutoffs.
 */
object AmphoraBreakpoints {
    const val COMPACT: Int = 600
    const val EXPANDED: Int = 840
}
