package app.amphora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amphora.ui.theme.Amphora

/** A labelled instrument value: "DOWNLOADED / 92.4 MiB". */
@Composable
public fun Readout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tone: Tone? = null,
) {
    val colors = Amphora.colors
    Column(modifier) {
        Text(label.uppercase(), style = Amphora.type.overline, color = colors.textFaint, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = Amphora.type.monoLarge,
                color = if (tone != null) colors.of(tone) else colors.text,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = Amphora.type.monoSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
    }
}

/**
 * Key on the left, value on the right. Values are mono and selectable: paths,
 * hashes and versions are things people copy into bug reports.
 */
@Composable
public fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTone: Tone? = null,
    mono: Boolean = true,
) {
    val colors = Amphora.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            modifier = Modifier.width(112.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value,
                style = if (mono) Amphora.type.mono else MaterialTheme.typography.bodyMedium,
                color = if (valueTone != null) colors.of(valueTone) else colors.text,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A rounded square holding an icon, for the leading slot of list rows. */
@Composable
public fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Amphora.colors.textMuted,
    background: Color = Amphora.colors.surfaceRaised,
) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier =
        modifier
            .size(36.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, Amphora.colors.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** A recessed well for monospaced text: commands, log tails, environment. */
@Composable
public fun CodeWell(
    lines: List<String>,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    placeholder: String = "",
) {
    val colors = Amphora.colors
    val shape = MaterialTheme.shapes.small
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val shown = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        if (shown.isEmpty()) {
            Text(placeholder, style = Amphora.type.monoSmall, color = colors.textFaint)
        }
        SelectionContainer {
            Column {
                shown.forEach { line ->
                    Text(
                        line,
                        style = Amphora.type.monoSmall,
                        color = lineColor(line),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: String): Color {
    val colors = Amphora.colors
    return when {
        line.contains(":err:") || line.startsWith("E ") || line.contains("error", ignoreCase = true) -> colors.danger
        line.contains(":warn:") || line.startsWith("W ") -> colors.warn
        line.startsWith("$ ") || line.startsWith("> ") -> colors.accent
        line.contains(":fixme:") -> colors.textFaint
        else -> colors.textMuted
    }
}

/** Centered message for an empty list, with an optional action below. */
@Composable
public fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Amphora.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, tint = colors.textFaint)
        Spacer(Modifier.padding(top = 12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.padding(top = 14.dp))
            action()
        }
    }
}
