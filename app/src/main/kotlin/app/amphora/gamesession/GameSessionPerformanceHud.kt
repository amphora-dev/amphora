package app.amphora.gamesession

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.amphora.core.engine.GameSessionSurface
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.HostPerformanceOverlay(surface: GameSessionSurface) {
    val context = LocalContext.current
    val monitor =
        remember(surface.xServer, surface.guestGraphicsBackend) {
            HostPerformanceMonitor(
                context = context,
                xServer = surface.xServer,
                configuredBackend = surface.guestGraphicsBackend,
                guestProcessId = surface.guestProcessId,
            )
        }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(monitor, expanded) { monitor.setDetailsEnabled(expanded) }
    val stats by monitor.stats.collectAsState()
    val paddingPx = with(LocalDensity.current) { HUD_PADDING.toPx() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var hudSize by remember { mutableStateOf(IntSize.Zero) }
    var hudX by remember { mutableFloatStateOf(Float.NaN) }
    var hudY by remember { mutableFloatStateOf(Float.NaN) }

    fun defaultX(): Float = (containerSize.width - hudSize.width - paddingPx).coerceAtLeast(0f)

    fun clampPosition(x: Float, y: Float): Offset = Offset(
        x.coerceIn(0f, (containerSize.width - hudSize.width).coerceAtLeast(0).toFloat()),
        y.coerceIn(0f, (containerSize.height - hudSize.height).coerceAtLeast(0).toFloat()),
    )

    LaunchedEffect(containerSize, hudSize) {
        if (containerSize == IntSize.Zero || hudSize == IntSize.Zero) return@LaunchedEffect
        val position =
            clampPosition(
                if (hudX.isNaN()) defaultX() else hudX,
                if (hudY.isNaN()) paddingPx else hudY,
            )
        hudX = position.x
        hudY = position.y
    }

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .zIndex(2f)
            .onSizeChanged { containerSize = it },
    ) {
        Surface(
            modifier =
            Modifier
                .widthIn(
                    min = if (expanded) 330.dp else 235.dp,
                    max = if (expanded) 390.dp else 285.dp,
                )
                .offset {
                    IntOffset(
                        (if (hudX.isNaN()) defaultX() else hudX).roundToInt(),
                        (if (hudY.isNaN()) paddingPx else hudY).roundToInt(),
                    )
                }
                .onSizeChanged { hudSize = it },
            color = Color.Black.copy(alpha = 0.80f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier =
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(containerSize, hudSize) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                val currentX = if (hudX.isNaN()) defaultX() else hudX
                                val currentY = if (hudY.isNaN()) paddingPx else hudY
                                val next =
                                    clampPosition(
                                        currentX + amount.x,
                                        currentY + amount.y,
                                    )
                                hudX = next.x
                                hudY = next.y
                            }
                        },
                ) {
                    Text(
                        "⠿  HOST · ALL APIs",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF80CBC4),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (expanded) "LESS ▲" else "MORE ▼",
                        modifier = Modifier.clickable { expanded = !expanded },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF80CBC4),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "${stats.fps.roundToInt()} FPS  ${frameTimeLabel(stats.fps)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    buildString {
                        append("HOST CPU ${stats.hostCpuPercent}%")
                        stats.gpuPercent?.let { append("  GPU $it%") }
                        append("  RAM ${stats.ramPercent}%")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    buildString {
                        append("HOST ${stats.hostMemoryMb} MB")
                        stats.batteryTemperatureC?.let { append("  BAT %.1f°C".format(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )

                if (expanded) {
                    Spacer(modifier = Modifier.padding(top = 1.dp))
                    HudDivider()
                    MetricLine(
                        "FRAME",
                        buildString {
                            append("P95 ${stats.frameTimeP95Ms?.format(1) ?: "--"} ms")
                            append(" · 1% LOW ${stats.onePercentLowFps?.roundToInt() ?: "--"} FPS")
                        },
                    )
                    MetricLine(
                        "COMPOSITOR",
                        if (stats.gpuTimingSupported) {
                            "GPU ${stats.compositorGpuMs?.format(2) ?: "--"} ms"
                        } else {
                            "GPU timing unsupported"
                        },
                    )
                    MetricLine(
                        "DISPLAY",
                        if (stats.displayTimingSupported) {
                            "${stats.displayFps?.format(1) ?: "--"} FPS" +
                                " · ${stats.presentIntervalMs?.format(2) ?: "--"} ms"
                        } else {
                            "present timing unsupported"
                        },
                    )
                    if (stats.displayTimingSupported) {
                        MetricLine(
                            "PRESENT",
                            "margin ${stats.presentMarginMs?.format(2) ?: "--"} ms" +
                                " · refresh ${stats.refreshCycleMs?.format(2) ?: "--"} ms",
                        )
                    }
                    MetricLine(
                        "CPU",
                        buildString {
                            append("SYS ${stats.systemCpuPercent ?: "--"}%")
                            append(" · GUEST ${stats.guestCpuPercent ?: "--"}%")
                            append(" · HOST ${stats.hostCpuPercent}%")
                        },
                    )
                    CpuCoreGrid(stats.cpuCores)
                    MetricLine(
                        "GPU",
                        buildString {
                            append("${stats.gpuPercent ?: "--"}%")
                            append(" · ${frequencyLabel(stats.gpuCurrentMhz, stats.gpuMaxMhz)}")
                        },
                    )
                    if (stats.cpuHeadroom != null || stats.gpuHeadroom != null) {
                        MetricLine(
                            "HEADROOM",
                            buildString {
                                append("CPU ${stats.cpuHeadroom?.roundToInt() ?: "--"}%")
                                append(" · GPU ${stats.gpuHeadroom?.roundToInt() ?: "--"}%")
                            },
                        )
                    }
                    MetricLine(
                        "MEM",
                        "GUEST RSS ${stats.guestMemoryMb} MB · HOST PSS ${stats.hostMemoryMb} MB",
                    )
                    MetricLine(
                        "RAM",
                        "${stats.availableMemoryMb}/${stats.totalMemoryMb} MB available",
                    )
                    MetricLine(
                        "PROC",
                        "${stats.guestProcessCount} guest · ${stats.guestThreadCount} threads",
                    )
                    MetricLine(
                        "THERM",
                        buildString {
                            append(thermalStatusLabel(stats.thermalStatus))
                            stats.thermalHeadroom?.let { append(" · HEAD ${it.format(2)}") }
                        },
                    )
                    MetricLine(
                        "TEMP",
                        buildString {
                            append("SOC ${stats.socTemperatureC?.format(1) ?: "--"}°C")
                            append(" · BAT ${stats.batteryTemperatureC?.format(1) ?: "--"}°C")
                        },
                    )
                    MetricLine(
                        "POWER",
                        buildString {
                            append(stats.batteryLevelPercent?.let { "$it%" } ?: "--")
                            stats.batteryPowerW?.let { append(" · ${it.format(2)} W") }
                        },
                    )
                    HudDivider()
                    MetricLine("D3D NOW", stats.detectedBackend ?: "not observed")
                    MetricLine("D3D CFG", stats.configuredBackend)
                    MetricLine("VULKAN", surface.graphicsDriver)
                    MetricLine("MODE", surface.presentMode ?: "renderer default")
                    MetricLine(
                        "RES",
                        "${surface.xServer.screenInfo.width}×${surface.xServer.screenInfo.height}",
                    )
                    surface.wineVersion?.let { MetricLine("WINE", it) }
                }
            }
        }
    }
}

@Composable
private fun CpuCoreGrid(cores: List<CpuCoreStats>) {
    cores.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { core ->
                Text(
                    buildString {
                        append("C${core.index} ${core.usagePercent ?: "--"}%")
                        core.currentMhz?.let { append(" ${frequencyShort(it)}") }
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCFD8DC),
                )
            }
            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.widthIn(min = 62.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFFCC80),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun HudDivider() {
    Text(
        "────────────────────────",
        color = Color.White.copy(alpha = 0.28f),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
    )
}

private fun frameTimeLabel(fps: Float): String = if (fps > 0.1f) "%.1f ms".format(1_000f / fps) else "-- ms"

private fun frequencyLabel(currentMhz: Int?, maxMhz: Int?): String = when {
    currentMhz != null && maxMhz != null ->
        "${frequencyShort(currentMhz)}/${frequencyShort(maxMhz)}"
    currentMhz != null -> frequencyShort(currentMhz)
    maxMhz != null -> "max ${frequencyShort(maxMhz)}"
    else -> "-- MHz"
}

private fun frequencyShort(mhz: Int): String = if (mhz >= 1_000) "${(mhz / 1_000f).format(2)}G" else "${mhz}M"

private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

private fun thermalStatusLabel(status: Int?): String = when (status) {
    0 -> "NONE"
    1 -> "LIGHT"
    2 -> "MODERATE"
    3 -> "SEVERE"
    4 -> "CRITICAL"
    5 -> "EMERGENCY"
    6 -> "SHUTDOWN"
    else -> "--"
}

private val HUD_PADDING = 12.dp
