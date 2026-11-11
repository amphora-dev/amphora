package app.amphora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.amphora.ui.R

/** JetBrains Mono (OFL, bundled): readouts, paths, hashes, environment variables. */
public val MonoFamily: FontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    )

private val tightLines =
    LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/** Text styles beyond Material's: the technical voice of the UI. */
@Immutable
public data class AmphoraType(
    /** Small caps label above a group: "RUNTIME". */
    val overline: TextStyle,
    val monoSmall: TextStyle,
    val mono: TextStyle,
    val monoLarge: TextStyle,
    /** Big numbers: progress, sizes. */
    val monoDisplay: TextStyle,
)

internal val DefaultAmphoraType =
    AmphoraType(
        overline =
        TextStyle(
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 0.14.em,
            lineHeight = 14.sp,
        ),
        monoSmall = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 16.sp),
        mono = TextStyle(fontFamily = MonoFamily, fontSize = 12.5.sp, lineHeight = 18.sp),
        monoLarge = TextStyle(
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        ),
        monoDisplay =
        TextStyle(
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.02).em,
        ),
    )

public val LocalAmphoraType: androidx.compose.runtime.ProvidableCompositionLocal<AmphoraType> =
    staticCompositionLocalOf { DefaultAmphoraType }

internal val AmphoraTypography: Typography =
    Typography().run {
        copy(
            displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
            headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeightStyle = tightLines),
            titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
            bodyLarge = bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
            bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.01.em),
        )
    }
