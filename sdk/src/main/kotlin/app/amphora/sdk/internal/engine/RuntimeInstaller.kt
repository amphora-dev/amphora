package app.amphora.sdk.internal.engine

import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.RuntimeArtifact
import app.amphora.sdk.engine.RuntimeManifest
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.io.deleteTree
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException

/** The archive or the tree it produced does not match what the recipe pins. */
internal class RuntimeMismatchException(message: String) : IOException(message)

/**
 * Turns a verified archive into an installed runtime: extract into a staging
 * directory, check the manifest and the totals against the recipe, then
 * rename into place. The final directory only ever appears complete, so
 * "the directory exists and its manifest matches" means "installed".
 */
internal class RuntimeInstaller(private val runtimes: File) {
    /** Extracts [archive]; [onProgress] gets compressed bytes consumed. */
    suspend fun install(archive: File, artifact: RuntimeArtifact, onProgress: (Long) -> Unit): InstalledRuntime {
        val staging = File(runtimes, ".staging-${artifact.name}")
        deleteTree(staging)
        staging.mkdirs()
        try {
            val counted = extract(archive, staging, artifact, onProgress)
            val manifest =
                readManifest(staging) ?: throw RuntimeMismatchException("archive has no ${RuntimeManifest.FILE_NAME}")
            check(manifest, artifact)
            if (counted.files != manifest.files || counted.bytes != manifest.bytes) {
                throw RuntimeMismatchException(
                    "archive holds ${counted.files} files / ${counted.bytes} bytes, manifest says ${manifest.files} / ${manifest.bytes}",
                )
            }
            val target = File(runtimes, artifact.name)
            deleteTree(target)
            if (!staging.renameTo(target)) throw IOException("cannot move runtime into place")
            return InstalledRuntime(manifest, target)
        } catch (e: Throwable) {
            runCatching { deleteTree(staging) }
            throw e
        }
    }

    /** Cheap check of an installed runtime: its manifest parses and matches. */
    fun installed(artifact: RuntimeArtifact): InstalledRuntime? {
        val dir = File(runtimes, artifact.name)
        val manifest = readManifest(dir) ?: return null
        return try {
            check(manifest, artifact)
            InstalledRuntime(manifest, dir).takeIf { it.loader.isFile && it.server.isFile }
        } catch (_: RuntimeMismatchException) {
            null
        }
    }

    /** Runtimes on disk other than [keep], plus leftovers of interrupted installs. */
    fun stale(keep: String): List<File> = runtimes.listFiles()?.filter { it.name != keep }.orEmpty()

    private data class Totals(val files: Int, val bytes: Long)

    private suspend fun extract(
        archive: File,
        into: File,
        artifact: RuntimeArtifact,
        onProgress: (Long) -> Unit,
    ): Totals {
        val context = currentCoroutineContext()
        var files = 0
        var bytes = 0L
        // Generous ceilings: an archive that keeps going past the recipe's totals is not ours.
        val maxFiles = artifact.files + 1
        val maxBytes = artifact.bytes + MANIFEST_SLACK
        val buffer = ByteArray(256 * 1024)
        CountingInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 20), onProgress).use { raw ->
            val tar = TarReader(GZIPInputStream(raw, 64 * 1024))
            while (true) {
                context.ensureActive()
                val entry = tar.next() ?: break
                val dest = File(into, entry.path)
                when (entry.type) {
                    TarEntryType.Directory -> if (!dest.isDirectory &&
                        !dest.mkdirs()
                    ) {
                        throw IOException("cannot create ${entry.path}")
                    }
                    TarEntryType.File -> {
                        val isManifest = entry.path == RuntimeManifest.FILE_NAME
                        if (!isManifest) {
                            files++
                            bytes += entry.size
                            if (files > maxFiles ||
                                bytes > maxBytes
                            ) {
                                throw RuntimeMismatchException("archive is larger than the recipe says")
                            }
                        }
                        dest.parentFile?.let {
                            if (!it.isDirectory &&
                                !it.mkdirs()
                            ) {
                                throw IOException("cannot create $it")
                            }
                        }
                        FileOutputStream(dest).use { out ->
                            while (true) {
                                val n = tar.data.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                        // Installed files are read-only; Wine never writes into its own tree.
                        if (entry.executable) dest.setExecutable(true, true)
                        dest.setWritable(false, false)
                    }
                }
            }
        }
        return Totals(files, bytes)
    }

    private fun readManifest(dir: File): RuntimeManifest? {
        val file = File(dir, RuntimeManifest.FILE_NAME)
        if (!file.isFile) return null
        return try {
            AmphoraJson.decodeFromString(RuntimeManifest.serializer(), file.readText())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun check(manifest: RuntimeManifest, artifact: RuntimeArtifact) {
        fun fail(what: String): Nothing = throw RuntimeMismatchException("manifest $what does not match the recipe")
        if (manifest.format != RuntimeManifest.FORMAT) fail("format ${manifest.format}")
        if (manifest.name != artifact.name) fail("name ${manifest.name}")
        if (manifest.files != artifact.files) fail("file count ${manifest.files}")
        if (manifest.bytes != artifact.bytes) fail("size ${manifest.bytes}")
        if (manifest.abi != "arm64-v8a") fail("abi ${manifest.abi}")
        for (path in listOf(manifest.loader, manifest.server) + manifest.libraryPath) {
            try {
                TarReader.normalize(path, TarEntryType.File)
            } catch (_: TarFormatException) {
                fail("path $path")
            }
        }
    }

    private class CountingInputStream(input: InputStream, private val onProgress: (Long) -> Unit) :
        FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it >
                0
            ) {
                report(it.toLong())
            }
        }

        private fun report(n: Long) {
            count += n
            onProgress(count)
        }
    }

    private companion object {
        const val MANIFEST_SLACK = 1L shl 20
    }
}
