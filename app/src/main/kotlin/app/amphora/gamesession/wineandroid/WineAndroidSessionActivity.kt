package app.amphora.gamesession.wineandroid

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.amphora.core.engine.model.DisplayBackend
import dagger.hilt.android.AndroidEntryPoint

/**
 * P1 host for [DisplayBackend.WINEANDROID].
 *
 * Must be a real [android.app.Activity] with a [FrameLayout] that can hold
 * per-HWND [android.view.SurfaceView]s. Do not host those surfaces in Compose
 * `AndroidView` — SurfaceView never gets a surface there (see XServerSurfaceView).
 *
 * Not launched yet. Wire [app.amphora.gamesession.SessionActivity] here only after
 * the Proton WCP contains `wineandroid.so`.
 */
@AndroidEntryPoint
class WineAndroidSessionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        val placeholder = TextView(this).apply {
            text = "wineandroid session (P1) — waiting for WCP with wineandroid.so"
            gravity = Gravity.CENTER
        }
        root.addView(
            placeholder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
    }
}
