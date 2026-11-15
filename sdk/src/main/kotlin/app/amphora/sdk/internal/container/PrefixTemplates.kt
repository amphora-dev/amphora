package app.amphora.sdk.internal.container

import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.io.deleteTree
import app.amphora.sdk.internal.io.linkTo
import app.amphora.sdk.internal.io.measureTree
import app.amphora.sdk.internal.io.readTextOrNull
import app.amphora.sdk.internal.io.sameContent
import app.amphora.sdk.internal.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** `templates/<recipe>/template.json`: which runtime the template was built from, and what it holds. */
@Serializable
internal data class TemplateRecord(
    val format: Int,
    val recipe: String,
    val runtime: String,
    /** [runtimeStamp] of the runtime it links into. */
    val stamp: Long,
    val builtAt: Long,
    val buildMillis: Long,
    val files: Int,
    val links: Int,
    val bytes: Long,
) {
    companion object {
        const val FORMAT = 1
    }
}

/**
 * Identifies one installation of a runtime: the modification time of
 * `wine.inf`, which is also what Wine compares with a prefix's
 * `.update-timestamp` to decide whether to run wineboot again. Installing the
 * same runtime anew changes it, and prefixes made before are rebuilt.
 */
internal fun runtimeStamp(runtime: InstalledRuntime): Long =
    File(runtime.dir, "share/wine/wine.inf").lastModified() / 1000

/**
 * The template every prefix is made from (12 §3.3): a prefix Wine initialized
 * once, in which each file that is byte-for-byte a runtime file has been
 * replaced by a link to that file. What stays real is the registry and a few
 * small generated files.
 *
 * It is built on the device, once per installed runtime: only the target can
 * run the target's wineboot.
 *
 * ```
 * templates/<recipe>/
 * ├── template.json     [TemplateRecord], written last: its presence means "complete"
 * ├── wineboot.log
 * └── tree/             the thinned prefix
 * ```
 */
internal class PrefixTemplates(
    private val paths: AmphoraPaths,
    private val recipe: Recipe,
    private val initializer: PrefixInitializer,
    /** Bytes that can still be written to the volume holding a directory. */
    private val freeSpace: (File) -> Long,
) {
    val dir: File get() = paths.template(recipe.id)
    val tree: File get() = File(dir, TREE)

    /** The template on disk, if it is complete and belongs to this recipe (and to [runtime], when given). */
    fun current(runtime: InstalledRuntime? = null): TemplateRecord? {
        val text = File(dir, RECORD).readTextOrNull() ?: return null
        val record =
            try {
                AmphoraJson.decodeFromString(TemplateRecord.serializer(), text)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (record.format != TemplateRecord.FORMAT || record.recipe != recipe.id) return null
        if (runtime != null && (record.runtime != runtime.manifest.name || record.stamp != runtimeStamp(runtime))) {
            return null
        }
        return record.takeIf { File(tree, "system.reg").isFile }
    }

    /** The template for [runtime], building it first when there is none; [onBuild] is called before a build. */
    suspend fun ensure(runtime: InstalledRuntime, onBuild: () -> Unit = {}): TemplateRecord {
        current(runtime)?.let { return it }
        onBuild()
        return build(runtime)
    }

    private suspend fun build(runtime: InstalledRuntime): TemplateRecord {
        val started = System.currentTimeMillis()
        // Other recipes' templates link into runtimes that are gone; unfinished builds are of no use either.
        paths.templates.listFiles()?.forEach { deleteTree(it) }
        // Wine copies every system file into the new prefix before we can link them back.
        val needed = runtime.manifest.bytes
        val available = freeSpace(paths.templates.apply { mkdirs() })
        if (available < needed) {
            throw IOException("$FAILED：存储空间不足，要临时占用约 ${needed shr 20} MiB，现在只有 ${available shr 20} MiB")
        }

        // A name of its own, so a wineboot orphaned by an earlier attempt cannot write into this one.
        val staging = File(paths.templates, "$STAGING${recipe.id}-${System.nanoTime()}")
        val stagingTree = File(staging, TREE)
        if (!stagingTree.mkdirs()) throw IOException("cannot create $stagingTree")
        val log = File(staging, LOG)
        try {
            initializer.initialize(stagingTree, runtime, log)
            deleteTree(File(stagingTree, SERVER_DIR))
            val linked = linkIdenticalFiles(stagingTree, runtime.dir)
            val size = measureTree(stagingTree)
            val record =
                TemplateRecord(
                    format = TemplateRecord.FORMAT,
                    recipe = recipe.id,
                    runtime = runtime.manifest.name,
                    stamp = runtimeStamp(runtime),
                    builtAt = started,
                    buildMillis = System.currentTimeMillis() - started,
                    files = size.files,
                    links = size.links,
                    bytes = size.bytes,
                )
            writeTextAtomically(File(staging, RECORD), AmphoraJson.encodeToString(TemplateRecord.serializer(), record))
            if (!staging.renameTo(dir)) throw IOException("cannot move the template into place")
            AmphoraLog.i(
                "template ${recipe.id}: $linked files linked to ${runtime.manifest.name}, " +
                    "${size.files} kept (${size.bytes} bytes), ${record.buildMillis} ms",
            )
            return record
        } catch (e: Throwable) {
            // Keep Wine's output: it is the only account of why the build failed.
            runCatching { if (log.isFile) log.copyTo(File(paths.templates, FAILED_LOG), overwrite = true) }
            runCatching { deleteTree(staging) }
            if (e is IOException) throw IOException("$FAILED：${e.message}", e)
            throw e
        }
    }

    internal companion object {
        const val TREE = "tree"
        const val RECORD = "template.json"
        const val LOG = "wineboot.log"
        const val FAILED_LOG = "wineboot-failed.log"
        const val STAGING = ".building-"
        const val FAILED = "初始化 Windows 环境失败"

        /** The wineserver's socket directory; it belongs to a running server, not to the prefix. */
        const val SERVER_DIR = ".wineserver"
    }
}

/**
 * Replaces every file under [tree] that is identical to a file under [store]
 * by a link to it, and returns how many were replaced. Files directly in
 * [tree] (the registry, `.update-timestamp`) stay: Wine rewrites them whole.
 * Among store files with the same content, one with the same name is
 * preferred, so `system32/kernel32.dll` links to the runtime's `kernel32.dll`.
 */
internal suspend fun linkIdenticalFiles(tree: File, store: File): Int {
    val context = currentCoroutineContext()
    val bySize = HashMap<Long, MutableList<File>>()
    walkFiles(store.toPath()) { file, attrs ->
        if (attrs.isRegularFile && attrs.size() > 0) bySize.getOrPut(attrs.size()) { ArrayList() }.add(file.toFile())
    }
    val root = tree.toPath()
    val candidates = ArrayList<File>()
    walkFiles(root) { file, attrs ->
        if (attrs.isRegularFile && attrs.size() > 0 && file.parent != root) candidates.add(file.toFile())
    }
    var linked = 0
    for (file in candidates) {
        context.ensureActive()
        val same =
            bySize[file.length()]
                ?.sortedByDescending { it.name.equals(file.name, ignoreCase = true) }
                ?.firstOrNull { sameContent(file, it) }
                ?: continue
        linkTo(file, same)
        linked++
    }
    return linked
}

/** Visits everything that is not a directory under [root], without following links. */
private fun walkFiles(root: Path, visit: (Path, BasicFileAttributes) -> Unit) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visit(file, attrs)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
