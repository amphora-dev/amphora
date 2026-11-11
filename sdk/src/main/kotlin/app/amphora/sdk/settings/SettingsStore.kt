package app.amphora.sdk.settings

import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.io.writeTextAtomically
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal data class SettingsFile(val format: Int = 1, val values: Map<String, String> = emptyMap())

/**
 * The user's two layers on disk: `settings/global.json` and one file per game.
 * Loaded on first use, written atomically, one writer at a time. A file that
 * does not parse is moved aside (never silently overwritten) and treated as empty.
 */
internal class SettingsStore(private val paths: AmphoraPaths, private val io: CoroutineContext) {
    private val mutex = Mutex()
    private val loaded = ConcurrentHashMap<File, MutableStateFlow<Map<String, String>>>()

    fun global(): Flow<Map<String, String>> = observe(paths.globalSettings)

    fun game(gameId: String): Flow<Map<String, String>> = observe(paths.gameSettings(gameId))

    suspend fun globalNow(): Map<String, String> = state(paths.globalSettings).value

    suspend fun gameNow(gameId: String): Map<String, String> = state(paths.gameSettings(gameId)).value

    suspend fun updateGlobal(transform: (Map<String, String>) -> Map<String, String>) =
        update(paths.globalSettings, transform)

    suspend fun updateGame(gameId: String, transform: (Map<String, String>) -> Map<String, String>) =
        update(paths.gameSettings(gameId), transform)

    suspend fun deleteGame(gameId: String) {
        val file = paths.gameSettings(gameId)
        mutex.withLock {
            withContext(io) { file.delete() }
            loaded[file]?.value = emptyMap()
        }
    }

    private fun observe(file: File): Flow<Map<String, String>> = flow { emitAll(state(file)) }

    private suspend fun state(file: File): MutableStateFlow<Map<String, String>> {
        loaded[file]?.let { return it }
        val values = withContext(io) { read(file) }
        return loaded.getOrPut(file) { MutableStateFlow(values) }
    }

    private suspend fun update(file: File, transform: (Map<String, String>) -> Map<String, String>) {
        val state = state(file)
        mutex.withLock {
            val next = transform(state.value)
            if (next == state.value) return
            withContext(io) {
                if (next.isEmpty()) {
                    file.delete()
                } else {
                    val json = AmphoraJson.encodeToString(
                        SettingsFile.serializer(),
                        SettingsFile(values = next.toSortedMap()),
                    )
                    writeTextAtomically(file, json)
                }
            }
            state.value = next
        }
    }

    private fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return try {
            AmphoraJson.decodeFromString(SettingsFile.serializer(), file.readText()).values
        } catch (e: SerializationException) {
            quarantine(file, e)
        } catch (e: IllegalArgumentException) {
            quarantine(file, e)
        }
    }

    private fun quarantine(file: File, e: Exception): Map<String, String> {
        val aside = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        AmphoraLog.w("settings file $file is unreadable; moved to ${aside.name}", e)
        file.renameTo(aside)
        return emptyMap()
    }
}
