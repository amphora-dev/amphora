package app.amphora.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amphora.sdk.session.EndedSession
import app.amphora.sdk.session.LiveSession
import app.amphora.sdk.session.SessionPhase
import app.amphora.shell.rememberNow
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.ButtonSize
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.Panel
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.StatusDot
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora

/** The running session, with the two things one does with it: go back, or end it. */
@Composable
fun LiveSessionBanner(session: LiveSession, onResume: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Amphora.colors
    val now by rememberNow()
    val ending = session.phase == SessionPhase.Ending
    Panel(modifier.fillMaxWidth(), border = colors.ok.copy(alpha = 0.28f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (ending) Tone.Warn else Tone.Ok, pulse = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "SESSION · ${session.phase.label}",
                    style = Amphora.type.overline,
                    color = if (ending) colors.warn else colors.ok,
                )
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(Formats.clock((now - session.startedAt) / 1000), style = Amphora.type.monoLarge, color = colors.text)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                "pid ${session.pid}",
                session.desktop?.let {
                    "desktop $it"
                } ?: "等待桌面",
                session.id,
            ).joinToString("  ·  "),
            style = Amphora.type.monoSmall,
            color = colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp),
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AmphoraButton(
                "回到会话",
                onResume,
                kind = ButtonKind.Primary,
                icon = Icons.Outlined.PlayArrow,
                enabled = !ending,
            )
            AmphoraButton(
                if (ending) "正在结束…" else "结束会话",
                onEnd,
                kind = ButtonKind.Danger,
                icon = Icons.Outlined.PowerSettingsNew,
                enabled = !ending,
            )
        }
    }
}

/** Between "launch" and the session process reporting in. */
@Composable
fun LaunchingBanner(title: String, modifier: Modifier = Modifier) {
    val colors = Amphora.colors
    Panel(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Tone.Accent, pulse = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("SESSION · 准备中", style = Amphora.type.overline, color = colors.accent)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressTrack(null, ticks = false, height = 4.dp)
    }
}

/**
 * The session that just ended. A start that never reached the desktop is a
 * failure and says so; the log is one tap away either way.
 */
@Composable
fun EndedSessionBanner(
    session: EndedSession,
    onOpenLog: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Amphora.colors
    val now by rememberNow(periodMillis = 30_000)
    val failed = !session.reachedDesktop
    Panel(
        modifier.fillMaxWidth(),
        border = if (failed) colors.danger.copy(alpha = 0.3f) else colors.hairline,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            end = 10.dp,
            top = 12.dp,
            bottom = 12.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (failed) Tone.Danger else Tone.Neutral)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (failed) "「${session.title}」没能启动" else "「${session.title}」已结束",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (failed) {
                        "Wine 没显示桌面就退出了，日志里有原因"
                    } else {
                        "运行 ${Formats.clock(session.seconds)} · ${Formats.ago(session.endedAt, now)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) colors.danger else colors.textMuted,
                )
            }
            AmphoraButton(
                "日志",
                onOpenLog,
                kind = ButtonKind.Ghost,
                size = ButtonSize.Compact,
                icon = Icons.AutoMirrored.Outlined.Article,
            )
            Spacer(Modifier.width(6.dp))
            IconAction(Icons.Outlined.Close, "关闭", onDismiss)
        }
    }
}
