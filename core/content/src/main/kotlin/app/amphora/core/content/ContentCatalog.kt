package app.amphora.core.content

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import java.io.File
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
 */
class ContentCatalog(private val context: Context, private val dispatchers: DispatcherProvider) {
    sealed interface Status {
        data object Idle : Status

        data object Loading : Status

        data class Ready(val manifest: ContentManifest, val sourceUrl: String) : Status

        data class Failed(val error: String) : Status
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val diskCache = File(context.filesDir, "content/content_manifest.json")

    /** Return the cached manifest, fetching from the remote URL if needed. */
    suspend fun require(): ContentManifest = mutex.withLock {
        when (val current = _status.value) {
            is Status.Ready -> current.manifest
            else -> refreshLocked()
        }
    }

    /** Force a fresh HTTPS fetch (e.g. pull-to-refresh / before rootfs upgrade). */
    suspend fun refresh(): ContentManifest = mutex.withLock { refreshLocked() }

    fun peek(): ContentManifest? = (_status.value as? Status.Ready)?.manifest

    private suspend fun refreshLocked(): ContentManifest {
        _status.value = Status.Loading
        return try {
            val url =
                ContentManifestLoader.resolveRemoteUrl(context)
                    ?: error("content manifest remote URL is not configured")
            val json =
                withContext(dispatchers.io) {
                    ContentManifestLoader.fetchHttpsText(url)
                }
            val manifest = ContentManifest.parse(json)
            withContext(dispatchers.io) {
                runCatching { writeCacheAtomically(json) }
            }
            _status.value = Status.Ready(manifest, url)
            manifest
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val cached =
                withContext(dispatchers.io) {
                    runCatching {
                        ContentManifest.parse(diskCache.readText())
                    }.getOrNull()
                }
            if (cached != null) {
                _status.value = Status.Ready(cached, diskCache.toURI().toString())
                cached
            } else {
                _status.value = Status.Failed(failure.message ?: failure.javaClass.simpleName)
                throw failure
            }
        }
    }

    private fun writeCacheAtomically(json: String) {
        val parent = requireNotNull(diskCache.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create manifest cache directory" }
        val temporary = File.createTempFile("content_manifest.", ".tmp", parent)
        try {
            temporary.writeText(json)
            check(temporary.renameTo(diskCache)) { "Cannot replace manifest cache" }
        } finally {
            temporary.delete()
        }
    }
}
