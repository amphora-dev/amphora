package app.amphora.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
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
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/**
 * Configuration for [ContentStagingConventionPlugin]. What gets staged is
 * entirely manifest-driven; [winnativeDir] is only an optional verified local
 * source that avoids downloading the same bytes.
 */
abstract class ContentStagingExtension {
    /** WinNative checkout's `app/src/main/assets/` directory. */
    abstract val winnativeDir: DirectoryProperty

    /** Generated Android assets directory. */
    abstract val stagedAssetsDir: DirectoryProperty

    /** Verified download cache under this module's build directory. */
    abstract val contentCacheDir: DirectoryProperty

    /** Remote `content_manifest.json`, shared with runtime provisioning. */
    abstract val manifestUrl: Property<String>

    /** Optional offline manifest override. */
    abstract val manifestFile: RegularFileProperty
}

internal data class StagingAsset(
    val id: String,
    val assetPath: String,
    val sha256: String,
    val size: Long,
    val remoteUrl: String?,
    val catalogEligible: Boolean,
)

internal data class StagingManifest(val assets: List<StagingAsset>, val wcpCatalogUrl: String?)

internal fun parseStagingManifest(json: String): StagingManifest {
    val root = JsonSlurper().parseText(json) as? Map<*, *>
        ?: throw GradleException("[stageBundledContent] manifest root must be a JSON object")
    val components = root["components"] as? Map<*, *>
        ?: throw GradleException("[stageBundledContent] manifest components must be a JSON object")
    val assets = mutableListOf<StagingAsset>()

    for ((rawId, rawEntry) in components) {
        val id = rawId as? String
            ?: throw GradleException("[stageBundledContent] component id must be a string")
        val entry = rawEntry as? Map<*, *>
            ?: throw GradleException("[stageBundledContent] component $id must be a JSON object")
        when (val kind = requiredString(entry, "kind", "component $id")) {
            "ROOTFS" -> Unit // RootfsInstaller owns this runtime download.
            "ARCHIVE", "WCP" ->
                assets +=
                    parseAsset(
                        id = "component:$id",
                        entry = entry,
                        catalogEligible = kind == "WCP",
                    )
            else -> throw GradleException("[stageBundledContent] component $id has unsupported kind '$kind'")
        }
    }

    val runtimeAssets = root["runtimeAssets"]
    if (runtimeAssets != null) {
        val entries = runtimeAssets as? List<*>
            ?: throw GradleException("[stageBundledContent] manifest runtimeAssets must be a JSON array")
        for ((index, rawEntry) in entries.withIndex()) {
            val entry = rawEntry as? Map<*, *>
                ?: throw GradleException("[stageBundledContent] runtimeAssets[$index] must be a JSON object")
            assets += parseAsset("runtimeAssets[$index]", entry, catalogEligible = false)
        }
    }

    val duplicates = assets.groupBy { it.assetPath }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw GradleException(
            "[stageBundledContent] assetPath must be unique across components and runtimeAssets: " +
                duplicates.sorted().joinToString(),
        )
    }
    return StagingManifest(
        assets = assets,
        wcpCatalogUrl = optionalString(root, "wcpCatalogUrl"),
    )
}

private fun parseAsset(id: String, entry: Map<*, *>, catalogEligible: Boolean): StagingAsset {
    val assetPath = requiredString(entry, "assetPath", id)
    validateAssetPath(assetPath, id)
    val sha = requiredString(entry, "sha256", id).lowercase()
    if (!sha.matches(Regex("[0-9a-f]{64}"))) {
        throw GradleException("[stageBundledContent] $id has invalid sha256 '$sha'")
    }
    val size =
        (entry["size"] as? Number)?.toLong()
            ?: throw GradleException("[stageBundledContent] $id must declare numeric size")
    if (size < 0) {
        throw GradleException("[stageBundledContent] $id has invalid size $size")
    }
    return StagingAsset(
        id = id,
        assetPath = assetPath,
        sha256 = sha,
        size = size,
        remoteUrl = optionalString(entry, "remoteUrl"),
        catalogEligible = catalogEligible,
    )
}

private fun requiredString(entry: Map<*, *>, key: String, id: String): String = optionalString(entry, key)
    ?: throw GradleException("[stageBundledContent] $id must declare $key")

private fun optionalString(entry: Map<*, *>, key: String): String? = (entry[key] as? String)?.takeIf { it.isNotBlank() }

private fun validateAssetPath(path: String, id: String) {
    val segments = path.split('/')
    if (
        path.startsWith('/') ||
        '\\' in path ||
        segments.any { it.isBlank() || it == "." || it == ".." }
    ) {
        throw GradleException("[stageBundledContent] $id has unsafe assetPath '$path'")
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun verifyStagingAsset(file: File, asset: StagingAsset) {
    if (!file.isFile) {
        throw GradleException("[stageBundledContent] ${asset.id}: missing ${asset.assetPath}")
    }
    if (file.length() != asset.size) {
        throw GradleException(
            "[stageBundledContent] ${asset.id}: size mismatch for ${asset.assetPath} " +
                "(expected ${asset.size}, got ${file.length()})",
        )
    }
    val actualSha = sha256(file)
    if (actualSha != asset.sha256) {
        throw GradleException(
            "[stageBundledContent] ${asset.id}: SHA-256 mismatch for ${asset.assetPath} " +
                "(expected ${asset.sha256}, got $actualSha)",
        )
    }
}

internal fun interface AssetDownloader {
    fun download(url: String, destination: File)
}

internal class ExactContentStager(
    private val winnativeDir: File?,
    private val outputDir: File,
    private val cacheDir: File,
    private val downloader: AssetDownloader,
    private val log: (String) -> Unit = {},
) {
    fun sync(assets: List<StagingAsset>) {
        val parent = outputDir.parentFile
            ?: throw GradleException("[stageBundledContent] output has no parent: $outputDir")
        parent.mkdirs()
        val temporary = File(parent, "${outputDir.name}.tmp-${UUID.randomUUID()}")
        if (!temporary.mkdirs()) {
            throw GradleException("[stageBundledContent] cannot create temporary output $temporary")
        }
        try {
            for (asset in assets) {
                val source = sourceFor(asset)
                verifyStagingAsset(source, asset)
                val destination = childOf(temporary, asset.assetPath)
                destination.parentFile.mkdirs()
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                verifyStagingAsset(destination, asset)
                log("staged ${asset.assetPath} (${asset.size} bytes)")
            }
            assertExactOutput(temporary, assets)
            replaceOutput(temporary)
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun sourceFor(asset: StagingAsset): File {
        val local = winnativeDir?.let { childOf(it, asset.assetPath) }
        if (local?.exists() == true) {
            // A present-but-corrupt local file is an error, not permission to
            // silently substitute bytes from another source.
            verifyStagingAsset(local, asset)
            return local
        }

        val url =
            asset.remoteUrl
                ?: throw GradleException(
                    "[stageBundledContent] ${asset.id}: ${asset.assetPath} is absent from WinNative " +
                        "and has no verified remote URL",
                )
        val cached = childOf(cacheDir, asset.assetPath)
        if (cached.exists()) {
            try {
                verifyStagingAsset(cached, asset)
                return cached
            } catch (_: GradleException) {
                if (!cached.delete()) {
                    throw GradleException("[stageBundledContent] cannot remove stale cache file $cached")
                }
            }
        }

        cached.parentFile.mkdirs()
        val partial = File(cached.parentFile, "${cached.name}.part-${UUID.randomUUID()}")
        try {
            downloader.download(url, partial)
            verifyStagingAsset(partial, asset)
            Files.move(
                partial.toPath(),
                cached.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            verifyStagingAsset(cached, asset)
            return cached
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    private fun assertExactOutput(directory: File, assets: List<StagingAsset>) {
        val expected = assets.mapTo(sortedSetOf()) { it.assetPath }
        val actual =
            directory
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(directory).invariantSeparatorsPath }
                .toCollection(sortedSetOf())
        if (actual != expected) {
            throw GradleException(
                "[stageBundledContent] generated assets are not an exact manifest sync " +
                    "(expected=$expected, actual=$actual)",
            )
        }
    }

    private fun replaceOutput(temporary: File) {
        val backup = File(outputDir.parentFile, "${outputDir.name}.previous-${UUID.randomUUID()}")
        val hadOutput = outputDir.exists()
        if (hadOutput && !outputDir.renameTo(backup)) {
            throw GradleException("[stageBundledContent] cannot move existing output $outputDir")
        }
        if (!temporary.renameTo(outputDir)) {
            if (hadOutput && !backup.renameTo(outputDir)) {
                throw GradleException(
                    "[stageBundledContent] cannot publish $temporary or restore previous output $backup",
                )
            }
            throw GradleException("[stageBundledContent] cannot publish generated assets to $outputDir")
        }
        if (hadOutput && !backup.deleteRecursively()) {
            throw GradleException("[stageBundledContent] cannot remove previous output $backup")
        }
    }

    private fun childOf(root: File, relativePath: String): File {
        val child = File(root, relativePath)
        val canonicalRoot = root.canonicalFile
        val canonicalChild = child.canonicalFile
        if (
            canonicalChild != canonicalRoot &&
            !canonicalChild.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            throw GradleException("[stageBundledContent] path escapes staging root: $relativePath")
        }
        return canonicalChild
    }
}

private class HttpsAssetDownloader : AssetDownloader {
    override fun download(url: String, destination: File) {
        requireHttps(url, "asset")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 300_000
        connection.setRequestProperty("User-Agent", "Amphora-ContentStagingPlugin")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} ${connection.responseMessage} for $url")
            }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (failure: Throwable) {
            throw GradleException("[stageBundledContent] download failed for $url", failure)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Exact, verified staging task. It refreshes the manifest on every invocation,
 * stages all non-rootfs components plus every runtime asset into a temporary
 * directory, then replaces the generated assets directory only after all size
 * and SHA-256 checks pass.
 */
@DisableCachingByDefault(because = "The remote manifest must be refreshed on every invocation")
abstract class StageBundledContentTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val manifestUrl: Property<String>

    @get:Internal
    abstract val winnativeDir: DirectoryProperty

    @get:OutputDirectory
    abstract val stagedAssetsDir: DirectoryProperty

    @get:Internal
    abstract val contentCacheDir: DirectoryProperty

    init {
        group = "amphora content"
        description =
            "Exactly stage and verify manifest components/runtimeAssets into generated Android assets."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun stage() {
        val manifestJson =
            manifestFile.orNull?.asFile?.readText()
                ?: fetchHttpsText(manifestUrl.get())
        val manifest = parseStagingManifest(manifestJson)
        val winnative = winnativeDir.orNull?.asFile
        val needsCatalog =
            manifest.assets.any { asset ->
                asset.catalogEligible &&
                    asset.remoteUrl == null &&
                    winnative?.let { File(it, asset.assetPath).isFile } != true
            }
        val catalog =
            if (needsCatalog) {
                val url =
                    manifest.wcpCatalogUrl
                        ?: throw GradleException(
                            "[stageBundledContent] a WCP has no remoteUrl and the manifest has no wcpCatalogUrl",
                        )
                fetchCatalog(url)
            } else {
                emptyMap()
            }
        val resolved =
            manifest.assets.map { asset ->
                if (asset.remoteUrl == null && asset.catalogEligible) {
                    asset.copy(remoteUrl = catalog[asset.assetPath])
                } else {
                    asset
                }
            }
        ExactContentStager(
            winnativeDir = winnative,
            outputDir = stagedAssetsDir.get().asFile,
            cacheDir = contentCacheDir.get().asFile,
            downloader = HttpsAssetDownloader(),
            log = { logger.lifecycle("[stageBundledContent] $it") },
        ).sync(resolved)
        logger.lifecycle(
            "[stageBundledContent] exactly staged ${resolved.size} verified assets into " +
                stagedAssetsDir.get().asFile,
        )
    }

    private fun fetchCatalog(url: String): Map<String, String> {
        val parsed = JsonSlurper().parseText(fetchHttpsText(url)) as? List<*>
            ?: throw GradleException("[stageBundledContent] WCP catalog must be a JSON array: $url")
        return parsed.mapNotNull { rawEntry ->
            val entry = rawEntry as? Map<*, *> ?: return@mapNotNull null
            val remoteUrl = optionalString(entry, "remoteUrl") ?: return@mapNotNull null
            val assetName = URI(remoteUrl).path.substringAfterLast('/')
            assetName.takeIf { it.isNotBlank() }?.let { it to remoteUrl }
        }.toMap()
    }

    private fun fetchHttpsText(url: String): String {
        requireHttps(url, "manifest/catalog")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Amphora-ContentStagingPlugin")
        connection.setRequestProperty(
            "Accept",
            if (isGithubContentsApi(url)) "application/vnd.github.raw" else "application/json, text/plain, */*",
        )
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} ${connection.responseMessage} for $url")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (failure: Throwable) {
            throw GradleException("[stageBundledContent] cannot fetch $url", failure)
        } finally {
            connection.disconnect()
        }
    }
}

class ContentStagingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val extension = extensions.create<ContentStagingExtension>("amphoraContentStaging")
        extension.stagedAssetsDir.convention(layout.buildDirectory.dir("generated/assets/bundledContent"))
        extension.contentCacheDir.convention(layout.buildDirectory.dir("content-cache"))
        extension.manifestUrl.convention(providers.gradleProperty(CONTENT_MANIFEST_URL_PROPERTY))
        providers.gradleProperty("amphora.contentManifest.file").orNull?.let {
            extension.manifestFile.set(file(it))
        }

        plugins.withId("com.android.application") {
            extensions.configure<ApplicationExtension> {
                sourceSets.getByName("main").assets.srcDir(extension.stagedAssetsDir.get().asFile)
            }
        }

        tasks.register<StageBundledContentTask>("stageBundledContent") {
            manifestFile.set(extension.manifestFile)
            manifestUrl.set(extension.manifestUrl)
            winnativeDir.set(extension.winnativeDir)
            stagedAssetsDir.set(extension.stagedAssetsDir)
            contentCacheDir.set(extension.contentCacheDir)
        }
    }
}

private fun requireHttps(url: String, label: String) {
    if (!URI(url).scheme.equals("https", ignoreCase = true)) {
        throw GradleException("[stageBundledContent] $label URL must use HTTPS: $url")
    }
}

private fun isGithubContentsApi(url: String): Boolean =
    url.startsWith("https://api.github.com/repos/") && url.contains("/contents/")

/** Gradle property holding the runtime manifest URL; see `gradle.properties`. */
const val CONTENT_MANIFEST_URL_PROPERTY = "amphora.contentManifest.url"

/** Gradle property holding the APK update pin URL; see `gradle.properties`. */
const val APP_UPDATE_URL_PROPERTY = "amphora.appUpdate.url"
