package app.amphora.core.engine

/**
 * 容器里的 DX 包装选择。Amphora 只认一种格式：
 * `dxvk-<ver>;vkd3d-<ver>;<ddraw>[;d3d89-<ver>]`
 *
 * The optional fourth part names a second DXVK install that supplies only
 * d3d8.dll/d3d9.dll while the first keeps dxgi/d3d10core/d3d11 — see
 * [DxvkFlavorIds.d3d89Flavor]. It stays empty whenever the launch is not
 * split, so the delimited form of an unsplit selection is unchanged.
 */
data class DxWrapperSelection(val dxvk: String, val vkd3d: String, val ddraw: String, val d3d89: String = "") {
    fun gateKey(arch: String): String = "${asDelimited()}|arch=$arch"

    fun asDelimited(): String = (listOf(dxvk, vkd3d, ddraw) + if (d3d89.isNotEmpty()) listOf(d3d89) else emptyList())
        .joinToString(";")

    companion object {
        /** Delimited-token prefix for the split D3D8/9 DXVK profile. */
        const val D3D89_PREFIX = "d3d89"

        fun parse(raw: String): DxWrapperSelection? {
            if (raw.isBlank() || !raw.contains(";")) return null
            val parts = raw.split(";")
            return DxWrapperSelection(
                dxvk = parts.getOrNull(0).orEmpty(),
                vkd3d = parts.getOrNull(1).orEmpty(),
                ddraw = parts.getOrNull(2).orEmpty(),
                d3d89 = parts.getOrNull(3).orEmpty(),
            )
        }
    }
}
