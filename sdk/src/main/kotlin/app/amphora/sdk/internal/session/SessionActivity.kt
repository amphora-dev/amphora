package app.amphora.sdk.internal.session

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.window.SurfaceHost
import app.amphora.ui.theme.AmphoraTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * The session's window, in the `:wine` process: the Wine desktop, scaled to
 * the screen, with Amphora's overlay on top (start-up progress, the session
 * menu on the back gesture). The session itself lives in [SessionHost].
 */
internal class SessionActivity : ComponentActivity() {
    private lateinit var surface: SurfaceHost
    private lateinit var overlay: ComposeView
    private lateinit var insets: WindowInsetsControllerCompat
    private lateinit var inputMethods: InputMethodManager
    private var imeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = intent.getStringExtra(EXTRA_SPEC)?.let(::decode)
        if (!SessionHost.active && spec == null) {
            // A resume for a session that has ended: nothing to show.
            finishAndRemoveTask()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        inputMethods = getSystemService(InputMethodManager::class.java)
        insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        surface = SurfaceHost(this)
        overlay =
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val ui by SessionHost.ui.collectAsStateWithLifecycle()
                    AmphoraTheme { ui?.let { SessionOverlay(it, actions) } }
                }
            }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val visible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible && !visible) keyboardClosed()
            imeVisible = visible
            windowInsets
        }

        onBackPressedDispatcher.addCallback(this) { SessionHost.toggleMenu() }
        SessionHost.attach(surface) { finishAndRemoveTask() }

        if (!SessionHost.active && spec != null) {
            val bounds = windowManager.currentWindowMetrics.bounds
            SessionHost.begin(applicationContext, spec, bounds.width(), bounds.height())
        } else if (spec != null && spec.id != SessionHost.spec?.id) {
            AmphoraLog.w("session ${spec.id} asked for while ${SessionHost.spec?.id} runs; ignored")
        }

        lifecycleScope.launch {
            SessionHost.ui.collect { ui ->
                val visible = ui?.overlayVisible ?: true
                if (overlay.isVisible != visible) {
                    // Gone, not transparent: a visible overlay would swallow the game's touches.
                    overlay.isVisible = visible
                    if (visible) hideKeyboard() else SessionHost.focusTarget?.requestFocus()
                }
            }
        }
    }

    private val actions =
        object : OverlayActions {
            override fun resume() = SessionHost.closeMenu()

            override fun keyboard() {
                SessionHost.closeMenu()
                surface.post {
                    val target = SessionHost.softKeyboard(true) ?: return@post
                    target.requestFocus()
                    inputMethods.restartInput(target)
                    WindowCompat.getInsetsController(window, target).show(WindowInsetsCompat.Type.ime())
                }
            }

            override fun end() = SessionHost.end()

            override fun close() = SessionHost.exit()
        }

    /** Back to raw keys, and the bars the keyboard brought along go away again. */
    private fun keyboardClosed() {
        SessionHost.softKeyboard(false)?.let(inputMethods::restartInput)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::insets.isInitialized) hideSystemBars()
    }

    override fun onDestroy() {
        if (::surface.isInitialized) SessionHost.detach(surface)
        super.onDestroy()
    }

    private fun hideSystemBars() {
        insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideKeyboard() {
        insets.hide(WindowInsetsCompat.Type.ime())
    }

    private fun decode(json: String): SessionSpec? = try {
        AmphoraJson.decodeFromString(SessionSpec.serializer(), json)
    } catch (e: SerializationException) {
        AmphoraLog.e("bad session spec", e)
        null
    } catch (e: IllegalArgumentException) {
        AmphoraLog.e("bad session spec", e)
        null
    }

    companion object {
        private const val EXTRA_SPEC = "app.amphora.sdk.session.SPEC"

        fun launchIntent(context: Context, specJson: String): Intent =
            Intent(context, SessionActivity::class.java).putExtra(EXTRA_SPEC, specJson)

        fun resumeIntent(context: Context): Intent = Intent(context, SessionActivity::class.java)
    }
}
