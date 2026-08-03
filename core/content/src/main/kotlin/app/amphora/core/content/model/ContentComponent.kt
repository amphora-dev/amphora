package app.amphora.core.content.model

/**
 * Components the engine resolves through [app.amphora.core.content.ContentSource].
 *
 * The Mesa vulkan wrapper (`graphics_driver/wrapper.tzst`) is deliberately absent:
 * it is a `runtimeAssets[]` entry, provisioned by
 * [app.amphora.core.content.RuntimeAssetProvisioner] into `filesDir/runtime-assets/`
 * where the kernel reads it. It used to be pinned here as well, which meant two
 * copies of one digest that had to be bumped together.
 */
enum class ContentComponent {
    ROOTFS,
    WINE,
    BOX64,
    DXVK,
    VKD3D,
}

@JvmInline
value class ComponentId(val value: String)

val ContentComponent.id: ComponentId get() = ComponentId(name.lowercase())
