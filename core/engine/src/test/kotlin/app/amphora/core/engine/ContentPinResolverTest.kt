package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentPinResolverTest {
    @Test
    fun versionIdentityStripsTypePrefix() {
        assertEquals(
            "0.4.5-0db8df775-0",
            ContentPinResolver.versionIdentity("Box64-0.4.5-0db8df775-0"),
        )
        assertEquals(
            "11.0-amphora-x86_64-1",
            ContentPinResolver.versionIdentity("Proton-11.0-amphora-x86_64-1"),
        )
        assertEquals("alone", ContentPinResolver.versionIdentity("alone"))
    }

    @Test
    fun wrapperTokenUsesVerNameAndCode() {
        val profile =
            com.winlator.cmod.runtime.content.ContentProfile().apply {
                type = com.winlator.cmod.runtime.content.ContentProfile.ContentType.CONTENT_TYPE_DXVK
                verName = "2.7.1-gplasync"
                verCode = 0
            }
        assertEquals("dxvk-2.7.1-gplasync-0", ContentPinResolver.wrapperToken("dxvk", profile))
        assertEquals("vkd3d-2.7.1-gplasync-0", ContentPinResolver.wrapperToken("vkd3d", profile))
    }
}
