package app.amphora.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.amphora.sdk.AmphoraSdk
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.library.AddResult
import app.amphora.sdk.library.Game
import app.amphora.sdk.session.LaunchResult
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.settings.SettingsCatalog
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts or ends a session from the command line, for device tests: what a
 * user does with taps, done through the SDK's public API. Only in debug
 * builds (`app/src/debug`); a release build has no such entry.
 *
 * ```
 * am start -n app.amphora/.debug.RunActivity --es exe /sdcard/…/Game.exe \
 *     [--es args "--bench 8"] [--es set "display.frameRate=game|debug.dxvkLog=info"]
 * am start -n app.amphora/.debug.RunActivity --es end 1
 * am start -n app.amphora/.debug.RunActivity --es remove /sdcard/…/Game.exe
 * ```
 *
 * `exe` is added to the library when it is not there. `args` and `set`
 * replace all of the game's own settings, so a run does not depend on what an
 * earlier one left behind. A session that is still running is ended first.
 * `remove` takes the game out of the library again, with its prefix and
 * settings (the exe stays). The outcome is one line in the system log under
 * [TAG]: `started <id>`, `ended`, `removed`, or `failed: <why>`.
 */
class RunActivity : ComponentActivity() {
    private var finishOnStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sdk = AmphoraSdk.get(this)
        // The activity stays until the session's own activity is up: an app without one may not start it.
        lifecycleScope.launch {
            val outcome =
                try {
                    sdk.sessions.end()
                    when {
                        intent.hasExtra("end") -> "ended"
                        intent.hasExtra("remove") -> remove(sdk)
                        else -> run(sdk)
                    }
                } catch (e: IllegalStateException) {
                    "failed: ${e.message}"
                }
            Log.i(TAG, outcome)
            // Finishing while SessionActivity's new-task transition is still in flight can
            // hand focus back to the launcher on Android 15; wait until we are stopped.
            if (outcome.startsWith("started ") && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                finishOnStop = true
            } else {
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (finishOnStop) {
            finishOnStop = false
            finish()
        }
    }

    private suspend fun run(sdk: AmphoraSdk): String {
        val exe = File(intent.getStringExtra("exe") ?: error("no exe"))
        check(
            sdk.runtime.state.first {
                it !is RuntimeState.Checking
            } is RuntimeState.Ready,
        ) { "the runtime is not installed" }
        sdk.library.games.filterNotNull().first()
        val game =
            when (val added = sdk.library.add(exe)) {
                is AddResult.Added -> added.game
                is AddResult.AlreadyAdded -> added.game
                is AddResult.Rejected -> error("${exe.path}: ${added.reason}")
            }
        applySettings(sdk, game)
        return when (val result = sdk.sessions.launch(LaunchTarget.Play(game))) {
            is LaunchResult.Started -> "started ${result.sessionId}" + result.warnings.joinToString("") { " ($it)" }
            is LaunchResult.Busy -> "failed: ${result.session.title} is still running"
            LaunchResult.RuntimeNotReady -> "failed: the runtime is not installed"
            is LaunchResult.Failed -> "failed: ${result.message}"
        }
    }

    private suspend fun remove(sdk: AmphoraSdk): String {
        val path = File(intent.getStringExtra("remove").orEmpty()).canonicalPath
        val game = sdk.library.games.filterNotNull().first().firstOrNull { it.exePath == path }
            ?: error("$path is not in the library")
        sdk.library.remove(game.id)?.let { error(it) }
        return "removed"
    }

    private suspend fun applySettings(sdk: AmphoraSdk, game: Game) {
        sdk.settings.resetGame(game)
        val values = LinkedHashMap<String, String>()
        intent.getStringExtra("args")?.takeIf { it.isNotEmpty() }?.let { values[SettingsCatalog.ARGS] = it }
        for (pair in intent.getStringExtra("set").orEmpty().split('|').filter { it.isNotEmpty() }) {
            val (id, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: error("not id=value: $pair")
            values[id] = value
        }
        for ((id, value) in values) sdk.settings.setGame(game, id, value)?.let { error("$id=$value: $it") }
    }

    private companion object {
        const val TAG = "AmphoraRun"
    }
}
