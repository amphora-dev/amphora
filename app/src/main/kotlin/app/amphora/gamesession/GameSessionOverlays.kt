package app.amphora.gamesession

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.engine.model.SessionState

@Composable
internal fun SessionEndingOverlay(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Ending session…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Closing Windows processes and releasing runtime resources",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun BoxScope.PausedSessionOverlay() {
    Surface(
        modifier = Modifier.align(Alignment.Center).zIndex(3f),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Session paused", style = MaterialTheme.typography.titleMedium)
            Text(
                "Press Back to open session controls",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun SessionPlaceholder(
    sessionState: SessionState?,
    launchError: String?,
    provisionProgress: ProvisionProgress?,
    waitingForFirstFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    val title =
        when {
            launchError != null -> "Session failed"
            provisionProgress != null -> "Updating content…"
            waitingForFirstFrame -> "Starting Windows…"
            sessionState == SessionState.STARTING -> "Starting session…"
            else -> "Initializing…"
        }
    val detail =
        when {
            launchError != null -> launchError
            provisionProgress != null ->
                listOfNotNull(
                    provisionProgress.stage,
                    provisionProgress.detail.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            waitingForFirstFrame -> "Waiting for the first application frame"
            else -> ""
        }
    val showProgress =
        launchError == null &&
            (
                provisionProgress != null ||
                    waitingForFirstFrame ||
                    sessionState in setOf(SessionState.CREATED, SessionState.STARTING)
                )
    val bytesLabel =
        provisionProgress
            ?.totalBytes
            ?.let { total ->
                "${formatBytes(provisionProgress.bytesDownloaded)} / ${formatBytes(total)}"
            }.orEmpty()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showProgress) {
                    val fraction = provisionProgress?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (bytesLabel.isNotBlank()) {
                        Text(bytesLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExitSessionConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit Windows session?") },
        text = { Text("The Windows program and all processes in this session will be closed.") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Exit Windows")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
