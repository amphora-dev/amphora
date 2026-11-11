package app.amphora.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.LocalAppGraph
import app.amphora.sdk.settings.Availability
import app.amphora.sdk.settings.ResolvedSetting
import app.amphora.sdk.settings.SettingGroup
import app.amphora.sdk.settings.SettingLevel
import app.amphora.sdk.settings.SettingType
import app.amphora.sdk.settings.ValueSource
import app.amphora.shell.ConfirmDialog
import app.amphora.shell.Screen
import app.amphora.ui.components.AmphoraSwitch
import app.amphora.ui.components.EmptyState
import app.amphora.ui.components.Hairline
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.Panel
import app.amphora.ui.components.SectionHeader
import app.amphora.ui.components.Segmented
import app.amphora.ui.components.Tag
import app.amphora.ui.components.Tone
import app.amphora.ui.theme.Amphora

/** Global settings when [gameId] is null, otherwise one game's overrides. */
@Composable
fun SettingsScreen(gameId: String?, onBack: (() -> Unit)?) {
    val graph = LocalAppGraph.current
    val model = viewModel { SettingsViewModel(graph, gameId) }
    val ui by model.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf<String?>(null) }

    when (val state = ui) {
        SettingsUi.Loading -> Screen(title = if (gameId == null) "设置" else " ", onBack = onBack) {}
        SettingsUi.GameMissing ->
            Screen(title = "游戏设置", overline = "Game settings", onBack = onBack) {
                item {
                    Panel(Modifier.fillMaxWidth()) { EmptyState(Icons.Outlined.ErrorOutline, "这个游戏不在游戏库里了") }
                }
            }
        is SettingsUi.Ready -> {
            val game = (state.context as? SettingsContext.ForGame)?.game
            Screen(
                title = game?.title ?: "设置",
                overline = if (game != null) "Game settings" else "Settings · 全局",
                subtitle = if (game != null) "只对这个游戏生效；没改的跟随全局设置。" else "所有游戏的默认值，单个游戏可以在它的设置里覆盖。",
                onBack = onBack,
                actions = {
                    if (state.setHere > 0) IconAction(Icons.Outlined.RestartAlt, "全部恢复", { confirmReset = ALL })
                },
            ) { _ ->
                item(key = "level") { LevelBar(state, model::setLevel) }
                state.groups.forEach { group ->
                    if (group.items.isEmpty() && group.hidden == 0) return@forEach
                    item(key = "group:${group.group.name}", contentType = "group") {
                        GroupPanel(
                            group = group,
                            context = state.context,
                            level = state.level,
                            onOpen = { editing = it },
                            onToggle = { id, on ->
                                model.set(id, if (on) SettingType.Toggle.ON else SettingType.Toggle.OFF)
                            },
                            onReset = { confirmReset = group.group.name },
                            onShowMore = { model.setLevel(nextLevel(state.level)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            editing?.let { id ->
                val item = state.item(id)
                if (item != null) {
                    SettingEditorSheet(item, state.context, onSet = {
                        model.set(id, it)
                    }, onDismiss = { editing = null })
                } else {
                    // Out of scope now (a condition changed): nothing to edit.
                    LaunchedEffect(id) { editing = null }
                }
            }
            confirmReset?.let { which ->
                val group = SettingGroup.entries.firstOrNull { it.name == which }
                ConfirmDialog(
                    title = if (group == null) "全部恢复？" else "恢复「${group.title}」？",
                    message =
                    when {
                        game != null && group == null -> "清掉为这个游戏改过的所有设置，全部跟随全局。"
                        game != null -> "清掉这一组里为这个游戏改过的设置，跟随全局。"
                        group == null -> "所有全局设置回到配方默认值。单个游戏的设置不受影响。"
                        else -> "这一组的全局设置回到配方默认值。"
                    },
                    confirmLabel = "恢复",
                    onConfirm = { model.reset(group) },
                    onDismiss = { confirmReset = null },
                )
            }
        }
    }
}

private const val ALL = "*"

private fun nextLevel(level: SettingLevel): SettingLevel = SettingLevel.entries.getOrElse(level.ordinal + 1) { level }

@Composable
private fun LevelBar(state: SettingsUi.Ready, onLevel: (SettingLevel) -> Unit) {
    val colors = Amphora.colors
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Segmented(SettingLevel.entries, state.level, onLevel, label = { it.label })
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            Text("显示 ${state.shown} 项", style = Amphora.type.monoSmall, color = colors.textFaint)
            if (state.setHere > 0) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
                Spacer(Modifier.width(6.dp))
                Text("已改 ${state.setHere}", style = Amphora.type.monoSmall, color = colors.accent)
            }
        }
    }
}

@Composable
private fun GroupPanel(
    group: SettingsGroupUi,
    context: SettingsContext,
    level: SettingLevel,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Amphora.colors
    Panel(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            SectionHeader(group.group.title, tag = group.group.tag) {
                if (group.setHere > 0) {
                    Tag("已改 ${group.setHere}", tone = Tone.Accent)
                    IconAction(Icons.Outlined.RestartAlt, "恢复本组", onReset)
                }
            }
            Text(group.group.summary, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        group.items.forEachIndexed { index, item ->
            if (index > 0) Hairline(Modifier.padding(horizontal = 12.dp))
            SettingRow(item, context, onOpen = { onOpen(item.spec.id) }, onToggle = { onToggle(item.spec.id, it) })
        }
        if (group.hidden > 0) {
            Hairline(Modifier.padding(horizontal = 12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onShowMore)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.UnfoldMore, null, tint = colors.textFaint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "另有 ${group.hidden} 项${nextLevel(level).label}设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    item: ResolvedSetting,
    context: SettingsContext,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val colors = Amphora.colors
    val spec = item.spec
    val setHere = context.setHere(item)
    val available = item.availability == Availability.Available
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "编辑${spec.doc.title}", onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (setHere) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    spec.doc.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (available) colors.text else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (spec.level == SettingLevel.Developer) {
                    Spacer(Modifier.width(8.dp))
                    Tag("dev", tone = Tone.Dev)
                }
            }
            Text(
                spec.doc.summary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(spec.id, style = Amphora.type.monoSmall, color = colors.textFaint)
                when (val a = item.availability) {
                    is Availability.NeedsComponent -> Tag(
                        "需要 ${a.component.label} · ${a.component.milestone}",
                        tone = Tone.Warn,
                    )
                    is Availability.Planned -> Tag("计划中", tone = Tone.Info)
                    Availability.Available -> Unit
                }
                if (item.invalid.isNotEmpty()) Tag("有无效值", tone = Tone.Danger)
            }
            if (item.fixOverridesGlobal) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "内置修正覆盖了你的全局值：${item.fix?.reason.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warn,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (spec.type) {
                SettingType.Toggle -> AmphoraSwitch(item.value == SettingType.Toggle.ON, onToggle)
                else ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val mono = spec.type is SettingType.Text
                        Text(
                            display(item, item.value),
                            style = if (mono) Amphora.type.mono else MaterialTheme.typography.labelLarge,
                            color = if (setHere) colors.accent else colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 168.dp),
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textFaint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
            }
            SourceTag(item, context)
        }
    }
}

@Composable
private fun SourceTag(item: ResolvedSetting, context: SettingsContext) {
    when (item.source) {
        ValueSource.Default -> Unit
        ValueSource.Global -> if (context is SettingsContext.ForGame) Tag("全局", tone = Tone.Info)
        is ValueSource.Fix -> Tag("修正", tone = Tone.Warn)
        ValueSource.Game -> Tag("本游戏", tone = Tone.Accent)
    }
}
