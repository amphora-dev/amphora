package app.amphora.gamesession.wineandroid

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import app.amphora.core.engine.model.DisplayBackend
import dagger.hilt.android.AndroidEntryPoint

/**
 * P1 host for [DisplayBackend.WINEANDROID].
 *
 * Real Activity + [WineAndroidDesktop] (SurfaceView children). Not Compose,
 * not Winlator XServerSurfaceView, not org.winehq.wine.WineActivity.
 *
 * Not the default launch path. Wine is still started with box64 exec, so the
 * pin's in-process JNI (ntdll java_vm / RegisterNatives) does not apply.
 * The next bridge is ours: Kotlin receives HWND/Surface events, unix side
 * only gets ANativeWindow — no WineActivity method names.
 */
@AndroidEntryPoint
class WineAndroidSessionActivity : ComponentActivity() {
    private lateinit var desktop: WineAndroidDesktop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desktop = WineAndroidDesktop(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(desktop)
        Log.i(TAG, "wineandroid desktop ready backend=${DisplayBackend.WINEANDROID}")
    }

    internal fun onNativeSurface(hwnd: Int, surface: Surface) {
        Log.i(TAG, "HWND $hwnd surface=$surface")
    }

    companion object {
        private const val TAG = "WineAndroidSession"
    }
}
