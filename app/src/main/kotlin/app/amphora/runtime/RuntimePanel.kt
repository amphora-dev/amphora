package app.amphora.runtime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.amphora.sdk.engine.InstallPhase
import app.amphora.sdk.engine.RuntimeArtifact
import app.amphora.sdk.engine.RuntimeError
import app.amphora.sdk.engine.RuntimeState
import app.amphora.shell.adbPath
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.CodeWell
import app.amphora.ui.components.Panel
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.Readout
import app.amphora.ui.components.StatusDot
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import java.io.File

/**
 * The runtime while it is not ready: what installing costs, the install as
 * it runs (phase by phase), and what went wrong. Ready is the system screen's
 * business; this panel is for getting there.
 */
@Composable
fun RuntimePanel(
    state: RuntimeState,
    sideloadDir: File?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Amphora.colors
    Panel(
        modifier = modifier.fillMaxWidth(),
        border = if (state is RuntimeState.Failed) colors.danger.copy(alpha = 0.35f) else colors.hairline,
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it.javaClass },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "runtime",
        ) { current ->
            Column {
                when (current) {
                    RuntimeState.Checking -> Checking()
                    is RuntimeState.Missing -> Missing(current, sideloadDir, onInstall)
                    is RuntimeState.Working -> Working(current, onCancel)
                    is RuntimeState.Failed -> Failed(current, sideloadDir, onInstall)
                    is RuntimeState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(overline: String, title: String, trailing: @Composable () -> Unit = {}) {
    val colors = Amphora.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(overline.uppercase(), style = Amphora.type.overline, color = colors.textFaint)
            Spacer(Modifier.height(2.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text)
        }
        trailing()
    }
}

@Composable
private fun Checking() {
    PanelTitle("Runtime", "正在检查运行时")
    Spacer(Modifier.height(14.dp))
    ProgressTrack(null, ticks = false, height = 4.dp)
}

@Composable
private fun Missing(state: RuntimeState.Missing, sideloadDir: File?, onInstall: () -> Unit) {
    val colors = Amphora.colors
    val artifact = state.artifact
    PanelTitle("Runtime · 未安装", "安装 Wine 运行时") {
        StatusPill(if (state.sideloaded) "sideload" else "missing", if (state.sideloaded) Tone.Ok else Tone.Warn)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            state.sideloaded -> "找到了 adb 推送的运行时包：跳过下载，只校验和解包。"
            state.downloaded > 0 -> "上次下载到 ${Formats.bytes(state.downloaded)}，会从断点接着下。"
            else -> "运行任何 Windows 程序都要它。装一次，之后离线可用。"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textMuted,
    )
    Spacer(Modifier.height(16.dp))
    ArtifactFacts(artifact, downloaded = state.downloaded.takeIf { it > 0 && !state.sideloaded })
    Spacer(Modifier.height(18.dp))
    var showSideload by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AmphoraButton(
            text =
            when {
                state.sideloaded -> "从侧载包安装"
                state.downloaded > 0 -> "继续下载"
                else -> "下载并安装"
            },
            onClick = onInstall,
            kind = ButtonKind.Primary,
            icon = Icons.Outlined.Download,
        )
        if (!state.sideloaded && sideloadDir != null) {
            AmphoraButton(
                if (showSideload) "收起" else "用 adb 侧载",
                onClick = { showSideload = !showSideload },
                kind = ButtonKind.Ghost,
                icon = Icons.Outlined.Terminal,
            )
        }
    }
    if (showSideload && sideloadDir != null) {
        Spacer(Modifier.height(14.dp))
        SideloadHint(artifact, sideloadDir)
    }
}

@Composable
private fun ArtifactFacts(artifact: RuntimeArtifact, downloaded: Long?) {
    val colors = Amphora.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Readout("下载", Formats.bytes(artifact.size))
        if (downloaded !=
            null
        ) {
            Readout("已下载", Formats.percent(downloaded.toFloat() / artifact.size), tone = Tone.Accent)
        }
        Readout("解包后", Formats.bytes(artifact.bytes))
        Readout("文件", artifact.files.toString())
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "${artifact.name}  ·  sha256 ${Formats.shortHash(artifact.sha256)}",
        style = Amphora.type.monoSmall,
        color = colors.textFaint,
    )
}

@Composable
private fun SideloadHint(artifact: RuntimeArtifact, dir: File) {
    val colors = Amphora.colors
    Text(
        "电脑上有这个包时，推到下面的目录，回到这里就会识别（大小必须一致，安装时照样校验 sha256）：",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textMuted,
    )
    Spacer(Modifier.height(8.dp))
    CodeWell(
        listOf(
            "$ adb push \\",
            "    ${artifact.name}.tar.gz \\",
            "    ${adbPath(dir)}",
        ),
    )
}

@Composable
private fun Working(state: RuntimeState.Working, onCancel: () -> Unit) {
    val colors = Amphora.colors
    PanelTitle("Runtime · 安装中", state.phase.label + "…") {
        Text(Formats.percent(state.fraction), style = Amphora.type.monoDisplay, color = colors.text)
    }
    Spacer(Modifier.height(14.dp))
    Pipeline(state.phase)
    Spacer(Modifier.height(14.dp))
    ProgressTrack(state.fraction.takeIf { state.total > 0 })
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Readout("进度", "${Formats.bytes(state.done)} / ${Formats.bytes(state.total)}")
            if (state.bytesPerSecond > 0) Readout("速度", Formats.rate(state.bytesPerSecond))
            Formats.eta(state.total - state.done, state.bytesPerSecond)?.let { Readout("剩余", it) }
        }
        if (state.phase == InstallPhase.Downloading) {
            AmphoraButton("暂停", onCancel, kind = ButtonKind.Ghost, icon = Icons.Outlined.Pause)
        }
    }
}

/** The four install phases as a pipeline, done ones checked, the current one lit. */
@Composable
private fun Pipeline(current: InstallPhase) {
    val colors = Amphora.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        InstallPhase.entries.forEachIndexed { index, phase ->
            val done = phase.ordinal < current.ordinal
            val active = phase == current
            if (index > 0) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(1.dp)
                        .background(if (done || active) colors.accent.copy(alpha = 0.5f) else colors.hairlineStrong),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    done ->
                        Box(
                            Modifier.size(16.dp).clip(CircleShape).background(colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Check, null, tint = colors.accent, modifier = Modifier.size(11.dp)) }
                    active -> StatusDot(Tone.Accent, pulse = true, size = 7.dp)
                    else -> StatusDot(Tone.Neutral, size = 5.dp)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    phase.label,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                    when {
                        active -> colors.text
                        done -> colors.textMuted
                        else -> colors.textFaint
                    },
                )
            }
        }
    }
}

@Composable
private fun Failed(state: RuntimeState.Failed, sideloadDir: File?, onRetry: () -> Unit) {
    val colors = Amphora.colors
    val error = state.error
    PanelTitle(
        "Runtime · 安装失败",
        when (error) {
            is RuntimeError.Network -> "下载中断"
            is RuntimeError.Integrity -> "校验没通过"
            is RuntimeError.Storage -> "存储空间不够"
            is RuntimeError.Corrupt -> "运行时包有问题"
            is RuntimeError.Io -> "写入失败"
        },
    ) { StatusPill("failed", Tone.Danger) }
    Spacer(Modifier.height(6.dp))
    Text(
        when (error) {
            is RuntimeError.Network -> "网络断了或服务器出错。已下载的部分保留着，重试会接着下。"
            is RuntimeError.Integrity -> "下载的包和配方锁定的不一致，已删除。重试会重新下载。"
            is RuntimeError.Storage -> "需要 ${Formats.bytes(error.needed)}，只剩 ${Formats.bytes(error.available)}。清出空间后重试。"
            is RuntimeError.Corrupt -> "包能读，但内容和配方对不上。重试会重新下载；侧载的包请换一份。"
            is RuntimeError.Io -> "解包或写文件时出错，多半是空间或权限问题。"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textMuted,
    )
    Spacer(Modifier.height(12.dp))
    CodeWell(listOf("E " + error.message))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AmphoraButton("重试", onRetry, kind = ButtonKind.Primary, icon = Icons.Outlined.Refresh)
    }
    if (sideloadDir != null && error !is RuntimeError.Storage) {
        Spacer(Modifier.height(16.dp))
        SideloadHint(state.artifact, sideloadDir)
    }
}
