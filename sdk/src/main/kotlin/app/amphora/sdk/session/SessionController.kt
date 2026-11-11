package app.amphora.sdk.session

import android.content.Context
import android.content.Intent
import app.amphora.sdk.container.PrefixStore
import app.amphora.sdk.engine.RuntimeManager
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.session.Plan
import app.amphora.sdk.internal.session.SessionActivity
import app.amphora.sdk.internal.session.SessionLog
import app.amphora.sdk.internal.session.SessionPlanner
import app.amphora.sdk.internal.session.SessionProcess
import app.amphora.sdk.internal.session.SessionRecord
import app.amphora.sdk.internal.session.SessionRecords
import app.amphora.sdk.internal.session.SessionSpec
import app.amphora.sdk.internal.session.WineServer
import app.amphora.sdk.library.Game
import app.amphora.sdk.library.GameLibrary
import app.amphora.sdk.logs.LogStore
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Starts, watches and ends sessions from the app process. A session runs in
 * the `:wine` process ([SessionActivity]); this side only plans it, hands it
 * over, and reads `sessions/current.json` to follow it. The session process
 * can die at any moment (Wine's driver `_exit`s when the desktop goes away),
 * so ending is detected, never announced: whoever looks next finalizes it.
 */
public class SessionController internal constructor(
    private val context: Context,
    private val paths: AmphoraPaths,
    private val runtime: RuntimeManager,
    private val planner: SessionPlanner,
    private val prefixes: PrefixStore,
    private val library: GameLibrary,
    private val logs: LogStore,
    scope: CoroutineScope,
    private val io: CoroutineContext,
) {
    private val records = SessionRecords(paths.currentSession)
    private val lock = Mutex()
    private val launching = MutableStateFlow<String?>(null)
    private val ending = MutableStateFlow(false)
    private val last = MutableStateFlow<EndedSession?>(null)
    private val live = MutableStateFlow<LiveSession?>(null)

    private val polled =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(observe())
                delay(if (live.value != null) POLL_LIVE_MS else POLL_IDLE_MS)
            }
        }

    /** Polls only while someone is watching. */
    public val state: StateFlow<SessionState> =
        combine(polled, launching, ending, last) { session, title, isEnding, ended ->
            when {
                session != null ->
                    SessionState.Live(if (isEnding) session.copy(phase = SessionPhase.Ending) else session)
                title != null -> SessionState.Launching(title)
                else -> SessionState.Idle(ended)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SessionState.Idle(null))

    /** What [launch] would run for [target]; null until the runtime is installed. */
    public suspend fun preview(target: LaunchTarget): LaunchPreview? {
        val installed = runtime.current ?: return null
        val plan = planner.plan(target, installed)
        return LaunchPreview(plan.config, plan.spec.argv(), plan.spec.workingDir, plan.warnings)
    }

    public suspend fun launch(target: LaunchTarget): LaunchResult {
        val installed = runtime.current ?: return LaunchResult.RuntimeNotReady
        observe()?.let { return LaunchResult.Busy(it) }
        if (!launching.compareAndSet(null, target.title)) {
            return LaunchResult.Failed("另一个会话正在启动")
        }
        return try {
            val plan = planner.plan(target, installed)
            logs.prune(LogStore.KEEP - 1)
            withContext(io) { prepare(plan, target) }
            start(plan.spec)
            AmphoraLog.i("session ${plan.spec.id}: ${plan.spec.argv()}")
            // Stay "launching" until the session process reports in, so the UI does not flash idle.
            awaitRecord(plan.spec.id)
            LaunchResult.Started(plan.spec.id, plan.warnings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AmphoraLog.e("launch failed", e)
            LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            launching.value = null
        }
    }

    /** Brings the running session to the front. */
    public fun resume(): Boolean {
        if (live.value == null) return false
        context.startActivity(SessionActivity.resumeIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * Ends the running session: `wineserver -k` first, so Windows programs
     * see a normal termination and the registry is saved; the session process
     * is killed only if it is still there afterwards.
     */
    public suspend fun end() {
        val record = withContext(io) { records.read() } ?: return
        ending.value = true
        try {
            withContext(io) {
                SessionLog(File(record.spec.logFile)).line("end requested from the app")
                WineServer.kill(record.spec)
                if (!waitForExit(record.pid, END_GRACE_MS)) {
                    SessionLog(File(record.spec.logFile)).line("session process still alive, killing pid ${record.pid}")
                    android.os.Process.killProcess(record.pid)
                    waitForExit(record.pid, KILL_GRACE_MS)
                }
            }
            observe()
        } finally {
            ending.value = false
        }
    }

    /** Reads the record, and finalizes it if its process is gone. */
    private suspend fun observe(): LiveSession? = lock.withLock {
        val session =
            withContext(io) {
                val record = records.read() ?: return@withContext null
                if (SessionProcess.alive(record.pid, context.packageName)) return@withContext record.toLive()
                finalize(record)
                null
            }
        live.value = session
        session
    }

    private suspend fun finalize(record: SessionRecord) {
        val spec = record.spec
        val ended =
            EndedSession(
                id = spec.id,
                title = spec.title,
                gameId = spec.gameId,
                startedAt = record.startedAt,
                endedAt = record.lastSeen,
                reachedDesktop = record.desktop != null,
                logFile = File(spec.logFile),
            )
        SessionLog(ended.logFile).line("session ended after ${ended.seconds}s (pid ${record.pid} gone)")
        if (spec.gameId != null && ended.reachedDesktop) {
            library.recordPlay(spec.gameId, ended.startedAt, ended.seconds)
        }
        records.delete()
        last.value = ended
    }

    private fun prepare(plan: Plan, target: LaunchTarget) {
        val spec = plan.spec
        val gameDir = (target as? LaunchTarget.Play)?.game?.directory
        val installed = runtime.current ?: throw IOException("运行时已被移除")
        prefixes.prepare((target as? LaunchTarget.Play)?.game?.prefixId ?: Game.DEFAULT_PREFIX, installed, gameDir)
        paths.home.mkdirs()
        paths.tmp.mkdirs()
        paths.sessions.mkdirs()
        val log = SessionLog(File(spec.logFile))
        log.start()
        log.header(spec, plan.config, plan.warnings)
    }

    private fun start(spec: SessionSpec) {
        val intent =
            SessionActivity
                .launchIntent(context, AmphoraJson.encodeToString(SessionSpec.serializer(), spec))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun awaitRecord(id: String) {
        val deadline = System.currentTimeMillis() + REPORT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val session = observe()
            if (session != null && session.id == id) return
            delay(100)
        }
        AmphoraLog.w("session $id did not report within ${REPORT_TIMEOUT_MS}ms")
    }

    private suspend fun waitForExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!SessionProcess.alive(pid, context.packageName)) return true
            delay(100)
        }
        return !SessionProcess.alive(pid, context.packageName)
    }

    private fun SessionRecord.toLive(): LiveSession = LiveSession(
        id = spec.id,
        title = spec.title,
        gameId = spec.gameId,
        pid = pid,
        startedAt = startedAt,
        phase = phase,
        desktop = desktop,
        logFile = File(spec.logFile),
    )

    /** Finalizes a session that ended while the app was not running. */
    internal suspend fun housekeeping() {
        logs.prune()
        observe()
    }

    private companion object {
        const val POLL_LIVE_MS = 1_000L
        const val POLL_IDLE_MS = 2_500L
        const val REPORT_TIMEOUT_MS = 8_000L
        const val END_GRACE_MS = 6_000L
        const val KILL_GRACE_MS = 2_000L
    }
}
