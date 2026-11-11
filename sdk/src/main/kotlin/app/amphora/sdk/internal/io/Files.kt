package app.amphora.sdk.internal.io

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Replaces [file] with [bytes] so a reader sees either the old or the new
 * content, never a torn write: write a sibling temp file, fsync it, rename it
 * over the target. Crashing midway leaves at most a stray temp file.
 */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory: $file")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
    val tmp = File(dir, ".${file.name}.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("cannot replace $file")
    }
}

internal fun writeTextAtomically(file: File, text: String): Unit = writeAtomically(file, text.encodeToByteArray())

internal fun File.readTextOrNull(): String? = try {
    if (isFile) readText() else null
} catch (_: IOException) {
    null
}

/** Size and entry count of a tree, without following symbolic links. */
internal data class TreeSize(val bytes: Long, val files: Int, val links: Int)

internal fun measureTree(root: File): TreeSize {
    val path = root.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return TreeSize(0, 0, 0)
    var bytes = 0L
    var files = 0
    var links = 0
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                when {
                    attrs.isSymbolicLink -> links++
                    attrs.isRegularFile -> {
                        files++
                        bytes += attrs.size()
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    return TreeSize(bytes, files, links)
}

/**
 * Deletes a tree without ever following a symbolic link out of it: a prefix
 * links into the runtime and to the user's game folders, and deleting a prefix
 * must never delete those. Links are removed as links.
 */
internal fun deleteTree(root: File) {
    val path = root.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(path)
        return
    }
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Read-only files and directories (0444 / 0555 store entries) still delete:
                // unlinking needs write permission on the parent, which we restore below.
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                dir.toFile().setWritable(true, true)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

/**
 * Deletes [dir] by first renaming it into [trash], so the original path is
 * free (and consistent) immediately; the slow recursive delete happens after.
 */
internal fun moveToTrashAndDelete(dir: File, trash: File) {
    if (!dir.exists() && !Files.isSymbolicLink(dir.toPath())) return
    trash.mkdirs()
    val target = File(trash, "${dir.name}-${System.nanoTime()}")
    val victim = if (dir.renameTo(target)) target else dir
    deleteTree(victim)
}

/** Empties [trash], e.g. after a crash interrupted [moveToTrashAndDelete]. */
internal fun emptyTrash(trash: File) {
    trash.listFiles()?.forEach { runCatching { deleteTree(it) } }
}
