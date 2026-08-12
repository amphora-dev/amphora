package app.amphora.core.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMetricPathDiscoveryTest {
    @Test
    fun discoversGpuMetricsUnderVendorNamedNodes() {
        val root = Files.createTempDirectory("host-gpu-discovery-").toFile()
        try {
            val gpu = root.resolve("13000000.mali").apply { mkdirs() }
            gpu.resolve("utilisation").writeText("72")
            gpu.resolve("clock").writeText("800000000")
            gpu.resolve("max_freq").writeText("950000000")

            val paths =
                HostMetricPathDiscovery.discoverGpuPaths(
                    roots = listOf(root),
                    includeStatic = false,
                )

            assertTrue(gpu.resolve("utilisation").path in paths.load)
            assertTrue(gpu.resolve("clock").path in paths.currentFrequency)
            assertTrue(gpu.resolve("max_freq").path in paths.maxFrequency)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun filtersAndPrioritizesThermalZonesByType() {
        val root = Files.createTempDirectory("host-thermal-discovery-").toFile()
        try {
            root.resolve("thermal_zone0").apply {
                mkdirs()
                resolve("type").writeText("battery")
                resolve("temp").writeText("310")
            }
            val cpu = root.resolve("thermal_zone1").apply {
                mkdirs()
                resolve("type").writeText("cpu-silicon")
                resolve("temp").writeText("58500")
            }
            val skin = root.resolve("thermal_zone2").apply {
                mkdirs()
                resolve("type").writeText("skin")
                resolve("temp").writeText("35000")
            }

            val paths = HostMetricPathDiscovery.discoverThermalPaths(root)

            assertEquals(listOf(cpu.resolve("temp").path, skin.resolve("temp").path), paths.map { it.path })
            assertEquals(58.5f, HostMetricPathDiscovery.normalizeTemperatureC("58500"))
        } finally {
            root.deleteRecursively()
        }
    }
}
