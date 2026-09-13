package app.amphora.gamesession.wineandroid

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.amphora.core.container.model.DEFAULT_CONTAINER_ID
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.GameSessionHostEnvironment
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
 * WCP already ships `wineandroid.drv`. Wine still starts via box64 exec, so the
 * pin's in-process JNI (ntdll java_vm / RegisterNatives) does not apply.
 * [WineAndroidHostBridge] owns the HWND/Surface contract over
 * `AMPHORA_WINEANDROID_SOCK` (incl. desktop metrics + surface buffer-op fds).
 */
@AndroidEntryPoint
class WineAndroidSessionActivity : ComponentActivity() {
    @Inject lateinit var bootstrap: WineAndroidSessionBootstrap
    @Inject lateinit var launcher: WineAndroidLauncher
    @Inject lateinit var hostEnvironment: GameSessionHostEnvironment

    private lateinit var desktop: WineAndroidDesktop
    private lateinit var statusView: TextView
    private var hostBridge: WineAndroidHostBridge? = null
    private var runningGuest: WineAndroidLauncher.RunningGuest? = null
    private val processExitScheduled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desktop = WineAndroidDesktop(this).apply { setBackgroundColor(Color.BLACK) }
        statusView =
            TextView(this).apply {
                setTextColor(Color.LTGRAY)
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                setPadding(32, 32, 32, 64)
                text = "wineandroid: preparing prefix…"
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
            }
        setContentView(root)

        hostBridge =
            WineAndroidHostBridge(
                activity = this,
                desktop = desktop,
                onSurfaceChanged = ::onNativeSurface,
            )

        // Desktop metrics unblock unix ANDROID_CreateDesktop (wine_desktop_changed).
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val w = desktop.width
            val h = desktop.height
            if (w > 0 && h > 0) {
                val scale = resources.displayMetrics.density
                hostBridge?.updateDesktopMetrics(w, h, scale)
            }
        }

        val exePath = intent.getStringExtra(EXTRA_EXE_PATH).orEmpty()
        val width = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH)
        val height = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT)
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
                hostBridge?.startHostSocket(prepared.bridgeSocketPath)
                hostBridge?.updateDesktopMetrics(width, height, resources.displayMetrics.density)
                statusView.text =
                    "wineandroid: prefix ready\n" +
                        "starting box64 wine explorer /desktop=shell…\n" +
                        "socket=${prepared.bridgeSocketPath.absolutePath}"
                val guest = launcher.start(prepared)
                runningGuest = guest
                statusView.text =
                    "wineandroid: guest pid=${guest.pid}\n" +
                        "${guest.guestExecutable}\n" +
                        "sock=${guest.bridgeSocketPath.absolutePath}\n" +
                        "host socket listening; unix ioctl client needs WCP wineandroid.drv"
                Log.i(TAG, "wineandroid guest pid=${guest.pid}")
            } catch (t: Throwable) {
                Log.e(TAG, "wineandroid prepare failed", t)
                statusView.text = "wineandroid prepare failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.w(TAG, "Ignoring launch request while a wineandroid session is already active")
    }

    override fun onDestroy() {
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

    private fun onNativeSurface(hwnd: Int, surface: android.view.Surface, opengl: Boolean) {
        // HostBridge emits HOST_SURFACE_CHANGED + SCM_RIGHTS buffer-op fd.
        Log.i(TAG, "HWND $hwnd surface=$surface opengl=$opengl (HOST_SURFACE_CHANGED + anw fd)")
    }

    companion object {
        private const val TAG = "WineAndroidSession"
        private const val EXTRA_EXE_PATH = "exePath"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_GRAPHICS_DIAG = "graphicsDiag"
        private const val EXTRA_DISPLAY_BACKEND = "displayBackend"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val SESSION_PROCESS_EXIT_GRACE_MS = 2_000L

        fun intent(
            context: Context,
            exePath: String,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            target: LaunchTarget = LaunchTarget.PROGRAM,
            graphicsDiag: Boolean = false,
        ): Intent =
            Intent(context, WineAndroidSessionActivity::class.java).apply {
                putExtra(EXTRA_EXE_PATH, exePath)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_TARGET, target.name)
                putExtra(EXTRA_GRAPHICS_DIAG, graphicsDiag)
                putExtra(EXTRA_DISPLAY_BACKEND, DisplayBackend.WINEANDROID.name)
            }

        fun launch(
            context: Context,
            exePath: String,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            target: LaunchTarget = LaunchTarget.PROGRAM,
            graphicsDiag: Boolean = false,
        ) {
            context.startActivity(
                intent(
                    context = context,
                    exePath = exePath,
                    width = width,
                    height = height,
                    target = target,
                    graphicsDiag = graphicsDiag,
                ),
            )
        }
    }
}
