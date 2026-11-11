package app.amphora.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import app.amphora.ui.theme.Amphora
import kotlin.random.Random

/**
 * The canvas behind every screen: graphite with a faint film grain and a warm
 * glow in the top corner, so large dark areas read as a material instead of a
 * flat fill. The grain is generated once (fixed seed) and tiled.
 */
@Composable
public fun Backdrop(
    modifier: Modifier = Modifier,
    glow: Color = Amphora.colors.accent,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Amphora.colors
    val grain = remember { ShaderBrush(ImageShader(grainBitmap(), TileMode.Repeated, TileMode.Repeated)) }
    Box(
        modifier =
        modifier
            .background(colors.canvas)
            .drawBehind {
                val r = size.maxDimension
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.11f), Color.Transparent),
                        center = Offset(size.width * 0.08f, -size.height * 0.05f),
                        radius = r * 0.75f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(colors.info.copy(alpha = 0.035f), Color.Transparent),
                        center = Offset(size.width * 1.05f, size.height * 1.1f),
                        radius = r * 0.8f,
                    ),
                )
                drawRect(grain)
            },
        content = content,
    )
}

private fun grainBitmap(size: Int = 144): ImageBitmap {
    val random = Random(0x414D50)
    val pixels =
        IntArray(size * size) {
            val alpha = random.nextInt(0, 11)
            val shade = if (random.nextBoolean()) 0xFF else 0x00
            (alpha shl 24) or (shade shl 16) or (shade shl 8) or shade
        }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
