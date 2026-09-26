package app.amphora.core.engine

import android.util.Log
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun buildWineExplorerCommand(screenInfo: String): String =
    "wine start /wait explorer /desktop=shell,$screenInfo winefile.exe"

/**
 * Build the guest Wine PROGRAM launch command.
 *
 * [exeArgs] are trailing CLI tokens after the quoted Windows exe path. They are
 * passed through [sanitizeWineExeArgs]: unsafe input is logged and dropped
 * (empty args) rather than injected into the shell command.
 */
fun buildWineProgramCommand(screenInfo: String, wineExePath: String, exeArgs: String = ""): String {
    val trimmed = sanitizeWineExeArgs(exeArgs)
    return if (trimmed.isEmpty()) {
        "wine start /wait explorer /desktop=shell,$screenInfo \"$wineExePath\""
    } else {
        "wine start /wait explorer /desktop=shell,$screenInfo \"$wineExePath\" $trimmed"
    }
}

/**
 * Sanitize trailing Wine program CLI args before embedding in a guest shell command.
 *
 * Rejects args containing `"`, `` ` ``, `$`, `|`, `;`, `&`, newline/CR, or unpaired
 * single quotes. Prefer dropping unsafe args (return empty after log) over injecting
 * them; callers must not bypass this for Intent / debug extras.
 */
fun sanitizeWineExeArgs(exeArgs: String): String {
    val trimmed = exeArgs.trim()
    if (trimmed.isEmpty()) return ""
    val hasMetachar = trimmed.any {
        it == '"' || it == '`' || it == '$' || it == '|' || it == ';' || it == '&' ||
            it == '\n' || it == '\r'
    }
    val unpairedSingleQuotes = trimmed.count { it == '\'' } % 2 != 0
    if (hasMetachar || unpairedSingleQuotes) {
        Log.w("WineLaunchCommands", "Dropping unsafe wine exeArgs: $trimmed")
        return ""
    }
    return trimmed
}

/** Already a Wine DOS path (`C:\foo.exe` or `C:/foo.exe`), not an Android file. */
fun resolveWineDosPath(path: String): String? {
    val p = path.trim()
    if (p.length < 3) return null
    if (!p[0].isLetter() || p[1] != ':') return null
    if (p[2] != '\\' && p[2] != '/') return null
    return p.replace('/', '\\')
}

/**
 * Publishes a changed executable through a same-directory temporary file so a
 * failed copy never truncates the last usable destination.
 */
fun stageExecutable(source: File, destination: File): Boolean {
    if (!source.isFile) return false
    if (destination.isFile && FileUtils.contentEquals(source, destination)) return true
    val parent = destination.parentFile ?: return false
    if (!parent.isDirectory && !parent.mkdirs()) return false
    val temporary =
        try {
            File.createTempFile(".${destination.name}.", ".tmp", parent)
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
    return try {
        if (!FileUtils.copy(source, temporary) || !FileUtils.contentEquals(source, temporary)) {
            false
        } else {
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
