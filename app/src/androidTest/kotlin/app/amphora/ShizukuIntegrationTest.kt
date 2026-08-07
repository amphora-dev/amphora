package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.ShizukuEmergencyStopper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuIntegrationTest {
    @Test
    fun installedShizuku_deliversBinderToAmphoraProvider() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stopper = ShizukuEmergencyStopper(context)
        val status =
            withTimeout(5_000L) {
                while (stopper.status.value == ShizukuCleanupStatus.UNAVAILABLE) {
                    delay(50L)
                    stopper.refreshStatus()
                }
                stopper.status.value
            }

        assertNotEquals(ShizukuCleanupStatus.UNAVAILABLE, status)
    }
}
