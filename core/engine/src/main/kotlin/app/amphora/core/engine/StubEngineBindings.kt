package app.amphora.core.engine

import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.PointerButton
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Temporary scaffold bindings (P1). `:core:engine` is the only Hilt-equipped
 * module right now, so sibling-interface stubs live here and are @Provides-bound
 * in [app.amphora.core.engine.di.EngineModule]. When P2/P4 add Hilt + real impls
 * to `:core:rootfs` / `:core:container`, move those bindings there and delete the
 * corresponding @Provides lines in EngineModule (see `docs/03-TRACKING.md`).
 *
 * All method bodies are `TODO` tagged with the owning phase + the XSDA source
 * line / port target, so the next agent can grep straight to the work.
 */
internal class StubWineSessionPreparer : WineSessionPreparer {
    override suspend fun setupWineSystemFiles(spec: LaunchSpec, container: Container): Unit =
        TODO("P2/P3: extract setupWineSystemFiles body from XSDA (L6127) - Steam/recording stripped (D9)")
    override suspend fun ensureWinePrefixReady(container: Container): Unit =
        TODO("P2/P3: XSDA ensureWinePrefixReady (L7127)")
    override suspend fun ensureLaunchRuntimeFilesReady(container: Container): Unit =
        TODO("P2/P3: XSDA ensureLaunchRuntimeFilesReady (L6280) + ensureBox64RuntimeReady (L6290)")
    override suspend fun ensureWinePrefixEssentialFiles(container: Container): Unit =
        TODO("P2/P3: XSDA ensureWinePrefixEssentialFiles (L7164)")
    override suspend fun extractDXWrapperFiles(container: Container, dxwrapper: String): Unit =
        TODO("P2/P3: XSDA extractDXWrapperFiles (L7970) + extractD8VKIfNeeded (L8098)")
    override suspend fun extractGraphicsDriverFiles(container: Container): Unit =
        TODO("P2/P3: XSDA extractGraphicsDriverFiles (L7537) - Turnip pinned (D8)")
}

internal class StubContainerManager : ContainerManager {
    override suspend fun getOrCreate(id: ContainerId): Container =
        TODO("P4: port WinNative ContainerManager (861 lines) -> :core:container")
    override suspend fun list(): List<Container> =
        TODO("P4: ContainerManager.list")
    override suspend fun delete(id: ContainerId): Boolean =
        TODO("P4: ContainerManager.delete")
}

internal class StubRootfsInstaller : RootfsInstaller {
    override suspend fun ensureInstalled(spec: RootfsSpec): Boolean =
        TODO("P2: imagefs install/extract/version -> :core:rootfs (winlator-imagefs, termuxfs rpath)")
    override suspend fun currentVersion(): String? =
        TODO("P2: RootfsInstaller.currentVersion")
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
