package app.amphora.core.engine

/**
 * 容器里的 DX 包装选择。Amphora 只认一种格式：
 * `dxvk-<ver>;vkd3d-<ver>;<ddraw>`
 */
data class DxWrapperSelection(
    val dxvk: String,
    val vkd3d: String,
    val ddraw: String,
) {
    fun gateKey(arch: String): String = "${asDelimited()}|arch=$arch"

    fun asDelimited(): String = listOf(dxvk, vkd3d, ddraw).joinToString(";")

    companion object {
        fun parse(raw: String): DxWrapperSelection? {
            if (raw.isBlank() || !raw.contains(";")) return null
            val parts = raw.split(";")
            return DxWrapperSelection(
                dxvk = parts.getOrNull(0).orEmpty(),
                vkd3d = parts.getOrNull(1).orEmpty(),
                ddraw = parts.getOrNull(2).orEmpty(),
            )
        }
    }
}
