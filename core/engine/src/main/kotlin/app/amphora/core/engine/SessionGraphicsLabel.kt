package app.amphora.core.engine

/** Human-readable configured guest graphics stack for the runtime performance HUD. */
internal object SessionGraphicsLabel {
    fun fromDxWrapper(dxWrapper: String?): String {
        val labels =
            dxWrapper
                .orEmpty()
                .split(';')
                .mapNotNull(::tokenLabel)
                .distinct()
        return labels.joinToString(" + ").ifEmpty { "WineD3D / auto" }
    }

    private fun tokenLabel(rawToken: String): String? {
        val token = rawToken.trim()
        if (token.isEmpty() || token.endsWith("-none", ignoreCase = true)) return null
        return when {
            token.startsWith("dxvk-", ignoreCase = true) ->
                "DXVK ${version(token, "dxvk-")}"
            token.startsWith("vkd3d-", ignoreCase = true) ->
                "VKD3D ${version(token, "vkd3d-")}"
            token.equals("wined3d", ignoreCase = true) ||
                token.equals("original-wined3d", ignoreCase = true) -> "WineD3D"
            token.equals(DirectDrawWrapperIds.DXWRAPPER_DD7TO9, ignoreCase = true) -> "Dd7to9"
            token.equals(DirectDrawWrapperIds.CNC_DDRAW, ignoreCase = true) -> "cnc-ddraw"
            token.equals(DirectDrawWrapperIds.D7VK, ignoreCase = true) -> "D7VK"
            else -> null
        }
    }

    private fun version(token: String, prefix: String): String {
        val value = token.substring(prefix.length)
        val withoutRevision =
            value.substringBeforeLast('-').takeIf {
                value.substringAfterLast('-').all(Char::isDigit)
            } ?: value
        return withoutRevision
    }
}
