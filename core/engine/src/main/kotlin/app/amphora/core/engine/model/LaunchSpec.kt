package app.amphora.core.engine.model

import app.amphora.core.container.model.ContainerId

data class DisplaySize(val width: Int, val height: Int)

/**
 * What the engine needs to start a Wine session (RFC §6 / §8). The launch
 * command itself (`box64 wine explorer /desktop=WxH exe`) is constructed by the
 * ported `GuestProgramLauncherComponent` - Amphora only passes exe + env, it
 * never rewrites `getWineStartCommand` (RFC D9).
 */
data class LaunchSpec(
    val exePath: String,
    val containerId: ContainerId,
    val displaySize: DisplaySize,
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
)
