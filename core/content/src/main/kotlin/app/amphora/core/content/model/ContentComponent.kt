package app.amphora.core.content.model

enum class ContentComponent {
    ROOTFS,
    WINE,
    BOX64,
    TURNIP,
    DXVK,
    /** Reserved: Pulse/PA modules archive; MVP is ALSA-only (plugin lives in imagefs). */
    AUDIO_PLUGIN,
}

@JvmInline
value class ComponentId(val value: String)

val ContentComponent.id: ComponentId get() = ComponentId(name.lowercase())
