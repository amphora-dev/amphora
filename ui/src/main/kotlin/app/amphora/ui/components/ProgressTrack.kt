package app.amphora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amphora.ui.theme.Amphora

/**
 * A thin progress track. With a [progress] it fills (springing to new values,
 * so chunky updates still move smoothly); with null it shows a sweeping
 * highlight for work whose size is unknown. Ticks every 10% give the bar the
 * look of an instrument rather than a loading spinner.
 */
@Composable
public fun ProgressTrack(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = Amphora.colors.accent,
    height: Dp = 6.dp,
    ticks: Boolean = true,
) {
    val colors = Amphora.colors
    val animated by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = spring(stiffness = 60f),
        label = "progress",
    )
    val sweep =
        if (progress == null) {
            rememberInfiniteTransition(label = "sweep").animateFloat(
                initialValue = -0.35f,
                targetValue = 1.35f,
                animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                label = "sweep",
            )
        } else {
            null
        }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(colors.surfaceRaised, cornerRadius = radius)
        val s = sweep?.value
        if (s == null) {
            if (animated > 0f) {
                val w = (size.width * animated).coerceAtLeast(size.height)
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color), endX = w),
                    size = Size(w, size.height),
                    cornerRadius = radius,
                )
                // A bright leading edge.
                drawCircle(
                    color.copy(alpha = 0.35f),
                    radius = size.height * 1.1f,
                    center = Offset(
                        w - size.height / 2,
                        size.height / 2,
                    ),
                )
            }
        } else {
            val band = size.width * 0.3f
            val x = size.width * s
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, color, Color.Transparent),
                    startX = x - band,
                    endX =
                    x + band,
                ),
                cornerRadius = radius,
            )
        }
        if (ticks) {
            for (i in 1..9) {
                val x = size.width * i / 10f
                drawRect(
                    colors.canvas.copy(alpha = 0.55f),
                    topLeft = Offset(x - 0.5f, 0f),
                    size = Size(1f, size.height),
                )
            }
        }
    }
}
