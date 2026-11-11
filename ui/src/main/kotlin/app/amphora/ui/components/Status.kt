package app.amphora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amphora.ui.theme.Amphora
import app.amphora.ui.theme.AmphoraColors

/** The meaning of a status color, so call sites say what they mean, not which hue. */
public enum class Tone {
    Neutral,
    Accent,
    Ok,
    Warn,
    Danger,
    Info,
    Dev,
}

/** The foreground color for a tone. */
public fun AmphoraColors.of(tone: Tone): Color = when (tone) {
    Tone.Neutral -> textMuted
    Tone.Accent -> accent
    Tone.Ok -> ok
    Tone.Warn -> warn
    Tone.Danger -> danger
    Tone.Info -> info
    Tone.Dev -> dev
}

/**
 * A status light. [pulse] adds a slow expanding ring, for states that are in
 * progress (installing, starting, running).
 */
@Composable
public fun StatusDot(tone: Tone, modifier: Modifier = Modifier, pulse: Boolean = false, size: Dp = 8.dp) {
    val color by animateColorAsState(Amphora.colors.of(tone), label = "dot")
    val phase =
        if (pulse) {
            val transition = rememberInfiniteTransition(label = "pulse")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
                label = "pulse",
            )
        } else {
            null
        }
    Canvas(modifier.size(size * 2.25f)) {
        val r = size.toPx() / 2
        val p = phase?.value
        if (p != null) {
            drawCircle(color.copy(alpha = 0.45f * (1f - p)), radius = r * (1f + 1.25f * p))
        }
        drawCircle(color.copy(alpha = 0.18f), radius = r * 1.6f)
        drawCircle(color, radius = r)
    }
}

/** A dot and an uppercase mono label: "● READY", "● INSTALLING". */
@Composable
public fun StatusPill(label: String, tone: Tone, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val colors = Amphora.colors
    val fg = colors.of(tone)
    val shape = RoundedCornerShape(50)
    Row(
        modifier =
        modifier
            .clip(shape)
            .background(fg.copy(alpha = 0.09f))
            .border(1.dp, fg.copy(alpha = 0.22f), shape)
            .padding(start = 4.dp, end = 11.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone = tone, pulse = pulse, size = 6.dp)
        Spacer(Modifier.width(2.dp))
        Text(label.uppercase(), style = Amphora.type.overline, color = fg)
    }
}

/** A small rectangular label: a scope, an arch, a source ("全局", "x86", "配方"). */
@Composable
public fun Tag(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, filled: Boolean = false) {
    val colors = Amphora.colors
    val fg = colors.of(tone)
    val shape = RoundedCornerShape(5.dp)
    Text(
        text = text,
        style = Amphora.type.monoSmall,
        color = if (filled) colors.onAccent else fg,
        maxLines = 1,
        modifier =
        modifier
            .clip(shape)
            .background(if (filled) fg else fg.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
