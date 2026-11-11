package app.amphora.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.LocalAppGraph
import app.amphora.sdk.engine.Component
import app.amphora.sdk.library.Game
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.session.LiveSession
import app.amphora.sdk.session.SessionState
import app.amphora.shell.ConfirmDialog
import app.amphora.shell.Screen
import app.amphora.shell.ScreenLayout
import app.amphora.shell.TextPromptDialog
import app.amphora.shell.rememberCopier
import app.amphora.shell.rememberLaunchGate
import app.amphora.shell.rememberNow
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.ButtonSize
import app.amphora.ui.components.EmptyState
import app.amphora.ui.components.GameCover
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.KeyValueRow
import app.amphora.ui.components.Panel
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.Readout
import app.amphora.ui.components.SectionHeader
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import java.text.DateFormat
import java.util.Date

@Composable
fun GameScreen(id: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenLog: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val model = viewModel { GameViewModel(graph, id) }
    val lookup by model.game.collectAsStateWithLifecycle()
    var removing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(lookup, removing) { if (removing && lookup is GameLookup.Missing) onBack() }

    when (val current = lookup) {
        GameLookup.Loading -> Screen(title = " ", onBack = onBack) {}
        GameLookup.Missing ->
            Screen(title = "游戏", overline = "Game", onBack = onBack) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(Icons.Outlined.ErrorOutline, "这个游戏不在游戏库里了", detail = "可能已在别处移除。")
                    }
                }
            }
        is GameLookup.Found ->
            GameContent(
                game = current.game,
                model = model,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                onOpenLog = onOpenLog,
                onRemove = {
                    removing = true
                    model.remove()
                },
            )
    }
}

@Composable
private fun GameContent(
    game: Game,
    model: GameViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val session by model.session.collectAsStateWithLifecycle()
    val ready by model.runtimeReady.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val copy = rememberCopier()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    Screen(
        title = game.title,
        overline = "Game · ${game.arch.label}",
        onBack = onBack,
        actions = {
            IconAction(Icons.Outlined.DriveFileRenameOutline, "重命名", { renaming = true })
            IconAction(Icons.Outlined.Tune, "游戏设置", onOpenSettings)
        },
    ) { layout ->
        item(key = "hero") { Hero(game, session, ready, layout, model, onOpenLog) }

        val warnings = (preview as? PreviewState.Ready)?.preview?.warnings.orEmpty()
        if (warnings.isNotEmpty()) {
            item(key = "warnings") { Warnings(warnings) }
        }

        item(key = "file") { FilePanel(game, onRefresh = model::refresh) }

        item(key = "config") {
            when (val p = preview) {
                is PreviewState.Ready -> EffectiveConfigPanel(game.title, p.preview, onCopy = { copy("生效配置", it) })
                PreviewState.Computing ->
                    Panel(Modifier.fillMaxWidth()) {
                        SectionHeader("生效配置", tag = "Effective config")
                        ProgressTrack(null, ticks = false, height = 4.dp)
                    }
                PreviewState.NoRuntime ->
                    Panel(Modifier.fillMaxWidth()) {
                        SectionHeader("生效配置", tag = "Effective config")
                        Text(
                            "装好运行时后，这里会列出启动时实际使用的命令和环境。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amphora.colors.textMuted,
                        )
                    }
                is PreviewState.Failed ->
                    Panel(Modifier.fillMaxWidth(), border = Amphora.colors.danger.copy(alpha = 0.3f)) {
                        SectionHeader("生效配置", tag = "Effective config")
                        Text(
                            "算不出启动配置：${p.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amphora.colors.danger,
                        )
                    }
            }
        }

        item(key = "danger") {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("从游戏库移除", style = MaterialTheme.typography.titleSmall, color = Amphora.colors.text)
                        Text(
                            "删除条目、游戏时间和它的设置。游戏文件和存档不动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amphora.colors.textMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    AmphoraButton("移除", {
                        confirmRemove = true
                    }, kind = ButtonKind.Danger, icon = Icons.Outlined.DeleteOutline)
                }
            }
        }
    }

    if (renaming) {
        TextPromptDialog(
            title = "重命名",
            initial = game.title,
            confirmLabel = "保存",
            placeholder = game.exeName,
            validate = { if (it.isBlank()) "名字不能为空" else null },
            onConfirm = model::rename,
            onDismiss = { renaming = false },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "移除「${game.title}」？",
            message = "游戏时间和这个游戏的设置会删除；${game.directory.path} 里的文件不动，随时可以再加回来。",
            confirmLabel = "移除",
            danger = true,
            onConfirm = onRemove,
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun Hero(
    game: Game,
    session: SessionState,
    ready: Boolean,
    layout: ScreenLayout,
    model: GameViewModel,
    onOpenLog: (String) -> Unit,
) {
    val gate = rememberLaunchGate()
    val now by rememberNow(periodMillis = 1_000)
    val live = (session as? SessionState.Live)?.session
    val mine = live?.takeIf { it.gameId == game.id }
    val graph = LocalAppGraph.current
    val canTranslate = remember(graph) { Component.Fex in graph.sdk.recipe.components }

    Column {
        Row {
            GameCover(
                seed = game.id,
                title = game.title,
                label = game.arch.label,
                modifier = Modifier.width(if (layout.wide) 200.dp else 116.dp),
            )
            Spacer(Modifier.width(if (layout.wide) 28.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        mine != null -> StatusPill("running", Tone.Ok, pulse = true)
                        game.arch.native -> StatusPill("${game.arch.label} 原生", Tone.Ok)
                        canTranslate -> StatusPill("${game.arch.label} · FEX 翻译", Tone.Info)
                        else -> StatusPill("${game.arch.label} · 缺 FEX", Tone.Warn)
                    }
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Readout("游戏时间", Formats.clock(game.playSeconds))
                    Readout("上次", game.lastPlayedAt?.let { Formats.ago(it, now) } ?: "—")
                    if (mine != null) Readout("本次", Formats.clock((now - mine.startedAt) / 1000), tone = Tone.Ok)
                    if (layout.wide) Readout("大小", Formats.bytes(game.exeSize))
                }
                if (layout.wide) {
                    Spacer(Modifier.height(22.dp))
                    Actions(game, live, ready, gate::request, model, onOpenLog)
                }
            }
        }
        if (!layout.wide) {
            Spacer(Modifier.height(16.dp))
            Actions(game, live, ready, gate::request, model, onOpenLog)
        }
    }
}

/** Play, or — while this game runs — go back to it or end it. */
@Composable
private fun Actions(
    game: Game,
    live: LiveSession?,
    ready: Boolean,
    launch: (LaunchTarget) -> Unit,
    model: GameViewModel,
    onOpenLog: (String) -> Unit,
) {
    val colors = Amphora.colors
    if (live != null && live.gameId == game.id) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AmphoraButton("回到游戏", model::resume, kind = ButtonKind.Primary, icon = Icons.Outlined.PlayArrow)
            AmphoraButton("结束", model::end, kind = ButtonKind.Danger, icon = Icons.Outlined.PowerSettingsNew)
            AmphoraButton("日志", { onOpenLog(live.id) }, kind = ButtonKind.Ghost)
        }
        return
    }
    AmphoraButton(
        "开始游戏",
        { launch(LaunchTarget.Play(game)) },
        kind = ButtonKind.Primary,
        icon = Icons.Outlined.PlayArrow,
        enabled = ready && live == null,
    )
    val reason =
        when {
            !ready -> "先在游戏库装好运行时"
            live != null -> "「${live.title}」正在运行，先结束它"
            else -> null
        }
    if (reason != null) {
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Warnings(warnings: List<String>) {
    val colors = Amphora.colors
    Panel(Modifier.fillMaxWidth(), color = colors.warn.copy(alpha = 0.06f), border = colors.warn.copy(alpha = 0.3f)) {
        warnings.forEach { warning ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = colors.warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(warning, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            }
        }
    }
}

@Composable
private fun FilePanel(game: Game, onRefresh: () -> Unit) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Panel(Modifier.fillMaxWidth()) {
        SectionHeader("可执行文件", tag = "Executable") {
            AmphoraButton(
                "重新读取",
                onRefresh,
                kind = ButtonKind.Ghost,
                size = ButtonSize.Compact,
                icon = Icons.Outlined.Refresh,
            )
        }
        KeyValueRow("文件", game.exeName)
        KeyValueRow("目录 → D:", game.directory.path)
        KeyValueRow("架构", game.arch.label + if (game.arch.native) "（原生）" else "（需要 x86 翻译）")
        KeyValueRow("大小", Formats.bytes(game.exeSize))
        KeyValueRow("SHA-256", game.exeSha256)
        KeyValueRow("Steam AppID", game.detectedAppId?.toString() ?: "没找到 steam_appid.txt")
        KeyValueRow("前缀", game.prefixId)
        KeyValueRow("加入于", date.format(Date(game.addedAt)), mono = false)
    }
}
