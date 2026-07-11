package app.amphora.core.engine

import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.PointerButton
import app.amphora.core.engine.model.SessionHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder engine until the `com.winlator.cmod` runtime port lands
 * (RFC §7 / D9). Wired so the Hilt graph is valid end-to-end; [launch] throws
 * to make the not-yet-implemented state explicit at the call site.
 */
@Singleton
class StubWineEngine @Inject constructor() : WineEngine {
    override suspend fun launch(spec: LaunchSpec): SessionHandle =
        TODO("WineEngine implementation pending runtime port (spec=$spec) - see RFC §7/D9")

    override fun inputFeed(): InputSink = StubInputSink
    override fun audioSink(): AudioSink = StubAudioSink
}

private object StubInputSink : InputSink {
    override suspend fun injectPointerMove(x: Float, y: Float) {}
    override suspend fun injectPointerButton(button: PointerButton, pressed: Boolean) {}
    override suspend fun injectCharacter(char: Char) {}
}

private object StubAudioSink : AudioSink {
    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    override suspend fun setVolume(volume: Float) { _volume.value = volume.coerceIn(0f, 1f) }
    override suspend fun setMuted(muted: Boolean) {}
}
