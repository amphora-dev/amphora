package app.amphora.sdk.engine

import android.os.StatFs
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.engine.Downloader
import app.amphora.sdk.internal.engine.HttpStatusException
import app.amphora.sdk.internal.engine.RuntimeInstaller
import app.amphora.sdk.internal.engine.RuntimeMismatchException
import app.amphora.sdk.internal.engine.TarFormatException
import app.amphora.sdk.internal.io.deleteTree
import app.amphora.sdk.internal.io.measureTree
import app.amphora.sdk.internal.io.sha256
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the runtime the recipe pins: finds it on disk, installs it (download
 * or side-loaded archive → verify → extract → check), and removes it.
 *
 * Side-loading: an archive named `<runtime>.tar.gz` in the app's external
 * files directory (`/sdcard/Android/data/<package>/files/`) is used instead of
 * downloading. It is verified exactly like a download.
 */
public class RuntimeManager internal constructor(
    private val recipe: Recipe,
    private val paths: AmphoraPaths,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val downloader: Downloader,
    private val installer: RuntimeInstaller,
) {
    public val artifact: RuntimeArtifact get() = recipe.runtime

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Checking)
    public val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null

    /** The installed runtime, if ready. */
    public val current: InstalledRuntime? get() = (state.value as? RuntimeState.Ready)?.runtime

    init {
        refresh()
    }

    /** Re-reads the disk. Does nothing while an install is running. */
    public fun refresh() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { _state.value = withContext(io) { inspect() } }
        }
    }

    /** Starts (or resumes) installing. Idempotent while running or ready. */
    public fun install() {
        synchronized(lock) {
            if (job?.isActive == true || state.value is RuntimeState.Ready) return
            job = scope.launch { _state.value = withContext(io) { runInstall() } }
        }
    }

    /** Stops an install. The partial download is kept and resumed next time. */
    public fun cancel() {
        synchronized(lock) { job?.cancel() }
    }

    /** Deletes the installed runtime and any downloads. Prefixes stay. */
    public suspend fun uninstall() {
        synchronized(lock) { job?.cancel() }
        job?.join()
        withContext(io) {
            runCatching { deleteTree(paths.runtimes) }.onFailure { AmphoraLog.w("uninstall runtime", it) }
            runCatching { deleteTree(paths.downloads) }
        }
        refresh()
    }

    /** Deep check of the installed tree: every file present, totals as the manifest says. */
    public suspend fun verify(): VerifyResult = withContext(io) {
        val runtime = current ?: return@withContext VerifyResult(false, "运行时未安装")
        val tree = measureTree(runtime.dir)
        val manifestSize = File(runtime.dir, RuntimeManifest.FILE_NAME).length()
        val files = tree.files - 1
        val bytes = tree.bytes - manifestSize
        when {
            files != runtime.manifest.files -> VerifyResult(false, "文件数 $files，应为 ${runtime.manifest.files}")
            bytes != runtime.manifest.bytes -> VerifyResult(false, "总大小 $bytes，应为 ${runtime.manifest.bytes}")
            else -> VerifyResult(true, "${runtime.manifest.files} 个文件，大小一致")
        }
    }

    private fun sideloaded(): File? = paths.sideload
        ?.let { File(it, "${artifact.name}.tar.gz") }
        ?.takeIf { it.isFile && it.length() == artifact.size }

    private fun partial(): File = File(paths.downloads, "${artifact.name}.tar.gz.part")

    private fun inspect(): RuntimeState {
        installer.installed(artifact)?.let { return RuntimeState.Ready(it) }
        return RuntimeState.Missing(artifact, sideloaded = sideloaded() != null, downloaded = partial().length())
    }

    private suspend fun runInstall(): RuntimeState {
        installer.installed(artifact)?.let { return RuntimeState.Ready(it) }
        val meter = Meter()
        return try {
            cleanStale()
            val local = sideloaded()
            val part = partial()
            val needed = artifact.bytes + (if (local == null) artifact.size - part.length() else 0) + HEADROOM
            val available = StatFs(paths.root.apply { mkdirs() }.path).availableBytes
            if (available < needed) return RuntimeState.Failed(artifact, RuntimeError.Storage(needed, available))

            val archive =
                local ?: run {
                    meter.start(InstallPhase.Downloading, artifact.size, part.length())
                    downloader.download(artifact.url, part, artifact.size) { meter.update(it) }
                    part
                }

            meter.start(InstallPhase.Verifying, artifact.size)
            val actual = sha256(archive) { meter.update(it) }
            if (!actual.equals(artifact.sha256, ignoreCase = true)) {
                if (archive == part) part.delete()
                return RuntimeState.Failed(artifact, RuntimeError.Integrity(artifact.sha256, actual))
            }

            meter.start(InstallPhase.Extracting, artifact.size)
            val runtime = installer.install(archive, artifact) { meter.update(it) }

            meter.start(InstallPhase.Finalizing, 1)
            part.delete()
            cleanStale()
            AmphoraLog.i(
                "runtime ${artifact.name} installed from ${if (local != null) "side-loaded archive" else "download"}",
            )
            RuntimeState.Ready(runtime)
        } catch (e: CancellationException) {
            _state.value = inspect()
            throw e
        } catch (e: HttpStatusException) {
            RuntimeState.Failed(artifact, RuntimeError.Network("服务器返回 HTTP ${e.status}"))
        } catch (e: RuntimeMismatchException) {
            RuntimeState.Failed(artifact, RuntimeError.Corrupt(e.message ?: "运行时包内容不符"))
        } catch (e: TarFormatException) {
            RuntimeState.Failed(artifact, RuntimeError.Corrupt(e.message ?: "运行时包格式错误"))
        } catch (e: IOException) {
            AmphoraLog.w("runtime install failed in ${meter.phase}", e)
            val error =
                if (meter.phase == InstallPhase.Downloading) {
                    RuntimeError.Network(e.message ?: e.javaClass.simpleName)
                } else {
                    RuntimeError.Io(e.message ?: e.javaClass.simpleName)
                }
            RuntimeState.Failed(artifact, error)
        }
    }

    /** Removes other runtime versions and interrupted staging directories. */
    private fun cleanStale() {
        installer.stale(keep = artifact.name).forEach { dir ->
            runCatching { deleteTree(dir) }.onFailure { AmphoraLog.w("cannot remove $dir", it) }
        }
        paths.downloads.listFiles()?.filter { it.name != "${artifact.name}.tar.gz.part" }?.forEach { it.delete() }
    }

    /** Publishes progress at most every 100 ms, with a smoothed rate. */
    private inner class Meter {
        var phase: InstallPhase = InstallPhase.Downloading
            private set
        private var total = 0L
        private var lastPublish = 0L
        private var lastBytes = 0L
        private var lastTime = 0L
        private var rate = 0.0

        fun start(phase: InstallPhase, total: Long, done: Long = 0) {
            this.phase = phase
            this.total = total
            lastBytes = done
            lastTime = System.nanoTime()
            lastPublish = 0
            rate = 0.0
            publish(done)
        }

        fun update(done: Long) {
            val now = System.nanoTime()
            if (now - lastPublish < 100_000_000 && done < total) return
            val dt = (now - lastTime) / 1e9
            if (dt > 0) {
                val instant = (done - lastBytes) / dt
                rate = if (rate == 0.0) instant else rate * 0.8 + instant * 0.2
            }
            lastBytes = done
            lastTime = now
            publish(done)
        }

        private fun publish(done: Long) {
            lastPublish = System.nanoTime()
            _state.value = RuntimeState.Working(artifact, phase, done, total, rate.toLong())
        }
    }

    private companion object {
        /** Room for a prefix to be created right after install. */
        const val HEADROOM = 768L shl 20
    }
}

public data class VerifyResult(val ok: Boolean, val detail: String)
