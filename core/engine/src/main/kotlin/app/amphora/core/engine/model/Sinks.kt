package app.amphora.core.engine.model

import kotlinx.coroutines.flow.StateFlow

enum class PointerButton { LEFT, RIGHT, MIDDLE }

/**
 * Where the wineandroid IME/input overlay feeds input (RFC §8 输入衔接).
 */
interface InputSink {
    suspend fun injectPointerMove(x: Float, y: Float)

    suspend fun injectPointerButton(button: PointerButton, pressed: Boolean)

    suspend fun injectCharacter(char: Char)
}

/**
 * Audio control surface. PCM data itself flows out-of-band over the ALSA
 * aserver Unix socket -> ALSAServerComponent -> AudioTrack (RFC §8 音频衔接).
 */
interface AudioSink {
    val volume: StateFlow<Float>

    suspend fun setVolume(volume: Float)

    suspend fun setMuted(muted: Boolean)
}
