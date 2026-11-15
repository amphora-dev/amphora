package app.amphora.sdk.internal.container

import app.amphora.sdk.container.PrefixRecord
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.io.deleteTree
import app.amphora.sdk.internal.io.linkTarget
import app.amphora.sdk.internal.io.moveToTrashAndDelete
import app.amphora.sdk.internal.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** The slow parts of getting a prefix ready, for whoever shows progress. */
internal enum class PrepareStep { Template, Prefix }

/**
 * Makes a prefix from the template: directories, links and the few real files
 * are copied, nothing is run. `C:\users` is not part of the prefix: it lives
 * in `userdata/<id>` and is linked in, so rebuilding a prefix keeps saves.
 *
 * ```
 * prefixes/<id>/
 * ├── .amphora/prefix.json                    [PrefixRecord]
 * ├── system.reg user.reg userdef.reg         real: the wineserver rewrites them whole
 * ├── dosdevices/{c:, z:, d:}
 * └── drive_c/
 *     ├── windows/system32/<dll>  →  runtimes/<name>/lib/wine/aarch64-windows/<dll>
 *     └── users  →  userdata/<id>
 * ```
 */
internal class PrefixBuilder(private val paths: AmphoraPaths) {
    /**
     * Replaces prefix [record].id by a fresh copy of [template]. The new
     * prefix is assembled next to the old one and renamed into place, so a
     * prefix directory is always complete.
     */
    fun rebuild(template: File, record: PrefixRecord) {
        val id = record.id
        val dir = paths.prefix(id)
        val userdata = paths.userdata(id)
        keepUsers(dir, userdata)

        val staging = File(paths.prefixes, "$STAGING$id")
        deleteTree(staging)
        if (!staging.mkdirs()) throw IOException("cannot create $staging")
        try {
            copyTemplate(template.toPath(), staging.toPath(), dir.toPath())
            mergeTree(File(template, USERS).toPath(), userdata.toPath())
            Files.createSymbolicLink(
                File(staging, USERS).toPath(),
                File(dir, USERS).toPath().parent.relativize(userdata.toPath()),
            )
            writeTextAtomically(
                File(staging, PrefixRecord.FILE),
                AmphoraJson.encodeToString(PrefixRecord.serializer(), record),
            )
            moveToTrashAndDelete(dir, paths.trash)
            if (!staging.renameTo(dir)) throw IOException("cannot move prefix $id into place")
        } catch (e: Throwable) {
            runCatching { deleteTree(staging) }
            throw e
        }
    }

    /** Removes what interrupted rebuilds left behind. */
    fun cleanUp() {
        paths.prefixes.listFiles { file -> file.name.startsWith(STAGING) }?.forEach { runCatching { deleteTree(it) } }
    }

    /**
     * A prefix Wine filled itself (before thin prefixes) has a real
     * `C:\users`: move it out before the prefix is replaced, so its saves
     * are not lost with it.
     */
    private fun keepUsers(prefix: File, userdata: File) {
        val users = File(prefix, USERS).toPath()
        if (!Files.isDirectory(users, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.exists(userdata.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            userdata.parentFile?.mkdirs()
            if (users.toFile().renameTo(userdata)) return
        }
        mergeTree(users, userdata.toPath())
    }

    /**
     * Copies the template tree at [from] into [into], which will end up at
     * [finalDir]. Links are rewritten for the new place: one that pointed
     * inside the template (`dosdevices/c:`) points inside the prefix, and
     * relative links stay relative.
     */
    private fun copyTemplate(from: Path, into: Path, finalDir: Path) {
        val users = from.resolve(USERS)
        val server = from.resolve(PrefixTemplates.SERVER_DIR)
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == users || dir == server) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectories(into.resolve(from.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = from.relativize(file).toString()
                    val dest = into.resolve(relative)
                    when {
                        attrs.isSymbolicLink -> {
                            val raw = Files.readSymbolicLink(file)
                            if (raw.isAbsolute) {
                                Files.createSymbolicLink(dest, raw)
                            } else {
                                val target = linkTarget(file)
                                val moved =
                                    if (target.startsWith(from)) {
                                        finalDir.resolve(from.relativize(target).toString())
                                    } else {
                                        target
                                    }
                                Files.createSymbolicLink(dest, finalDir.resolve(relative).parent.relativize(moved))
                            }
                        }
                        attrs.isRegularFile -> Files.copy(file, dest)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Copies what [from] has and [into] lacks; nothing in [into] is overwritten. */
    private fun mergeTree(from: Path, into: Path) {
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(into)
            return
        }
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dest = into.resolve(from.relativize(dir).toString())
                    if (Files.isDirectory(dest)) return FileVisitResult.CONTINUE
                    // Something else took the directory's name in the user's data: leave theirs.
                    if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectories(dest)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dest = into.resolve(from.relativize(file).toString())
                    if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE
                    when {
                        attrs.isSymbolicLink -> Files.createSymbolicLink(dest, Files.readSymbolicLink(file))
                        attrs.isRegularFile -> Files.copy(file, dest)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        const val STAGING = ".staging-"
        const val USERS = "drive_c/users"
    }
}
