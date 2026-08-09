package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliedMarksTest {
    @Test
    fun prefixOwnedIncludesOnlyPrefixDerivedState() {
        val keys = AppliedMarks.prefixOwnedKeys
        assertTrue(keys.contains("appliedAudio"))
        assertTrue(keys.contains("appliedDxwrapper"))
        assertTrue(keys.contains("appliedServices"))
        assertTrue(keys.contains("appliedInput"))
        assertFalse(keys.contains("appliedBox64"))
        assertFalse(keys.contains("audioDriver"))
    }

    @Test
    fun dxWrapperSelectionParsesDelimitedOnly() {
        val ok = DxWrapperSelection.parse("dxvk-a;vkd3d-b;dd7to9")
        assertEquals("dxvk-a", ok!!.dxvk)
        assertEquals("vkd3d-b", ok.vkd3d)
        assertEquals("dd7to9", ok.ddraw)
        assertEquals("dxvk-a;vkd3d-b;dd7to9|arch=x86_64", ok.gateKey("x86_64"))
        assertNull(DxWrapperSelection.parse("dxvk+vkd3d"))
        assertNull(DxWrapperSelection.parse(""))
    }
}
