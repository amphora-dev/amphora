package app.amphora.gamesession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.winlator.cmod.runtime.display.xserver.XServer
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.HostPerformanceOverlay(xServer: XServer) {
    val context = LocalContext.current
    val monitor = remember(xServer) { HostPerformanceMonitor(context, xServer) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    val stats by monitor.stats.collectAsState()
    Surface(
        modifier =
        Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .zIndex(2f),
        color = Color.Black.copy(alpha = 0.76f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "HOST · ALL APIs",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF80CBC4),
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${stats.fps.roundToInt()} FPS  ${frameTimeLabel(stats.fps)}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                buildString {
                    append("CPU ${stats.appCpuPercent}%")
                    stats.gpuPercent?.let { append("  GPU $it%") }
                    append("  RAM ${stats.ramPercent}%")
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                buildString {
                    append("APP ${stats.appMemoryMb} MB")
                    stats.batteryTemperatureC?.let { append("  BAT %.1f°C".format(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun frameTimeLabel(fps: Float): String = if (fps > 0.1f) "%.1f ms".format(1000f / fps) else "-- ms"
