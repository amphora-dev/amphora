package app.amphora.gamesession

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val GameSessionRoute = "game_session"

fun NavGraphBuilder.gameSessionScreen(onExit: () -> Unit) {
    composable(route = GameSessionRoute) {
        GameSessionScreen(viewModel = hiltViewModel(), onExit = onExit)
    }
}

@Composable
internal fun GameSessionScreen(viewModel: GameSessionViewModel, onExit: () -> Unit) {
    // Scaffold placeholder. The real surface pipeline — AndroidView{SurfaceView} +
    // TouchpadView overlay -> WineEngine.inputFeed()/audioSink() — lands per RFC §8/D9.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Game session (scaffold)")
    }
}
