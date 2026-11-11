package app.amphora.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.LocalAppGraph
import app.amphora.shell.ConfirmDialog
import app.amphora.shell.Screen
import app.amphora.ui.components.EmptyState
import app.amphora.ui.components.Hairline
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.IconTile
import app.amphora.ui.components.ListRow
import app.amphora.ui.components.Panel
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

@Composable
fun LogsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val model = viewModel { LogsViewModel(graph) }
    val logs by model.logs.collectAsStateWithLifecycle()
    val liveId by model.liveId.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }

    Screen(
        title = "日志",
        overline = "Logs",
        subtitle = "每个会话一份：Wine 的输出和 amphora: 开头的 SDK 记录。保留最近 20 份。",
        onBack = onBack,
        actions = {
            if (!logs.isNullOrEmpty()) IconAction(Icons.Outlined.DeleteSweep, "清空日志", { confirmClear = true })
        },
    ) {
        val list = logs ?: return@Screen
        item(key = "list") {
            Panel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                if (list.isEmpty()) {
                    EmptyState(Icons.AutoMirrored.Outlined.Article, "还没有日志", detail = "启动一个程序后，这里会出现它的会话日志。")
                }
                list.forEachIndexed { index, log ->
                    if (index > 0) Hairline()
                    val name = SessionName.parse(log.id)
                    ListRow(
                        title = name.name,
                        detail = listOfNotNull(
                            name.startedAt?.format(stamp),
                            Formats.bytes(log.bytes),
                        ).joinToString("  ·  "),
                        detailMono = true,
                        leading = { IconTile(Icons.AutoMirrored.Outlined.Article) },
                        trailing = {
                            if (log.id == liveId) StatusPill("live", Tone.Ok, pulse = true)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Amphora.colors.textFaint)
                        },
                        onClick = { onOpen(log.id) },
                    )
                }
            }
        }
        item(key = "note") {
            Column {
                Spacer(Modifier.height(2.dp))
                Text(
                    "日志在应用私有目录里；分享时只授权对方读那一份。",
                    style = Amphora.type.monoSmall,
                    color = Amphora.colors.textFaint,
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空日志？",
            message = "删除所有会话日志。正在运行的会话会继续写它自己的日志。",
            confirmLabel = "清空",
            danger = true,
            onConfirm = model::clear,
            onDismiss = { confirmClear = false },
        )
    }
}
