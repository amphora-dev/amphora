package app.amphora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import app.amphora.ui.theme.Amphora

/**
 * The amphora from the launcher icon, cropped to the vessel: two handles, a
 * body with two cut bands. Same geometry as `ic_launcher_foreground`.
 */
@Composable
public fun AmphoraMark(
    modifier: Modifier = Modifier,
    color: Color = Amphora.colors.accent,
    /** The color of the bands; the surface the mark sits on. */
    cutout: Color = Amphora.colors.canvas,
) {
    val paths =
        remember {
            Triple(
                PathParser().parsePathString(BODY).toPath(),
                PathParser().parsePathString(LEFT_HANDLE).toPath(),
                PathParser().parsePathString(RIGHT_HANDLE).toPath(),
            )
        }
    Canvas(modifier.aspectRatio(VIEW_WIDTH / VIEW_HEIGHT)) {
        val scale = size.width / VIEW_WIDTH
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(-VIEW_LEFT, -VIEW_TOP)
        }) {
            val (body, left, right) = paths
            val stroke = Stroke(width = 2.6f, cap = StrokeCap.Round)
            drawPath(left, color, style = stroke)
            drawPath(right, color, style = stroke)
            clipPath(body) {
                drawRect(color, topLeft = Offset(30f, 28f), size = androidx.compose.ui.geometry.Size(48f, 54f))
                drawRect(cutout, topLeft = Offset(30f, 52.2f), size = androidx.compose.ui.geometry.Size(48f, 1.6f))
                drawRect(cutout, topLeft = Offset(30f, 57.2f), size = androidx.compose.ui.geometry.Size(48f, 1.6f))
            }
        }
    }
}

private const val VIEW_LEFT = 37f
private const val VIEW_TOP = 28.5f
private const val VIEW_WIDTH = 34f
private const val VIEW_HEIGHT = 52f

private const val BODY =
    "M47,30H61V33H58.5V41C58.5,43 70,46 70,56C70,66 60,71 57,76H60V79H48V76H51C48,71 38,66 38,56C38,46 49.5,43 49.5,41V33H47Z"
private const val LEFT_HANDLE = "M49.5,35.5C42.5,35.5 40,40.5 42.8,46.5"
private const val RIGHT_HANDLE = "M58.5,35.5C65.5,35.5 68,40.5 65.2,46.5"
