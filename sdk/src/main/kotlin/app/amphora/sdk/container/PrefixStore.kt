package app.amphora.sdk.container

import android.os.StatFs
import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.container.PrefixBuilder
import app.amphora.sdk.internal.container.PrefixInitializer
import app.amphora.sdk.internal.container.PrefixTemplates
import app.amphora.sdk.internal.container.PrepareStep
import app.amphora.sdk.internal.container.TemplateRecord
import app.amphora.sdk.internal.container.WineBoot
import app.amphora.sdk.internal.io.measureTree
import app.amphora.sdk.internal.io.moveToTrashAndDelete
import app.amphora.sdk.internal.io.readTextOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** What we record about a prefix in `<prefix>/.amphora/prefix.json`. */
@Serializable
public data class PrefixRecord(
    val id: String,
    val recipe: String,
    val runtime: String,
    val createdAt: Long,
    /** Made from the template: system files are links into the runtime. False for a prefix Wine filled itself. */
    val thin: Boolean = false,
    /** Which installation of the runtime the links point into. */
    val stamp: Long = 0,
) {
    internal companion object {
        const val FILE = ".amphora/prefix.json"
    }
}

public data class PrefixInfo(
    val id: String,
    val dir: File,
    /** Null until the first session creates the prefix. */
    val record: PrefixRecord?,
    /** Ready to run in (system.reg exists). */
    val initialized: Boolean,
    /** Bytes in real files: the registry and whatever was installed or modified. Links take no data. */
    val bytes: Long,
    val files: Int,
    val links: Int,
    /** Made for another recipe or runtime: the next launch rebuilds it (D11: no migration). */
    val stale: Boolean,
    /** `C:\users` of this prefix. It is outside the prefix, so a rebuild keeps saves. */
    val userdata: File,
    val userdataBytes: Long,
    val userdataFiles: Int,
)

/** The template prefixes are made from: built on the device, once per installed runtime. */
public data class TemplateInfo(
    val recipe: String,
    val runtime: String,
    val builtAt: Long,
    /** How long the build took: Wine's own initialization plus linking. */
    val buildMillis: Long,
    val bytes: Long,
    val files: Int,
    val links: Int,
)

/**
 * Wine prefixes (12 §3.3, D13). A prefix is thin: its system files are
 * links into the installed runtime, and the wineserver copies one into the
 * prefix before it is modified (`WINE_COW_STORE`). Only the registry and what
 * a game installs or changes take space.
 *
 * Prefixes are copies of a template that Wine initializes once per runtime
 * ([template]); making one runs nothing. A prefix made for another recipe or
 * runtime is not migrated but rebuilt at the next launch (D11); `C:\users`
 * lives outside the prefix and survives that.
 *
 * Ids are separate from game ids, so games can share a prefix: each game has
 * its own, built-in tools run in [app.amphora.sdk.library.Game.DEFAULT_PREFIX].
 */
public class PrefixStore internal constructor(
    private val paths: AmphoraPaths,
    private val recipe: Recipe,
    private val io: CoroutineContext,
    initializer: PrefixInitializer = WineBoot(paths),
    freeSpace: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    private val templates = PrefixTemplates(paths, recipe, initializer, freeSpace)
    private val builder = PrefixBuilder(paths)

    /** One change to prefixes or the template at a time. */
    private val lock = Mutex()

    public suspend fun info(id: String): PrefixInfo = withContext(io) { inspect(id, templates.current()) }

    /** Every prefix on disk, and user data whose prefix was deleted; the default prefix first. */
    public suspend fun list(): List<PrefixInfo> = withContext(io) {
        val template = templates.current()
        (names(paths.prefixes) + names(paths.userdata))
            .sortedWith(compareBy<String> { it != DEFAULT }.thenBy { it })
            .map { inspect(it, template) }
    }

    /** The template of this recipe; null until the first launch has built it. */
    public suspend fun template(): TemplateInfo? = withContext(io) {
        templates.current()?.let {
            TemplateInfo(it.recipe, it.runtime, it.builtAt, it.buildMillis, it.bytes, it.files, it.links)
        }
    }

    /**
     * Deletes the prefix; the next launch makes a fresh one. `C:\users`
     * (saves, the user's documents) is kept, and so are the game's own files:
     * links are never followed.
     */
    public suspend fun reset(id: String) {
        check(id)
        lock.withLock { withContext(io) { moveToTrashAndDelete(paths.prefix(id), paths.trash) } }
    }

    /** Deletes the prefix and its `C:\users`: everything the game wrote outside its own directory. */
    public suspend fun delete(id: String) {
        check(id)
        lock.withLock {
            withContext(io) {
                moveToTrashAndDelete(paths.prefix(id), paths.trash)
                moveToTrashAndDelete(paths.userdata(id), paths.trash)
            }
        }
    }

    /**
     * Makes prefix [id] ready for a session with [runtime] and returns its
     * directory: builds the template if this runtime has none yet, (re)builds
     * the prefix unless it is current, and maps drive D: to [gameDir] (or
     * removes D: for built-in tools). [onStep] is told what is slow.
     */
    internal suspend fun prepare(
        id: String,
        runtime: InstalledRuntime,
        gameDir: File?,
        onStep: (PrepareStep) -> Unit = {},
    ): File {
        check(id)
        return lock.withLock {
            withContext(io) {
                val template = templates.ensure(runtime) { onStep(PrepareStep.Template) }
                val dir = paths.prefix(id)
                builder.cleanUp()
                if (!current(dir, readRecord(dir), template)) {
                    onStep(PrepareStep.Prefix)
                    builder.rebuild(
                        templates.tree,
                        PrefixRecord(
                            id = id,
                            recipe = recipe.id,
                            runtime = runtime.manifest.name,
                            createdAt = System.currentTimeMillis(),
                            thin = true,
                            stamp = template.stamp,
                        ),
                    )
                }
                mapDrive(dir, 'd', gameDir)
                dir
            }
        }
    }

    private fun inspect(id: String, template: TemplateRecord?): PrefixInfo {
        val dir = paths.prefix(id)
        val record = readRecord(dir)
        val tree = measureTree(dir)
        val userdata = paths.userdata(id)
        val saved = measureTree(userdata)
        val initialized = File(dir, "system.reg").isFile
        return PrefixInfo(
            id = id,
            dir = dir,
            record = record,
            initialized = initialized,
            bytes = tree.bytes,
            files = tree.files,
            links = tree.links,
            // Without a template nothing is current; say so only once there is a prefix to speak of.
            stale = (record != null || initialized) && (template == null || !current(dir, record, template)),
            userdata = userdata,
            userdataBytes = saved.bytes,
            userdataFiles = saved.files,
        )
    }

    /** Whether the prefix at [dir] was made from [template] and is complete. */
    private fun current(dir: File, record: PrefixRecord?, template: TemplateRecord): Boolean = record != null &&
        record.thin &&
        record.recipe == template.recipe &&
        record.runtime == template.runtime &&
        record.stamp == template.stamp &&
        File(dir, "system.reg").isFile

    private fun mapDrive(prefix: File, letter: Char, target: File?) {
        val dosdevices = ensureDosDevices(prefix)
        val link = File(dosdevices, "$letter:").toPath()
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
        if (target != null) Files.createSymbolicLink(link, target.toPath())
    }

    /**
     * The template brings `dosdevices` with C: and Z:. Should it ever not:
     * Wine creates them only when it creates `dosdevices` itself (ntdll
     * `setup_config_dir`), so a directory made here for D: would leave the
     * prefix without C:. Create all of them the way Wine does.
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
        val text = File(dir, PrefixRecord.FILE).readTextOrNull() ?: return null
        return try {
            AmphoraJson.decodeFromString(PrefixRecord.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Prefix ids under [dir]; names starting with a dot are ours (staging). */
    private fun names(dir: File): Set<String> =
        dir.listFiles { file -> file.isDirectory && ID.matches(file.name) }?.mapTo(HashSet()) { it.name }.orEmpty()

    private fun check(id: String) {
        require(ID.matches(id)) { "not a prefix id: $id" }
    }

    private companion object {
        const val DEFAULT = "default"

        /** A single path component that cannot be hidden or climb out of the directory. */
        val ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    }
}
