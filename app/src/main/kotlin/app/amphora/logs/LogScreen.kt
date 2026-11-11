package app.amphora.logs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.LocalAppGraph
import app.amphora.Message
import app.amphora.shell.FixedScreen
import app.amphora.shell.rememberCopier
import app.amphora.ui.components.Chip
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import java.time.format.DateTimeFormatter

/** Which lines the viewer shows. */
private enum class Filter(val label: String, val accepts: (LineKind) -> Boolean) {
    All("全部", { true }),
    Errors("错误", { it == LineKind.Error }),
    Warnings("警告+", { it == LineKind.Error || it == LineKind.Warning }),
    Amphora("amphora", { it == LineKind.Amphora }),
}

@Composable
fun LogScreen(id: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val model = viewModel { LogViewModel(graph, id) }
    val text by model.text.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val follow by model.follow.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val copy = rememberCopier()
    val context = LocalContext.current
    val name = remember(id) { SessionName.parse(id) }

    val lines = text?.lines.orEmpty()
    val shown = remember(lines, filter) { lines.withIndex().filter { filter.accepts(LineKind.of(it.value)) } }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, follow) {
        if (shown.isNotEmpty() && (follow || list.firstVisibleItemIndex == 0)) list.scrollToItem(shown.lastIndex)
    }

    FixedScreen(
        title = name.name,
        overline = "Log",
        subtitle =
        listOfNotNull(
            name.startedAt?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            text?.let { Formats.bytes(it.bytes.coerceAtLeast(0)) },
            text?.let { "${it.lines.size} 行" + if (it.truncated) "（末尾）" else "" },
        ).joinToString("  ·  "),
        onBack = onBack,
        actions = {
            if (live) {
                IconAction(
                    Icons.Outlined.VerticalAlignBottom,
                    if (follow) "停止跟随" else "跟随输出",
                    { model.follow.value = !follow },
                    tint = if (follow) Amphora.colors.ok else Amphora.colors.textMuted,
                )
            }
            IconAction(Icons.Outlined.Refresh, "重新读取", model::reload)
            IconAction(Icons.Outlined.ContentCopy, "复制", { copy("日志", shown.joinToString("\n") { it.value }) })
            IconAction(Icons.Outlined.Share, "分享", {
                share(context, graph.sdk.logs.shareUri(id), name.name) { graph.messages.show(it, Message.Tone.Error) }
            })
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Filter.entries.forEach { option ->
                Chip(option.label, selected = option == filter, onClick = { filter = option })
            }
            Spacer(Modifier.weight(1f))
            if (live) StatusPill(if (follow) "following" else "live", Tone.Ok, pulse = follow)
        }
        Terminal(
            shown,
            loading = text == null,
            truncated = text?.truncated == true,
            list = list,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The log as a terminal would show it: numbered, colored by kind, selectable. */
@Composable
private fun Terminal(
    lines: List<IndexedValue<String>>,
    loading: Boolean,
    truncated: Boolean,
    list: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = Amphora.colors
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline, shape),
    ) {
        when {
            loading -> ProgressTrack(null, ticks = false, height = 3.dp, modifier = Modifier.align(Alignment.TopCenter))
            lines.isEmpty() ->
                Text(
                    "（没有匹配的行）",
                    style = Amphora.type.monoSmall,
                    color = colors.textFaint,
                    modifier = Modifier.padding(16.dp),
                )
            else ->
                SelectionContainer {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        if (truncated) {
                            item(key = "truncated") {
                                Text(
                                    "… 只读了文件末尾；完整日志请分享出去看",
                                    style = Amphora.type.monoSmall,
                                    color = colors.textFaint,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                        items(lines, key = { it.index }) { (index, line) ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                Text(
                                    (index + 1).toString().padStart(5),
                                    style = Amphora.type.monoSmall,
                                    color = colors.textFaint.copy(alpha = 0.6f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    line,
                                    style = Amphora.type.monoSmall,
                                    color = lineColor(LineKind.of(line)),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun lineColor(kind: LineKind): Color {
    val colors = Amphora.colors
    return when (kind) {
        LineKind.Error -> colors.danger
        LineKind.Warning -> colors.warn
        LineKind.Amphora -> colors.accent
        LineKind.Fixme -> colors.textFaint
        LineKind.Other -> colors.textMuted
    }
}

private fun share(context: Context, uri: android.net.Uri, title: String, onError: (String) -> Unit) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "amphora log: $title")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, "分享日志"))
    } catch (_: ActivityNotFoundException) {
        onError("没有能接收文件的应用")
    }
}
