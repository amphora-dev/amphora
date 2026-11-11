package app.amphora.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.AppGraph
import app.amphora.Message
import app.amphora.sdk.library.Game
import app.amphora.sdk.settings.ResolvedSetting
import app.amphora.sdk.settings.ResolvedSettings
import app.amphora.sdk.settings.SettingGroup
import app.amphora.sdk.settings.SettingLevel
import app.amphora.sdk.settings.SettingSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Global settings, or one game's. */
sealed interface SettingsContext {
    data object Global : SettingsContext

    data class ForGame(val game: Game) : SettingsContext

    fun inScope(spec: SettingSpec): Boolean = when (this) {
        Global -> spec.scope.global
        is ForGame -> spec.scope.game
    }

    /** The user set this setting in this context (not inherited). */
    fun setHere(item: ResolvedSetting): Boolean = when (this) {
        Global -> item.globalValue != null
        is ForGame -> item.gameValue != null
    }

    /** What the setting would be here without a value of its own. */
    fun inherited(item: ResolvedSetting): String = when (this) {
        Global -> item.spec.default
        is ForGame -> item.fix?.values?.get(item.spec.id) ?: item.globalValue ?: item.spec.default
    }
}

data class SettingsGroupUi(
    val group: SettingGroup,
    val items: List<ResolvedSetting>,
    /** In scope and visible, but above the chosen level. */
    val hidden: Int,
    val setHere: Int,
)

sealed interface SettingsUi {
    data object Loading : SettingsUi

    data object GameMissing : SettingsUi

    data class Ready(
        val context: SettingsContext,
        val level: SettingLevel,
        val resolved: ResolvedSettings,
        val groups: List<SettingsGroupUi>,
    ) : SettingsUi {
        val setHere: Int get() = groups.sumOf { it.setHere }
        val shown: Int get() = groups.sumOf { it.items.size }

        fun item(id: String): ResolvedSetting? = resolved.items.firstOrNull {
            it.spec.id == id
        }?.takeIf { context.inScope(it.spec) }
    }

    companion object {
        /**
         * What the screen lists: settings in scope whose condition holds, at
         * or below [level] — plus any the user changed, whatever their level,
         * so nothing that affects a launch is ever hidden.
         */
        fun build(context: SettingsContext, level: SettingLevel, resolved: ResolvedSettings): Ready {
            val groups =
                resolved.items
                    .filter { context.inScope(it.spec) && resolved.visible(it.spec) }
                    .groupBy { it.spec.group }
                    .map { (group, items) ->
                        val shown = items.filter { it.spec.level <= level || context.setHere(it) }
                        SettingsGroupUi(
                            group,
                            shown,
                            hidden = items.size - shown.size,
                            setHere = items.count {
                                context.setHere(it)
                            },
                        )
                    }.sortedBy { it.group.ordinal }
            return Ready(context, level, resolved, groups)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val graph: AppGraph, private val gameId: String?) : ViewModel() {
    private val sdk = graph.sdk
    private val level = graph.prefs.settingsLevel

    val state: StateFlow<SettingsUi> =
        contexts()
            .flatMapLatest { context ->
                when (context) {
                    null -> flowOf(SettingsUi.GameMissing)
                    SettingsContext.Global -> combine(sdk.settings.global(), level) { r, l ->
                        SettingsUi.build(context, l, r)
                    }
                    is SettingsContext.ForGame -> combine(sdk.settings.game(context.game), level) { r, l ->
                        SettingsUi.build(context, l, r)
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi.Loading)

    /** Null when the game is not in the library. Waits for the library to load. */
    private fun contexts(): Flow<SettingsContext?> = if (gameId == null) {
        flowOf(SettingsContext.Global)
    } else {
        sdk.library.games
            .filterNotNull()
            .map { games -> games.firstOrNull { it.id == gameId }?.let { SettingsContext.ForGame(it) } }
            .distinctUntilChanged()
    }

    fun setLevel(level: SettingLevel) = graph.prefs.setSettingsLevel(level)

    /** Sets the value here; null clears it (back to default, or to following global). */
    fun set(id: String, value: String?) {
        val context = (state.value as? SettingsUi.Ready)?.context ?: return
        viewModelScope.launch {
            val error =
                when (context) {
                    SettingsContext.Global -> sdk.settings.setGlobal(id, value)
                    is SettingsContext.ForGame -> sdk.settings.setGame(context.game, id, value)
                }
            if (error != null) graph.messages.show(error, Message.Tone.Error)
        }
    }

    fun reset(group: SettingGroup?) {
        val ready = state.value as? SettingsUi.Ready ?: return
        val ids =
            group?.let { g ->
                ready.resolved.items.filter { it.spec.group == g && ready.context.inScope(it.spec) }.map { it.spec.id }
            }
        viewModelScope.launch {
            when (val context = ready.context) {
                SettingsContext.Global -> sdk.settings.resetGlobal(ids)
                is SettingsContext.ForGame -> sdk.settings.resetGame(context.game, ids)
            }
            graph.messages.show(if (group == null) "已全部恢复" else "已恢复「${group.title}」", Message.Tone.Ok)
        }
    }
}
