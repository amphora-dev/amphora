package app.amphora.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.AppGraph
import app.amphora.Message
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.library.Game
import app.amphora.sdk.session.LaunchPreview
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the game entry is: still loading, gone, or here. */
sealed interface GameLookup {
    data object Loading : GameLookup

    data object Missing : GameLookup

    data class Found(val game: Game) : GameLookup
}

/** The launch preview, which needs the runtime. */
sealed interface PreviewState {
    data object NoRuntime : PreviewState

    data object Computing : PreviewState

    data class Ready(val preview: LaunchPreview) : PreviewState

    data class Failed(val message: String) : PreviewState
}

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    private val sdk = graph.sdk

    val game: StateFlow<GameLookup> =
        sdk.library.games
            .map { games ->
                when {
                    games == null -> GameLookup.Loading
                    else -> games.firstOrNull { it.id == id }?.let { GameLookup.Found(it) } ?: GameLookup.Missing
                }
            }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameLookup.Loading)

    val session: StateFlow<SessionState> = sdk.sessions.state

    val runtimeReady: StateFlow<Boolean> =
        sdk.runtime.state
            .map { it is RuntimeState.Ready }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sdk.runtime.current != null)

    /** Recomputed whenever the game, the runtime or any setting that reaches it changes. */
    val preview: StateFlow<PreviewState> =
        game
            .flatMapLatest { lookup ->
                val found = (lookup as? GameLookup.Found)?.game ?: return@flatMapLatest flowOf(null)
                combine(runtimeReady, sdk.settings.game(found)) { ready, _ -> if (ready) found else null }
            }.mapLatest { found ->
                if (found == null) return@mapLatest PreviewState.NoRuntime
                try {
                    sdk.sessions.preview(LaunchTarget.Play(found))?.let { PreviewState.Ready(it) }
                        ?: PreviewState.NoRuntime
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PreviewState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreviewState.Computing)

    fun rename(title: String) {
        viewModelScope.launch {
            sdk.library.rename(id, title)?.let { graph.messages.show("改名失败：$it", Message.Tone.Error) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            when (val error = sdk.library.refresh(id)) {
                null -> graph.messages.show("已重新读取可执行文件", Message.Tone.Ok)
                else -> graph.messages.show("重新读取失败：$error", Message.Tone.Error)
            }
        }
    }

    /** Removal outlives the screen, which closes as soon as the entry is gone. */
    fun remove() {
        val title = (game.value as? GameLookup.Found)?.game?.title
        graph.scope.launch {
            when (val error = sdk.library.remove(id)) {
                null -> graph.messages.show("已移除「${title.orEmpty()}」，游戏文件没动", Message.Tone.Ok)
                else -> graph.messages.show("移除失败：$error", Message.Tone.Error)
            }
        }
    }

    fun resume() = graph.launcher.resume()

    fun end() = graph.launcher.end()
}
