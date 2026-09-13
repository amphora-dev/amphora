package app.amphora.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.engine.RuntimeSettingsStore
import app.amphora.feature.launcher.LauncherProgramLibrary
import app.amphora.feature.launcher.RecentProgram
import app.amphora.feature.launcher.Resolution
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DesktopUiState(
    val programs: List<RecentProgram> = emptyList(),
    val resolution: Resolution = Resolution.DEFAULT,
    /** Last program started from this desktop (taskbar "this game"). */
    val activeSessionLabel: String? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class DesktopViewModel
@Inject
constructor(
    private val programLibrary: LauncherProgramLibrary,
    private val settingsStore: RuntimeSettingsStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val base = MutableStateFlow(DesktopUiState())

    val uiState: StateFlow<DesktopUiState> =
        combine(base, settingsStore.settings) { state, settings ->
            state.copy(resolution = Resolution.fromPreference(settings.resolutionName))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DesktopUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            base.update { it.copy(loading = true) }
            val programs = withContext(dispatchers.io) { programLibrary.listRecent() }
            base.update { it.copy(programs = programs, loading = false) }
        }
    }

    fun onProgramLaunched(program: RecentProgram) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                runCatching { programLibrary.markLaunched(program.path) }
            }
            val programs = withContext(dispatchers.io) { programLibrary.listRecent() }
            base.update {
                it.copy(
                    programs = programs,
                    activeSessionLabel = program.name.removeSuffix(".exe").removeSuffix(".EXE"),
                )
            }
        }
    }

    fun clearActiveSession() {
        base.update { it.copy(activeSessionLabel = null) }
    }
}
