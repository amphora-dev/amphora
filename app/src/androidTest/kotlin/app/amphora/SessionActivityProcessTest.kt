package app.amphora

import android.content.pm.ActivityInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.gamesession.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionActivityProcessTest {
    @Test
    fun sessionActivityIsPrivateAndRunsOutsideMainProcess() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, SessionActivity::class.java),
                0,
            )

        assertEquals("${context.packageName}:session", info.processName)
        assertFalse(info.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }
}
