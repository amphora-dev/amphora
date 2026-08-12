package app.amphora.core.engine

import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.PointerButton
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient
import com.winlator.cmod.runtime.display.environment.components.PulseAudioComponent
import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * XServer-backed [InputSink] (RFC §8 输入衔接). Routes overlay touch / keyboard input into
 * the X server via `xServer.injectPointerMove` / `injectPointerButtonPress|Release` - the
 * same path WinNative's `TouchpadView` uses. Character input uses the Android IME Unicode
 * keysym path shared with the session touch overlay.
 */
internal class XServerInputSink(private val xServer: XServer) : InputSink {
    override suspend fun injectPointerMove(x: Float, y: Float) {
        xServer.injectPointerMove(x.toInt(), y.toInt())
    }

    override suspend fun injectPointerButton(button: PointerButton, pressed: Boolean) {
        val pb =
            when (button) {
                PointerButton.LEFT -> Pointer.Button.BUTTON_LEFT
                PointerButton.RIGHT -> Pointer.Button.BUTTON_RIGHT
                PointerButton.MIDDLE -> Pointer.Button.BUTTON_MIDDLE
            }
        if (pressed) xServer.injectPointerButtonPress(pb) else xServer.injectPointerButtonRelease(pb)
    }

    override suspend fun injectCharacter(char: Char) {
        xServer.injectText(char.toString())
    }
}

/**
 * Session [AudioSink] for both the ALSA/AudioTrack and PulseAudio/AAudio backends.
 */
internal class XServerAudioSink : AudioSink {
    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    val currentVolume: Float
        get() = _volume.value

    @Volatile
    var currentMuted: Boolean = false
        private set

    @Volatile
    private var pulseAudio: PulseAudioComponent? = null

    fun useAlsa() {
        pulseAudio = null
        ALSAClient.setMasterVolume(currentVolume)
        ALSAClient.setMuted(currentMuted)
    }

    fun usePulseAudio(component: PulseAudioComponent) {
        component.initializeSinkState(currentVolume, currentMuted)
        pulseAudio = component
    }

    override suspend fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
        pulseAudio?.setVolume(_volume.value) ?: ALSAClient.setMasterVolume(_volume.value)
    }

    override suspend fun setMuted(muted: Boolean) {
        currentMuted = muted
        pulseAudio?.setMuted(muted) ?: ALSAClient.setMuted(muted)
    }
}
