package app.amphora.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.LocalAppGraph
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.library.BuiltinTool
import app.amphora.sdk.library.Game
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.session.SessionState
import app.amphora.shell.LaunchGate
import app.amphora.shell.Screen
import app.amphora.shell.gridItems
import app.amphora.shell.rememberLaunchGate
import app.amphora.shell.rememberNow
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.EmptyState
import app.amphora.ui.components.GameCover
import app.amphora.ui.components.IconTile
import app.amphora.ui.components.Panel
import app.amphora.ui.components.SectionHeader
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora

@Composable
fun LibraryScreen(onOpenGame: (String) -> Unit, onAddGame: () -> Unit, onOpenLog: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val model = viewModel { LibraryViewModel(graph) }
    val ui by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }
    val gate = rememberLaunchGate()
    val games = ui.games

    Screen(
        title = "游戏库",
        overline = "Library",
        subtitle =
        when {
            games == null -> " "
            games.isEmpty() -> "加一个 Windows 游戏，或先用内置程序试试运行时"
            else -> "${games.size} 个游戏 · 共玩了 ${playTotal(games)}"
        },
        actions = { AmphoraButton("添加", onAddGame, kind = ButtonKind.Secondary, icon = Icons.Outlined.Add) },
    ) { layout ->
        if (ui.runtime !is RuntimeState.Ready) {
            item(key = "runtime", contentType = "runtime") {
                app.amphora.runtime.RuntimePanel(
                    ui.runtime,
                    model.sideloadDir,
                    model::install,
                    model::cancelInstall,
                    Modifier.animateItem(),
                )
            }
        }
        when (val session = ui.session) {
            is SessionState.Live ->
                item(key = "session", contentType = "session") {
                    LiveSessionBanner(session.session, model::resume, model::end, Modifier.animateItem())
                }
            is SessionState.Launching ->
                item(key = "session", contentType = "session") {
                    LaunchingBanner(session.title, Modifier.animateItem())
                }
            is SessionState.Idle ->
                ui.ended?.let { ended ->
                    item(key = "ended:${ended.id}", contentType = "ended") {
                        EndedSessionBanner(ended, {
                            onOpenLog(ended.id)
                        }, { model.dismissEnded(ended.id) }, Modifier.animateItem())
                    }
                }
        }

        item(key = "games-header", contentType = "section") {
            SectionHeader("游戏", tag = "Games", modifier = Modifier.padding(top = 8.dp)) {
                if (!games.isNullOrEmpty()) {
                    Text(
                        "${games.size}",
                        style = Amphora.type.mono,
                        color = Amphora.colors.textFaint,
                    )
                }
            }
        }
        when {
            games == null -> Unit
            games.isEmpty() ->
                item(key = "games-empty", contentType = "empty") {
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            icon = Icons.Outlined.SportsEsports,
                            title = "还没有游戏",
                            detail = "选一个 .exe 加进来。游戏文件留在原处，amphora 只记路径，运行时把它的目录挂成 D: 盘。",
                            action = {
                                AmphoraButton("添加游戏", onAddGame, kind = ButtonKind.Primary, icon = Icons.Outlined.Add)
                            },
                        )
                    }
                }
            else ->
                gridItems(games, layout.columns(if (layout.wide) 168.dp else 140.dp, 14.dp), key = {
                    it.id
                }, contentType = "games", spacing = 14.dp) { game ->
                    GameCard(
                        game = game,
                        running = ui.liveGameId == game.id,
                        canLaunch = ui.canLaunch,
                        onOpen = { onOpenGame(game.id) },
                        onPlay = { gate.request(LaunchTarget.Play(game)) },
                    )
                }
        }

        item(key = "tools-header", contentType = "section") {
            SectionHeader("内置程序", tag = "Wine", modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    if (ui.ready) "不用添加，直接运行" else "装好运行时后可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amphora.colors.textFaint,
                )
            }
        }
        gridItems(BuiltinTool.entries, layout.columns(232.dp), key = { it.name }, contentType = "tools") { tool ->
            ToolTile(tool, enabled = ui.canLaunch, gate = gate)
        }
    }
}

private fun playTotal(games: List<Game>): String {
    val seconds = games.sumOf { it.playSeconds }
    return when {
        seconds < 60 -> "0 分钟"
        seconds < 3600 -> "${seconds / 60} 分钟"
        else -> "${"%.1f".format(java.util.Locale.ROOT, seconds / 3600.0)} 小时"
    }
}

@Composable
private fun GameCard(game: Game, running: Boolean, canLaunch: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = Amphora.colors
    val now by rememberNow(periodMillis = 60_000)
    Column(
        Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "打开 ${game.title}", onClick = onOpen)
            .padding(4.dp),
    ) {
        Box {
            GameCover(seed = game.id, title = game.title, label = game.arch.label, modifier = Modifier.fillMaxWidth())
            if (running) {
                StatusPill(
                    "running",
                    Tone.Ok,
                    pulse = true,
                    modifier = Modifier.align(
                        Alignment.TopStart,
                    ).padding(8.dp).background(colors.canvas.copy(alpha = 0.6f), CircleShape),
                )
            }
            PlayButton(
                visible = canLaunch,
                title = game.title,
                onPlay = onPlay,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            game.title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            game.lastPlayedAt?.let { "${Formats.ago(it, now)} · ${Formats.clock(game.playSeconds)}" } ?: "还没玩过",
            style = Amphora.type.monoSmall,
            color = colors.textFaint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The play button on a cover; it appears only when a launch can start. */
@Composable
private fun PlayButton(visible: Boolean, title: String, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Amphora.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .border(1.dp, colors.onAccent.copy(alpha = 0.15f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = "开始 $title", onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.PlayArrow,
                contentDescription = "开始",
                tint = colors.onAccent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ToolTile(tool: BuiltinTool, enabled: Boolean, gate: LaunchGate) {
    val colors = Amphora.colors
    Panel(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        onClick = if (enabled) ({ gate.request(LaunchTarget.Tool(tool)) }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(tool.icon, tint = colors.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tool.title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1)
                    Text(tool.command.first(), style = Amphora.type.monoSmall, color = colors.textFaint, maxLines = 1)
                }
                Text(
                    tool.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val BuiltinTool.icon: ImageVector
    get() =
        when (this) {
            BuiltinTool.Console -> Icons.Outlined.Terminal
            BuiltinTool.Notepad -> Icons.Outlined.EditNote
            BuiltinTool.Explorer -> Icons.Outlined.FolderOpen
            BuiltinTool.Regedit -> Icons.Outlined.AccountTree
            BuiltinTool.Winecfg -> Icons.Outlined.Tune
            BuiltinTool.TaskManager -> Icons.Outlined.QueryStats
            BuiltinTool.Control -> Icons.Outlined.SettingsApplications
            BuiltinTool.Minesweeper -> Icons.Outlined.GridOn
        }
