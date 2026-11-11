package app.amphora.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.AppGraph
import app.amphora.Message
import app.amphora.sdk.logs.LogFile
import app.amphora.sdk.session.SessionState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsViewModel(private val graph: AppGraph) : ViewModel() {
    private val _logs = MutableStateFlow<List<LogFile>?>(null)
    val logs: StateFlow<List<LogFile>?> = _logs.asStateFlow()

    /** The live session's id: its log is still being written. */
    val liveId: StateFlow<String?> =
        graph.sdk.sessions.state
            .map { (it as? SessionState.Live)?.session?.id }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        viewModelScope.launch { _logs.value = graph.sdk.logs.list() }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            graph.sdk.logs.delete(id)
            _logs.value = graph.sdk.logs.list()
        }
    }

    fun clear() {
        viewModelScope.launch {
            graph.sdk.logs.clear()
            _logs.value = graph.sdk.logs.list()
            graph.messages.show("日志已清空", Message.Tone.Ok)
        }
    }
}

data class LogText(
    val lines: List<String>,
    /** The file is longer than what was read. */
    val truncated: Boolean,
    val bytes: Long,
)

/**
 * One log. Read once; while its session is live and [follow] is on, re-read
 * every second (only when the file grew). Reading stops when nobody watches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModel(private val graph: AppGraph, val id: String) : ViewModel() {
    val follow = MutableStateFlow(true)
    private val reloads = MutableStateFlow(0)

    val live: StateFlow<Boolean> =
        graph.sdk.sessions.state
            .map { (it as? SessionState.Live)?.session?.id == id }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val text: StateFlow<LogText?> =
        combine(live, follow, reloads) { live, follow, _ -> live && follow }
            .flatMapLatest { tail ->
                flow {
                    var last: LogText? = null
                    while (true) {
                        val next = read(last)
                        if (next != null) {
                            last = next
                            emit(next)
                        }
                        if (!tail) break
                        delay(1_000)
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reload() {
        reloads.value++
    }

    /** Null when the file has not changed since [previous]. */
    private suspend fun read(previous: LogText?): LogText? {
        val file = graph.sdk.logs.file(id)
        val bytes = withContext(Dispatchers.IO) { file.length() }
        if (previous != null && bytes == previous.bytes) return null
        return try {
            val lines = graph.sdk.logs.tail(id, MAX_LINES, MAX_BYTES)
            LogText(lines, truncated = bytes > MAX_BYTES || lines.size >= MAX_LINES, bytes = bytes)
        } catch (e: IOException) {
            LogText(listOf("E 读不了日志：${e.message}"), truncated = false, bytes = bytes)
        }
    }

    private companion object {
        const val MAX_LINES = 5_000
        const val MAX_BYTES = 1 shl 20
    }
}
