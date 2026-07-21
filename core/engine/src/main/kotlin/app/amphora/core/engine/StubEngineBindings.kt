package app.amphora.core.engine

import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.PointerButton

/**
 * Non-throwing [InputSink] fallback used by [WineEngineImpl.inputFeed] before
 * [WineEngineImpl.launch] has bound an [com.winlator.cmod.runtime.display.xserver.XServer].
 * Audio always goes through [XServerAudioSink] (volume/mute state; PCM is out-of-band via ALSA).
 */
internal object StubInputSink : InputSink {
    override suspend fun injectPointerMove(x: Float, y: Float) {}
    override suspend fun injectPointerButton(button: PointerButton, pressed: Boolean) {}
    override suspend fun injectCharacter(char: Char) {}
}
