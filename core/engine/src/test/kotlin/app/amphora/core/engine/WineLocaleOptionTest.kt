package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WineLocaleOptionTest {
    @Test
    fun automaticLocaleUsesDeviceLocale() {
        assertEquals("ko_KR.UTF-8", WineLocaleOption.AUTO.resolve("ko_KR.UTF-8"))
    }

    @Test
    fun automaticUnsupportedLocaleFallsBackCompletelyToEnglish() {
        assertEquals("en_US.UTF-8", WineLocaleOption.AUTO.resolve("hi_IN.UTF-8"))
        assertEquals("en_US.UTF-8", WineLocaleOption.AUTO.resolve("C.UTF-8"))
        assertEquals("en_US.UTF-8", WineLocaleOption.AUTO.resolve(""))
    }

    @Test
    fun explicitLocaleOverridesDeviceLocale() {
        assertEquals("ja_JP.UTF-8", WineLocaleOption.JAPANESE.resolve("zh_CN.UTF-8"))
    }

    @Test
    fun unknownPreferenceFallsBackToAutomatic() {
        assertEquals(WineLocaleOption.AUTO, WineLocaleOption.fromPreference("unknown"))
    }
}
