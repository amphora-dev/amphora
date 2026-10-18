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

    /**
     * DXVK-Sarek. Installed instead of [DXVK] whenever the guest Vulkan probe
     * finds the adapter cannot meet 3.0.2's requirement set (Vulkan 1.3,
     * required features/extensions, queue/limits). On the Qualcomm proprietary
     * path with the flavor setting on auto it is installed alongside [DXVK] as
     * the split D3D8/9 build — see [app.amphora.core.engine.DxvkFlavorIds.d3d89Flavor].
     */
    DXVK_SAREK,
    VKD3D,
}

@JvmInline
value class ComponentId(val value: String)

val ContentComponent.id: ComponentId get() = ComponentId(name.lowercase())
