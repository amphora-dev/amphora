package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContainerFontsTest {
    @Test
    fun packFaces_includeCnAndJpRegularBold() {
        val faces = SharedContainerFonts.PACK_FACES
        assertTrue(faces.contains(SharedContainerFonts.CN_REGULAR))
        assertTrue(faces.contains(SharedContainerFonts.CN_BOLD))
        assertTrue(faces.contains(SharedContainerFonts.JP_REGULAR))
        assertTrue(faces.contains(SharedContainerFonts.JP_BOLD))
    }

    @Test
    fun chineseLinks_useCnFaces() {
        val links = SharedContainerFonts.WINDOWS_FONT_LINKS
        assertEquals(SharedContainerFonts.CN_REGULAR, links["msyh.ttc"])
        assertEquals(SharedContainerFonts.CN_BOLD, links["msyhbd.ttc"])
        assertEquals(SharedContainerFonts.CN_REGULAR, links["simsun.ttc"])
        assertEquals(SharedContainerFonts.CN_REGULAR, links["simhei.ttf"])
    }

    @Test
    fun japaneseLinks_useJpFaces() {
        val links = SharedContainerFonts.WINDOWS_FONT_LINKS
        assertEquals(SharedContainerFonts.JP_REGULAR, links["msgothic.ttc"])
        assertEquals(SharedContainerFonts.JP_REGULAR, links["meiryo.ttc"])
        assertEquals(SharedContainerFonts.JP_BOLD, links["meiryob.ttc"])
        assertEquals(SharedContainerFonts.JP_REGULAR, links["yugothic.ttf"])
        assertEquals(SharedContainerFonts.JP_BOLD, links["yugothib.ttf"])
    }

    @Test
    fun familySubstitutes_splitCnAndJp() {
        val map = SharedContainerFonts.FAMILY_SUBSTITUTES.toMap()
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["Microsoft YaHei"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["微软雅黑"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_CN, map["SimSun"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["MS Gothic"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["Meiryo"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["Yu Gothic"])
        assertEquals(SharedContainerFonts.FONT_FAMILY_JP, map["メイリオ"])
    }
}
