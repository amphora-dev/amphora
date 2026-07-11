package app.amphora.gamesession

import androidx.lifecycle.ViewModel
import app.amphora.core.engine.WineEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GameSessionViewModel @Inject constructor(
    private val wineEngine: WineEngine,
) : ViewModel() {
    // TODO: lifecycle orchestration (launch/stop) delegating to WineEngine per RFC D9.
}
