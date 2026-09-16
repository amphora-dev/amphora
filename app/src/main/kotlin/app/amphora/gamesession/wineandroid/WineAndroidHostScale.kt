package app.amphora.gamesession.wineandroid

/**
 * Letterbox scale-to-fill for a guest desktop into a host Activity size.
 *
 * Pure math so multi-resolution layouts can be unit-tested without a device.
 * Wine DPI stays classic 96 ([WineAndroidDpi]); this only sizes the View tree.
 */
data class WineAndroidHostScaleLayout(
    val scale: Float,
    val offsetX: Int,
    val offsetY: Int,
    val contentWidth: Int,
    val contentHeight: Int,
) {
    companion object {
        val IDENTITY =
            WineAndroidHostScaleLayout(
                scale = 1f,
                offsetX = 0,
                offsetY = 0,
                contentWidth = 0,
                contentHeight = 0,
            )
    }
}

object WineAndroidHostScale {
    /**
     * Scale = min(hostW/guestW, hostH/guestH); content centered in host.
     * Invalid sizes return [WineAndroidHostScaleLayout.IDENTITY].
     */
    fun compute(
        guestWidth: Int,
        guestHeight: Int,
        hostWidth: Int,
        hostHeight: Int,
    ): WineAndroidHostScaleLayout {
        if (guestWidth <= 0 || guestHeight <= 0 || hostWidth <= 0 || hostHeight <= 0) {
            return WineAndroidHostScaleLayout.IDENTITY
        }
        val scale = minOf(hostWidth.toFloat() / guestWidth, hostHeight.toFloat() / guestHeight)
        val contentWidth = (guestWidth * scale).toInt()
        val contentHeight = (guestHeight * scale).toInt()
        return WineAndroidHostScaleLayout(
            scale = scale,
            offsetX = (hostWidth - contentWidth) / 2,
            offsetY = (hostHeight - contentHeight) / 2,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
    }
}
