package app.amphora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amphora.ui.theme.Amphora
import app.amphora.ui.theme.MonoFamily
import kotlin.random.Random

/**
 * Cover art for a game we have no artwork for: a pattern and palette derived
 * from [seed] (stable per game), with the title's monogram. Two games never
 * look alike by accident, and the same game always looks the same.
 */
@Composable
public fun GameCover(seed: String, title: String, modifier: Modifier = Modifier, label: String? = null) {
    val colors = Amphora.colors
    val art = remember(seed) { CoverArt.from(seed) }
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier =
        modifier
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.hairline, shape),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawArt(art) }
        Text(
            text = monogram(title),
            color = Color.White.copy(alpha = 0.92f),
            fontFamily = MonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        if (label != null) {
            Text(
                label.uppercase(),
                style = Amphora.type.overline,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}

/** First letters of the first two words, or the first character of CJK titles. */
internal fun monogram(title: String): String {
    val words = title.trim().split(Regex("[\\s_\\-.:]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words[0]
    if (Character.isIdeographic(first.codePointAt(0))) return String(Character.toChars(first.codePointAt(0)))
    val letters = words.take(2).map { it.first().uppercaseChar() }
    return letters.joinToString("")
}

@Immutable
internal data class CoverArt(val from: Color, val to: Color, val line: Color, val pattern: Int, val seed: Long) {
    companion object {
        // Muted pairs that sit well on graphite; no pure saturated hues.
        private val palettes =
            listOf(
                Color(0xFF8A3B24) to Color(0xFF2A1612), // clay
                Color(0xFF1F6F66) to Color(0xFF0F2422), // verdigris
                Color(0xFF3E4F8F) to Color(0xFF141A2E), // cobalt
                Color(0xFF6B4A8C) to Color(0xFF1E1528), // amethyst
                Color(0xFF8C6A2F) to Color(0xFF2A2112), // ochre
                Color(0xFF4E6B3A) to Color(0xFF172113), // moss
                Color(0xFF7A3550) to Color(0xFF241019), // garnet
                Color(0xFF3F5D6E) to Color(0xFF131C21), // slate
            )

        fun from(seed: String): CoverArt {
            val h = hash(seed)
            val (a, b) = palettes[((h ushr 8) % palettes.size).toInt()]
            return CoverArt(from = a, to = b, line = Color.White, pattern = ((h ushr 24) % 4).toInt(), seed = h)
        }

        /**
         * FNV-1a, finished with murmur3's fmix64: FNV alone mixes the last
         * bytes poorly into the high bits, so "Game 1" and "Game 2" would often
         * share a pattern.
         */
        private fun hash(s: String): Long {
            var h = 0xcbf29ce484222325uL
            for (byte in s.encodeToByteArray()) {
                h = h xor byte.toUByte().toULong()
                h *= 0x100000001b3uL
            }
            h = (h xor (h shr 33)) * 0xff51afd7ed558ccduL
            h = (h xor (h shr 33)) * 0xc4ceb9fe1a85ec53uL
            h = h xor (h shr 33)
            return (h and 0x7fffffffffffffffuL).toLong()
        }
    }
}

private fun DrawScope.drawArt(art: CoverArt) {
    drawRect(
        Brush.linearGradient(
            listOf(art.from, art.to),
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.6f, size.height),
        ),
    )
    val random = Random(art.seed)
    val stroke = Stroke(width = 1.dp.toPx())
    val line = art.line.copy(alpha = 0.16f)
    when (art.pattern) {
        // Contours around an off-canvas center.
        0 -> {
            val c = Offset(size.width * random.nextFloat(), size.height * (0.15f + 0.3f * random.nextFloat()))
            val step = size.minDimension / (7 + random.nextInt(5))
            var r = step
            while (r < size.maxDimension * 1.4f) {
                drawCircle(line, radius = r, center = c, style = stroke)
                r += step
            }
        }
        // Hatching at a per-game angle.
        1 -> {
            val step = size.width / (9 + random.nextInt(8))
            val slant = size.height * (0.3f + 0.6f * random.nextFloat())
            var x = -slant
            while (x < size.width + slant) {
                drawLine(line, Offset(x, 0f), Offset(x + slant, size.height), strokeWidth = stroke.width)
                x += step
            }
        }
        // Dot matrix with a few lit cells.
        2 -> {
            val cols = 8 + random.nextInt(4)
            val step = size.width / cols
            val rows = (size.height / step).toInt() + 1
            for (row in 0..rows) {
                for (col in 0..cols) {
                    val lit = random.nextInt(11) == 0
                    drawCircle(
                        if (lit) art.line.copy(alpha = 0.55f) else line,
                        radius = if (lit) step * 0.14f else step * 0.06f,
                        center = Offset(col * step + step / 2, row * step + step / 2),
                    )
                }
            }
        }
        // Bands, like the painted rings on an amphora.
        else -> {
            var y = size.height * 0.08f
            while (y < size.height) {
                val h = size.height * (0.004f + 0.03f * random.nextFloat())
                drawRect(line, topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, h))
                y += h + size.height * (0.02f + 0.08f * random.nextFloat())
            }
        }
    }
    // Shade toward the bottom so the monogram always reads.
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
            startY =
            size.height * 0.45f,
        ),
    )
}
