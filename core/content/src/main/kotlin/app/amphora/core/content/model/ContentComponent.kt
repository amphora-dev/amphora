package app.amphora.core.content.model

enum class ContentComponent {
    ROOTFS,
    WINE,
    BOX64,
    TURNIP,
    DXVK,
}

@JvmInline
value class ComponentId(val value: String)

val ContentComponent.id: ComponentId get() = ComponentId(name.lowercase())
