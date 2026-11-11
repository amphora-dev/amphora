package app.amphora.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amphora.ui.components.IconAction
import app.amphora.ui.theme.Amphora

/** Widest a screen's content gets; beyond this, lines get too long to read. */
val ContentMaxWidth: Dp = 1120.dp

/**
 * The system bar space a screen pads its scrolling content by, set by the
 * scaffold: content scrolls under the bars, never ends behind them.
 */
private val LocalScreenInsets = compositionLocalOf { PaddingValues(0.dp) }

@Composable
fun ScreenInsets(insets: PaddingValues, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScreenInsets provides insets, content = content)
}

/** The width a screen's content gets, for screens that adapt their layout to it. */
@Immutable
data class ScreenLayout(val width: Dp) {
    /** Room for two columns side by side. */
    val wide: Boolean get() = width >= 720.dp

    fun columns(minCell: Dp, spacing: Dp = 12.dp): Int = columnsFor(width, minCell, spacing)
}

/**
 * A screen: a header and a scrolling column, centered and width-limited,
 * scrolling under the system bars. [content] gets the content width.
 */
@Composable
fun Screen(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.(ScreenLayout) -> Unit,
) {
    val insets = LocalScreenInsets.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val gutter = if (maxWidth < 600.dp) 16.dp else 28.dp
        val layout = ScreenLayout(minOf(maxWidth, ContentMaxWidth) - gutter * 2)
        LazyColumn(
            state = state,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
            contentPadding =
            PaddingValues(
                start = gutter,
                end = gutter,
                top = insets.calculateTopPadding() + 20.dp,
                bottom = insets.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header", contentType = "header") {
                ScreenHeader(title, overline, subtitle, onBack, actions)
            }
            content(layout)
        }
    }
}

/**
 * A screen that scrolls its own content (a log, a file list): the header and
 * whatever [content] lays out in the remaining height, centered and
 * width-limited like [Screen].
 */
@Composable
fun FixedScreen(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(ScreenLayout) -> Unit,
) {
    val insets = LocalScreenInsets.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val gutter = if (maxWidth < 600.dp) 16.dp else 28.dp
        val layout = ScreenLayout(minOf(maxWidth, ContentMaxWidth) - gutter * 2)
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize()
                .padding(
                    start = gutter,
                    end = gutter,
                    top = insets.calculateTopPadding() + 20.dp,
                    bottom = insets.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader(title, overline, subtitle, onBack, actions)
            content(layout)
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    overline: String?,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = Amphora.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Text(overline.uppercase(), style = Amphora.type.overline, color = colors.textFaint, maxLines = 1)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = actions,
        )
    }
}
