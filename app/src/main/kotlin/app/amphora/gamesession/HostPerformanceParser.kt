package app.amphora.gamesession

import kotlin.math.roundToInt

internal data class CpuTimes(val total: Long, val idle: Long)

internal data class ProcessStatus(val residentMemoryKb: Long = 0, val threads: Int = 0)

internal object HostPerformanceParser {
    /**
     * Parses `/proc/stat` CPU rows. Key `-1` is the aggregate `cpu` row; non-negative keys are
     * logical core indices.
     */
    fun parseCpuTimes(raw: String?): Map<Int, CpuTimes> {
        if (raw.isNullOrBlank()) return emptyMap()
        return buildMap {
            raw.lineSequence().forEach { line ->
                val fields = line.trim().split(Regex("\\s+"))
                val label = fields.firstOrNull() ?: return@forEach
                val index =
                    when {
                        label == "cpu" -> -1
                        label.startsWith("cpu") -> label.removePrefix("cpu").toIntOrNull()
                        else -> null
                    } ?: return@forEach
                val values = fields.drop(1).mapNotNull(String::toLongOrNull)
                if (values.size < 4) return@forEach
                val idle = values[3] + values.getOrElse(4) { 0L }
                put(index, CpuTimes(total = values.sum(), idle = idle))
            }
        }
    }

    fun usagePercent(previous: CpuTimes?, current: CpuTimes?): Int? {
        if (previous == null || current == null) return null
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0 || idleDelta < 0) return null
        return ((totalDelta - idleDelta).coerceAtLeast(0) * 100.0 / totalDelta)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun parseGpuPercent(raw: String?): Int? {
        val values =
            Regex("\\d+")
                .findAll(raw.orEmpty())
                .mapNotNull { it.value.toLongOrNull() }
                .toList()
        val percent =
            when {
                values.size >= 2 && values[1] > 0 -> values[0] * 100.0 / values[1]
                values.isNotEmpty() -> values[0].toDouble()
                else -> return null
            }
        return percent.roundToInt().coerceIn(0, 100)
    }

    fun parseFrequencyMhz(raw: String?): Int? {
        val value = Regex("\\d+").find(raw.orEmpty())?.value?.toLongOrNull() ?: return null
        return frequencyToMhz(value)
    }

    fun parseMaxFrequencyMhz(raw: String?): Int? = Regex("\\d+")
        .findAll(raw.orEmpty())
        .mapNotNull { it.value.toLongOrNull() }
        .maxOrNull()
        ?.let(::frequencyToMhz)

    fun parseProcessStatus(raw: String?): ProcessStatus {
        var memoryKb = 0L
        var threads = 0
        raw.orEmpty().lineSequence().forEach { line ->
            when {
                line.startsWith("VmRSS:") ->
                    memoryKb = Regex("\\d+").find(line)?.value?.toLongOrNull() ?: memoryKb
                line.startsWith("Threads:") ->
                    threads = Regex("\\d+").find(line)?.value?.toIntOrNull() ?: threads
            }
        }
        return ProcessStatus(memoryKb, threads)
    }

    fun parseProcessCpuTicks(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val commandEnd = raw.lastIndexOf(')')
        if (commandEnd < 0 || commandEnd + 2 >= raw.length) return null
        val fields = raw.substring(commandEnd + 2).split(' ')
        val userTicks = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val systemTicks = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return userTicks + systemTicks
    }

    fun parseChildPids(raw: String?): List<Int> = raw.orEmpty()
        .split(Regex("\\s+"))
        .mapNotNull(String::toIntOrNull)
        .filter { it > 0 }

    fun parseTemperatureC(raw: String?): Float? {
        val value = Regex("-?\\d+").find(raw.orEmpty())?.value?.toFloatOrNull() ?: return null
        val celsius = if (kotlin.math.abs(value) >= 1_000f) value / 1_000f else value
        return celsius.takeIf { it in -40f..150f }
    }

    private fun frequencyToMhz(value: Long): Int {
        val mhz =
            when {
                value >= 100_000_000L -> value / 1_000_000.0
                value >= 100_000L -> value / 1_000.0
                else -> value.toDouble()
            }
        return mhz.roundToInt().takeIf { it > 0 } ?: 0
    }
}
