package app.amphora.core.engine

import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.PointerButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Temporary scaffold bindings. `:core:engine` is the only Hilt-equipped module,
 * so the remaining sibling-interface stub ([StubContainerManager] P4) lives here
 * and is @Provides-bound in [app.amphora.core.engine.di.EngineModule].
 * [RootfsInstaller] (P2) and [WineSessionPreparer] (P2) graduated to their real
 * concretions ([ImageFsRootfsInstaller] / [XServerWineSessionPreparer]).
 *
 * All method bodies are `TODO` tagged with the owning phase + the XSDA source
 * line / port target, so the next agent can grep straight to the work.
 */
internal class StubContainerManager : ContainerManager {
    override suspend fun getOrCreate(id: ContainerId): Container =
        TODO("P4: port WinNative ContainerManager (861 lines) -> :core:container")
    override suspend fun list(): List<Container> =
        TODO("P4: ContainerManager.list")
    override suspend fun delete(id: ContainerId): Boolean =
        TODO("P4: ContainerManager.delete")
}

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
