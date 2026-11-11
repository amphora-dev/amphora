package app.amphora.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.Panel
import app.amphora.ui.components.TextInput
import app.amphora.ui.theme.Amphora

/** The frame every dialog shares: a raised panel with a title, a body and right-aligned actions. */
@Composable
fun AmphoraDialog(
    title: String,
    onDismiss: () -> Unit,
    overline: String? = null,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Amphora.colors
    Dialog(onDismissRequest = onDismiss) {
        Panel(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            color = colors.surface,
            border = colors.hairlineStrong,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 16.dp),
        ) {
            if (overline != null) {
                Text(overline.uppercase(), style = Amphora.type.overline, color = colors.textFaint)
                Spacer(Modifier.height(4.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text)
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                actions()
            }
        }
    }
}

/** Asks before doing something that cannot be undone, saying exactly what happens. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    overline: String? = null,
) {
    AmphoraDialog(
        title = title,
        onDismiss = onDismiss,
        overline = overline,
        actions = {
            AmphoraButton("取消", onDismiss, kind = ButtonKind.Ghost)
            AmphoraButton(
                confirmLabel,
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                kind = if (danger) ButtonKind.Danger else ButtonKind.Primary,
            )
        },
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Amphora.colors.textMuted)
    }
}

/** One line of text, validated as it is typed. */
@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    validate: (String) -> String? = { null },
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val error = validate(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        if (error == null) {
            onDismiss()
            onConfirm(text)
        }
    }
    AmphoraDialog(
        title = title,
        onDismiss = onDismiss,
        actions = {
            AmphoraButton("取消", onDismiss, kind = ButtonKind.Ghost)
            AmphoraButton(confirmLabel, onClick = submit, kind = ButtonKind.Primary, enabled = error == null)
        },
    ) {
        Column {
            TextInput(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder,
                error = error.takeIf { text.isNotEmpty() },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus).padding(top = 2.dp),
            )
        }
    }
}
