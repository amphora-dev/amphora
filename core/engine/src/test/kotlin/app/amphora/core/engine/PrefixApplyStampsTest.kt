package app.amphora.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixApplyStampsTest {
    @Test
    fun prefixRepairClearsHeavyWorkMarksOnly() {
        val keys = PrefixApplyStamps.prefixRepairClearKeys
        assertTrue(keys.contains("dxwrapper"))
        assertTrue(keys.contains("wincomponents"))
        assertTrue(keys.contains("startupSelection"))
        // 声音不靠这些标记；box64 也不在前缀里，重建前缀不用清。
        assertFalse(keys.contains("audioDriver"))
        assertFalse(keys.contains("box64Version"))
    }
}
