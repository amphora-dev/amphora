package app.amphora.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatsTest {
    @Test
    fun `bytes use binary units with three significant digits`() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("1023 B", Formats.bytes(1023))
        assertEquals("1.00 KiB", Formats.bytes(1024))
        assertEquals("1.50 KiB", Formats.bytes(1536))
        assertEquals("15.0 MiB", Formats.bytes(15L * 1024 * 1024))
        assertEquals("153 MiB", Formats.bytes(160_218_827))
        assertEquals("-2.00 KiB", Formats.bytes(-2048))
        assertEquals("1.00 KiB/s", Formats.rate(1024))
    }

    @Test
    fun `clock shows hours only when needed`() {
        assertEquals("0:42", Formats.clock(42))
        assertEquals("2:55", Formats.clock(175))
        assertEquals("1:04:09", Formats.clock(3849))
        assertEquals("-0:05", Formats.clock(-5))
    }

    @Test
    fun `eta is coarse and unknown without a rate`() {
        assertNull(Formats.eta(1000, 0))
        assertNull(Formats.eta(-1, 10))
        assertEquals("不到 1 分钟", Formats.eta(30, 1))
        assertEquals("约 10 分钟", Formats.eta(600, 1))
        assertEquals("约 2.0 小时", Formats.eta(7200, 1))
    }

    @Test
    fun `percent floors and clamps`() {
        assertEquals("99%", Formats.percent(0.996f))
        assertEquals("100%", Formats.percent(1.5f))
        assertEquals("0%", Formats.percent(-1f))
    }

    @Test
    fun `ago picks the largest whole unit`() {
        val now = 1_000_000_000_000L
        assertEquals("刚刚", Formats.ago(now - 30_000, now))
        assertEquals("5 分钟前", Formats.ago(now - 5 * 60_000, now))
        assertEquals("3 小时前", Formats.ago(now - 3 * 3_600_000, now))
        assertEquals("2 天前", Formats.ago(now - 2 * 86_400_000L, now))
        assertEquals("2 个月前", Formats.ago(now - 65 * 86_400_000L, now))
    }

    @Test
    fun `short hashes keep twelve digits`() {
        assertEquals(
            "8ffff4c89360",
            Formats.shortHash("8ffff4c893607e46be608bc072c055112000eb8018fa84cf6bdadc3d4087a6ff"),
        )
        assertEquals("abc", Formats.shortHash("abc"))
    }
}
