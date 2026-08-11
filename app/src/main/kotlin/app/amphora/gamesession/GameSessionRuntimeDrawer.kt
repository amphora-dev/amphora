package app.amphora.gamesession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amphora.core.engine.model.SessionState
import app.amphora.gamesession.input.TouchpadView

@Composable
internal fun RuntimeSessionDrawer(
    sessionState: SessionState?,
    controlsEnabled: Boolean,
    inputMode: Int,
    onInputModeChange: (Int) -> Unit,
    pointerSensitivity: Float,
    onPointerSensitivityChange: (Float) -> Unit,
    tapToClick: Boolean,
    onTapToClickChange: (Boolean) -> Unit,
    fpsLimit: Int,
    onFpsLimitChange: (Int) -> Unit,
    stretchToFill: Boolean,
    onStretchToFillChange: (Boolean) -> Unit,
    performanceHudVisible: Boolean,
    onPerformanceHudVisibleChange: (Boolean) -> Unit,
    onPauseToggle: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Session controls", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        runtimeStatusLabel(sessionState),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onClose) {
                    Text("Hide controls", maxLines = 2, textAlign = TextAlign.Center)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onPauseToggle,
                    enabled =
                    controlsEnabled &&
                        sessionState in setOf(SessionState.RUNNING, SessionState.PAUSED),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (sessionState == SessionState.PAUSED) "Resume" else "Pause",
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = onExit,
                    enabled = sessionState != SessionState.STOPPING,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Exit Windows", maxLines = 2, textAlign = TextAlign.Center)
                }
            }

            RuntimeDrawerSection(title = "Input") {
                Text("Touch mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = inputMode == TouchpadView.MODE_TRACKPAD,
                        onClick = { onInputModeChange(TouchpadView.MODE_TRACKPAD) },
                        enabled = controlsEnabled,
                        label = { Text("Trackpad") },
                    )
                    FilterChip(
                        selected = inputMode == TouchpadView.MODE_TOUCHSCREEN,
                        onClick = { onInputModeChange(TouchpadView.MODE_TOUCHSCREEN) },
                        enabled = controlsEnabled,
                        label = { Text("Direct touch") },
                    )
                }
                Text(
                    "Pointer speed · ${"%.1f".format(pointerSensitivity)}×",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = pointerSensitivity,
                    onValueChange = onPointerSensitivityChange,
                    enabled = controlsEnabled && inputMode == TouchpadView.MODE_TRACKPAD,
                    valueRange = 0.5f..2f,
                )
                RuntimeToggleRow(
                    title =
                    if (inputMode == TouchpadView.MODE_TRACKPAD) {
                        "Tap to click · ${if (tapToClick) "On" else "Off"}"
                    } else {
                        "Touch click · ${if (tapToClick) "On" else "Off"}"
                    },
                    subtitle = tapGestureDescription(inputMode, tapToClick),
                    checked = tapToClick,
                    enabled = controlsEnabled,
                    onCheckedChange = onTapToClickChange,
                )
            }

            RuntimeDrawerSection(title = "Display") {
                Text("Frame limit", style = MaterialTheme.typography.labelLarge)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FPS_LIMITS.chunked(3).forEach { rowLimits ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowLimits.forEach { limit ->
                                FilterChip(
                                    selected = fpsLimit == limit,
                                    onClick = { onFpsLimitChange(limit) },
                                    enabled = controlsEnabled,
                                    label = { Text(if (limit == 0) "Off" else "$limit") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                RuntimeToggleRow(
                    title = "Stretch to fill",
                    subtitle = "Fill the display instead of preserving aspect ratio",
                    checked = stretchToFill,
                    enabled = controlsEnabled,
                    onCheckedChange = onStretchToFillChange,
                )
                RuntimeToggleRow(
                    title = "Performance overlay",
                    subtitle = "FPS, CPU, GPU, memory and temperature",
                    checked = performanceHudVisible,
                    enabled = controlsEnabled,
                    onCheckedChange = onPerformanceHudVisibleChange,
                )
            }

            Text(
                "Press Back during play to open this panel. A four-finger tap also works in trackpad mode.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RuntimeDrawerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RuntimeToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private fun tapGestureDescription(inputMode: Int, enabled: Boolean): String = when {
    inputMode == TouchpadView.MODE_TOUCHSCREEN && enabled ->
        "Touching the screen positions the cursor and holds left click"
    inputMode == TouchpadView.MODE_TOUCHSCREEN ->
        "Touch positions the cursor only; use a mouse or controller to click"
    enabled -> "One-finger tap: left click\nTwo-finger tap: right click"
    else -> "Touch moves the pointer only; use a mouse or controller to click"
}

private fun runtimeStatusLabel(sessionState: SessionState?): String = when (sessionState) {
    SessionState.CREATED -> "Preparing session"
    SessionState.STARTING -> "Starting Windows session"
    SessionState.RUNNING -> "Running"
    SessionState.PAUSED -> "Paused"
    SessionState.STOPPING -> "Closing session"
    SessionState.STOPPED -> "Session ended"
    SessionState.FAILED -> "Session failed"
    null -> "Preparing session"
}

private val FPS_LIMITS = listOf(0, 30, 45, 60, 90, 120)
