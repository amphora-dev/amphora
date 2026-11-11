package app.amphora.sdk.container

import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.io.measureTree
import app.amphora.sdk.internal.io.moveToTrashAndDelete
import app.amphora.sdk.internal.io.readTextOrNull
import app.amphora.sdk.internal.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** What we record about a prefix in `<prefix>/.amphora/prefix.json`. */
@Serializable
public data class PrefixRecord(val id: String, val recipe: String, val runtime: String, val createdAt: Long)

public data class PrefixInfo(
    val id: String,
    val dir: File,
    /** Null until the first session creates the prefix. */
    val record: PrefixRecord?,
    /** Wine has initialized it (system.reg exists). */
    val initialized: Boolean,
    val bytes: Long,
    val files: Int,
    val links: Int,
    /** Created by another recipe: rebuilding is recommended (D11: no migration). */
    val stale: Boolean,
)

/**
 * Wine prefixes (12 §3.3, D13). Today every game uses one full prefix
 * (`default`): the thin-prefix template and content store are the next step
 * of milestone 1. Ids are separate from game ids so that switch is local.
 */
public class PrefixStore internal constructor(
    private val paths: AmphoraPaths,
    private val recipe: Recipe,
    private val io: CoroutineContext,
) {
    public suspend fun info(id: String): PrefixInfo = withContext(io) {
        val dir = paths.prefix(id)
        val record = readRecord(dir)
        val tree = measureTree(dir)
        PrefixInfo(
            id = id,
            dir = dir,
            record = record,
            initialized = File(dir, "system.reg").isFile,
            bytes = tree.bytes,
            files = tree.files,
            links = tree.links,
            stale = record != null && record.recipe != recipe.id,
        )
    }

    /** Deletes the prefix; the next session recreates it. Game files are never touched (links are not followed). */
    public suspend fun reset(id: String) {
        withContext(io) { moveToTrashAndDelete(paths.prefix(id), paths.trash) }
    }

    /**
     * Makes sure the prefix directory exists and is recorded, and maps drive
     * D: to [gameDir] (or removes D: for built-in tools). Wine initializes the
     * rest on first use.
     */
    internal fun prepare(id: String, runtime: InstalledRuntime, gameDir: File?): File {
        val dir = paths.prefix(id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create prefix $dir")
        if (readRecord(dir) == null) {
            val record = PrefixRecord(id, recipe.id, runtime.manifest.name, System.currentTimeMillis())
            writeTextAtomically(
                File(dir, ".amphora/prefix.json"),
                AmphoraJson.encodeToString(PrefixRecord.serializer(), record),
            )
        }
        mapDrive(dir, 'd', gameDir)
        return dir
    }

    private fun mapDrive(prefix: File, letter: Char, target: File?) {
        val dosdevices = ensureDosDevices(prefix)
        val link = File(dosdevices, "$letter:").toPath()
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
        if (target != null) Files.createSymbolicLink(link, target.toPath())
    }

    /**
     * Wine creates C: and Z: only when it creates `dosdevices` itself
     * (ntdll `setup_config_dir`), so a directory made here for D: would leave
     * a new prefix without C:. Create all three the way Wine does.
     */
    private fun ensureDosDevices(prefix: File): File {
        val dosdevices = File(prefix, "dosdevices")
        if (dosdevices.isDirectory) return dosdevices
        if (!dosdevices.mkdirs()) throw IOException("cannot create $dosdevices")
        File(prefix, "drive_c").mkdirs()
        Files.createSymbolicLink(File(dosdevices, "c:").toPath(), File("../drive_c").toPath())
        Files.createSymbolicLink(File(dosdevices, "z:").toPath(), File("/").toPath())
        return dosdevices
    }

    private fun readRecord(dir: File): PrefixRecord? {
        val text = File(dir, ".amphora/prefix.json").readTextOrNull() ?: return null
        return try {
            AmphoraJson.decodeFromString(PrefixRecord.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
