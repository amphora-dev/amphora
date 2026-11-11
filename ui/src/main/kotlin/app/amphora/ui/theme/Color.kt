package app.amphora.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Amphora's palette: graphite surfaces separated by hairlines rather than
 * shadows, one warm accent (fired clay), and status hues that stay readable
 * on the darkest surface. Dark only: the app is a tool that sits next to a
 * game, and one tuned theme beats two approximate ones.
 */
@Immutable
public data class AmphoraColors(
    /** Behind everything. */
    val canvas: Color,
    /** Panels and cards. */
    val surface: Color,
    /** Elements that sit on a panel: rows being pressed, chips, sheets. */
    val surfaceRaised: Color,
    /** Wells: code blocks, logs, inputs. */
    val surfaceSunken: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    /** Developer-level things. */
    val dev: Color,
)

internal val DarkAmphoraColors =
    AmphoraColors(
        canvas = Color(0xFF0B0C0E),
        surface = Color(0xFF131518),
        surfaceRaised = Color(0xFF1B1E22),
        surfaceSunken = Color(0xFF0E1012),
        hairline = Color(0x17FFFFFF),
        hairlineStrong = Color(0x26FFFFFF),
        text = Color(0xFFEDEEF0),
        textMuted = Color(0xFFA2A8B0),
        textFaint = Color(0xFF6B727B),
        accent = Color(0xFFF2784B),
        accentSoft = Color(0x29F2784B),
        onAccent = Color(0xFF1C0D07),
        ok = Color(0xFF3DD9AC),
        warn = Color(0xFFF5B841),
        danger = Color(0xFFFF6464),
        info = Color(0xFF7FA6FF),
        dev = Color(0xFFB797F2),
    )

public val LocalAmphoraColors: androidx.compose.runtime.ProvidableCompositionLocal<AmphoraColors> =
    staticCompositionLocalOf { DarkAmphoraColors }
