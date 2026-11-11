package app.amphora.sdk.library

import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.io.sha256
import app.amphora.sdk.internal.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

public sealed interface AddResult {
    public data class Added(val game: Game) : AddResult

    public data class AlreadyAdded(val game: Game) : AddResult

    public data class Rejected(val reason: String) : AddResult
}

@Serializable
internal data class LibraryFile(val format: Int = 1, val games: List<Game> = emptyList())

/** The games the user added, persisted in `library.json`. */
public class GameLibrary internal constructor(
    private val paths: AmphoraPaths,
    private val io: CoroutineContext,
    scope: CoroutineScope,
    private val onRemoved: suspend (Game) -> Unit,
) {
    private val mutex = Mutex()
    private val _games = MutableStateFlow<List<Game>?>(null)

    /** Null until loaded; then sorted by last played, then title. */
    public val games: StateFlow<List<Game>?> = _games.asStateFlow()

    init {
        scope.launch { load() }
    }

    public fun find(id: String): Game? = _games.value?.firstOrNull { it.id == id }

    /** The machine type of [exe], or null when it is not a Windows executable. Reads only the header. */
    public suspend fun inspect(exe: File): PeArch? = withContext(io) { PeInfo.arch(exe) }

    /** Adds [exe]: checks it is a PE executable, hashes it, picks a title. */
    public suspend fun add(exe: File): AddResult {
        load()
        val path = exe.absolutePath
        _games.value?.firstOrNull { it.exePath == path }?.let { return AddResult.AlreadyAdded(it) }
        if (!exe.isFile) return AddResult.Rejected("找不到文件，或没有读取权限")
        val arch = withContext(io) { PeInfo.arch(exe) } ?: return AddResult.Rejected("不是 Windows 可执行文件")
        return try {
            val game =
                withContext(io) {
                    Game(
                        id = UUID.randomUUID().toString().replace("-", "").take(12),
                        title = suggestTitle(exe),
                        exePath = path,
                        exeSha256 = sha256(exe),
                        exeSize = exe.length(),
                        arch = arch,
                        detectedAppId = detectAppId(exe.parentFile),
                        addedAt = System.currentTimeMillis(),
                    )
                }
            mutate { it + game }
            AddResult.Added(game)
        } catch (e: IOException) {
            AmphoraLog.e("could not add $path", e)
            AddResult.Rejected("读写失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Returns why it failed, or null. A blank title is ignored. */
    public suspend fun rename(id: String, title: String): String? {
        val clean = title.trim()
        if (clean.isEmpty()) return null
        return guarded("rename $id") {
            mutate { games ->
                games.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(title = clean)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Removes the entry and its settings; the game's files are not touched. Returns why it failed, or null. */
    public suspend fun remove(id: String): String? {
        val game = find(id) ?: return null
        return guarded("remove $id") {
            mutate { games -> games.filterNot { it.id == id } }
            onRemoved(game)
        }
    }

    /**
     * Re-reads steam_appid.txt and the exe's hash, e.g. after the game
     * updated. Returns why it failed, or null.
     */
    public suspend fun refresh(id: String): String? {
        val game = find(id) ?: return null
        val exe = File(game.exePath)
        if (!exe.isFile) return "找不到 ${exe.path}"
        return guarded("refresh $id") {
            val hash = withContext(io) { sha256(exe) }
            val appId = withContext(io) { detectAppId(exe.parentFile) }
            val size = withContext(io) { exe.length() }
            mutate { games ->
                games.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(exeSha256 = hash, exeSize = size, detectedAppId = appId)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: IOException) {
        AmphoraLog.e("library: $what failed", e)
        e.message ?: e.javaClass.simpleName
    }

    /** Best effort: losing a play-time entry must not stop a session from being finalized. */
    internal suspend fun recordPlay(id: String, startedAt: Long, seconds: Long) {
        guarded("record play $id") {
            mutate { games ->
                games.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(lastPlayedAt = startedAt, playSeconds = it.playSeconds + seconds)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private suspend fun load() {
        if (_games.value != null) return
        mutex.withLock {
            if (_games.value != null) return
            _games.value = sort(withContext(io) { read() })
        }
    }

    private fun read(): List<Game> {
        val file = paths.library
        if (!file.isFile) return emptyList()
        return try {
            AmphoraJson.decodeFromString(LibraryFile.serializer(), file.readText()).games
        } catch (e: SerializationException) {
            AmphoraLog.e("library.json is unreadable; keeping it aside", e)
            file.renameTo(File(file.parentFile, "library.json.corrupt-${System.currentTimeMillis()}"))
            emptyList()
        } catch (e: IllegalArgumentException) {
            AmphoraLog.e("library.json is unreadable; keeping it aside", e)
            file.renameTo(File(file.parentFile, "library.json.corrupt-${System.currentTimeMillis()}"))
            emptyList()
        }
    }

    private suspend fun mutate(transform: (List<Game>) -> List<Game>) {
        load()
        mutex.withLock {
            val next = sort(transform(_games.value.orEmpty()))
            val json = AmphoraJson.encodeToString(LibraryFile.serializer(), LibraryFile(games = next))
            withContext(io) { writeTextAtomically(paths.library, json) }
            _games.value = next
        }
    }

    private fun sort(games: List<Game>): List<Game> =
        games.sortedWith(compareByDescending<Game> { it.lastPlayedAt ?: 0L }.thenBy { it.title.lowercase() })

    internal companion object {
        private val platformSuffix = Regex("[-_.]?(win64|win32|shipping|x64|x86)")
        private val genericNames =
            setOf(
                "game", "launcher", "start", "play", "main", "run", "client", "app",
                "setup", "shipping", "win64", "win32", "bin", "x64", "x86",
            )

        /** The exe name, unless it is generic ("Game.exe"); then the nearest meaningful folder name. */
        fun suggestTitle(exe: File): String {
            val stem = exe.nameWithoutExtension
            // A stem made only of platform tags ("Win64", "x64-Shipping") strips to nothing: generic too.
            val core = stem.lowercase().replace(platformSuffix, "")
            if (core.isNotEmpty() && core !in genericNames) return stem
            var dir = exe.parentFile
            while (dir != null && dir.name.lowercase() in genericNames) dir = dir.parentFile
            return dir?.name?.takeIf { it.isNotBlank() } ?: stem
        }

        fun detectAppId(dir: File?): Long? {
            val file = File(dir ?: return null, "steam_appid.txt")
            if (!file.isFile || file.length() > 64) return null
            return file.readText().trim().toLongOrNull()?.takeIf { it > 0 }
        }
    }
}
