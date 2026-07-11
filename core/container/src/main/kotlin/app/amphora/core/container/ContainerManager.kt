package app.amphora.core.container

import app.amphora.core.container.model.Container
import app.amphora.core.container.model.ContainerId

/**
 * Wine prefix lifecycle (RFC §6 / §7 `runtime/container`). Creates / loads a
 * `WINEPREFIX` and manages its essential files. Implementation is ported from
 * WinNative `ContainerManager` (861 lines) - kept Java, packaged as
 * `com.winlator.cmod` (RFC §7) so the native JNI bindings resolve unchanged.
 */
interface ContainerManager {
    suspend fun getOrCreate(id: ContainerId): Container
    suspend fun list(): List<Container>
    suspend fun delete(id: ContainerId): Boolean
}
