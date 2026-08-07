package app.amphora.gamesession

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.amphora.ui.theme.AmphoraTheme
import com.winlator.cmod.runtime.system.ProcessHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one Wine/XServer/Vulkan generation in a dedicated process.
 *
 * The native graphics stack intentionally contains process-global Vulkan dispatch and
 * adrenotools state. Ending this process after every session makes driver changes atomic:
 * a following session cannot observe a renderer or linker namespace from the previous one.
 */
@AndroidEntryPoint
class SessionActivity : ComponentActivity() {
    private val viewModel: GameSessionViewModel by viewModels()
    private val processExitScheduled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AmphoraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameSessionScreen(
                        viewModel = viewModel,
                        onExit = ::finish,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A session owns process-global Vulkan state. Keep the current generation instead
        // of creating a second ViewModel/renderer when the launcher is tapped repeatedly.
        Log.w(TAG, "Ignoring launch request while a Wine session is already active")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isChangingConfigurations || !processExitScheduled.compareAndSet(false, true)) return

        // Normal UI exit reaches STOPPED before finishing. This defensive sweep also covers
        // back-stack removal and Activity teardown while launch is still in progress.
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
            "SessionProcessExit",
        ).start()
    }

    companion object {
        private const val TAG = "SessionActivity"
        private const val EXTRA_EXE_PATH = "exePath"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_GRAPHICS_DIAG = "graphicsDiag"
        private const val SESSION_PROCESS_EXIT_GRACE_MS = 2_000L

        fun intent(
            context: Context,
            exePath: String,
            width: Int = 1280,
            height: Int = 720,
            graphicsDiag: Boolean = false,
        ): Intent = Intent(context, SessionActivity::class.java).apply {
            putExtra(EXTRA_EXE_PATH, exePath)
            putExtra(EXTRA_WIDTH, width)
            putExtra(EXTRA_HEIGHT, height)
            putExtra(EXTRA_GRAPHICS_DIAG, graphicsDiag)
        }

        fun launch(
            context: Context,
            exePath: String,
            width: Int = 1280,
            height: Int = 720,
            graphicsDiag: Boolean = false,
        ) {
            context.startActivity(intent(context, exePath, width, height, graphicsDiag))
        }
    }
}
