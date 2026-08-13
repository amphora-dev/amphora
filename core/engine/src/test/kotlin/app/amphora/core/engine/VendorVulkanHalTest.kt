package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorVulkanHalTest {
    @Test
    fun prefersTheHalNamedAfterABuildHint() {
        withHalDir { dir ->
            dir.stage("vulkan.default.so", "vulkan.kirin990.so", "vulkan.mali.so")

            assertEquals(
                File(dir, "vulkan.kirin990.so"),
                VendorVulkanHal.find(listOf(dir), listOf("kirin990", "hi3690")),
            )
        }
    }

    @Test
    fun fallsBackToTheNamedHalWhenNoHintMatches() {
        withHalDir { dir ->
            dir.stage("vulkan.default.so", "vulkan.exynos9820.so")

            assertEquals(
                File(dir, "vulkan.exynos9820.so"),
                VendorVulkanHal.find(listOf(dir), listOf("universal9820")),
            )
        }
    }

    @Test
    fun acceptsDefaultHalWhenItIsTheOnlyOne() {
        withHalDir { dir ->
            dir.stage("vulkan.default.so")

            assertEquals(File(dir, "vulkan.default.so"), VendorVulkanHal.find(listOf(dir), listOf("goldfish")))
        }
    }

    @Test
    fun searchesLaterDirectoriesWhenTheFirstHasNoHal() {
        withHalDir { vendor ->
            withHalDir { system ->
                vendor.stage("gralloc.default.so")
                system.stage("vulkan.mali.so")

                assertEquals(
                    File(system, "vulkan.mali.so"),
                    VendorVulkanHal.find(listOf(vendor, system), listOf("mt6893")),
                )
            }
        }
    }

    @Test
    fun reportsNoHalOnDevicesThatShipNone() {
        withHalDir { dir ->
            dir.stage("gralloc.default.so", "vulkan.so")

            assertNull(VendorVulkanHal.find(listOf(dir, File("/nonexistent")), listOf("ranchu")))
        }
    }

    private fun File.stage(vararg names: String) {
        for (name in names) resolve(name).writeBytes(byteArrayOf(0x7f, 'E'.code.toByte()))
    }

    private inline fun withHalDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("vendor-vulkan-hal-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
