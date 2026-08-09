package app.amphora.buildlogic

import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/**
 * Configuration for the [ContentStagingConventionPlugin]. The only
 * consumer-specific input is [winnativeDir]; everything else about *what* to stage
 * comes from the manifest, so a pin bump upstream needs no edit here.
 */
abstract class ContentStagingExtension {
    /** WinNative checkout root; ARCHIVE (.tzst) assets are copied from its `app/src/main/assets/`. */
    abstract val winnativeDir: DirectoryProperty

    /** Where staged assets land. Defaults to this module's `src/main/assets/`. */
    abstract val stagedAssetsDir: DirectoryProperty

    /** Download cache dir. Defaults to this module's `build/content-cache/`. */
    abstract val wcpCacheDir: DirectoryProperty

    /**
     * Remote `content_manifest.json` — the same URL the app fetches at runtime, so
     * staged assets always match the pins the device will verify against.
     */
    abstract val manifestUrl: Property<String>

    /**
     * Offline override: read the manifest from this file instead of [manifestUrl].
     * Set via the `amphora.contentManifest.file` Gradle property.
     */
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
    @get:Optional
    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val manifestUrl: Property<String>

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
        val catalogUrl = manifestRoot()["wcpCatalogUrl"] as? String ?: return emptyMap()
        return try {
            val conn = URI(catalogUrl).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage}")
            }
            @Suppress("UNCHECKED_CAST")
            val entries =
                conn.inputStream.bufferedReader().use { reader ->
                    JsonSlurper().parse(reader) as List<Map<String, Any?>>
                }
            entries
                .mapNotNull { entry ->
                    val remoteUrl = entry["remoteUrl"] as? String ?: return@mapNotNull null
                    val assetName = URI(remoteUrl).path.substringAfterLast('/')
                    assetName.takeIf { it.isNotBlank() }?.let { it to remoteUrl }
                }.toMap()
        } catch (t: Throwable) {
            logger.warn(
                "[stageBundledContent] cannot load WCP catalog $catalogUrl ($t); direct URL fallbacks remain available.",
            )
            emptyMap()
        }
    }

    /**
     * The manifest text, from [manifestFile] when set (offline) or fetched from
     * [manifestUrl]. Cached per task run so the up-to-date check and the action do
     * not fetch twice.
     */
    private val manifestJson: String by lazy {
        manifestFile.orNull?.asFile?.readText() ?: fetchText(manifestUrl.get())
    }

    @Suppress("UNCHECKED_CAST")
    private fun manifestRoot(): Map<String, Any?> = JsonSlurper().parseText(manifestJson) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun manifestComponents(): Map<String, Map<String, Any?>> =
        manifestRoot()["components"] as Map<String, Map<String, Any?>>

    private fun fetchText(url: String): String {
        require(URI(url).scheme.equals("https", ignoreCase = true)) {
            "[stageBundledContent] manifest URL must be https: $url"
        }
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage} for $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun allAssetsStaged(): Boolean = try {
        val dir = stagedAssetsDir.get().asFile
        val manifestStaged =
            manifestComponents().values.all { e ->
                val staged = File(dir, e["assetPath"] as String)
                staged.exists() &&
                    when (e["kind"] as String) {
                        "ARCHIVE" -> (e["sha256"] as String?)?.let { sha256(staged) == it } ?: true
                        else -> true
                    }
            }
        // Kernel-direct .tzst (imagefs.tzst + preparer wincomponents/* / ddrawrapper/* /
        // fonts.tzst / wincomponents/* / ddrawrapper/* ...) read via runtime-assets must all
        // be present too, or the task is stale and must re-run stageKernelDirectAssets().
        val winnative = winnativeDir.orNull?.asFile
        val kernelStaged =
            winnative?.takeIf { it.isDirectory }?.let { w ->
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
        val catalog = catalogDownloadUrls()

        for ((id, entry) in manifestComponents()) {
            val assetPath = entry["assetPath"] as String
            val kind = entry["kind"] as String
            val expectedSha = entry["sha256"] as String?
            val staged = File(stagedDir, assetPath)
            staged.parentFile.mkdirs()
            when (kind) {
                "ARCHIVE" -> stageArchive(id, assetPath, expectedSha, staged, winnativeAssets)
                // remoteUrl in the manifest wins; the catalog resolves entries that
                // deliberately have none (RemoteUrlResolver does the same at runtime).
                "WCP" ->
                    stageWcp(
                        id,
                        assetPath,
                        expectedSha,
                        staged,
                        cacheDir,
                        entry["remoteUrl"] as String? ?: catalog[assetPath],
                    )
                // ROOTFS is installed by RootfsInstaller from the manifest at runtime.
                "ROOTFS" -> logger.lifecycle("[stageBundledContent] $id: ROOTFS is downloaded on device; not staged.")
                else -> logger.warn("[stageBundledContent] $id: unknown kind '$kind'; skipping.")
            }
        }

        // Kernel-direct runtime archives: ImageFsRootfsInstaller (imagefs.tzst) +
        // XServerWineSessionPreparer / WinComponentSetup (fonts.tzst,
        // wincomponents/*, ddrawrapper/*, ...) read these via RuntimeAssetProvisioner,
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
            logger.warn(
                "[stageBundledContent] WinNative checkout absent; skipping kernel-direct assets (imagefs.tzst, metadata/startmenu.json, wincomponents/*, ...).",
            )
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
        if (copied ==
            0
        ) {
            logger.lifecycle("[stageBundledContent] kernel-direct .tzst assets all present; nothing copied.")
        }
    }

    private fun stageArchive(
        id: String,
        assetPath: String,
        expectedSha: String?,
        staged: File,
        winnativeAssets: File?,
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
            logger.error(
                "[stageBundledContent] $id: SHA-256 MISMATCH for $assetPath (expected $expectedSha, got $actualSha). Runtime resolve will reject this asset.",
            )
        } else {
            logger.lifecycle("[stageBundledContent] $id: staged $assetPath (sha256=$actualSha).")
        }
    }

    private fun stageWcp(
        id: String,
        assetPath: String,
        expectedSha: String?,
        staged: File,
        cacheDir: File,
        url: String?,
    ) {
        if (staged.exists()) {
            val actualSha = sha256(staged)
            if (expectedSha == null || actualSha == expectedSha) {
                logger.lifecycle("[stageBundledContent] $id: already staged ($assetPath, sha256=$actualSha); skipping.")
                return
            }
            logger.warn(
                "[stageBundledContent] $id: removing stale staged asset $assetPath (expected $expectedSha, got $actualSha).",
            )
            staged.delete()
        }
        if (url == null) {
            throw GradleException(
                "[stageBundledContent] $id: $assetPath has no remoteUrl in the manifest " +
                    "and is absent from the WCP catalog.",
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
                "[stageBundledContent] $id: SHA-256 mismatch for $assetPath (expected $expectedSha, got $actualSha).",
            )
        } else {
            logger.lifecycle(
                "[stageBundledContent] $id: staged $assetPath (sha256=$actualSha).${if (expectedSha == null) " Paste into content_manifest.json to lock." else ""}",
            )
        }
    }
}

/**
 * Registers [StageBundledContentTask]. The manifest comes from the same remote URL
 * the app fetches at runtime (`amphora.contentManifest.url`), so there is no copy of
 * it in this repository to keep in sync. Pass
 * `-Pamphora.contentManifest.file=<path>` to stage offline from a local manifest.
 */
class ContentStagingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val ext = extensions.create<ContentStagingExtension>("amphoraContentStaging")

        // Defaults local to the applying module.
        ext.stagedAssetsDir.convention(layout.projectDirectory.dir("src/main/assets"))
        ext.wcpCacheDir.convention(layout.buildDirectory.dir("content-cache"))
        ext.manifestUrl.convention(
            providers.gradleProperty(CONTENT_MANIFEST_URL_PROPERTY),
        )
        providers.gradleProperty("amphora.contentManifest.file").orNull?.let {
            ext.manifestFile.set(file(it))
        }

        tasks.register<StageBundledContentTask>("stageBundledContent") {
            manifestFile.set(ext.manifestFile)
            manifestUrl.set(ext.manifestUrl)
            winnativeDir.set(ext.winnativeDir)
            stagedAssetsDir.set(ext.stagedAssetsDir)
            wcpCacheDir.set(ext.wcpCacheDir)
        }
    }
}

/** Gradle property holding the runtime manifest URL; see `gradle.properties`. */
const val CONTENT_MANIFEST_URL_PROPERTY = "amphora.contentManifest.url"

/** Gradle property holding the APK update pin URL; see `gradle.properties`. */
const val APP_UPDATE_URL_PROPERTY = "amphora.appUpdate.url"
