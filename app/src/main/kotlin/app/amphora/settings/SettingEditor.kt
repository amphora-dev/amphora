package app.amphora.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.amphora.sdk.settings.Availability
import app.amphora.sdk.settings.ResolvedSetting
import app.amphora.sdk.settings.SettingType
import app.amphora.sdk.settings.ValueSource
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.Hairline
import app.amphora.ui.components.SectionLabel
import app.amphora.ui.components.Tag
import app.amphora.ui.components.TextInput
import app.amphora.ui.components.Tone
import app.amphora.ui.theme.Amphora

/**
 * One setting, in full: its value, how it got that value (the four layers,
 * D15), why the default is the default, where the value lands, and how to
 * see that it took effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingEditorSheet(
    item: ResolvedSetting,
    context: SettingsContext,
    onSet: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Amphora.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.surface,
        contentColor = colors.text,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.hairlineStrong),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            EditorBody(item, context, onSet, onDismiss)
        }
    }
}

@Composable
private fun EditorBody(
    item: ResolvedSetting,
    context: SettingsContext,
    onSet: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Amphora.colors
    val spec = item.spec
    val setHere = context.setHere(item)

    Text("${spec.group.tag} · ${spec.id}".uppercase(), style = Amphora.type.overline, color = colors.textFaint)
    Spacer(Modifier.height(4.dp))
    Text(spec.doc.title, style = MaterialTheme.typography.titleLarge, color = colors.text)
    Spacer(Modifier.height(4.dp))
    Text(spec.doc.summary, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
    AvailabilityNote(item)
    item.fix?.takeIf { item.source is ValueSource.Fix }?.let { fix ->
        Spacer(Modifier.height(10.dp))
        Note("本游戏有内置修正：${fix.reason}（来源：${fix.source}）", Tone.Warn)
    }

    Spacer(Modifier.height(18.dp))
    when (val type = spec.type) {
        is SettingType.Choice ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                type.options.forEach { option ->
                    OptionRow(
                        label = option.label,
                        detail = option.detail,
                        selected = option.key == item.value,
                        enabled = option.available,
                        marker =
                        when {
                            !option.available -> "尚未提供"
                            option.key == context.inherited(
                                item,
                            ) -> if (context is SettingsContext.ForGame) "继承" else "默认"
                            else -> null
                        },
                        onClick = { onSet(option.key) },
                    )
                }
            }
        SettingType.Toggle ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SettingType.Toggle.ON to "开启", SettingType.Toggle.OFF to "关闭").forEach { (key, label) ->
                    OptionRow(
                        label = label,
                        detail = null,
                        selected = key == item.value,
                        enabled = true,
                        marker = if (key ==
                            context.inherited(item)
                        ) {
                            (if (context is SettingsContext.ForGame) "继承" else "默认")
                        } else {
                            null
                        },
                        onClick = { onSet(key) },
                    )
                }
            }
        is SettingType.Text -> TextEditor(item, type, context, onSet)
    }

    Spacer(Modifier.height(22.dp))
    Layers(item, context)

    Spacer(Modifier.height(20.dp))
    Hairline()
    Spacer(Modifier.height(16.dp))
    Doc("为什么默认是这样", spec.doc.rationale)
    if (spec.targets.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        SectionLabel("落到")
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            spec.targets.forEach { Tag(it) }
        }
    }
    spec.doc.verify?.let {
        Spacer(Modifier.height(14.dp))
        Doc("怎么确认生效", it)
    }
    Spacer(Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag(spec.scope.label)
        Tag(
            spec.level.label,
            tone = if (spec.level ==
                app.amphora.sdk.settings.SettingLevel.Developer
            ) {
                Tone.Dev
            } else {
                Tone.Neutral
            },
        )
    }

    Spacer(Modifier.height(22.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmphoraButton(
            if (context is SettingsContext.ForGame) "跟随全局" else "恢复默认",
            { onSet(null) },
            kind = ButtonKind.Secondary,
            enabled = setHere,
        )
        Spacer(Modifier.weight(1f))
        AmphoraButton("完成", onDismiss, kind = ButtonKind.Primary)
    }
}

@Composable
private fun OptionRow(
    label: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    marker: String?,
    onClick: () -> Unit,
) {
    val colors = Amphora.colors
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(if (selected) colors.accentSoft else colors.surfaceSunken)
            .border(1.dp, if (selected) colors.accent.copy(alpha = 0.5f) else colors.hairline, shape)
            .clickable(enabled = enabled && !selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (marker != null) Tag(marker)
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val colors = Amphora.colors
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) colors.accent else colors.hairlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
    }
}

@Composable
private fun TextEditor(
    item: ResolvedSetting,
    type: SettingType.Text,
    context: SettingsContext,
    onSet: (String?) -> Unit,
) {
    var text by rememberSaveable(item.spec.id) { mutableStateOf(item.value) }
    val error = type.validate(text)
    val changed = text != item.value
    TextInput(
        value = text,
        onValueChange = { text = it },
        placeholder = type.placeholder,
        mono = true,
        singleLine = !type.multiline,
        error = error,
        hint = if (context is SettingsContext.ForGame && context.setHere(item).not()) "现在跟随全局" else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AmphoraButton("保存", { onSet(text) }, kind = ButtonKind.Primary, enabled = changed && error == null)
        if (changed) AmphoraButton("还原", { text = item.value }, kind = ButtonKind.Ghost)
    }
}

/** D15 made visible: each layer's value, the winner marked, overridden ones struck through. */
@Composable
private fun Layers(item: ResolvedSetting, context: SettingsContext) {
    val colors = Amphora.colors
    SectionLabel("取值来源 · 低 → 高")
    Spacer(Modifier.height(8.dp))
    val rows =
        buildList {
            add(Triple("配方默认", item.spec.default, item.source == ValueSource.Default))
            add(Triple("你的全局", item.globalValue, item.source == ValueSource.Global))
            if (context is SettingsContext.ForGame) {
                add(Triple("内置修正", item.fix?.values?.get(item.spec.id), item.source is ValueSource.Fix))
                add(Triple("本游戏", item.gameValue, item.source == ValueSource.Game))
            }
        }
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        rows.forEach { (label, value, wins) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    value?.let { display(item, it) } ?: "—",
                    style = Amphora.type.mono,
                    color =
                    when {
                        wins -> colors.accent
                        value == null -> colors.textFaint
                        else -> colors.textMuted
                    },
                    textDecoration = if (value != null && !wins) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                if (wins) Text("◀ 生效", style = Amphora.type.monoSmall, color = colors.accent)
            }
        }
    }
    if (item.invalid.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Note("存储的值不合法，已忽略：${item.invalid.joinToString { it.label }}", Tone.Danger)
    }
}

@Composable
private fun AvailabilityNote(item: ResolvedSetting) {
    when (val a = item.availability) {
        Availability.Available -> Unit
        is Availability.NeedsComponent -> {
            Spacer(Modifier.height(10.dp))
            Note("需要 ${a.component.label}（${a.component.milestone}）：当前配方没有它，值会保存，但现在不起作用。", Tone.Warn)
        }
        is Availability.Planned -> {
            Spacer(Modifier.height(10.dp))
            Note("还没实现：${a.note}。值会保存，实现后生效。", Tone.Info)
        }
    }
}

@Composable
private fun Note(text: String, tone: Tone) {
    val colors = Amphora.colors
    val color =
        when (tone) {
            Tone.Warn -> colors.warn
            Tone.Danger -> colors.danger
            Tone.Info -> colors.info
            else -> colors.textMuted
        }
    val shape = MaterialTheme.shapes.small
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.22f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun Doc(label: String, text: String) {
    SectionLabel(label)
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Amphora.colors.textMuted)
}

/** A value as the user reads it: the option's label, 开/关, or the raw text. */
fun display(item: ResolvedSetting, value: String): String = when (val type = item.spec.type) {
    is SettingType.Choice -> type.option(value)?.label ?: value
    SettingType.Toggle -> if (value == SettingType.Toggle.ON) "开" else "关"
    is SettingType.Text -> value.ifEmpty { "（空）" }
}
