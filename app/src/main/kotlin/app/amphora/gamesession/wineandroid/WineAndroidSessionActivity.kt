package app.amphora.gamesession.wineandroid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.amphora.core.container.model.DEFAULT_CONTAINER_ID
import app.amphora.core.engine.WineAndroidGuestResolution
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.GameSessionHostEnvironment
import app.amphora.gamesession.input.ImeUiState
import com.winlator.cmod.runtime.system.ProcessHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * P1 host for [DisplayBackend.WINEANDROID] (product default).
 *
 * Real Activity + [WineAndroidDesktop] (SurfaceView children). Not Compose,
 * not Winlator XServerSurfaceView, not org.winehq.wine.WineActivity.
 *
 * Host speaks upstream wineandroid wire protocol on abstract SEQPACKET
 * `\0\Device\WineAndroid` ([WineAndroidHostBridge] / native IPC). Pair with
 * proton branch `amphora/wineandroid-backport`.
 */
@AndroidEntryPoint
@SuppressLint("SetTextI18n")
class WineAndroidSessionActivity : ComponentActivity() {
    @Inject lateinit var bootstrap: WineAndroidSessionBootstrap

    @Inject lateinit var launcher: WineAndroidLauncher

    @Inject lateinit var hostEnvironment: GameSessionHostEnvironment

    private lateinit var desktop: WineAndroidDesktop
    private lateinit var statusView: TextView

    /** Host-local CJK composing chip (cleared on IME commit/finish). */
    private lateinit var composingOverlay: TextView

    /** Unobtrusive explicit soft-IME toggle (does not auto-show on tap). */
    private lateinit var keyboardChip: TextView
    private var hostBridge: WineAndroidHostBridge? = null
    private var runningGuest: WineAndroidLauncher.RunningGuest? = null
    private val processExitScheduled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        desktop = WineAndroidDesktop(this).apply { setBackgroundColor(Color.BLACK) }
        statusView =
            TextView(this).apply {
                setTextColor(Color.LTGRAY)
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                setPadding(32, 32, 32, 64)
                text = "wineandroid: preparing prefix…"
                // Do not steal KEYCODE_* from the desktop / GDI WindowGroup.
                isFocusable = false
                isFocusableInTouchMode = false
            }
        composingOverlay =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xC7000000.toInt())
                textSize = 16f
                typeface = Typeface.MONOSPACE
                setPadding(28, 16, 28, 16)
                maxLines = 2
                visibility = View.GONE
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        keyboardChip =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xC7000000.toInt())
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setPadding(28, 16, 28, 16)
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener {
                    desktop.toggleSoftKeyboard()
                    applyKeyboardChip(desktop.isSoftKeyboardWanted())
                }
            }
        val root =
            FrameLayout(this).apply {
                addView(
                    desktop,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    statusView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM,
                    ),
                )
                addView(
                    composingOverlay,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    ).apply {
                        topMargin = 48
                        marginStart = 48
                    },
                )
                addView(
                    keyboardChip,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END,
                    ).apply {
                        topMargin = 48
                        marginEnd = 48
                    },
                )
            }
        setContentView(root)
        applyKeyboardChip(false)
        desktop.setImeUiStateListener { state ->
            applyComposingOverlay(state)
            applyKeyboardChip(state.keyboardVisible)
        }
        desktop.requestFocus()
        maybeScheduleDebugImeInject(intent, reason = "onCreate")

        hostBridge =
            WineAndroidHostBridge(
                activity = this,
                desktop = desktop,
            )

        // Desktop size is LaunchSpec / explorer /desktop=shell,WxH — not the
        // Activity's pixel size (that would overwrite 1280x720 with 3040x1904).
        // Host scale-to-fill letterboxes that guest size onto the Activity view.
        // Wine DPI = classic 96 (host scale already enlarges; do not stack 254).

        val exePath = intent.getStringExtra(EXTRA_EXE_PATH).orEmpty()
        val width = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH)
        val height = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT)
        desktop.setGuestDesktopSize(width, height)
        val target =
            intent
                .getStringExtra(EXTRA_TARGET)
                ?.let { runCatching { LaunchTarget.valueOf(it) }.getOrNull() }
                ?: LaunchTarget.PROGRAM
        val graphicsDiag = intent.getBooleanExtra(EXTRA_GRAPHICS_DIAG, false)

        Log.i(
            TAG,
            "onCreate backend=${DisplayBackend.WINEANDROID} target=$target " +
                "size=${width}x$height exe=$exePath graphicsDiag=$graphicsDiag",
        )

        lifecycleScope.launch {
            try {
                val diagEnv =
                    if (graphicsDiag) {
                        hostEnvironment.prepareGraphicsDiagnostics()
                    } else {
                        emptyMap()
                    }
                val spec =
                    LaunchSpec(
                        exePath = exePath,
                        containerId = DEFAULT_CONTAINER_ID,
                        displaySize = DisplaySize(width, height),
                        target = target,
                        displayBackend = DisplayBackend.WINEANDROID,
                        env = diagEnv,
                    )
                val prepared = bootstrap.prepare(spec)
                hostBridge?.startServer()
                val wineDpi = WineAndroidDpi.forVirtualDesktopWithHostScale()
                Log.i(
                    TAG,
                    "wine DPI=$wineDpi (classic; host scale enlarges); " +
                        "android densityDpi=${resources.displayMetrics.densityDpi} unused",
                )
                hostBridge?.updateDesktopMetrics(
                    width,
                    height,
                    wineDpi,
                )
                statusView.text =
                    "wineandroid: prefix ready\n" +
                    "starting box64 wine explorer /desktop=shell…\n" +
                    "IPC abstract \\0\\Device\\WineAndroid"
                val guest = launcher.start(prepared)
                runningGuest = guest
                statusView.text =
                    "wineandroid: guest pid=${guest.pid}\n" +
                    "${guest.guestExecutable}\n" +
                    "IPC \\0\\Device\\WineAndroid (upstream)"
                Log.i(TAG, "wineandroid guest pid=${guest.pid}")
            } catch (t: Throwable) {
                Log.e(TAG, "wineandroid prepare failed", t)
                statusView.text = "wineandroid prepare failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        if (::desktop.isInitialized) {
            desktop.hideSoftKeyboard()
        }
        restoreSystemBars()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Same pipe as MOTION: KEYBOARD_EVENT via nativeSendKeyboardEvent.
        // Unmapped keys (BACK, VOLUME_*) fall through to Android.
        if (::desktop.isInitialized && desktop.sendKeyboardEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val injected = maybeScheduleDebugImeInject(intent, reason = "onNewIntent")
        if (!injected) {
            Log.w(TAG, "Ignoring launch request while a wineandroid session is already active")
        }
    }

    /** Mirror TouchpadView / GameSessionImeOverlay: show composing on host only. */
    private fun applyComposingOverlay(state: ImeUiState) {
        val text = WineAndroidImeUi.composingOverlayText(state)
        if (text.isEmpty()) {
            composingOverlay.visibility = View.GONE
            composingOverlay.text = ""
            return
        }
        composingOverlay.text = text
        composingOverlay.visibility = View.VISIBLE
    }

    private fun applyKeyboardChip(imeWanted: Boolean) {
        val ui = WineAndroidImeUi.softKeyboardControl(imeWanted)
        keyboardChip.text = ui.label
        keyboardChip.contentDescription = ui.contentDescription
    }

    /**
     * Debug-only: unicode commit, host composing chip, and/or IME_SHOW extras.
     * Returns true when any inject was scheduled (incl. composing clear / hide).
     */
    private fun maybeScheduleDebugImeInject(intent: Intent?, reason: String): Boolean {
        val debuggable =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        var scheduled = false
        val unicode = WineAndroidDebugImeInject.textFromIntent(intent, debuggable)
        if (unicode != null) {
            Log.i(TAG, "IME unicode inject scheduled reason=$reason text='$unicode'")
            // Post so layout/IPC can run; Desktop defers until hwnd via setDesktopHwnd.
            desktop.post { desktop.injectCommittedTextForDebug(unicode) }
            scheduled = true
        }
        val composing = WineAndroidDebugImeInject.composingFromIntent(intent, debuggable)
        if (composing != null) {
            Log.i(
                TAG,
                "IME composing inject scheduled reason=$reason len=${composing.length}",
            )
            desktop.post { desktop.injectComposingTextForDebug(composing) }
            scheduled = true
        }
        val showIme = WineAndroidDebugImeInject.showSoftKeyboardFromIntent(intent, debuggable)
        if (showIme != null) {
            Log.i(TAG, "IME soft keyboard inject scheduled reason=$reason show=$showIme")
            // Belt-and-suspenders: give IMM a moment to attach servedView;
            // Desktop.showSoftKeyboard also retries until serve-ready.
            desktop.postDelayed(
                {
                    if (showIme) {
                        desktop.showSoftKeyboard()
                    } else {
                        desktop.hideSoftKeyboard()
                    }
                },
                400L,
            )
            scheduled = true
        }
        return scheduled
    }

    override fun onDestroy() {
        restoreSystemBars()
        if (::desktop.isInitialized) {
            desktop.setImeUiStateListener(null)
        }
        runningGuest?.stop()
        runningGuest = null
        hostBridge?.close()
        hostBridge = null
        super.onDestroy()
        if (isChangingConfigurations || !processExitScheduled.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    ProcessHelper.terminateSessionProcessesAndWait(
                        SESSION_PROCESS_EXIT_GRACE_MS,
                        /* forceKillAfterTimeout = */
                        true,
                    )
                } finally {
                    Process.killProcess(Process.myPid())
                }
            },
            "WineAndroidSessionExit",
        ).start()
    }

    /** Match GameSessionScreen: bars stay hidden, with transient edge-swipe access. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun restoreSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val TAG = "WineAndroidSession"
        private const val EXTRA_EXE_PATH = "exePath"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_GRAPHICS_DIAG = "graphicsDiag"
        private const val EXTRA_DISPLAY_BACKEND = "displayBackend"
        private val DEFAULT_WIDTH = WineAndroidGuestResolution.DEFAULT.width
        private val DEFAULT_HEIGHT = WineAndroidGuestResolution.DEFAULT.height
        private const val SESSION_PROCESS_EXIT_GRACE_MS = 2_000L

        fun intent(
            context: Context,
            exePath: String,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            target: LaunchTarget = LaunchTarget.PROGRAM,
            graphicsDiag: Boolean = false,
            debugImeUnicodeText: String? = null,
            debugImeComposingText: String? = null,
            debugImeShow: Boolean? = null,
        ): Intent = Intent(context, WineAndroidSessionActivity::class.java).apply {
            putExtra(EXTRA_EXE_PATH, exePath)
            putExtra(EXTRA_WIDTH, width)
            putExtra(EXTRA_HEIGHT, height)
            putExtra(EXTRA_TARGET, target.name)
            putExtra(EXTRA_GRAPHICS_DIAG, graphicsDiag)
            putExtra(EXTRA_DISPLAY_BACKEND, DisplayBackend.WINEANDROID.name)
            debugImeUnicodeText?.takeIf { it.isNotEmpty() }?.let {
                putExtra(WineAndroidDebugImeInject.EXTRA_IME_UNICODE_TEXT, it)
            }
            // null = omit; empty string = clear composing chip
            if (debugImeComposingText != null) {
                putExtra(WineAndroidDebugImeInject.EXTRA_IME_COMPOSING_TEXT, debugImeComposingText)
            }
            if (debugImeShow != null) {
                putExtra(WineAndroidDebugImeInject.EXTRA_IME_SHOW, debugImeShow)
            }
        }

        fun launch(
            context: Context,
            exePath: String,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            target: LaunchTarget = LaunchTarget.PROGRAM,
            graphicsDiag: Boolean = false,
            debugImeUnicodeText: String? = null,
            debugImeComposingText: String? = null,
            debugImeShow: Boolean? = null,
        ) {
            context.startActivity(
                intent(
                    context = context,
                    exePath = exePath,
                    width = width,
                    height = height,
                    target = target,
                    graphicsDiag = graphicsDiag,
                    debugImeUnicodeText = debugImeUnicodeText,
                    debugImeComposingText = debugImeComposingText,
                    debugImeShow = debugImeShow,
                ),
            )
        }
    }
}
