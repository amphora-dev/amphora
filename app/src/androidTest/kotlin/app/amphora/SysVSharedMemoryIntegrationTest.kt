package app.amphora

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.sharedmemory.SysVSharedMemory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SysVSharedMemoryIntegrationTest {
    @Test
    fun repeatedAllocationAndDeleteKeepsSingleFdOwner() {
        val sharedMemory = SysVSharedMemory()

        try {
            repeat(64) {
                val id = sharedMemory.get(4_096)
                assertTrue("shared-memory allocation failed at iteration $it", id > 0)
                assertTrue("shared-memory fd missing at iteration $it", sharedMemory.getFd(id) >= 0)

                val buffer = sharedMemory.attach(id)
                assertNotNull("shared-memory mapping failed at iteration $it", buffer)
                buffer!!.putInt(0, it)
                sharedMemory.detach(buffer)
                sharedMemory.delete(id)
            }
        } finally {
            sharedMemory.deleteAll()
        }
    }
}
