package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Host half of the in-session guest Vulkan probe. `amphora-vkprobe.exe` runs
 * **inside** the real wineandroid session as the launch command on a cache
 * miss (`wine start /wait explorer /desktop=shell,WxH "C:\amphora-vkprobe.exe"
 * --then "<game>" …`), so box64 → Wine → winevulkan → `VK_LAYER_AMPHORA_wsi`
 * → wrapper/Turnip/System ICD sees exactly the session's environment — no
 * reconstructed env, no second wineserver, no separate prefix boot.
 *
 * Handshake: the probe writes `C:\amphora-vkprobe.json` (always — failure is
 * recorded in the file), then waits up to ~20 s for `C:\amphora-vkprobe.go`.
 * [awaitVerdict] polls the result file while the session starts, decides the
 * DXVK flavor ([DxvkFlavorIds]), and the caller relinks + drops the go file
 * via [writeGoFile]. If the go file never lands the probe launches the game
 * anyway on the provisional links, so a dead host path can never hang a
 * launch.
 *
 * The verdict is cached by [Inputs.key]; only a changed key (driver, driver
 * package, wine/box64 build, guest-view env, layer, app build, probe binary)
 * pays the probe launch again.
 */
@Singleton
class GuestVulkanProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val cacheFile = File(context.filesDir, "amphora_vkprobe.json")
    private val memoryCache = java.util.concurrent.atomic.AtomicReference<Verdict?>()

    /**
     * Every input that can change the guest view. Any change re-probes.
     */
    data class Inputs(
        val effectiveDriverId: String,
        /** `graphicsDriverConfig.version` — wrapper flavor / Turnip package id. */
        val driverPackageVersion: String,
        /** Wine build id (container `wineVersion`). */
        val wineVersion: String,
        /** Box64 build id (container `box64Version`). */
        val box64Version: String,
        /** Implicit layer in the stack; empty when the System path drops it. */
        val layerName: String,
        /** APK build — the layer .so ships in it. */
        val appVersionCode: Long,
        /** SHA-256 of the staged probe exe asset — a rebuilt binary re-probes. */
        val probeExeHash: String,
        /** The complete env the session launches with (subset hashed). */
        val env: Map<String, String>,
    ) {
        fun key(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun feed(s: String) = digest.update(s.toByteArray())
            feed("v$SCHEMA|")
            feed(effectiveDriverId)
            feed("|")
            feed(driverPackageVersion)
            feed("|")
            feed(wineVersion)
            feed("|")
            feed(box64Version)
            feed("|")
            feed(layerName)
            feed("|")
            feed(appVersionCode.toString())
            feed("|")
            feed(probeExeHash)
            feed("|")
            // Only env that can alter what the guest enumerates is keyed; UI
            // knobs (DXVK_HUD etc.) must not re-probe.
            env.filterKeys { it.isGuestViewEnvKey() }
                .toSortedMap()
                .forEach { (k, v) ->
                    feed(k)
                    feed("=")
                    feed(v)
                    feed("\n")
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** One probe outcome — either a report or the failure that replaced it. */
    data class Verdict(
        val key: String,
        val report: GuestVulkanProbeReport?,
        /** Why there is no usable report; null on success. */
        val failure: String?,
        val durationMs: Long,
        val fromCache: Boolean,
        /** Driver the verdict was probed under — lets UI flag stale results. */
        val driverId: String,
    ) {
        /** True when the verdict came out of an actual probe of [inputs]. */
        fun appliesTo(inputs: Inputs): Boolean = key == inputs.key()
    }

    /**
     * The probe inputs for the session that's about to launch. [sessionEnv]
     * is the env map the session actually execs with — including the GPLC
     * layer/loader/preload vars, applied by the caller on the same EnvVars.
     */
    fun buildInputs(
        effectiveDriverId: String,
        driverPackageVersion: String,
        sessionEnv: Map<String, String>,
        wineVersion: String,
        box64Version: String,
    ): Inputs = Inputs(
        effectiveDriverId = effectiveDriverId,
        driverPackageVersion = driverPackageVersion,
        wineVersion = wineVersion,
        box64Version = box64Version,
        layerName = LAYER_NAME,
        appVersionCode = appVersionCode(),
        probeExeHash = probeExeHash(),
        env = sessionEnv,
    )

    /**
     * The last verdict on disk (or in memory). Used by the flavor decision
     * for the provisional dxwrapper and by settings to render what `auto`
     * already learned.
     */
    fun cached(): Verdict? {
        memoryCache.get()?.let { return it }
        val verdict = readCache() ?: return null
        memoryCache.set(verdict)
        return verdict
    }

    /** True when no cached verdict covers [inputs] — the launch must probe. */
    fun needsProbe(inputs: Inputs): Boolean = cached()?.appliesTo(inputs) != true

    /**
     * Stage the probe exe at `C:\amphora-vkprobe.exe` so the session launch
     * command can name it. Idempotent — skips an identical copy.
     */
    fun stageProbeExe(container: AmphoraContainer): Boolean {
        val driveC = File(container.rootPath, ".wine/drive_c")
        val dest = File(driveC, PROBE_EXE_NAME)
        return runCatching {
            context.assets.open(PROBE_ASSET).use { input ->
                val bytes = input.readBytes()
                if (!dest.isFile || dest.length() != bytes.size.toLong() ||
                    !dest.readBytes().contentEquals(bytes)
                ) {
                    driveC.mkdirs()
                    dest.writeBytes(bytes)
                }
            }
            dest.isFile
        }.onFailure { Log.w(TAG, "guest vulkan probe: stage failed", it) }
            .getOrDefault(false)
    }

    /** Drop stale handshake files before a probing session starts. */
    fun clearHandshake(container: AmphoraContainer) {
        resultFile(container).delete()
        goFile(container).delete()
    }

    /**
     * Poll `<prefix>/drive_c/amphora-vkprobe.json` while the session starts;
     * parse it into a [Verdict] and persist it. Bounded by [timeoutMs] — a
     * missing/unreadable file yields a failure verdict, never a hang.
     */
    suspend fun awaitVerdict(
        container: AmphoraContainer,
        inputs: Inputs,
        timeoutMs: Long = REPORT_TIMEOUT_MS,
    ): Verdict = withContext(dispatchers.io) {
        val key = inputs.key()
        val started = System.nanoTime()
        val elapsed = { (System.nanoTime() - started) / 1_000_000 }
        val file = resultFile(container)
        var report: GuestVulkanProbeReport? = null
        while (elapsed() < timeoutMs) {
            report = readProbeResult(file)
            if (report != null) break
            delay(POLL_MS)
        }
        val verdict =
            when {
                report == null -> {
                    Log.w(TAG, "guest vulkan probe: no JSON within ${timeoutMs}ms")
                    Verdict(
                        key,
                        null,
                        "no probe output within ${timeoutMs}ms",
                        elapsed(),
                        false,
                        inputs.effectiveDriverId,
                    )
                }
                !report.ok -> {
                    val reason =
                        if (report.instanceCreate != 0) {
                            "vkCreateInstance failed (${report.instanceCreate})"
                        } else {
                            "vkEnumeratePhysicalDevices failed (${report.enumerateDevices})"
                        }
                    Log.w(TAG, "guest vulkan probe: $reason")
                    Verdict(key, report, reason, elapsed(), false, inputs.effectiveDriverId)
                }
                else -> {
                    Log.i(
                        TAG,
                        "guest vulkan probe: ${report.devices.size} adapter(s) in ${elapsed()}ms: " +
                            report.devices.joinToString { "${it.deviceName} ${it.apiLabel}" },
                    )
                    Verdict(key, report, null, elapsed(), false, inputs.effectiveDriverId)
                }
            }
        writeCache(verdict)
        memoryCache.set(verdict)
        verdict
    }

    /** Tell the probing session the DXVK links are final — launch the game. */
    fun writeGoFile(container: AmphoraContainer) {
        runCatching {
            goFile(container).writeText("go")
        }.onFailure { Log.w(TAG, "guest vulkan probe: go-file write failed", it) }
    }

    private fun resultFile(container: AmphoraContainer): File =
        File(container.rootPath, ".wine/drive_c/$PROBE_RESULT_NAME")

    private fun goFile(container: AmphoraContainer): File = File(container.rootPath, ".wine/drive_c/$PROBE_GO_NAME")

    private fun readProbeResult(file: File): GuestVulkanProbeReport? = runCatching {
        if (!file.isFile || file.length() == 0L) return null
        GuestVulkanProbeReport.extract(file.readText())
    }.getOrNull()

    private fun readCache(): Verdict? = runCatching {
        val root = JSONObject(cacheFile.readText())
        val key = root.getString("key")
        val failure = if (root.isNull("failure")) null else root.getString("failure")
        val report = root.optJSONObject("report")?.toString()?.let { GuestVulkanProbeReport.parse(it) }
        Verdict(
            key = key,
            report = report,
            failure = failure,
            durationMs = root.optLong("durationMs", 0),
            fromCache = true,
            driverId = root.optString("driverId", ""),
        )
    }.getOrNull()

    private fun writeCache(verdict: Verdict) {
        runCatching {
            val root = JSONObject()
                .put("key", verdict.key)
                .put("driverId", verdict.driverId)
                .put("durationMs", verdict.durationMs)
                .put("failure", verdict.failure)
                .put("probedAt", System.currentTimeMillis())
            verdict.report?.let { root.put("report", reportToJson(it)) }
            cacheFile.writeText(root.toString())
        }.onFailure { Log.w(TAG, "guest vulkan probe: cache write failed", it) }
    }

    /** Round-trip through JSONObject so the persisted report parses identically. */
    private fun reportToJson(report: GuestVulkanProbeReport): JSONObject {
        val root = JSONObject()
        root.put("vkprobe", GuestVulkanProbeReport.SCHEMA)
        root.put("surfaceInstanceCreate", report.surfaceInstanceCreate)
        root.put("instanceCreate", report.instanceCreate)
        root.put("instanceExtensions", org.json.JSONArray(report.instanceExtensions.toList()))
        root.put("enumerateDevices", report.enumerateDevices)
        val arr = org.json.JSONArray()
        report.devices.forEach { d ->
            val dev = JSONObject()
            dev.put("deviceName", d.deviceName)
            dev.put("apiVersion", d.apiVersion)
            dev.put("vendorID", d.vendorId)
            dev.put("deviceID", d.deviceId)
            dev.put("deviceType", d.deviceType)
            dev.put(
                "driver",
                JSONObject()
                    .put("name", d.driverName).put("info", d.driverInfo)
                    .put("id", d.driverId).put("conformance", d.conformance),
            )
            val feats = JSONObject()
            d.features.forEach { (k, v) ->
                val tag = k.substringBefore('.')
                val field = k.substringAfter('.')
                val st = feats.optJSONObject(tag) ?: JSONObject().also { feats.put(tag, it) }
                st.put(field, if (v) 1 else 0)
            }
            dev.put("features", feats)
            dev.put("extensions", org.json.JSONArray(d.extensions.toList()))
            val lims = JSONObject()
            d.limits.forEach { (k, v) -> lims.put(k, v) }
            dev.put("limits", lims)
            val qs = org.json.JSONArray()
            d.queueFamilies.forEach { q ->
                qs.put(JSONObject().put("flags", q.flags).put("count", q.count))
            }
            dev.put("queueFamilies", qs)
            arr.put(dev)
        }
        root.put("devices", arr)
        return root
    }

    /** Hash of the bundled probe exe — keyed so a rebuilt asset re-probes. */
    private fun probeExeHash(): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(PROBE_ASSET).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun appVersionCode(): Long = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "GuestVulkanProbe"
        private const val SCHEMA = 2

        const val PROBE_ASSET = "vkprobe/amphora-vkprobe.exe"
        const val PROBE_EXE_NAME = "amphora-vkprobe.exe"

        /** `C:\` path the probe freopens its JSON into (`RESULT_PATH` in vkprobe.c). */
        const val PROBE_RESULT_NAME = "amphora-vkprobe.json"

        /** `C:\` path the host drops to release the game (`GO_PATH` in vkprobe.c). */
        const val PROBE_GO_NAME = "amphora-vkprobe.go"

        const val LAYER_NAME = "VK_LAYER_AMPHORA_wsi"

        /** Host-side wait for the JSON — longer than the guest's 20 s go-wait
         *  so a slow probe still gets its verdict applied to the cache. */
        const val REPORT_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 200L
    }
}

/** Env-var prefixes that can change what the guest enumerates (cache-keyed). */
private val GUEST_VIEW_ENV_PREFIXES = listOf(
    "VK_",
    "ADRENOTOOLS_",
    "WRAPPER_",
    "AMPHORA_",
    "TU_DEBUG",
    "LD_LIBRARY_PATH",
    "LD_PRELOAD",
    "XDG_DATA_DIRS",
    "VKD3D_",
)

private fun String.isGuestViewEnvKey(): Boolean = GUEST_VIEW_ENV_PREFIXES.any { startsWith(it) }
