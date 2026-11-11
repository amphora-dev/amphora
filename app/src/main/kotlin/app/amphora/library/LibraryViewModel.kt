package app.amphora.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.AppGraph
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.library.Game
import app.amphora.sdk.session.EndedSession
import app.amphora.sdk.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LibraryUi(
    /** Null while the library loads. */
    val games: List<Game>?,
    val runtime: RuntimeState,
    val session: SessionState,
    /** The session that just ended, unless dismissed. */
    val ended: EndedSession?,
) {
    val ready: Boolean get() = runtime is RuntimeState.Ready

    /** Launching is possible: runtime ready and no session in the way. */
    val canLaunch: Boolean get() = ready && session is SessionState.Idle

    val liveGameId: String? get() = (session as? SessionState.Live)?.session?.gameId
}

class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    private val sdk = graph.sdk
    private val dismissed = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryUi> =
        combine(sdk.library.games, sdk.runtime.state, sdk.sessions.state, dismissed) {
                games,
                runtime,
                session,
                dismissedId,
            ->
            val ended = (session as? SessionState.Idle)?.last?.takeIf { it.id != dismissedId }
            LibraryUi(games, runtime, session, ended)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LibraryUi(sdk.library.games.value, sdk.runtime.state.value, sdk.sessions.state.value, null),
        )

    val sideloadDir = sdk.sideloadDir

    /** On resume: an archive may have been pushed with adb meanwhile. */
    fun refresh() = sdk.runtime.refresh()

    fun install() = sdk.runtime.install()

    fun cancelInstall() = sdk.runtime.cancel()

    fun resume() = graph.launcher.resume()

    fun end() = graph.launcher.end()

    fun dismissEnded(id: String) {
        dismissed.value = id
    }
}
