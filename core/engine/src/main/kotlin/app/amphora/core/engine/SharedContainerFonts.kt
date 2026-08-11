package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.RuntimeAssetProvisioner
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.SharedDllLinker
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import java.io.File
import java.nio.file.Files

/**
 * Shared CJK font pack: Adobe Source Han Sans **CN + JP** (Regular + Bold),
 * linked into each Wine prefix like DXVK/ddraw (`contents/` + symlink), plus
 * Windows-style registry substitutes.
 *
 * Layout:
 * - `runtime-assets/fonts.tzst` (SHA-pinned)
 * - extract once → `filesDir/contents/FONTS/<sha>/`
 *     SourceHanSansCN-Regular.otf / SourceHanSansCN-Bold.otf
 *     SourceHanSansJP-Regular.otf / SourceHanSansJP-Bold.otf
 * - per container: `windows/Fonts/{msyh,msgothic,meiryo,…}` → region-appropriate face
 * - per container: `system.reg` FontSubstitutes + `user.reg` Wine Fonts\Replacements
 *
 * Chinese aliases → CN; Japanese aliases → JP. Bold Windows names → Bold OTFs.
 */
object SharedContainerFonts {
    const val ASSET_PATH = "fonts.tzst"
    const val REGISTRY_SCHEMA_VERSION = 6

    const val CN_REGULAR = "SourceHanSansCN-Regular.otf"
    const val CN_BOLD = "SourceHanSansCN-Bold.otf"
    const val JP_REGULAR = "SourceHanSansJP-Regular.otf"
    const val JP_BOLD = "SourceHanSansJP-Bold.otf"

    internal const val SYSTEM_FONT_SETTINGS_KEY =
        "System\\ControlSet001\\Hardware Profiles\\Current\\Software\\Fonts"
    internal const val PREVIOUS_SYSTEM_FONT_OVERRIDE = "tahoma.ttf"
    internal const val WINE_DEFAULT_SYSTEM_FONT = "svgasys.fon"

    /** Primary face kept for callers that only know the old single-file name. */
    const val FONT_FILE_NAME = CN_REGULAR

    const val FONT_FAMILY_CN = "Source Han Sans CN"
    const val FONT_FAMILY_CN_LOCALIZED = "思源黑体 CN"
    const val FONT_FAMILY_JP = "Source Han Sans JP"

    /** @deprecated use [FONT_FAMILY_CN] */
    const val FONT_FAMILY = FONT_FAMILY_CN

    internal fun cnFamilyForLanguage(language: String?): String =
        if (language.equals("zh", ignoreCase = true)) FONT_FAMILY_CN_LOCALIZED else FONT_FAMILY_CN

    internal fun languageForLocale(locale: String?): String = locale
        ?.substringBefore('.')
        ?.substringBefore('_')
        ?.substringBefore('-')
        ?.lowercase()
        .orEmpty()

    internal fun cnFamilyForLocale(locale: String?): String = cnFamilyForLanguage(languageForLocale(locale))

    private const val TAG = "SharedContainerFonts"
    private const val CONTENTS_TYPE = "FONTS"

    /** Required faces inside fonts.tzst / contents cache. */
    val PACK_FACES: List<String> = listOf(CN_REGULAR, CN_BOLD, JP_REGULAR, JP_BOLD)

    /**
     * Windows Fonts file name → pack face file name under contents/FONTS/<sha>/.
     * Bold variants map to Bold OTFs; everything else uses Regular of the region.
     */
    val WINDOWS_FONT_LINKS: Map<String, String> =
        linkedMapOf(
            // --- pack faces themselves (so apps can request Source Han by file) ---
            CN_REGULAR to CN_REGULAR,
            CN_BOLD to CN_BOLD,
            JP_REGULAR to JP_REGULAR,
            JP_BOLD to JP_BOLD,
            // --- Simplified Chinese (CN) ---
            "msyh.ttc" to CN_REGULAR,
            "msyhbd.ttc" to CN_BOLD,
            "msyhl.ttc" to CN_REGULAR,
            "simsun.ttc" to CN_REGULAR,
            "simsunb.ttf" to CN_REGULAR,
            "nsimsun.ttc" to CN_REGULAR,
            "simhei.ttf" to CN_REGULAR,
            "simkai.ttf" to CN_REGULAR,
            "simfang.ttf" to CN_REGULAR,
            "simli.ttf" to CN_REGULAR,
            "deng.ttf" to CN_REGULAR,
            "dengb.ttf" to CN_BOLD,
            "dengl.ttf" to CN_REGULAR,
            "dengxian.ttf" to CN_REGULAR,
            "dengxianb.ttf" to CN_BOLD,
            "youyuan.ttf" to CN_REGULAR,
            // --- Traditional Chinese (still CN face; better than missing) ---
            "mingliu.ttc" to CN_REGULAR,
            "mingliub.ttc" to CN_BOLD,
            "pmingliu.ttc" to CN_REGULAR,
            "msjh.ttc" to CN_REGULAR,
            "msjhbd.ttc" to CN_BOLD,
            "msjhl.ttc" to CN_REGULAR,
            // --- Japanese (JP) ---
            "msgothic.ttc" to JP_REGULAR,
            "msmincho.ttc" to JP_REGULAR,
            "meiryo.ttc" to JP_REGULAR,
            "meiryob.ttc" to JP_BOLD,
            "yugothic.ttf" to JP_REGULAR,
            "yugothib.ttf" to JP_BOLD,
            "yugothil.ttf" to JP_REGULAR,
            "yugothir.ttf" to JP_REGULAR,
            "yumin.ttf" to JP_REGULAR,
            "yumindb.ttf" to JP_BOLD,
            // --- Korean (no KR face yet; JP/CN both cover Hangul poorly —
            // prefer CN for CJK ideographs + Hangul in Source Han CN subset is limited;
            // map to JP Regular as a best-effort shared pan-CJK sans) ---
            "malgun.ttf" to JP_REGULAR,
            "malgunbd.ttf" to JP_BOLD,
            "gulim.ttc" to JP_REGULAR,
            "batang.ttc" to JP_REGULAR,
        )

    /**
     * Requested Windows family → Source Han family (CN or JP).
     * Written to HKLM FontSubstitutes and HKCU Wine Fonts\Replacements.
     */
    val FAMILY_SUBSTITUTES: List<Pair<String, String>> =
        listOf(
            // --- Chinese ---
            "Microsoft YaHei" to FONT_FAMILY_CN,
            "Microsoft YaHei Bold" to FONT_FAMILY_CN,
            "Microsoft YaHei Light" to FONT_FAMILY_CN,
            "Microsoft YaHei UI" to FONT_FAMILY_CN,
            "Microsoft YaHei UI Bold" to FONT_FAMILY_CN,
            "Microsoft YaHei UI Light" to FONT_FAMILY_CN,
            "微软雅黑" to FONT_FAMILY_CN,
            "Microsoft JhengHei" to FONT_FAMILY_CN,
            "Microsoft JhengHei Bold" to FONT_FAMILY_CN,
            "Microsoft JhengHei Light" to FONT_FAMILY_CN,
            "Microsoft JhengHei UI" to FONT_FAMILY_CN,
            "Microsoft JhengHei UI Bold" to FONT_FAMILY_CN,
            "Microsoft JhengHei UI Light" to FONT_FAMILY_CN,
            "微軟正黑體" to FONT_FAMILY_CN,
            "SimSun" to FONT_FAMILY_CN,
            "SimSun-ExtB" to FONT_FAMILY_CN,
            "NSimSun" to FONT_FAMILY_CN,
            "宋体" to FONT_FAMILY_CN,
            "新宋体" to FONT_FAMILY_CN,
            "SimHei" to FONT_FAMILY_CN,
            "黑体" to FONT_FAMILY_CN,
            "KaiTi" to FONT_FAMILY_CN,
            "楷体" to FONT_FAMILY_CN,
            "FangSong" to FONT_FAMILY_CN,
            "仿宋" to FONT_FAMILY_CN,
            "DengXian" to FONT_FAMILY_CN,
            "DengXian Bold" to FONT_FAMILY_CN,
            "DengXian Light" to FONT_FAMILY_CN,
            "等线" to FONT_FAMILY_CN,
            "YouYuan" to FONT_FAMILY_CN,
            "幼圆" to FONT_FAMILY_CN,
            "PMingLiU" to FONT_FAMILY_CN,
            "MingLiU" to FONT_FAMILY_CN,
            // --- Japanese ---
            "MS Gothic" to FONT_FAMILY_JP,
            "MS PGothic" to FONT_FAMILY_JP,
            "MS UI Gothic" to FONT_FAMILY_JP,
            "ＭＳ ゴシック" to FONT_FAMILY_JP,
            "ＭＳ Ｐゴシック" to FONT_FAMILY_JP,
            "MS Mincho" to FONT_FAMILY_JP,
            "MS PMincho" to FONT_FAMILY_JP,
            "ＭＳ 明朝" to FONT_FAMILY_JP,
            "ＭＳ Ｐ明朝" to FONT_FAMILY_JP,
            "Yu Gothic" to FONT_FAMILY_JP,
            "Yu Gothic UI" to FONT_FAMILY_JP,
            "Yu Mincho" to FONT_FAMILY_JP,
            "游ゴシック" to FONT_FAMILY_JP,
            "游ゴシック UI" to FONT_FAMILY_JP,
            "游明朝" to FONT_FAMILY_JP,
            "Meiryo" to FONT_FAMILY_JP,
            "Meiryo UI" to FONT_FAMILY_JP,
            "メイリオ" to FONT_FAMILY_JP,
            "BIZ UDGothic" to FONT_FAMILY_JP,
            "BIZ UDPGothic" to FONT_FAMILY_JP,
            "BIZ UDMincho" to FONT_FAMILY_JP,
            // --- Korean (best-effort) ---
            "Malgun Gothic" to FONT_FAMILY_JP,
            "Gulim" to FONT_FAMILY_JP,
            "Batang" to FONT_FAMILY_JP,
        )

    /** Windows NT-family logical shell font defaults. */
    val UI_FAMILY_SUBSTITUTES: Map<String, String> =
        linkedMapOf(
            "MS Shell Dlg" to "Microsoft Sans Serif",
            "MS Shell Dlg 2" to "Tahoma",
        )

    internal fun uiFamilySubstitutesForLocale(locale: String?): Map<String, String> =
        if (languageForLocale(locale) == "ja") {
            UI_FAMILY_SUBSTITUTES + ("MS Shell Dlg" to "MS UI Gothic")
        } else {
            UI_FAMILY_SUBSTITUTES
        }

    /**
     * Windows' normal Latin UI fonts rely on FontLink for CJK glyphs. Replacing
     * these families outright changes Latin metrics; linking preserves their
     * original face and uses Source Han only for missing Chinese characters.
     */
    val SYSTEM_FONT_LINKS: List<String> =
        listOf(
            "Segoe UI",
            "Segoe UI Light",
            "Segoe UI Semibold",
            "Tahoma",
            "Arial",
            "Arial Unicode MS",
            "Microsoft Sans Serif",
            "MS Sans Serif",
            "Lucida Sans Unicode",
            "Verdana",
            "Times New Roman",
            "Courier New",
            "System",
        )

    /** Font-file registration names commonly queried by Windows applications. */
    val FONT_REGISTRATIONS: Map<String, String> =
        linkedMapOf(
            "Source Han Sans CN Regular (OpenType)" to CN_REGULAR,
            "Source Han Sans CN Bold (OpenType)" to CN_BOLD,
            "Source Han Sans JP Regular (OpenType)" to JP_REGULAR,
            "Source Han Sans JP Bold (OpenType)" to JP_BOLD,
            // Register aliases against the canonical pack file. Wine de-duplicates
            // identical faces, so registering an alias file first (for example
            // dengl.ttf) makes later FontLink lookups by CN_REGULAR fail even
            // though both paths point to the same OTF.
            "Microsoft YaHei & Microsoft YaHei UI (TrueType)" to CN_REGULAR,
            "Microsoft YaHei Bold & Microsoft YaHei UI Bold (TrueType)" to CN_BOLD,
            "Microsoft YaHei Light & Microsoft YaHei UI Light (TrueType)" to CN_REGULAR,
            "SimSun & NSimSun (TrueType)" to CN_REGULAR,
            "SimSun-ExtB (TrueType)" to CN_REGULAR,
            "DengXian (TrueType)" to CN_REGULAR,
            "DengXian Bold (TrueType)" to CN_BOLD,
            "DengXian Light (TrueType)" to CN_REGULAR,
        )

    /**
     * Install shared font links + registry into [containerRoot] (the WinNative
     * container dir that contains `.wine/`).
     */
    fun ensureInstalled(
        context: Context,
        containerRoot: File,
        applyRegistry: Boolean = true,
        registryLocale: String = WineLocalePreferences.resolve(context),
    ): Boolean {
        val cacheDir =
            ensureSharedPack(context) ?: run {
                Log.w(TAG, "Shared font package missing; CJK may fall back to Wine defaults")
                return false
            }

        val fontsDir = File(containerRoot, ".wine/drive_c/windows/Fonts").apply { mkdirs() }
        val contentsRoot = ContentsManager.getContentDir(context)
        var linked = 0
        for ((windowsName, faceName) in WINDOWS_FONT_LINKS) {
            val source = File(cacheDir, faceName)
            if (!source.isFile) {
                Log.w(TAG, "Pack face missing for $windowsName: $source")
                continue
            }
            val target = File(fontsDir, windowsName)
            if (isLinkTo(target, source)) {
                linked++
                continue
            }
            if (SharedDllLinker.link(contentsRoot, source, target)) {
                linked++
            } else {
                Log.w(TAG, "Failed to link $windowsName -> $source")
            }
        }

        val registryOk = !applyRegistry || applyRegistry(containerRoot, registryLocale)

        val primary = File(fontsDir, CN_REGULAR)
        val primaryOk = primary.isFile || Files.isSymbolicLink(primary.toPath())
        val fontconfigOk =
            WineUtils.syncFontsToFontconfig(
                ImageFs.find(context).rootDir,
                fontsDir,
                applyRegistry,
            )
        val ok = primaryOk && registryOk && fontconfigOk
        Log.i(
            TAG,
            "CJK fonts: linked=$linked/${WINDOWS_FONT_LINKS.size} primary=$primaryOk " +
                "registry=$registryOk locale=$registryLocale fontconfig=$fontconfigOk cache=$cacheDir",
        )
        return ok
    }

    fun sharedFontFile(context: Context): File? =
        ensureSharedPack(context)?.let { File(it, CN_REGULAR).takeIf { f -> f.isFile } }

    /**
     * Ensure fonts.tzst is extracted under contents/FONTS/<sha>/ with all pack faces.
     * @return cache directory or null
     */
    @Synchronized
    fun ensureSharedPack(context: Context): File? {
        val archive = File(RuntimeAssetProvisioner.runtimeAssetsDir(context), ASSET_PATH)
        if (!archive.isFile) {
            Log.e(TAG, "Verified runtime asset missing: $archive")
            return null
        }
        val sha =
            AssetDigest.pinnedSha(archive)?.lowercase()
                ?: AssetDigest.of(archive).lowercase()
        val cacheDir = File(ContentsManager.getContentDir(context), "$CONTENTS_TYPE/$sha")
        if (packComplete(cacheDir)) {
            return cacheDir
        }

        val staging = File(cacheDir.parentFile, ".$sha.staging-${System.nanoTime()}")
        try {
            if (staging.exists()) staging.deleteRecursively()
            check(staging.mkdirs()) { "Cannot create font staging $staging" }
            val ok =
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    archive,
                    staging,
                )
            if (!ok) {
                Log.e(TAG, "Failed to extract $ASSET_PATH")
                staging.deleteRecursively()
                return null
            }

            // Collect faces from flat root or nested windows/Fonts/ (legacy).
            val found = mutableMapOf<String, File>()
            for (face in PACK_FACES) {
                val hit =
                    sequenceOf(
                        File(staging, face),
                        File(staging, "windows/Fonts/$face"),
                        File(staging, "Fonts/$face"),
                    ).firstOrNull { it.isFile && it.length() > 0L }
                if (hit != null) found[face] = hit
            }
            // Legacy single-file pack only had CN Regular under old name.
            if (CN_REGULAR !in found) {
                val legacy =
                    sequenceOf(
                        File(staging, "SourceHanSansCN-Regular.otf"),
                        File(staging, "windows/Fonts/SourceHanSansCN-Regular.otf"),
                    ).firstOrNull { it.isFile && it.length() > 0L }
                if (legacy != null) found[CN_REGULAR] = legacy
            }

            if (CN_REGULAR !in found) {
                Log.e(TAG, "fonts.tzst missing $CN_REGULAR after extract")
                staging.deleteRecursively()
                return null
            }

            cacheDir.parentFile?.mkdirs()
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            check(cacheDir.mkdirs()) { "Cannot create font cache $cacheDir" }

            for ((face, src) in found) {
                val dest = File(cacheDir, face)
                if (!src.renameTo(dest)) {
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                }
            }
            // If Bold/JP missing (old pack), fall back Regular CN for missing faces
            // so link table still has a file — imperfect but avoids total failure.
            for (face in PACK_FACES) {
                val dest = File(cacheDir, face)
                if (!dest.isFile) {
                    val fallback = File(cacheDir, CN_REGULAR)
                    fallback.copyTo(dest, overwrite = true)
                    Log.w(TAG, "Pack missing $face; cloned $CN_REGULAR as placeholder")
                }
            }

            staging.deleteRecursively()
            if (!packComplete(cacheDir)) {
                Log.e(TAG, "Font pack incomplete after publish: $cacheDir")
                return null
            }
            Log.i(TAG, "Published shared font pack $cacheDir (sha=$sha faces=${PACK_FACES.size})")
            return cacheDir
        } catch (e: Exception) {
            Log.e(TAG, "Cannot provision shared fonts from $archive", e)
            staging.deleteRecursively()
            return null
        }
    }

    private fun packComplete(cacheDir: File): Boolean =
        PACK_FACES.all { File(cacheDir, it).isFile && File(cacheDir, it).length() > 0L }

    private fun isLinkTo(target: File, source: File): Boolean {
        return try {
            if (!Files.isSymbolicLink(target.toPath())) return false
            target.canonicalFile == source.canonicalFile
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mimic a Windows CJK install: FontSubstitutes (HKLM) + Wine Replacements (HKCU).
     */
    fun applyRegistry(containerRoot: File, registryLocale: String = "en_US.UTF-8"): Boolean {
        val prefix = File(containerRoot, ".wine")
        val systemReg = File(prefix, "system.reg")
        val userReg = File(prefix, "user.reg")
        val cnFamily = cnFamilyForLocale(registryLocale)
        val uiSubstitutes = uiFamilySubstitutesForLocale(registryLocale)
        var systemOk = false
        var userOk = false

        if (systemReg.isFile) {
            try {
                WineRegistryEditor(systemReg).use { reg ->
                    val substitutesKey =
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes"
                    for ((from, to) in FAMILY_SUBSTITUTES) {
                        reg.setStringValue(
                            substitutesKey,
                            from,
                            if (to == FONT_FAMILY_CN) cnFamily else to,
                        )
                    }
                    for ((from, to) in uiSubstitutes) {
                        reg.setStringValue(substitutesKey, from, to)
                    }
                    reg.setStringValue(substitutesKey, "msyh", cnFamily)
                    reg.setStringValue(substitutesKey, "simsun", cnFamily)
                    reg.setStringValue(substitutesKey, "simhei", cnFamily)
                    reg.setStringValue(substitutesKey, "msgothic", FONT_FAMILY_JP)
                    reg.setStringValue(substitutesKey, "meiryo", FONT_FAMILY_JP)
                    reg.setStringValue(substitutesKey, "yugothic", FONT_FAMILY_JP)

                    val linksKey =
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink"
                    val chineseFallback = "$CN_REGULAR,$cnFamily"
                    for (family in SYSTEM_FONT_LINKS) {
                        reg.setMultiStringValue(linksKey, family, chineseFallback)
                    }

                    val fontsKey =
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
                    for ((name, file) in FONT_REGISTRATIONS) {
                        reg.setStringValue(fontsKey, name, file)
                    }

                    // Schema 5 temporarily replaced Wine's SYSTEM_FONT bitmap face
                    // with Tahoma. Restore only that value so custom user choices
                    // remain untouched.
                    val systemFont =
                        reg.getStringValue(SYSTEM_FONT_SETTINGS_KEY, "FONTS.FON")
                    if (systemFont.equals(PREVIOUS_SYSTEM_FONT_OVERRIDE, ignoreCase = true)) {
                        reg.setStringValue(
                            SYSTEM_FONT_SETTINGS_KEY,
                            "FONTS.FON",
                            WINE_DEFAULT_SYSTEM_FONT,
                        )
                    }
                }
                systemOk = true
                Log.d(TAG, "Wrote Windows font substitutes, links, and registrations into $systemReg")
            } catch (e: Exception) {
                Log.w(TAG, "FontSubstitutes update failed", e)
            }
        } else {
            Log.d(TAG, "Skip FontSubstitutes; missing $systemReg")
        }

        if (userReg.isFile) {
            try {
                WineRegistryEditor(userReg).use { reg ->
                    val key = "Software\\Wine\\Fonts\\Replacements"
                    for ((from, to) in FAMILY_SUBSTITUTES) {
                        reg.setStringValue(
                            key,
                            from,
                            if (to == FONT_FAMILY_CN) cnFamily else to,
                        )
                    }
                    for ((from, to) in uiSubstitutes) {
                        reg.setStringValue(key, from, to)
                    }
                }
                userOk = true
                Log.d(TAG, "Wrote Wine Fonts\\Replacements into $userReg")
            } catch (e: Exception) {
                Log.w(TAG, "Wine font replacements update failed", e)
            }
        } else {
            Log.d(TAG, "Skip Wine font replacements; missing $userReg")
        }
        return systemOk && userOk
    }
}
