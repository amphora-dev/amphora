package com.winlator.cmod.runtime.wine

import com.winlator.cmod.runtime.container.Container
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class WineServiceStartupProfileTest {
    @Test
    fun standardProfileKeepsMountManagerAndNdisEnabledAcrossControlSets() {
        val fixture = fixture()
        try {
            WineUtils.applyServiceStartupProfile(fixture.container, "0")

            assertStarts(fixture.systemReg, "MountMgr", 2)
            assertStarts(fixture.systemReg, "Ndis", 2)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun aggressiveProfileDisablesNdisButKeepsMountManagerEnabled() {
        val fixture = fixture()
        try {
            WineUtils.applyServiceStartupProfile(fixture.container, "2")

            assertStarts(fixture.systemReg, "MountMgr", 2)
            assertStarts(fixture.systemReg, "Ndis", 4)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("wine-services-").toFile()
        val wineDir = File(root, ".wine").apply { mkdirs() }
        val systemReg = File(wineDir, "system.reg").apply {
            writeText("WINE REGISTRY Version 2\n")
        }
        WineRegistryEditor(systemReg).use { editor ->
            CONTROL_SETS.forEach { controlSet ->
                listOf("MountMgr", "Ndis").forEach { service ->
                    editor.setDwordValue("$controlSet\\Services\\$service", "Start", 3)
                }
            }
        }
        return Fixture(
            root = root,
            systemReg = systemReg,
            container = Container(1).apply { rootDir = root },
        )
    }

    private fun assertStarts(systemReg: File, service: String, expected: Int) {
        WineRegistryEditor(systemReg).use { editor ->
            CONTROL_SETS.forEach { controlSet ->
                assertEquals(
                    "$controlSet/$service",
                    expected,
                    editor.getDwordValue("$controlSet\\Services\\$service"),
                )
            }
        }
    }

    private data class Fixture(val root: File, val systemReg: File, val container: Container)

    private companion object {
        val CONTROL_SETS =
            listOf(
                "System\\CurrentControlSet",
                "System\\ControlSet001",
                "System\\ControlSet002",
            )
    }
}
