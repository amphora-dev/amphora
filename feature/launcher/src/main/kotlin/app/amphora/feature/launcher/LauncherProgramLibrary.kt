package app.amphora.feature.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import app.amphora.core.engine.GuestFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Android and file boundaries for programs shown by the launcher.
 *
 * Methods perform blocking I/O. Callers are responsible for invoking them on an I/O dispatcher.
 */
@Singleton
class LauncherProgramLibrary internal constructor(
    private val boundary: LauncherProgramAndroidBoundary,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val updateTimestamp: (File, Long) -> Boolean = { file, timestamp ->
        file.setLastModified(timestamp)
    },
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(ContextLauncherProgramAndroidBoundary(context))

    /**
     * Copies one SAF document into the app-private guest program directory.
     *
     * A provider name is reduced to one Windows-safe `.exe` filename. The copy is written to a
     * temporary sibling first, so an empty or failed stream never truncates an existing program.
     */
    @Throws(IOException::class)
    fun stageExe(uri: Uri): String {
        val directory = requireProgramDirectory()
        val fileName = sanitizeExeFileName(boundary.queryDisplayName(uri))
        val destination = safeChild(directory, fileName)
        val input =
            boundary.openInputStream(uri)
                ?: throw IOException("Cannot open picked file: $uri")
        var temporary: File? = null

        try {
            input.use {
                val stagingFile = File.createTempFile(".program-", ".tmp", directory)
                temporary = stagingFile
                val copiedBytes =
                    FileOutputStream(stagingFile).use { output ->
                        val copied = it.copyTo(output)
                        output.fd.sync()
                        copied
                    }
                if (copiedBytes == 0L) {
                    throw IOException("Picked file is empty: $uri")
                }
            }

            val staged = checkNotNull(temporary)
            val timestamp = nowMillis()
            if (!updateTimestamp(staged, timestamp)) {
                throw IOException("Cannot update program timestamp: ${destination.absolutePath}")
            }
            moveReplacing(staged, destination)
            temporary = null
            return destination.absolutePath
        } finally {
            temporary?.delete()
        }
    }

    /** Returns direct, regular `.exe` files, newest launch first. */
    fun scanRecentPrograms(): List<RecentProgram> {
        val directory = existingProgramDirectory() ?: return emptyList()
        return directory
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { isDirectExecutable(it, directory) }
            .sortedWith(
                compareByDescending<File>(File::lastModified)
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy(File::getName),
            ).map {
                RecentProgram(
                    path = it.absolutePath,
                    name = it.name,
                    lastUsedAt = it.lastModified(),
                )
            }.toList()
    }

    /**
     * Marks a staged program as most recently launched.
     *
     * Paths outside the configured program directory, symlinks, missing files, and timestamp
     * update failures are rejected rather than silently changing launcher ordering.
     */
    @Throws(IOException::class)
    fun markProgramLaunched(path: String) {
        val directory =
            existingProgramDirectory()
                ?: throw IOException("Program directory does not exist")
        val program = requireManagedExecutable(path, directory)
        if (!updateTimestamp(program, nowMillis())) {
            throw IOException("Cannot update program timestamp: ${program.absolutePath}")
        }
    }

    fun readAppVersion(): String = boundary.readAppVersion().ifBlank { UNKNOWN_VERSION }

    private fun requireProgramDirectory(): File {
        val configured = boundary.programDirectory
        if (configured.exists()) {
            if (!configured.isDirectory) {
                throw IOException("Program directory is not a directory: ${configured.absolutePath}")
            }
        } else if (!configured.mkdirs()) {
            throw IOException("Cannot create program directory: ${configured.absolutePath}")
        }
        return configured.canonicalFile
    }

    private fun existingProgramDirectory(): File? {
        val configured = boundary.programDirectory
        if (!configured.exists()) return null
        if (!configured.isDirectory) {
            throw IOException("Program directory is not a directory: ${configured.absolutePath}")
        }
        return configured.canonicalFile
    }

    private fun safeChild(directory: File, fileName: String): File {
        val child = File(directory, fileName).canonicalFile
        if (child.parentFile != directory) {
            throw IOException("Unsafe program filename: $fileName")
        }
        return child
    }

    private fun isDirectExecutable(file: File, directory: File): Boolean = file.isFile &&
        file.extension.equals(EXE_EXTENSION, ignoreCase = true) &&
        !Files.isSymbolicLink(file.toPath()) &&
        runCatching { file.canonicalFile.parentFile == directory }.getOrDefault(false)

    private fun requireManagedExecutable(path: String, directory: File): File {
        val supplied = File(path)
        val canonical = supplied.canonicalFile
        if (
            canonical.parentFile != directory ||
            Files.isSymbolicLink(supplied.toPath()) ||
            !canonical.extension.equals(EXE_EXTENSION, ignoreCase = true)
        ) {
            throw IOException("Program path is outside the managed directory: $path")
        }
        if (!canonical.isFile) {
            throw IOException("Program does not exist: $path")
        }
        return canonical
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

    companion object {
        private const val EXE_EXTENSION = "exe"
        private const val FALLBACK_FILE_NAME = "game.exe"
        private const val MAX_STEM_UTF8_BYTES = 240
        private const val UNKNOWN_VERSION = "unknown"
        private val invalidWindowsFileNameCharacters = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        private val reservedWindowsFileNames =
            setOf("CON", "PRN", "AUX", "NUL") +
                (1..9).flatMap { listOf("COM$it", "LPT$it") }

        @Throws(IOException::class)
        internal fun sanitizeExeFileName(displayName: String?): String {
            if (displayName.isNullOrBlank()) return FALLBACK_FILE_NAME

            val leaf = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned =
                leaf
                    .map { character ->
                        when {
                            character.code < 32 || character in invalidWindowsFileNameCharacters -> '_'
                            else -> character
                        }
                    }.joinToString("")
                    .trimEnd(' ', '.')
            if (cleaned.isBlank()) return FALLBACK_FILE_NAME
            if (!cleaned.endsWith(".$EXE_EXTENSION", ignoreCase = true)) {
                throw IOException("Picked file name must end in .exe: $displayName")
            }

            val extension = cleaned.takeLast(EXE_EXTENSION.length + 1)
            var stem = cleaned.dropLast(extension.length).trimEnd(' ', '.')
            if (stem.isBlank()) return FALLBACK_FILE_NAME
            stem = truncateUtf8(stem, MAX_STEM_UTF8_BYTES).trimEnd(' ', '.')
            if (stem.isBlank()) return FALLBACK_FILE_NAME
            if (stem.substringBefore('.').uppercase(Locale.ROOT) in reservedWindowsFileNames) {
                stem = "_$stem"
            }
            return stem + extension
        }

        private fun truncateUtf8(value: String, maximumBytes: Int): String {
            val result = StringBuilder()
            var offset = 0
            var bytes = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                val characters = String(Character.toChars(codePoint))
                val codePointBytes = characters.toByteArray(Charsets.UTF_8).size
                if (bytes + codePointBytes > maximumBytes) break
                result.append(characters)
                bytes += codePointBytes
                offset += Character.charCount(codePoint)
            }
            return result.toString()
        }
    }
}

/** Only the Android APIs needed by [LauncherProgramLibrary], not a general filesystem facade. */
internal interface LauncherProgramAndroidBoundary {
    val programDirectory: File

    fun queryDisplayName(uri: Uri): String?

    fun openInputStream(uri: Uri): InputStream?

    fun readAppVersion(): String
}

private class ContextLauncherProgramAndroidBoundary(private val context: Context) : LauncherProgramAndroidBoundary {
    override val programDirectory: File
        get() = GuestFiles.exeDir(context)

    override fun queryDisplayName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                cursor.getString(column)
            } else {
                null
            }
        }

    override fun openInputStream(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    override fun readAppVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }
}
