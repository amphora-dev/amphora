package app.amphora.core.content

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.model.ContentComponent
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Remote-only content pin catalog. There is no APK-bundled fallback — pins live
 * in `amphora-dev/content_manifest` and are refreshed at runtime so imagefs /
 * WCP SHA bumps do not require an APK rebuild.
 *
 * On every successful load (disk cache or refresh), [DevPinOverlay] is applied
 * so callers always see the **effective** pin set.
 */
class ContentCatalog internal constructor(
    private val cacheFile: File,
    private val dispatchers: DispatcherProvider,
    private val sourceUrl: () -> String?,
    private val fetchManifest: suspend (String) -> String,
    private val contentDir: File = requireNotNull(cacheFile.parentFile),
) {
    constructor(context: Context, dispatchers: DispatcherProvider) : this(
        cacheFile = File(context.filesDir, "content/content_manifest.json"),
        dispatchers = dispatchers,
        sourceUrl = { ContentManifestLoader.resolveRemoteUrl(context) },
        fetchManifest = { url -> ContentManifestLoader.fetchHttpsText(url) },
    )

    sealed interface Status {
        data object Idle : Status

        data object Loading : Status

        data class Ready(
            val manifest: ContentManifest,
            val sourceUrl: String,
            val overriddenComponents: Set<ContentComponent> = emptySet(),
            val overriddenRuntimeAssets: Set<String> = emptySet(),
        ) : Status

        data class Failed(val error: String) : Status
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val manifestCache = ContentManifestCache(cacheFile)

    val overriddenComponents: Set<ContentComponent>
        get() = (_status.value as? Status.Ready)?.overriddenComponents.orEmpty()

    val overriddenRuntimeAssets: Set<String>
        get() = (_status.value as? Status.Ready)?.overriddenRuntimeAssets.orEmpty()

    fun isComponentOverridden(component: ContentComponent): Boolean =
        component in overriddenComponents

    fun isRuntimeAssetOverridden(assetPath: String): Boolean =
        assetPath in overriddenRuntimeAssets

    /**
     * Return the in-memory or disk-cached manifest, fetching from the remote URL
     * only when no valid cache exists. Session processes are short-lived, so a
     * disk-first cold path prevents every game launch from blocking on HTTPS.
     *
     * Always returns the **effective** manifest (remote ⊕ [DevPinOverlay]).
     */
    suspend fun require(): ContentManifest = mutex.withLock {
        when (val current = _status.value) {
            is Status.Ready -> current.manifest
            else -> loadDiskCacheLocked() ?: refreshLocked()
        }
    }

    /** Force a fresh HTTPS fetch (e.g. pull-to-refresh / before rootfs upgrade). */
    suspend fun refresh(): ContentManifest = mutex.withLock { refreshLocked() }

    fun peek(): ContentManifest? = (_status.value as? Status.Ready)?.manifest

    private suspend fun refreshLocked(): ContentManifest {
        val previous = _status.value as? Status.Ready
        if (previous == null) {
            _status.value = Status.Loading
        }
        return try {
            val url = sourceUrl() ?: error("content manifest remote URL is not configured")
            val json =
                withContext(dispatchers.io) {
                    fetchManifest(url)
                }
            // Parsing happens before publication. A malformed remote response or
            // failed atomic move therefore cannot replace the last-known-good cache.
            val remote = withContext(dispatchers.io) { manifestCache.replace(json) }
            publishReady(remote, url)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (previous != null) {
                _status.value = previous
                return previous.manifest
            }
            val remote = readDiskCacheRaw()
            if (remote != null) {
                return publishReady(remote, cacheFile.toURI().toString())
            }
            _status.value = Status.Failed(failure.message ?: failure.javaClass.simpleName)
            throw failure
        }
    }

    private suspend fun loadDiskCacheLocked(): ContentManifest? {
        val remote = readDiskCacheRaw() ?: return null
        return publishReady(remote, cacheFile.toURI().toString())
    }

    private suspend fun readDiskCacheRaw(): ContentManifest? = withContext(dispatchers.io) {
        manifestCache.read()
    }

    private suspend fun publishReady(remote: ContentManifest, sourceUrl: String): ContentManifest {
        val (effective, application) =
            withContext(dispatchers.io) {
                applyOverlay(remote)
            }
        _status.value =
            Status.Ready(
                manifest = effective,
                sourceUrl = sourceUrl,
                overriddenComponents = application.overriddenComponents,
                overriddenRuntimeAssets = application.overriddenRuntimeAssets,
            )
        return effective
    }

    private fun applyOverlay(remote: ContentManifest): Pair<ContentManifest, DevPinApplication> {
        val pins = DevPinOverlay.read(contentDir)
        return DevPinOverlay.apply(remote, pins)
    }
}

/** Disk-backed, structurally validated last-known-good manifest. */
internal class ContentManifestCache(private val file: File) {
    fun read(): ContentManifest? = runCatching {
        ContentManifest.parse(file.readText())
    }.getOrNull()

    fun replace(json: String): ContentManifest {
        val manifest = ContentManifest.parse(json)
        val parent = requireNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create manifest cache directory" }
        val temporary = File.createTempFile("content_manifest.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            AtomicFilePublisher.replace(temporary, file)
        } finally {
            temporary.delete()
        }
        return manifest
    }
}
