package app.amphora

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLauncherRelaunchTest {
    private val launcher = setOf(Intent.CATEGORY_LAUNCHER)
    private val launcherFlags = 0x10200000 // NEW_TASK | RESET_TASK_IF_NEEDED, as the launcher sends

    @Test
    fun launcherIntentOnTopOfATaskFinishes() {
        assertTrue(
            MainActivity.isLauncherRelaunchOnTop(
                isTaskRoot = false,
                action = Intent.ACTION_MAIN,
                categories = launcher,
                flags = launcherFlags,
            ),
        )
    }

    @Test
    fun taskRootStays() {
        assertFalse(
            MainActivity.isLauncherRelaunchOnTop(
                isTaskRoot = true,
                action = Intent.ACTION_MAIN,
                categories = launcher,
                flags = launcherFlags,
            ),
        )
    }

    @Test
    fun launcherIntentWithoutNewTaskStays() {
        assertFalse(
            MainActivity.isLauncherRelaunchOnTop(
                isTaskRoot = false,
                action = Intent.ACTION_MAIN,
                categories = launcher,
                flags = 0,
            ),
        )
    }

    @Test
    fun nonLauncherIntentStays() {
        assertFalse(
            MainActivity.isLauncherRelaunchOnTop(
                isTaskRoot = false,
                action = null,
                categories = emptySet(),
                flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
        assertFalse(
            MainActivity.isLauncherRelaunchOnTop(
                isTaskRoot = false,
                action = Intent.ACTION_MAIN,
                categories = emptySet(),
                flags = launcherFlags,
            ),
        )
    }
}
