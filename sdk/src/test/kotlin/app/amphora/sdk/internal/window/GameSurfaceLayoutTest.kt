package app.amphora.sdk.internal.window

import org.junit.Assert.assertEquals
import org.junit.Test

class GameSurfaceLayoutTest {
    @Test
    fun `scale modes are looked up by their setting key, defaulting to fit`() {
        assertEquals(ScaleMode.Fit, ScaleMode.of("fit"))
        assertEquals(ScaleMode.Stretch, ScaleMode.of("stretch"))
        assertEquals(ScaleMode.Integer, ScaleMode.of("integer"))
        assertEquals(ScaleMode.Fit, ScaleMode.of("zoom"))
        assertEquals(ScaleMode.Fit, ScaleMode.of(""))
        assertEquals(ScaleMode.Fit, ScaleMode.of("STRETCH"))
    }

    @Test
    fun `fit keeps the aspect ratio and centers with bars`() {
        assertEquals(SurfaceTransform(1.5f, 1.5f, 240f, 0f), transform(1280, 720, 2400, 1080, ScaleMode.Fit))
        // Portrait screen: bars above and below.
        val portrait = transform(1280, 720, 1080, 2400, ScaleMode.Fit)
        assertTransform(SurfaceTransform(0.84375f, 0.84375f, 0f, 896.25f), portrait)
    }

    @Test
    fun `stretch fills the container`() {
        assertEquals(SurfaceTransform(1.875f, 1.5f, 0f, 0f), transform(1280, 720, 2400, 1080, ScaleMode.Stretch))
        assertEquals(SurfaceTransform(0.5f, 2f, 0f, 0f), transform(1280, 720, 640, 1440, ScaleMode.Stretch))
    }

    @Test
    fun `integer uses the largest whole multiple that fits`() {
        assertEquals(SurfaceTransform(1f, 1f, 560f, 180f), transform(1280, 720, 2400, 1080, ScaleMode.Integer))
        assertEquals(SurfaceTransform(3f, 3f, 240f, 0f), transform(640, 360, 2400, 1080, ScaleMode.Integer))
        assertEquals(SurfaceTransform(2f, 2f, 0f, 0f), transform(640, 480, 1280, 960, ScaleMode.Integer))
    }

    @Test
    fun `integer falls back to fit when the content is larger than the container`() {
        val fit = transform(1920, 1080, 1600, 720, ScaleMode.Fit)
        assertTransform(SurfaceTransform(2f / 3, 2f / 3, 160f, 0f), fit)
        assertEquals(fit, transform(1920, 1080, 1600, 720, ScaleMode.Integer))
    }

    @Test
    fun `content of the container size is left alone in every mode`() {
        for (mode in ScaleMode.entries) {
            assertEquals(mode.key, SurfaceTransform(1f, 1f, 0f, 0f), transform(1280, 720, 1280, 720, mode))
        }
    }

    @Test
    fun `unknown sizes give the identity transform`() {
        val identity = SurfaceTransform(1f, 1f, 0f, 0f)
        val sizes =
            listOf(
                listOf(0, 720, 2400, 1080),
                listOf(1280, 0, 2400, 1080),
                listOf(1280, 720, 0, 1080),
                listOf(1280, 720, 2400, -1),
            )
        for ((w, h, containerW, containerH) in sizes) {
            for (mode in ScaleMode.entries) {
                assertEquals("$w $h $containerW $containerH", identity, transform(w, h, containerW, containerH, mode))
            }
        }
    }

    private fun transform(w: Int, h: Int, containerW: Int, containerH: Int, mode: ScaleMode) =
        GameSurfaceLayout.transform(w, h, containerW, containerH, mode)

    private fun assertTransform(expected: SurfaceTransform, actual: SurfaceTransform) {
        val delta = 1e-3f
        assertEquals("scaleX", expected.scaleX, actual.scaleX, delta)
        assertEquals("scaleY", expected.scaleY, actual.scaleY, delta)
        assertEquals("offsetX", expected.offsetX, actual.offsetX, delta)
        assertEquals("offsetY", expected.offsetY, actual.offsetY, delta)
    }
}
