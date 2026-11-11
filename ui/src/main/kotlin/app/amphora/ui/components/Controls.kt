package app.amphora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amphora.ui.theme.Amphora
import app.amphora.ui.theme.MonoFamily

/** "amphora▍" with a terminal cursor that blinks at a terminal's pace. */
@Composable
public fun Wordmark(modifier: Modifier = Modifier) {
    val colors = Amphora.colors
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec =
        infiniteRepeatable(
            keyframes {
                durationMillis = 1060
                1f at 0
                1f at 529
                0f at 530
                0f at 1059
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(0),
        ),
        label = "cursor",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "amphora",
            color = colors.text,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            letterSpacing = (-0.4).sp,
        )
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .width(9.dp)
                .height(18.dp)
                .alpha(blink)
                .background(colors.accent),
        )
    }
}

/**
 * Two to four mutually exclusive options in one row. For longer lists use a
 * sheet; a segmented control that scrolls is a list pretending.
 */
@Composable
public fun <T> Segmented(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Amphora.colors
    val shape = MaterialTheme.shapes.small
    Row(
        modifier =
        modifier
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(if (isSelected) colors.surfaceRaised else Color.Transparent, label = "seg")
            val fg by animateColorAsState(
                when {
                    !enabled -> colors.textFaint
                    isSelected -> colors.text
                    else -> colors.textMuted
                },
                label = "segText",
            )
            val itemShape = MaterialTheme.shapes.extraSmall
            Text(
                label(option),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                modifier =
                Modifier
                    .weight(1f, fill = false)
                    .clip(itemShape)
                    .background(bg)
                    .border(1.dp, if (isSelected) colors.hairlineStrong else Color.Transparent, itemShape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A pill that toggles or picks: filters, roots, sources. [icon] is optional
 * and sits before the label.
 */
@Composable
public fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = Amphora.colors
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(if (selected) colors.accentSoft else Color.Transparent, label = "chip")
    val fg by animateColorAsState(
        when {
            !enabled -> colors.textFaint
            selected -> colors.accent
            else -> colors.textMuted
        },
        label = "chipText",
    )
    Row(
        modifier =
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) colors.accent.copy(alpha = 0.35f) else colors.hairlineStrong, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (icon != null) 10.dp else 13.dp, end = 13.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Material's switch in Amphora's colors. */
@Composable
public fun AmphoraSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Amphora.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors =
        SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textMuted,
            uncheckedTrackColor = colors.surfaceSunken,
            uncheckedBorderColor = colors.hairlineStrong,
            disabledCheckedTrackColor = colors.accent.copy(alpha = 0.35f),
            disabledUncheckedTrackColor = colors.surfaceSunken,
            disabledUncheckedBorderColor = colors.hairline,
            disabledUncheckedThumbColor = colors.textFaint,
        ),
    )
}

/** Overline-styled label for groups inside a panel. */
@Composable
public fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Amphora.colors.textFaint) {
    Text(text.uppercase(), style = Amphora.type.overline, color = color, modifier = modifier)
}
