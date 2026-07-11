package app.amphora.core.rootfs.model

data class RootfsSpec(
    val targetRoot: String,
    val imagefsVersion: String,
    val termuxfsSha256: String,
)
