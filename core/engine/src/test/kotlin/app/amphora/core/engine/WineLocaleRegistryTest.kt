package app.amphora.core.engine

import com.winlator.cmod.runtime.wine.WineRegistryEditor
import com.winlator.cmod.runtime.wine.WineUtils
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineLocaleRegistryTest {
    @Test
    fun localeProfile_switchesCodepageAndWindowsLocale() {
        val container = Files.createTempDirectory("amphora-windows-locale").toFile()
        val prefix = container.resolve(".wine")
        try {
            assertTrue(prefix.mkdirs())
            prefix.resolve("system.reg").writeText("WINE REGISTRY Version 2\n")
            prefix.resolve("user.reg").writeText("WINE REGISTRY Version 2\n")

            assertTrue(WineUtils.applyLocaleToPrefix(container, "zh_CN.UTF-8"))
            assertLocaleRegistry(prefix, "936", "0804", "zh-CN")

            assertTrue(WineUtils.applyLocaleToPrefix(container, "en_US.UTF-8"))
            assertLocaleRegistry(prefix, "1252", "0409", "en-US")
        } finally {
            container.deleteRecursively()
        }
    }

    private fun assertLocaleRegistry(
        prefix: java.io.File,
        expectedCodepage: String,
        expectedLcid: String,
        expectedLocaleName: String,
    ) {
        WineRegistryEditor(prefix.resolve("system.reg")).use {
            assertEquals(
                expectedCodepage,
                it.getStringValue(
                    "System\\CurrentControlSet\\Control\\Nls\\Codepage",
                    "ACP",
                ),
            )
            assertEquals(
                expectedLcid,
                it.getStringValue(
                    "System\\CurrentControlSet\\Control\\Nls\\Language",
                    "Default",
                ),
            )
        }
        WineRegistryEditor(prefix.resolve("user.reg")).use {
            assertEquals(
                expectedLocaleName,
                it.getStringValue("Control Panel\\International", "LocaleName"),
            )
        }
    }
}
