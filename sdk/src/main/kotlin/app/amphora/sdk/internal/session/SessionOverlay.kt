package app.amphora.sdk.internal.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amphora.sdk.internal.window.ScaleMode
import app.amphora.sdk.logs.LogStore
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.Backdrop
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.CodeWell
import app.amphora.ui.components.Hairline
import app.amphora.ui.components.KeyValueRow
import app.amphora.ui.components.Panel
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.SectionLabel
import app.amphora.ui.components.StatusDot
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tone
import app.amphora.ui.components.Wordmark
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal interface OverlayActions {
    fun resume()

    fun keyboard()

    fun end()

    fun close()
}

/** Everything the session shows on top of (or instead of) the Wine desktop. */
@Composable
internal fun SessionOverlay(ui: SessionUi, actions: OverlayActions) {
    when (ui.stage) {
        Stage.Driver, Stage.Launch, Stage.Desktop, Stage.Failed -> BootScreen(ui, actions)
        Stage.Ending -> EndingScreen(ui)
        Stage.Running -> SessionMenu(ui, actions)
    }
}

@Composable
private fun BootScreen(ui: SessionUi, actions: OverlayActions) {
    val colors = Amphora.colors
    val failed = ui.stage == Stage.Failed
    val log = rememberLogTail(ui.logFile, lines = 14)
    Backdrop(Modifier.fillMaxSize(), glow = if (failed) colors.danger else colors.accent) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wordmark()
                Spacer(Modifier.weight(1f))
                Elapsed(ui.startedAt, running = !failed)
            }
            Spacer(Modifier.height(24.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val wide = maxWidth > 720.dp
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        BootStatus(ui, Modifier.weight(0.9f).fillMaxHeight(), actions)
                        LogPanel(log, Modifier.weight(1.1f).fillMaxHeight())
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        BootStatus(ui, Modifier.fillMaxWidth(), actions)
                        LogPanel(log, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BootStatus(ui: SessionUi, modifier: Modifier, actions: OverlayActions) {
    val colors = Amphora.colors
    val failed = ui.stage == Stage.Failed
    Column(modifier.verticalScroll(rememberScrollState())) {
        SectionLabel(
            if (failed) "session failed" else "starting session",
            color = if (failed) colors.danger else colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            ui.title,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${ui.runtime} · ${ui.desktop.width}×${ui.desktop.height}",
            style = Amphora.type.monoSmall,
            color = colors.textFaint,
        )
        Spacer(Modifier.height(24.dp))
        val steps = listOf(Stage.Driver, Stage.Launch, Stage.Desktop)
        val reached = if (failed) -1 else steps.indexOf(ui.stage)
        steps.forEachIndexed { index, step ->
            StepRow(
                label = step.label,
                state =
                when {
                    failed -> if (index == steps.lastIndex) StepState.Failed else StepState.Unknown
                    index < reached -> StepState.Done
                    index == reached -> StepState.Active
                    else -> StepState.Pending
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        if (failed) {
            Text(ui.failure ?: "启动失败", style = MaterialTheme.typography.bodyMedium, color = colors.danger)
            Spacer(Modifier.height(20.dp))
            AmphoraButton("关闭", onClick = actions::close, kind = ButtonKind.Primary, icon = Icons.Outlined.Close)
        } else {
            if (ui.newPrefix) {
                Panel(color = colors.surfaceSunken, contentPadding = PaddingValues(14.dp)) {
                    Text("首次启动", style = MaterialTheme.typography.labelLarge, color = colors.warn)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Wine 正在创建前缀（注册表、系统目录、字体）。通常需要一到两分钟，之后的启动只要几秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            AmphoraButton("取消", onClick = actions::end, kind = ButtonKind.Ghost, icon = Icons.Outlined.Close)
        }
    }
}

private enum class StepState { Pending, Active, Done, Failed, Unknown }

@Composable
private fun StepRow(label: String, state: StepState) {
    val colors = Amphora.colors
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> Icon(Icons.Outlined.Check, null, tint = colors.ok, modifier = Modifier.size(16.dp))
                StepState.Active -> StatusDot(Tone.Accent, pulse = true, size = 7.dp)
                StepState.Failed -> StatusDot(Tone.Danger, size = 7.dp)
                StepState.Pending, StepState.Unknown -> StatusDot(Tone.Neutral, size = 5.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color =
            when (state) {
                StepState.Active -> colors.text
                StepState.Done -> colors.textMuted
                StepState.Failed -> colors.danger
                StepState.Pending, StepState.Unknown -> colors.textFaint
            },
        )
    }
}

@Composable
private fun LogPanel(lines: List<String>, modifier: Modifier) {
    Column(modifier) {
        SectionLabel("log · tail")
        Spacer(Modifier.height(8.dp))
        CodeWell(lines, Modifier.fillMaxWidth().weight(1f, fill = false), placeholder = "等待输出…")
    }
}

@Composable
private fun EndingScreen(ui: SessionUi) {
    val colors = Amphora.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas.copy(alpha = 0.86f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 380.dp).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Tone.Warn, pulse = true)
                Spacer(Modifier.width(8.dp))
                Text("正在结束会话", style = MaterialTheme.typography.titleMedium, color = colors.text)
            }
            Spacer(Modifier.height(6.dp))
            Text(ui.title, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
            Spacer(Modifier.height(16.dp))
            ProgressTrack(progress = null)
            Spacer(Modifier.height(10.dp))
            Text("wineserver -k", style = Amphora.type.monoSmall, color = colors.textFaint)
        }
    }
}

@Composable
private fun SessionMenu(ui: SessionUi, actions: OverlayActions) {
    val colors = Amphora.colors
    AnimatedVisibility(ui.menuOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.canvas.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions::resume),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            ui.menuOpen,
            enter = slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(160)) { it / 3 } + fadeOut(tween(160)),
        ) {
            Panel(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .width(340.dp)
                    .fillMaxHeight()
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                contentPadding = PaddingValues(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("session")
                    Spacer(Modifier.weight(1f))
                    StatusPill("running", Tone.Ok, pulse = true)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Hairline()
                Spacer(Modifier.height(6.dp))
                KeyValueRow("运行时间", Formats.clock(elapsedSeconds(ui.runningSince ?: ui.startedAt)))
                KeyValueRow("桌面", "${ui.desktop.width}×${ui.desktop.height}")
                KeyValueRow("缩放", "${scaleLabel(ui.scale)} · ${ui.dpi} DPI")
                KeyValueRow("进程", "pid ${android.os.Process.myPid()}")
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmphoraButton(
                        "继续",
                        onClick = actions::resume,
                        kind = ButtonKind.Primary,
                        icon = Icons.Outlined.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AmphoraButton(
                        "键盘",
                        onClick = actions::keyboard,
                        icon = Icons.Outlined.Keyboard,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AmphoraButton(
                        "结束会话",
                        onClick = actions::end,
                        kind = ButtonKind.Danger,
                        icon = Icons.Outlined.PowerSettingsNew,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("返回手势打开或收起此菜单", style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
            }
        }
    }
}

private fun scaleLabel(mode: ScaleMode): String = when (mode) {
    ScaleMode.Fit -> "等比"
    ScaleMode.Stretch -> "拉伸"
    ScaleMode.Integer -> "整数倍"
}

@Composable
private fun Elapsed(since: Long, running: Boolean) {
    val seconds by produceState(elapsedNow(since), since, running) {
        while (running && isActive) {
            value = elapsedNow(since)
            delay(1000)
        }
    }
    Text(Formats.clock(seconds), style = Amphora.type.monoLarge, color = Amphora.colors.textMuted)
}

@Composable
private fun elapsedSeconds(since: Long): Long {
    val seconds by produceState(elapsedNow(since), since) {
        while (isActive) {
            value = elapsedNow(since)
            delay(1000)
        }
    }
    return seconds
}

private fun elapsedNow(since: Long): Long = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)

/** The end of a growing log, polled while shown. */
@Composable
private fun rememberLogTail(file: File, lines: Int): List<String> {
    val tail by produceState(emptyList<String>(), file) {
        while (isActive) {
            value = withContext(Dispatchers.IO) { LogStore.tailOf(file, lines, 32 * 1024) }
            delay(600)
        }
    }
    return tail
}
