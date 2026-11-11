package app.amphora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amphora.ui.theme.Amphora

/**
 * A surface separated from the canvas by a hairline, not a shadow. Content is
 * laid out in a column with the panel's padding.
 */
@Composable
public fun Panel(
    modifier: Modifier = Modifier,
    color: Color = Amphora.colors.surface,
    border: Color = Amphora.colors.hairline,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
        modifier
            .clip(shape)
            .background(color)
            .border(BorderStroke(1.dp, border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Label above a group: a mono index and name ("01  RUNTIME"), the Chinese title,
 * and optional trailing content (a count, an action).
 */
@Composable
public fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = Amphora.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (tag != null) {
                Text(tag.uppercase(), style = Amphora.type.overline, color = colors.textFaint)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trailing()
        }
    }
}

/** A row of a list inside a panel: leading slot, title and detail, trailing slot. */
@Composable
public fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailMono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = Amphora.colors
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = if (detailMono) Amphora.type.monoSmall else MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trailing()
            }
        }
    }
}

/**
 * One item of a panel whose rows are separate lazy-list items: draws the
 * panel's fill and border for this slice only, so a long list keeps the look
 * of [Panel] without composing every row. The first and last slices get the
 * rounded ends.
 */
public fun Modifier.panelSlice(
    first: Boolean,
    last: Boolean,
    fill: Color,
    edge: Color,
    radius: Dp = 16.dp,
    stroke: Dp = 1.dp,
): Modifier = drawWithCache {
    val r = radius.toPx()
    val w = stroke.toPx()
    // Middle slices extend the shape past their bounds, so only the sides show.
    val top = if (first) 0f else -2 * r
    val bottom = if (last) size.height else size.height + 2 * r
    val body = Path().apply { addRoundRect(RoundRect(0f, top, size.width, bottom, CornerRadius(r))) }
    val outline =
        Path().apply {
            addRoundRect(RoundRect(w / 2, top + w / 2, size.width - w / 2, bottom - w / 2, CornerRadius(r - w / 2)))
        }
    onDrawWithContent {
        clipRect {
            drawPath(body, fill)
            drawPath(outline, edge, style = Stroke(w))
        }
        drawContent()
    }
}

/** A hairline divider for use inside panels. */
@Composable
public fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Amphora.colors.hairline),
    )
}
