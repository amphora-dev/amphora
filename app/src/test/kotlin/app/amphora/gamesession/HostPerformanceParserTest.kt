package app.amphora.gamesession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPerformanceParserTest {
    @Test
    fun parsesAggregateAndPerCoreCpuUsage() {
        val previous =
            HostPerformanceParser.parseCpuTimes(
                """
                cpu  30 0 30 40 0 0 0 0
                cpu0 10 0 10 30 0 0 0 0
                """.trimIndent(),
            )
        val current =
            HostPerformanceParser.parseCpuTimes(
                """
                cpu  60 0 60 80 0 0 0 0
                cpu0 30 0 20 50 0 0 0 0
                """.trimIndent(),
            )

        assertEquals(60, HostPerformanceParser.usagePercent(previous[-1], current[-1]))
        assertEquals(60, HostPerformanceParser.usagePercent(previous[0], current[0]))
    }

    @Test
    fun parsesGpuBusyPairsAndSinglePercentages() {
        assertEquals(25, HostPerformanceParser.parseGpuPercent("250 1000"))
        assertEquals(73, HostPerformanceParser.parseGpuPercent("73"))
        assertEquals(
            73,
            HostPerformanceParser.parseGpuPercent(
                "/sys/class/drm/card0/device/gpu_busy_percent",
                "73 999",
            ),
        )
        assertNull(HostPerformanceParser.parseGpuPercent(""))
    }

    @Test
    fun calculatesMaliCumulativeGpuLoadAcrossSamples() {
        val sampler = GpuLoadSampler()
        val path = "/sys/class/misc/mali0/device/gpuinfo"

        assertNull(sampler.sample(path, "header\nbusy time 1000", nowMs = 2_000L))
        assertEquals(25, sampler.sample(path, "header\nbusy time 1250", nowMs = 3_000L))
    }

    @Test
    fun normalizesHertzAndKilohertzToMegahertz() {
        assertEquals(710, HostPerformanceParser.parseFrequencyMhz("710000000"))
        assertEquals(2419, HostPerformanceParser.parseFrequencyMhz("2419200"))
        assertEquals(800, HostPerformanceParser.parseMaxFrequencyMhz("200000 800000 600000"))
    }

    @Test
    fun parsesProcessMemoryThreadsAndCpuTicks() {
        val status =
            HostPerformanceParser.parseProcessStatus(
                """
                Name: game.exe
                VmRSS: 123456 kB
                Threads: 18
                """.trimIndent(),
            )
        val ticks =
            HostPerformanceParser.parseProcessCpuTicks(
                "123 (game name.exe) S 1 2 3 4 5 6 7 8 9 10 11 12 13",
            )

        assertEquals(123456L, status.residentMemoryKb)
        assertEquals(18, status.threads)
        assertEquals(23L, ticks)
    }

    @Test
    fun normalizesThermalZoneMilliCelsius() {
        assertEquals(58.5f, HostPerformanceParser.parseTemperatureC("58500"))
        assertEquals(35f, HostPerformanceParser.parseTemperatureC("3500"))
        assertEquals(35f, HostPerformanceParser.parseTemperatureC("350"))
        assertNull(HostPerformanceParser.parseTemperatureC("999999"))
    }

    @Test
    fun parsesKernelChildrenPidList() {
        assertEquals(listOf(120, 121, 2048), HostPerformanceParser.parseChildPids("120 121  2048\n"))
    }

    @Test
    fun selectsAllWineSiblingsUnderSessionHost() {
        assertEquals(
            listOf(8357, 8371, 9412),
            HostPerformanceParser.selectSessionGuestPids(
                descendants = listOf(8357, 8371, 8388, 9412),
                winePids = setOf(8357, 8371, 9412, 26528),
                launcherPid = 8357,
            ),
        )
    }
}
