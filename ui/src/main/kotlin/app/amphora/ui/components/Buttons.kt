package app.amphora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

public enum class ButtonKind {
    /** The one thing the screen is for. Accent fill. */
    Primary,

    /** Alternatives next to a primary. Raised surface with a hairline. */
    Secondary,

    /** Low-emphasis actions inside panels. Text only. */
    Ghost,

    /** Destructive. Tinted, never filled: the user should read it, not hit it by habit. */
    Danger,
}

public enum class ButtonSize { Regular, Compact }

/**
 * Amphora's button. One component with a [kind] instead of four lookalikes,
 * so emphasis is a parameter that reviews can see.
 */
@Composable
public fun AmphoraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    size: ButtonSize = ButtonSize.Regular,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = app.amphora.ui.theme.Amphora.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, label = "press")

    val (container, content, outline) =
        when (kind) {
            ButtonKind.Primary -> Triple(colors.accent, colors.onAccent, Color.Transparent)
            ButtonKind.Secondary -> Triple(colors.surfaceRaised, colors.text, colors.hairlineStrong)
            ButtonKind.Ghost -> Triple(Color.Transparent, colors.textMuted, Color.Transparent)
            ButtonKind.Danger -> Triple(
                colors.danger.copy(alpha = 0.10f),
                colors.danger,
                colors.danger.copy(alpha = 0.28f),
            )
        }
    val bg by animateColorAsState(if (enabled) container else colors.surfaceRaised.copy(alpha = 0.5f), label = "bg")
    val fg by animateColorAsState(if (enabled) content else colors.textFaint, label = "fg")
    val shape = MaterialTheme.shapes.medium
    val padding =
        when (size) {
            ButtonSize.Regular -> PaddingValues(horizontal = 18.dp, vertical = 11.dp)
            ButtonSize.Compact -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        }

    Row(
        modifier =
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.defaultMinSize(minHeight = if (size == ButtonSize.Regular) 44.dp else 32.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (enabled) outline else Color.Transparent, shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(
                    if (size ==
                        ButtonSize.Regular
                    ) {
                        18.dp
                    } else {
                        16.dp
                    },
                ),
            )
        }
        Text(
            text,
            color = fg,
            style = if (size ==
                ButtonSize.Regular
            ) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.labelMedium
            },
            maxLines = 1,
        )
    }
}

/** A square icon-only button with a hairline, for toolbars. */
@Composable
public fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = app.amphora.ui.theme.Amphora.colors.textMuted,
    enabled: Boolean = true,
) {
    val colors = app.amphora.ui.theme.Amphora.colors
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier =
        modifier
            .size(40.dp)
            .clip(shape)
            .border(1.dp, colors.hairline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else colors.textFaint,
            modifier = Modifier.size(19.dp),
        )
    }
}
