package app.amphora.feature.launcher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.amphora.core.engine.GuestFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** Blocking Android/file operations for programs shown by the launcher. */
@Singleton
class LauncherProgramLibrary internal constructor(
    private val programDirectory: File,
    private val uris: LauncherProgramUris,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        GuestFiles.exeDir(context),
        ContentResolverLauncherProgramUris(context),
    )

    /** Copies a picked document through a temporary sibling before replacing its destination. */
    fun stage(uri: Uri): String {
        programDirectory.mkdirs()
        val root = programDirectory.canonicalFile
        val displayName = uris.displayName(uri)
        val fileName =
            displayName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf(String::isNotBlank)
                ?: FALLBACK_FILE_NAME
        val destination = File(root, fileName).canonicalFile
        if (destination.parentFile != root) throw IOException("Unsafe program filename: $displayName")
        val input = uris.openInputStream(uri) ?: throw IOException("Cannot open picked file: $uri")
        val temporary = File.createTempFile(".program-", ".tmp", root)

        try {
            input.use { source ->
                temporary.outputStream().use { output -> source.copyTo(output) }
            }
            temporary.setLastModified(System.currentTimeMillis())
            moveReplacing(temporary, destination)
            return destination.absolutePath
        } finally {
            temporary.delete()
        }
    }

    /** Lists direct regular `.exe` files, newest modification time first. */
    fun listRecent(): List<RecentProgram> = programDirectory.canonicalFile
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter {
            Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                it.extension.equals("exe", ignoreCase = true)
        }.sortedByDescending(File::lastModified)
        .map { RecentProgram(it.absolutePath, it.name, it.lastModified()) }
        .toList()

    /** Updates launch ordering for a regular file contained directly by the program directory. */
    fun markLaunched(path: String) {
        val root = programDirectory.canonicalFile
        val supplied = File(path)
        val program = supplied.canonicalFile
        if (
            program.parentFile != root ||
            !Files.isRegularFile(supplied.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Program is outside the managed directory: $path")
        }
        program.setLastModified(System.currentTimeMillis())
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val FALLBACK_FILE_NAME = "game.exe"
    }
}

/** The narrow SAF boundary used by [LauncherProgramLibrary]. */
internal interface LauncherProgramUris {
    fun displayName(uri: Uri): String?

    fun openInputStream(uri: Uri): InputStream?
}

private class ContentResolverLauncherProgramUris(private val context: Context) : LauncherProgramUris {
    override fun displayName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun openInputStream(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
}
