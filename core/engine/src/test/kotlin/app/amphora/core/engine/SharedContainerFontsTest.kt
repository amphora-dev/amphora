package app.amphora.core.engine

import com.winlator.cmod.runtime.wine.WineRegistryEditor
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContainerFontsTest {
    @Test
    fun registrySchema_includesWindowsLanguageProfiles() {
        assertEquals(7, SharedContainerFonts.REGISTRY_SCHEMA_VERSION)
    }

    @Test
    fun chineseLocale_usesWinePrimaryLocalizedFamilyName() {
        assertEquals(
            SharedContainerFonts.FONT_FAMILY_CN_LOCALIZED,
            SharedContainerFonts.cnFamilyForLanguage("zh"),
        )
        assertEquals(
            SharedContainerFonts.FONT_FAMILY_CN,
            SharedContainerFonts.cnFamilyForLanguage("en"),
        )
        assertEquals(
            SharedContainerFonts.FONT_FAMILY_CN_LOCALIZED,
            SharedContainerFonts.cnFamilyForLocale("zh_CN.UTF-8"),
        )
    }

    @Test
    fun packFaces_includeCnAndJpRegularBold() {
        val faces = SharedContainerFonts.PACK_FACES
        assertTrue(faces.contains(SharedContainerFonts.CN_REGULAR))
        assertTrue(faces.contains(SharedContainerFonts.CN_BOLD))
        assertTrue(faces.contains(SharedContainerFonts.JP_REGULAR))
        assertTrue(faces.contains(SharedContainerFonts.JP_BOLD))
    }

    @Test
    fun chineseLinks_useCnFaces() {
        val links = SharedContainerFonts.WINDOWS_FONT_LINKS
        assertEquals(SharedContainerFonts.CN_REGULAR, links["msyh.ttc"])
        assertEquals(SharedContainerFonts.CN_BOLD, links["msyhbd.ttc"])
        assertEquals(SharedContainerFonts.CN_REGULAR, links["simsun.ttc"])
        assertEquals(SharedContainerFonts.CN_REGULAR, links["simsunb.ttf"])
        assertEquals(SharedContainerFonts.CN_REGULAR, links["simhei.ttf"])
        assertEquals(SharedContainerFonts.CN_REGULAR, links["deng.ttf"])
        assertEquals(SharedContainerFonts.CN_BOLD, links["dengb.ttf"])
    }

    @Test
    fun japaneseLinks_useJpFaces() {
        val links = SharedContainerFonts.WINDOWS_FONT_LINKS
        assertEquals(SharedContainerFonts.JP_REGULAR, links["msgothic.ttc"])
        assertEquals(SharedContainerFonts.JP_REGULAR, links["meiryo.ttc"])
        assertEquals(SharedContainerFonts.JP_BOLD, links["meiryob.ttc"])
        assertEquals(SharedContainerFonts.JP_REGULAR, links["yugothic.ttf"])
        assertEquals(SharedContainerFonts.JP_BOLD, links["yugothib.ttf"])
    }

    @Test
    fun familySubstitutes_splitCnAndJp() {
        val map = SharedContainerFonts.FAMILY_SUBSTITUTES.toMap()
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["Microsoft YaHei"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["Microsoft YaHei UI Bold"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["微软雅黑"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["SimSun"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["SimSun-ExtB"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["MS Gothic"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["Meiryo"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["Yu Gothic"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["メイリオ"])
    }

    @Test
    fun shellUiAliases_matchWindowsNtDefaults() {
        val cjkSubstitutes = SharedContainerFonts.FAMILY_SUBSTITUTES.toMap()
        assertTrue("MS Shell Dlg" !in cjkSubstitutes)
        assertTrue("MS Shell Dlg 2" !in cjkSubstitutes)
        assertEquals(
            "Microsoft Sans Serif",
            SharedContainerFonts.UI_FAMILY_SUBSTITUTES["MS Shell Dlg"],
        )
        assertEquals("Tahoma", SharedContainerFonts.UI_FAMILY_SUBSTITUTES["MS Shell Dlg 2"])
        assertEquals(
            "Microsoft Sans Serif",
            SharedContainerFonts.uiFamilySubstitutesForLocale("zh_CN.UTF-8")["MS Shell Dlg"],
        )
        assertEquals(
            "MS UI Gothic",
            SharedContainerFonts.uiFamilySubstitutesForLocale("ja_JP.UTF-8")["MS Shell Dlg"],
        )
    }

    @Test
    fun systemFontLinks_coverWindowsUiAndLegacyFamilies() {
        val links = SharedContainerFonts.SYSTEM_FONT_LINKS
        assertTrue(links.contains("Segoe UI"))
        assertTrue(links.contains("Tahoma"))
        assertTrue(links.contains("Arial"))
        assertTrue(links.contains("Microsoft Sans Serif"))
        assertTrue(links.contains("Times New Roman"))
    }

    @Test
    fun fontRegistrations_coverNormalSimplifiedChineseNames() {
        val fonts = SharedContainerFonts.FONT_REGISTRATIONS
        assertEquals(
            SharedContainerFonts.CN_REGULAR,
            fonts["Microsoft YaHei & Microsoft YaHei UI (TrueType)"],
        )
        assertEquals(
            SharedContainerFonts.CN_BOLD,
            fonts["Microsoft YaHei Bold & Microsoft YaHei UI Bold (TrueType)"],
        )
        assertEquals(SharedContainerFonts.CN_REGULAR, fonts["SimSun & NSimSun (TrueType)"])
        assertEquals(SharedContainerFonts.CN_REGULAR, fonts["DengXian (TrueType)"])
    }

    @Test
    fun applyRegistry_switchesChineseAndEnglishFontProfiles() {
        val container = Files.createTempDirectory("amphora-font-profile").toFile()
        val prefix = container.resolve(".wine")
        try {
            assertTrue(prefix.mkdirs())
            prefix.resolve("system.reg").writeText("WINE REGISTRY Version 2\n")
            prefix.resolve("user.reg").writeText("WINE REGISTRY Version 2\n")
            val key = "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes"

            assertTrue(SharedContainerFonts.applyRegistry(container, "zh_CN.UTF-8"))
            WineRegistryEditor(prefix.resolve("system.reg")).use {
                assertEquals(
                    SharedContainerFonts.FONT_FAMILY_CN_LOCALIZED,
                    it.getStringValue(key, "Microsoft YaHei"),
                )
                assertEquals("Tahoma", it.getStringValue(key, "MS Shell Dlg 2"))
            }

            assertTrue(SharedContainerFonts.applyRegistry(container, "en_US.UTF-8"))
            WineRegistryEditor(prefix.resolve("system.reg")).use {
                assertEquals(
                    SharedContainerFonts.FONT_FAMILY_CN,
                    it.getStringValue(key, "Microsoft YaHei"),
                )
                assertEquals(
                    "Microsoft Sans Serif",
                    it.getStringValue(key, "MS Shell Dlg"),
                )
            }
        } finally {
            container.deleteRecursively()
        }
    }

    @Test
    fun registryEditor_writesFontLinkAsMultiString() {
        val registry = Files.createTempFile("amphora-font-link", ".reg").toFile()
        try {
            registry.writeText(
                "WINE REGISTRY Version 2\n\n" +
                    "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontLink\\\\SystemLink] 1\n",
            )
            WineRegistryEditor(registry).use {
                it.setMultiStringValue(
                    "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink",
                    "Tahoma",
                    "SourceHanSansCN-Regular.otf,Source Han Sans CN",
                )
            }

            val output = registry.readText()
            assertTrue(output.contains("\"Tahoma\"=hex(7):"))
            assertTrue(output.contains("53,00,6f,00,75,00,72,00"))
            assertTrue(output.contains("00,00,00,00"))
        } finally {
            registry.delete()
        }
    }

    @Test
    fun registryEditor_skipsEquivalentMultiStringRewrite() {
        val registry = Files.createTempFile("amphora-font-link-idempotent", ".reg").toFile()
        try {
            registry.writeText(
                "WINE REGISTRY Version 2\n\n" +
                    "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontLink\\\\SystemLink] 1\n",
            )
            val key = "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink"
            val fallback = "SourceHanSansCN-Regular.otf,Source Han Sans CN"
            WineRegistryEditor(registry).use {
                it.setMultiStringValue(key, "Tahoma", fallback)
            }
            assertTrue(registry.setLastModified(1_000_000L))
            val stableTimestamp = registry.lastModified()

            WineRegistryEditor(registry).use {
                it.setMultiStringValue(key, "Tahoma", fallback)
            }

            assertEquals(stableTimestamp, registry.lastModified())
        } finally {
            registry.delete()
        }
    }
}
