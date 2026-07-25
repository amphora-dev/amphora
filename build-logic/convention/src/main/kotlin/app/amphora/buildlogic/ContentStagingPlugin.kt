package app.amphora.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Configuration for the [ContentStagingConventionPlugin]. Only the *external*
 * asset sources are consumer-specific:
 * - [winnativeDir]     -- the WinNative checkout whose `app/src/main/assets/` holds the ARCHIVE (.tzst) assets;
 * - [wcpDownloadUrls]  -- `.wcp` download URLs keyed by assetPath.
 *
 * The [manifestFile] is abstracted away: it defaults to `:core:content`'s
 * `content_manifest.json` (single source of truth, resolved via a *project
 * reference* -- never a hardcoded cross-module path in the consumer). Consumers
 * override it only in unusual layouts.
 */
abstract class ContentStagingExtension {
    /** WinNative checkout root; ARCHIVE (.tzst) assets are copied from its `app/src/main/assets/`. */
    abstract val winnativeDir: DirectoryProperty

    /** `.wcp` download URLs keyed by assetPath (build-only; not part of the runtime manifest). */
    abstract val wcpDownloadUrls: MapProperty<String, String>

    /** Stable upstream catalog used to discover `.wcp` release URLs by asset filename. */
    abstract val wcpCatalogUrl: Property<String>

    /** Where staged assets land. Defaults to this module's `src/main/assets/`. */
    abstract val stagedAssetsDir: DirectoryProperty

    /** Download cache dir. Defaults to this module's `build/content-cache/`. */
    abstract val wcpCacheDir: DirectoryProperty

    /** `content_manifest.json`. Defaults to `:core:content`'s manifest (single source of truth). */
    abstract val manifestFile: RegularFileProperty
}

/**
 * Stages the bundled-content assets referenced by `content_manifest.json` into
 * [stagedAssetsDir]. ARCHIVE (.tzst) are copied from [winnativeDir] with SHA-256
 * verification; WCP (.wcp) are downloaded from [wcpDownloadUrls]. Best-effort: a
 * missing WinNative checkout, a failed download, or an SHA mismatch logs a warning
 * and skips that asset -- it never fails the build.
 *
 * NOT auto-wired to preBuild: staging the 160 MB Proton .wcp bloats every debug
 * APK, so run explicitly (`./gradlew :app:stageBundledContent`). Staged assets are
 * git-ignored (`*.tzst` / `*.wcp`). See `docs/04-ASSET-MANIFEST.md` §4,
 * `docs/03-TRACKING.md` §P2 #9.
 */
abstract class StageBundledContentTask : DefaultTask() {

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val wcpDownloadUrls: MapProperty<String, String>

    @get:Input
    abstract val wcpCatalogUrl: Property<String>

    @get:Internal
    abstract val winnativeDir: DirectoryProperty

    @get:Internal
    abstract val stagedAssetsDir: DirectoryProperty

    @get:Internal
    abstract val wcpCacheDir: DirectoryProperty

    init {
        group = "amphora content"
        description =
            "Stage bundled-content assets (.tzst from WinNative, .wcp from GitHub) into src/main/assets/. Best-effort."
        // Up-to-date when every manifest asset is staged (ARCHIVE SHAs verified).
        outputs.upToDateWhen { allAssetsStaged() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun catalogDownloadUrls(): Map<String, String> {
        val catalogUrl = wcpCatalogUrl.orNull?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return try {
            val conn = URI(catalogUrl).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage}")
            }
            @Suppress("UNCHECKED_CAST")
            val entries = conn.inputStream.bufferedReader().use { reader ->
                JsonSlurper().parse(reader) as List<Map<String, Any?>>
            }
            entries.mapNotNull { entry ->
                val remoteUrl = entry["remoteUrl"] as? String ?: return@mapNotNull null
                val assetName = URI(remoteUrl).path.substringAfterLast('/')
                assetName.takeIf { it.isNotBlank() }?.let { it to remoteUrl }
            }.toMap()
        } catch (t: Throwable) {
            logger.warn("[stageBundledContent] cannot load WCP catalog $catalogUrl ($t); direct URL fallbacks remain available.")
            emptyMap()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun manifestComponents(): Map<String, Map<String, Any?>> {
        val parsed = JsonSlurper().parse(manifestFile.get().asFile) as Map<String, Any?>
        return parsed["components"] as Map<String, Map<String, Any?>>
    }

    private fun allAssetsStaged(): Boolean = try {
        val dir = stagedAssetsDir.get().asFile
        val manifestStaged = manifestComponents().values.all { e ->
            val staged = File(dir, e["assetPath"] as String)
            staged.exists() && when (e["kind"] as String) {
                "ARCHIVE" -> (e["sha256"] as String?)?.let { sha256(staged) == it } ?: true
                else -> true
            }
        }
        // Kernel-direct .tzst (imagefs.tzst + preparer wincomponents/* / ddrawrapper/* /
        // container_pattern_common.tzst ...) read straight from context.assets must all
        // be present too, or the task is stale and must re-run stageKernelDirectAssets().
        val winnative = winnativeDir.orNull?.asFile
        val kernelStaged = winnative?.takeIf { it.isDirectory }?.let { w ->
            w.walkTopDown().filter { it.isFile && !it.name.endsWith(".wcp") }.all { src ->
                val rel = src.relativeTo(w).path
                !(rel.startsWith("wnsteam/") || rel.contains("arm64ec")) ||
                    File(dir, rel).exists()
            }
        } ?: true
        manifestStaged && kernelStaged
    } catch (t: Throwable) {
        logger.debug("[stageBundledContent] up-to-date check failed: $t", t)
        false
    }

    @TaskAction
    fun stage() {
        val stagedDir = stagedAssetsDir.get().asFile
        val cacheDir = wcpCacheDir.get().asFile
        val winnativeAssets = winnativeDir.orNull?.asFile
        val urls = wcpDownloadUrls.get() + catalogDownloadUrls()

        for ((id, entry) in manifestComponents()) {
            val assetPath = entry["assetPath"] as String
            val kind = entry["kind"] as String
            val expectedSha = entry["sha256"] as String?
            val staged = File(stagedDir, assetPath)
            staged.parentFile.mkdirs()
            when (kind) {
                "ARCHIVE" -> stageArchive(id, assetPath, expectedSha, staged, winnativeAssets)
                "WCP" -> stageWcp(id, assetPath, expectedSha, staged, cacheDir, urls)
                else -> logger.warn("[stageBundledContent] $id: unknown kind '$kind'; skipping.")
            }
        }

        // Kernel-direct runtime archives: ImageFsRootfsInstaller (imagefs.tzst) +
        // XServerWineSessionPreparer / WinComponentSetup (container_pattern_common.tzst,
        // wincomponents/*, ddrawrapper/*, ...) read these .tzst straight from context.assets,
        // bypassing ContentSource/manifest. Stage them from the WinNative checkout too.
        stageKernelDirectAssets(winnativeAssets, stagedDir)
    }

    /**
     * Stages the kernel-direct assets the ported `com.winlator.cmod` kernel reads
     * straight from `context.assets` (rootfs + preparer/runtime files), bypassing
     * [ContentSource] / the manifest. Every file under the WinNative checkout's
     * assets is copied (idempotent on equal size) except `.wcp` (WCP-download-
     * managed by the manifest), Steam (`wnsteam/`) and arm64ec (RFC §7 / D5
     * non-targets). This covers `.tzst` archives *and* non-archive assets the
     * kernel reads straight from assets (e.g. `metadata/startmenu.json` --
     * `WineStartMenuCreator`). Manifest-managed archives are re-touched harmlessly.
     * Best-effort: a missing checkout logs a warning and skips.
     */
    private fun stageKernelDirectAssets(winnativeAssets: File?, stagedDir: File) {
        if (winnativeAssets == null || !winnativeAssets.isDirectory) {
            logger.warn("[stageBundledContent] WinNative checkout absent; skipping kernel-direct assets (imagefs.tzst, metadata/startmenu.json, wincomponents/*, ...).")
            return
        }
        var copied = 0
        winnativeAssets.walkTopDown().filter { it.isFile && !it.name.endsWith(".wcp") }.forEach { src ->
            val rel = src.relativeTo(winnativeAssets).path
            if (rel.startsWith("wnsteam/") || rel.contains("arm64ec")) return@forEach
            val staged = File(stagedDir, rel)
            staged.parentFile.mkdirs()
            if (staged.exists() && staged.length() == src.length()) return@forEach
            src.copyTo(staged, overwrite = true)
            logger.lifecycle("[stageBundledContent] kernel-direct: staged $rel (${src.length()} bytes).")
            copied++
        }
        if (copied == 0) logger.lifecycle("[stageBundledContent] kernel-direct .tzst assets all present; nothing copied.")
    }

    private fun stageArchive(
        id: String, assetPath: String, expectedSha: String?, staged: File, winnativeAssets: File?,
    ) {
        val src = winnativeAssets?.let { File(it, assetPath) }
        if (src == null || !src.exists()) {
            logger.warn("[stageBundledContent] $id: WinNative checkout absent; skipping ARCHIVE $assetPath.")
            return
        }
        if (staged.exists() && expectedSha != null && sha256(staged) == expectedSha) {
            logger.lifecycle("[stageBundledContent] $id: already staged ($assetPath); skipping.")
            return
        }
        src.copyTo(staged, overwrite = true)
        val actualSha = sha256(staged)
        if (expectedSha != null && actualSha != expectedSha) {
            logger.error("[stageBundledContent] $id: SHA-256 MISMATCH for $assetPath (expected $expectedSha, got $actualSha). Runtime resolve will reject this asset.")
        } else {
            logger.lifecycle("[stageBundledContent] $id: staged $assetPath (sha256=$actualSha).")
        }
    }

    private fun stageWcp(
        id: String, assetPath: String, expectedSha: String?, staged: File,
        cacheDir: File, urls: Map<String, String>,
    ) {
        if (staged.exists()) {
            val actualSha = sha256(staged)
            if (expectedSha == null || actualSha == expectedSha) {
                logger.lifecycle("[stageBundledContent] $id: already staged ($assetPath, sha256=$actualSha); skipping.")
                return
            }
            logger.warn("[stageBundledContent] $id: removing stale staged asset $assetPath (expected $expectedSha, got $actualSha).")
            staged.delete()
        }
        val url = urls[assetPath]
        if (url == null) {
            throw GradleException(
                "[stageBundledContent] $id: $assetPath is absent from default WCP catalog and has no direct URL fallback."
            )
        }
        cacheDir.mkdirs()
        val cached = File(cacheDir, assetPath)
        if (cached.exists() && expectedSha != null && sha256(cached) != expectedSha) {
            logger.warn("[stageBundledContent] $id: removing stale cached asset $assetPath.")
            cached.delete()
        }
        if (!cached.exists()) {
            logger.lifecycle("[stageBundledContent] $id: downloading $url ...")
            try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 300_000
                if (conn.responseCode !in 200..299) {
                    throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.inputStream.use { input ->
                    cached.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
            } catch (t: Throwable) {
                cached.delete()
                throw GradleException("[stageBundledContent] $id: download failed for $assetPath", t)
            }
        }
        cached.copyTo(staged, overwrite = false)
        val actualSha = sha256(staged)
        if (expectedSha != null && actualSha != expectedSha) {
            staged.delete()
            cached.delete()
            throw GradleException(
                "[stageBundledContent] $id: SHA-256 mismatch for $assetPath (expected $expectedSha, got $actualSha)."
            )
        } else {
            logger.lifecycle("[stageBundledContent] $id: staged $assetPath (sha256=$actualSha).${if (expectedSha == null) " Paste into content_manifest.json to lock." else ""}")
        }
    }
}

/**
 * Registers [StageBundledContentTask] and abstracts manifest acquisition: the
 * manifest is resolved from `:core:content`'s assets via a *project reference*
 * (not a hardcoded cross-module path), so consumers never know where the manifest
 * lives. Consumers configure only the external asset sources via the
 * [ContentStagingExtension] (`amphoraContentStaging { ... }`).
 */
class ContentStagingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val ext = extensions.create<ContentStagingExtension>("amphoraContentStaging")

        // Defaults local to the applying module.
        ext.stagedAssetsDir.convention(layout.projectDirectory.dir("src/main/assets"))
        ext.wcpCacheDir.convention(layout.buildDirectory.dir("content-cache"))
        ext.wcpCatalogUrl.convention("")

        // Manifest acquisition is abstracted: single source of truth in :core:content,
        // resolved via project reference (not a hardcoded path in the consumer).
        rootProject.findProject(":core:content")?.let { content ->
            ext.manifestFile.convention(
                content.layout.projectDirectory.file("src/main/assets/content_manifest.json")
            )
        } ?: logger.warn("amphora.content.staging: :core:content not found; set amphoraContentStaging.manifestFile explicitly.")

        tasks.register<StageBundledContentTask>("stageBundledContent") {
            manifestFile.set(ext.manifestFile)
            wcpDownloadUrls.set(ext.wcpDownloadUrls)
            wcpCatalogUrl.set(ext.wcpCatalogUrl)
            winnativeDir.set(ext.winnativeDir)
            stagedAssetsDir.set(ext.stagedAssetsDir)
            wcpCacheDir.set(ext.wcpCacheDir)
        }
    }
}
