package app.amphora.gamesession.wineandroid

/**
 * Classify wineandroid [setCursor] ioctl payload into host PointerIcon work.
 *
 * Matches upstream WineActivity.set_cursor + ANDROID_SetCursor:
 * - hide: handle NULL → id=0, 0×0, no bits → [System] id 0 ([PointerIcon.TYPE_NULL])
 * - system: known OCR map → android TYPE_* id, empty bits
 * - custom: ARGB bits + hotspot when width/height positive
 *
 * JNI may pass a zero-length array instead of null for empty bits.
 */
sealed class WineAndroidCursorSpec {
    data class System(val id: Int) : WineAndroidCursorSpec()

    data class Custom(
        val width: Int,
        val height: Int,
        val hotspotX: Int,
        val hotspotY: Int,
        val bits: IntArray,
    ) : WineAndroidCursorSpec() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Custom) return false
            return width == other.width &&
                height == other.height &&
                hotspotX == other.hotspotX &&
                hotspotY == other.hotspotY &&
                bits.contentEquals(other.bits)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + hotspotX
            result = 31 * result + hotspotY
            result = 31 * result + bits.contentHashCode()
            return result
        }
    }

    companion object {
        /**
         * @param id Android [android.view.PointerIcon] type when system; 0 = TYPE_NULL
         * @param bits ARGB pixels, or null / empty for system / hide
         */
        fun classify(
            id: Int,
            width: Int,
            height: Int,
            hotspotX: Int,
            hotspotY: Int,
            bits: IntArray?,
        ): WineAndroidCursorSpec {
            val need = width * height
            if (width > 0 && height > 0 && bits != null && bits.size >= need) {
                val hx = hotspotX.coerceIn(0, width - 1)
                val hy = hotspotY.coerceIn(0, height - 1)
                return Custom(width, height, hx, hy, bits.copyOf(need))
            }
            return System(id)
        }
    }
}
