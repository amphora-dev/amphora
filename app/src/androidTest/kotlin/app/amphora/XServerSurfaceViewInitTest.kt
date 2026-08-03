package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView
import com.winlator.cmod.runtime.display.xserver.ScreenInfo
import com.winlator.cmod.runtime.display.xserver.XServer
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the cursor-bitmap NPE that crashed `GameSessionScreen` on
 * surface creation (two crashes observed on device):
 * `new XServerSurfaceView` -> `new VulkanRenderer` ->
 * `createRootCursorDrawable` -> `BitmapFactory.decodeResource(res, R.drawable.cursor=0)`
 * -> null -> `Drawable.fromBitmap(null)` -> NPE.
 *
 * Constructs the view with a real app Context (so `getIdentifier("cursor",...)`
 * resolves the real `cursor.png` shipped in `:app`'s `res/drawable/`) and a cheap
 * `XServer` — the exact production one-liner from `WineEngineImpl.kt:124`. No
 * Wine / imagefs / session / Surface needed; the render thread only starts on
 * `surfaceCreated` (window attachment), so headless construction is safe.
 *
 * Does NOT require `stageBundledContent` — runs in seconds on plain
 * `connectedDebugAndroidTest`. The only native dependency is `libwinlator.so`
 * (loaded by `Drawable`'s static init during `new XServer`), which is always in
 * the app APK.
 */
@RunWith(AndroidJUnit4::class)
class XServerSurfaceViewInitTest {
    @Test
    fun constructor_doesNotCrash_withoutWineSession() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val xServer = XServer(ScreenInfo(1280, 720))
        val view = XServerSurfaceView(ctx, xServer)
        assertNotNull("VulkanRenderer not initialized (createRootCursorDrawable failed)", view.getRenderer())
    }
}
