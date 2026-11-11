package app.amphora.system

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.AppGraph
import app.amphora.Message
import app.amphora.sdk.container.PrefixInfo
import app.amphora.sdk.engine.DeviceInfo
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.engine.VerifyResult
import app.amphora.sdk.library.Game
import app.amphora.sdk.logs.LogFile
import app.amphora.sdk.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A value that is loaded once and refreshed on demand. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Done<T>(val value: T) : Load<T>

    data class Failed(val message: String) : Load<Nothing>
}

data class SystemUi(
    val device: Load<DeviceInfo> = Load.Loading,
    val prefix: Load<PrefixInfo> = Load.Loading,
    val logs: Load<List<LogFile>> = Load.Loading,
    val verify: Load<VerifyResult>? = null,
    val storageAccess: Boolean = false,
    /** Prefix reset or runtime removal running. */
    val busy: Boolean = false,
)

class SystemViewModel(private val graph: AppGraph) : ViewModel() {
    private val sdk = graph.sdk
    private val _ui = MutableStateFlow(SystemUi())
    val ui: StateFlow<SystemUi> = _ui.asStateFlow()

    val runtime: StateFlow<RuntimeState> = sdk.runtime.state
    val session: StateFlow<SessionState> = sdk.sessions.state
    val recipe = sdk.recipe
    val dataDir = sdk.dataDir
    val sideloadDir = sdk.sideloadDir

    init {
        loadDevice()
        refresh()
    }

    /** What can change while the screen is away: runtime archive, prefix, logs, storage access. */
    fun refresh() {
        sdk.runtime.refresh()
        _ui.update { it.copy(storageAccess = Environment.isExternalStorageManager()) }
        viewModelScope.launch { _ui.update { it.copy(prefix = load { sdk.prefixes.info(Game.DEFAULT_PREFIX) }) } }
        viewModelScope.launch { _ui.update { it.copy(logs = load { sdk.logs.list() }) } }
    }

    private fun loadDevice() {
        viewModelScope.launch { _ui.update { it.copy(device = load { sdk.device() }) } }
    }

    fun install() = sdk.runtime.install()

    fun cancelInstall() = sdk.runtime.cancel()

    fun verify() {
        _ui.update { it.copy(verify = Load.Loading) }
        viewModelScope.launch { _ui.update { it.copy(verify = load { sdk.runtime.verify() }) } }
    }

    fun uninstall() = busy("运行时已删除") {
        sdk.runtime.uninstall()
        _ui.update { it.copy(verify = null) }
    }

    fun resetPrefix() = busy("前缀已删除，下次启动会重建") {
        sdk.prefixes.reset(Game.DEFAULT_PREFIX)
        _ui.update { it.copy(prefix = load { sdk.prefixes.info(Game.DEFAULT_PREFIX) }) }
    }

    fun clearLogs() = busy("日志已清空") {
        sdk.logs.clear()
        _ui.update { it.copy(logs = load { sdk.logs.list() }) }
    }

    /** Destructive work runs in the app scope: leaving the screen must not stop it halfway. */
    private fun busy(done: String, block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        graph.scope.launch {
            try {
                block()
                graph.messages.show(done, Message.Tone.Ok)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.messages.show("失败：${e.message ?: e.javaClass.simpleName}", Message.Tone.Error)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: e.javaClass.simpleName)
    }
}
