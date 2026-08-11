package app.amphora.core.engine

import com.winlator.cmod.runtime.wine.WineRegistryEditor
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContainerFontsTest {
    @Test
    fun registrySchema_includesWindowsLanguageProfiles() {
        assertEquals(9, SharedContainerFonts.REGISTRY_SCHEMA_VERSION)
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
    fun nativeWindowsFonts_replaceOnlyBundledFamilies() {
        val substitutes = SharedContainerFonts.familySubstitutes(useNativeWindowsFonts = true).toMap()
        val registrations = SharedContainerFonts.fontRegistrations(useNativeWindowsFonts = true)

        assertTrue("Microsoft YaHei" !in substitutes)
        assertTrue("SimHei" !in substitutes)
        assertEquals(
            SharedContainerFonts.FONT_FAMILY_CN,
            substitutes["Microsoft YaHei Bold"],
        )
        assertEquals("PMingLiU", substitutes["MingLiU"])
        assertEquals(
            SharedContainerFonts.MICROSOFT_YAHEI,
            registrations["Microsoft YaHei & Microsoft YaHei UI (TrueType)"],
        )
        assertEquals(
            SharedContainerFonts.MICROSOFT_SANS_SERIF,
            registrations["Microsoft Sans Serif (TrueType)"],
        )
        assertEquals(SharedContainerFonts.TAHOMA, registrations["Tahoma (TrueType)"])
        assertEquals(
            SharedContainerFonts.CN_BOLD,
            registrations["Microsoft YaHei Bold & Microsoft YaHei UI Bold (TrueType)"],
        )
    }

    @Test
    fun japaneseProfileUsesJapaneseFontLinkFallback() {
        assertEquals(
            "${SharedContainerFonts.JP_REGULAR},${SharedContainerFonts.FONT_FAMILY_JP}",
            SharedContainerFonts.fontLinkFallback(
                "ja_JP.UTF-8",
                useNativeWindowsFonts = true,
            ),
        )
    }

    @Test
    fun fallbackProfileRemovesNativeOnlyManagedLinks() {
        val fontsDir = Files.createTempDirectory("amphora-managed-font-links").toFile()
        try {
            val source = fontsDir.resolve("source.ttf").apply { writeText("font") }
            val nativeOnly = fontsDir.resolve(SharedContainerFonts.TAHOMA)
            val desired = fontsDir.resolve(SharedContainerFonts.CN_REGULAR)
            Files.createSymbolicLink(nativeOnly.toPath(), source.toPath())
            Files.createSymbolicLink(desired.toPath(), source.toPath())

            assertEquals(
                1,
                SharedContainerFonts.removeObsoleteManagedLinks(
                    fontsDir,
                    SharedContainerFonts.WINDOWS_FONT_LINKS.keys,
                ),
            )
            assertFalse(Files.isSymbolicLink(nativeOnly.toPath()))
            assertTrue(Files.isSymbolicLink(desired.toPath()))
        } finally {
            fontsDir.deleteRecursively()
        }
    }

    @Test
    fun fontCacheRequiresCompletionMarkerMatchingSha() {
        val cache = Files.createTempDirectory("amphora-font-complete").toFile()
        try {
            SharedContainerFonts.PACK_FACES.forEach { cache.resolve(it).writeText(it) }
            assertFalse(SharedContainerFonts.packComplete(cache, "abc"))
            cache.resolve(SharedContainerFonts.PACK_COMPLETE_MARKER).writeText("wrong\n")
            assertFalse(SharedContainerFonts.packComplete(cache, "abc"))
            cache.resolve(SharedContainerFonts.PACK_COMPLETE_MARKER).writeText("abc\n")
            assertTrue(SharedContainerFonts.packComplete(cache, "abc"))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun windowsPack_requiresCompleteNativeSet() {
        val cache = Files.createTempDirectory("amphora-windows-font-pack").toFile()
        try {
            assertTrue(!SharedContainerFonts.windowsPackComplete(cache))
            for (face in SharedContainerFonts.WINDOWS_PACK_FACES) {
                cache.resolve(face).writeText(face)
            }
            assertTrue(SharedContainerFonts.windowsPackComplete(cache))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun legacyFontconfigCleanup_removesOnlyManagedLinks() {
        val root = Files.createTempDirectory("amphora-fontconfig-cleanup").toFile()
        try {
            val sharedFonts = root.resolve("contents/FONTS").apply { mkdirs() }
            val sharedFace = sharedFonts.resolve("sha/msyh.ttc").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("font")
            }
            val externalFace = root.resolve("external/custom.ttf").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("font")
            }
            val fontconfigDir = root.resolve("imagefs/usr/share/fonts").apply { mkdirs() }
            val managedLink = fontconfigDir.resolve("msyh.ttc")
            val externalLink = fontconfigDir.resolve("custom.ttf")
            val realFile = fontconfigDir.resolve("local.ttf").apply { writeText("font") }
            Files.createSymbolicLink(managedLink.toPath(), sharedFace.toPath())
            Files.createSymbolicLink(externalLink.toPath(), externalFace.toPath())

            assertEquals(
                1,
                SharedContainerFonts.removeLegacyFontconfigLinks(fontconfigDir, sharedFonts),
            )
            assertFalse(Files.isSymbolicLink(managedLink.toPath()))
            assertTrue(Files.isSymbolicLink(externalLink.toPath()))
            assertTrue(realFile.isFile)
        } finally {
            root.deleteRecursively()
        }
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
    fun applyRegistry_nativeProfileRemovesFallbackReplacement() {
        val container = Files.createTempDirectory("amphora-native-font-profile").toFile()
        val prefix = container.resolve(".wine")
        try {
            assertTrue(prefix.mkdirs())
            prefix.resolve("system.reg").writeText("WINE REGISTRY Version 2\n")
            prefix.resolve("user.reg").writeText("WINE REGISTRY Version 2\n")
            val substitutesKey =
                "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes"
            WineRegistryEditor(prefix.resolve("system.reg")).use {
                it.setStringValue(substitutesKey, "Microsoft YaHei", "Source Han Sans CN")
            }

            assertTrue(
                SharedContainerFonts.applyRegistry(
                    container,
                    "zh_CN.UTF-8",
                    useNativeWindowsFonts = true,
                ),
            )

            WineRegistryEditor(prefix.resolve("system.reg")).use {
                assertNull(it.getStringValue(substitutesKey, "Microsoft YaHei"))
                assertNull(it.getStringValue(substitutesKey, "msyh"))
                assertEquals(
                    SharedContainerFonts.MICROSOFT_YAHEI,
                    it.getStringValue(
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
                        "Microsoft YaHei & Microsoft YaHei UI (TrueType)",
                    ),
                )
                assertEquals(
                    SharedContainerFonts.CN_BOLD,
                    it.getStringValue(
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
                        "Microsoft YaHei Bold & Microsoft YaHei UI Bold (TrueType)",
                    ),
                )
            }

            assertTrue(
                SharedContainerFonts.applyRegistry(
                    container,
                    "zh_CN.UTF-8",
                    useNativeWindowsFonts = false,
                ),
            )
            WineRegistryEditor(prefix.resolve("system.reg")).use {
                val fontsKey = "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
                assertEquals(
                    SharedContainerFonts.FONT_FAMILY_CN_LOCALIZED,
                    it.getStringValue(substitutesKey, "msyh"),
                )
                assertNull(it.getStringValue(fontsKey, "Tahoma (TrueType)"))
                assertEquals(
                    SharedContainerFonts.CN_REGULAR,
                    it.getStringValue(
                        fontsKey,
                        "Microsoft YaHei & Microsoft YaHei UI (TrueType)",
                    ),
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
