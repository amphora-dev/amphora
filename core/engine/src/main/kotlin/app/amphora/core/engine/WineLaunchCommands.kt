package app.amphora.core.engine

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What the desktop launch opens inside the virtual desktop. */
private const val DESKTOP_PROGRAM = "winefile.exe"

fun buildWineExplorerCommand(screenInfo: String): String =
    "wine start /wait explorer /desktop=shell,$screenInfo $DESKTOP_PROGRAM"

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
 * Cache-miss variant of [buildWineProgramCommand]: run `amphora-vkprobe.exe`
 * as the session's launch target — it writes its Vulkan report into the
 * prefix, waits for the host's go file, then spawns [wineExePath]. The outer
 * `wine start` process the session tracks is unchanged.
 */
fun buildWineProbeCommand(screenInfo: String, wineExePath: String, exeArgs: String = ""): String {
    val trimmed = sanitizeWineExeArgs(exeArgs)
    val args = if (trimmed.isEmpty()) "" else " $trimmed"
    return "wine start /wait explorer /desktop=shell,$screenInfo " +
        "\"C:\\${GuestVulkanProbe.PROBE_EXE_NAME}\" --then \"$wineExePath\"$args"
}

/**
 * Cache-miss variant of [buildWineExplorerCommand]: the probe chains the
 * desktop's program (found on Wine's search path) instead of a game.
 */
fun buildWineExplorerProbeCommand(screenInfo: String): String = buildWineProbeCommand(screenInfo, DESKTOP_PROGRAM)

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
 * Makes [destination] (inside the prefix's `drive_c`) a symlink to [source]
 * so Wine runs the staged program as `C:\<name>.exe` without a second copy.
 * Wine opens the image through the link, so the module path the program sees
 * stays on `C:`. An existing link to [source] is kept; a regular file or a
 * link elsewhere is replaced through a same-directory temporary link, so a
 * failure never removes the last usable destination.
 */
fun linkExecutable(source: File, destination: File): Boolean {
    if (!source.isFile) return false
    val target = source.absoluteFile.toPath()
    val path = destination.toPath()
    if (Files.isSymbolicLink(path) && runCatching { Files.readSymbolicLink(path) }.getOrNull() == target) {
        return true
    }
    val parent = destination.parentFile ?: return false
    if (!parent.isDirectory && !parent.mkdirs()) return false
    val temporary = File(parent, ".${destination.name}.${System.nanoTime()}.lnk").toPath()
    return try {
        Files.createSymbolicLink(temporary, target)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    } finally {
        runCatching { Files.deleteIfExists(temporary) }
    }
}
