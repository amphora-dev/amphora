package app.amphora.core.engine

import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.PointerButton
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient
import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * XServer-backed [InputSink] (RFC §8 输入衔接). Routes overlay touch / keyboard input into
 * the X server via `xServer.injectPointerMove` / `injectPointerButtonPress|Release` - the
 * same path WinNative's `TouchpadView` uses. `injectCharacter` requires an XKeycode
 * keymap lookup (Keyboard); MVP stubs it pending the on-screen keyboard (P4+).
 */
internal class XServerInputSink(
    private val xServer: XServer,
) : InputSink {
    override suspend fun injectPointerMove(x: Float, y: Float) {
        xServer.injectPointerMove(x.toInt(), y.toInt())
    }

    override suspend fun injectPointerButton(button: PointerButton, pressed: Boolean) {
        val pb = when (button) {
            PointerButton.LEFT -> Pointer.Button.BUTTON_LEFT
            PointerButton.RIGHT -> Pointer.Button.BUTTON_RIGHT
            PointerButton.MIDDLE -> Pointer.Button.BUTTON_MIDDLE
        }
        if (pressed) xServer.injectPointerButtonPress(pb) else xServer.injectPointerButtonRelease(pb)
    }

    override suspend fun injectCharacter(char: Char) {
        // TODO(P4+): map char -> XKeycode via Keyboard (xServer.keyboard) and injectKeyPress.
        // MVP GameSession routes touch only; character injection waits on the OSK feature.
    }
}

/**
 * ALSA-backed [AudioSink] (RFC §8 音频衔接). PCM data itself flows out-of-band over the
 * ALSA aserver Unix socket (`ALSAServerComponent` -> `ALSAClient` -> `AudioTrack`); this
 * sink only exposes volume / mute control. [setMuted] maps to
 * `ALSAClient.setOutputSuspended` (the same lever `XEnvironment.onPause` pulls).
 * [setVolume] tracks the value in [volume] for the UI; wiring it to the live `AudioTrack`
 * requires exposing the track from `ALSAClient` (P4+ audio polish).
 */
internal class XServerAudioSink : AudioSink {
    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    private var muted = false

    override suspend fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
        // TODO(P4+): push to ALSAClient's AudioTrack once exposed.
    }

    override suspend fun setMuted(muted: Boolean) {
        this.muted = muted
        ALSAClient.setOutputSuspended(muted)
    }
}
