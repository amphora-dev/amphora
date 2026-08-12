package app.amphora.gamesession

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.amphora.gamesession.input.ImeUiState
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.ImeControlOverlay(state: ImeUiState, onToggleKeyboard: () -> Unit, onTypeClipboard: () -> Unit) {
    val paddingPx = with(LocalDensity.current) { IME_OVERLAY_PADDING.toPx() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var overlayX by remember { mutableFloatStateOf(Float.NaN) }
    var overlayY by remember { mutableFloatStateOf(Float.NaN) }

    fun clampPosition(x: Float, y: Float): Offset = Offset(
        x.coerceIn(0f, (containerSize.width - overlaySize.width).coerceAtLeast(0).toFloat()),
        y.coerceIn(0f, (containerSize.height - overlaySize.height).coerceAtLeast(0).toFloat()),
    )

    LaunchedEffect(containerSize, overlaySize) {
        if (containerSize == IntSize.Zero || overlaySize == IntSize.Zero) return@LaunchedEffect
        val position =
            clampPosition(
                if (overlayX.isNaN()) paddingPx else overlayX,
                if (overlayY.isNaN()) paddingPx else overlayY,
            )
        overlayX = position.x
        overlayY = position.y
    }

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .zIndex(3f)
            .onSizeChanged { containerSize = it },
    ) {
        Surface(
            modifier =
            Modifier
                .widthIn(min = 180.dp, max = 330.dp)
                .offset {
                    IntOffset(
                        (if (overlayX.isNaN()) paddingPx else overlayX).roundToInt(),
                        (if (overlayY.isNaN()) paddingPx else overlayY).roundToInt(),
                    )
                }
                .onSizeChanged { overlaySize = it },
            color = Color.Black.copy(alpha = 0.78f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(containerSize, overlaySize) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                val currentX = if (overlayX.isNaN()) paddingPx else overlayX
                                val currentY = if (overlayY.isNaN()) paddingPx else overlayY
                                val next =
                                    clampPosition(
                                        currentX + amount.x,
                                        currentY + amount.y,
                                    )
                                overlayX = next.x
                                overlayY = next.y
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "⠿  IME",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF80CBC4),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.keyboardVisible) "HIDE" else "KEYBOARD",
                        modifier = Modifier.clickable(onClick = onToggleKeyboard),
                        color = Color(0xFF80CBC4),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "CLIPBOARD",
                        modifier = Modifier.clickable(onClick = onTypeClipboard),
                        color = Color(0xFFFFCC80),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (state.composingText.isNotEmpty()) {
                    Text(
                        state.composingText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (state.keyboardVisible) {
                    Text(
                        "Select a candidate to commit text",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private val IME_OVERLAY_PADDING = 12.dp
