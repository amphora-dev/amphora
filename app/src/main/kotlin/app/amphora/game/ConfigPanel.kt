package app.amphora.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.amphora.sdk.session.LaunchPreview
import app.amphora.sdk.settings.Availability
import app.amphora.sdk.settings.ConfigEntry
import app.amphora.sdk.settings.SettingsCatalog
import app.amphora.shell.shellQuote
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.Panel
import app.amphora.ui.components.SectionHeader
import app.amphora.ui.components.SectionLabel
import app.amphora.ui.components.Tag
import app.amphora.ui.components.Tone
import app.amphora.ui.theme.Amphora

/**
 * What a launch will run, exactly: the command line, every environment
 * variable with the setting it came from, DLL overrides, and what the user
 * changed that has no effect yet. Built by the same code as the launch.
 */
@Composable
fun EffectiveConfigPanel(
    title: String,
    preview: LaunchPreview,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Amphora.colors
    val config = preview.config
    Panel(modifier.fillMaxWidth()) {
        SectionHeader("生效配置", tag = "Effective config") {
            IconAction(Icons.Outlined.ContentCopy, "复制生效配置", { onCopy(describe(title, preview)) })
        }
        Text(
            "启动时实际使用的命令和环境，和设置页走同一套解析：这里写什么，游戏就拿到什么。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("命令行")
        Spacer(Modifier.height(8.dp))
        ArgvWell(preview.argv, preview.workingDir)

        Spacer(Modifier.height(18.dp))
        SectionLabel("桌面")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(if (config.resolution == "custom") config.customResolution else config.resolution)
            Tag("scale=${config.scale}")
            Tag("dpi=${config.dpi}")
            config.steamAppId?.let { Tag("appid=$it", tone = Tone.Info) }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("环境变量 · ${config.env.size}")
        Spacer(Modifier.height(4.dp))
        config.env.forEach { EntryRow(it) }

        if (config.dllOverrides.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("DLL 覆盖 · ${config.dllOverrides.size}")
            Spacer(Modifier.height(4.dp))
            config.dllOverrides.forEach { EntryRow(it, separator = "=") }
        }

        if (config.rejectedEnv.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("被拒绝 · 引擎变量不能覆盖", color = colors.warn)
            Spacer(Modifier.height(4.dp))
            config.rejectedEnv.forEach { EntryRow(it, struck = true) }
        }

        if (config.inactive.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("改了但还不生效 · ${config.inactive.size}", color = colors.warn)
            Spacer(Modifier.height(6.dp))
            config.inactive.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.spec.doc.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Tag(
                        when (val a = item.availability) {
                            is Availability.NeedsComponent -> "需要 ${a.component.label} · ${a.component.milestone}"
                            is Availability.Planned -> "计划中"
                            Availability.Available -> "可用"
                        },
                        tone = Tone.Warn,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArgvWell(argv: List<String>, workingDir: String) {
    val colors = Amphora.colors
    val shape = MaterialTheme.shapes.small
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.hairline, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.textFaint)) { append("cwd ") }
                    append(workingDir)
                },
                style = Amphora.type.monoSmall,
                color = colors.textMuted,
            )
            argv.forEachIndexed { index, arg ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.textFaint)) { append("[$index] ".padStart(5)) }
                        append(arg)
                    },
                    style = Amphora.type.monoSmall,
                    color = if (index == 0) colors.accent else colors.text,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ConfigEntry, separator: String = "=", struck: Boolean = false) {
    val colors = Amphora.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.name + separator,
                style = Amphora.type.mono,
                color = if (struck) colors.textFaint else colors.text,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Tag(
                entry.origin,
                tone =
                when {
                    entry.fromEngine -> Tone.Neutral
                    entry.origin == SettingsCatalog.ENV -> Tone.Accent
                    else -> Tone.Info
                },
            )
        }
        SelectionContainer {
            Text(
                entry.value.ifEmpty { "（空）" },
                style = Amphora.type.monoSmall,
                color = if (struck) colors.textFaint else colors.textMuted,
            )
        }
    }
}

/** The copyable form: a header, the command as a shell line, then the SDK's own description. */
internal fun describe(title: String, preview: LaunchPreview): String = buildString {
    appendLine("# amphora launch preview: $title")
    appendLine("$ cd ${shellQuote(preview.workingDir)}")
    appendLine("$ " + preview.argv.joinToString(" ") { shellQuote(it) })
    preview.warnings.forEach { appendLine("# warning: $it") }
    append(preview.config.describe())
}
