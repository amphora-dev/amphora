package app.amphora.core.engine

import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.PointerButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-throwing sink stubs shared by [WineEngineImpl] (bound) and [StubWineEngine]
 * (fallback) so the DI graph + any preview UI stay stable pre-P3. Replaced by
 * XServer / ALSAServer-backed sinks in P3 (RFC §8).
 */
internal object StubInputSink : InputSink {
    override suspend fun injectPointerMove(x: Float, y: Float) {}
    override suspend fun injectPointerButton(button: PointerButton, pressed: Boolean) {}
    override suspend fun injectCharacter(char: Char) {}
}

internal object StubAudioSink : AudioSink {
    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    override suspend fun setVolume(volume: Float) { _volume.value = volume.coerceIn(0f, 1f) }
    override suspend fun setMuted(muted: Boolean) {}
}
