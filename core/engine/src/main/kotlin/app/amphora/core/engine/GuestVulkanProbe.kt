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
 * Caching lives in [CapabilityProbeStore]: the verdict is stored under the
 * full fingerprint of [Inputs] (named components — driver, driver package,
 * wine/box64 build, layer, app build, probe binary, guest-view env slice),
 * multiple fingerprints ride a bounded LRU so driver switching hits old
 * entries, failures carry a reason and retry a bounded number of launches,
 * and same-fingerprint callers share one run.
 */
@Singleton
class GuestVulkanProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val store = CapabilityProbeStore(File(context.filesDir, "amphora_vkprobe.json"))

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
        /** The complete env the session launches with (subset fingerprinted). */
        val env: Map<String, String>,
    ) {
        /**
         * The stored fingerprint: named components, persisted so a later miss
         * can explain *what* changed. Only env that can alter what the guest
         * enumerates is keyed; UI knobs (DXVK_HUD etc.) must not re-probe.
         */
        fun fingerprint(): CapabilityFingerprint {
            val components = LinkedHashMap<String, String>()
            components["driver"] = effectiveDriverId
            components["driverPackage"] = driverPackageVersion
            components["wine"] = wineVersion
            components["box64"] = box64Version
            components["layer"] = layerName
            components["appVersionCode"] = appVersionCode.toString()
            components["probeExe"] = probeExeHash
            env.filterKeys { it.isGuestViewEnvKey() }
                .toSortedMap()
                .forEach { (k, v) -> components["env.$k"] = v }
            return CapabilityFingerprint(components)
        }

        fun key(): String = fingerprint().key
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
        env = sessionEnv.mapValues { (_, v) -> withoutInstallDir(v) },
    )

    /**
     * Android renames the APK install directory (`/data/app/~~…==/…`) on every
     * install, and that path reaches the env through `LD_PRELOAD`. The app
     * build is already a fingerprint component, so the path itself must not
     * force a re-probe after a same-version reinstall.
     */
    private fun withoutInstallDir(value: String): String {
        val installDir = File(context.applicationInfo.sourceDir).parent ?: return value
        return value.replace(installDir, "<apk>")
    }

    /**
     * The most recent verdict probed under [driverId]. Every per-driver
     * decision (DXVK flavor, zink, D3D12, BCn) reads this: with one entry per
     * fingerprint, the newest entry overall can belong to another driver.
     */
    fun latestFor(driverId: String): Verdict? = store.latest { it.components["driver"] == driverId }?.toVerdict()

    /** The stored verdict for [inputs] exactly, or null. */
    fun cachedFor(inputs: Inputs): Verdict? = store.peek(inputs.key())?.toVerdict()

    /** True when no usable verdict covers [inputs] — a probe must run. */
    fun needsProbe(inputs: Inputs): Boolean = store.needsRun(inputs.fingerprint())

    /**
     * Why [inputs] can't reuse the most recent verdict — changed component
     * labels ("graphics driver", "wine", …) or a failure description. For
     * logs and settings.
     */
    fun explainMiss(inputs: Inputs): List<String> = store.explainMiss(inputs.fingerprint())

    /**
     * Forget [inputs]' verdict — the settings "Re-detect" action. The next
     * launch for this fingerprint pays the probe again.
     */
    fun invalidate(inputs: Inputs) = store.invalidate(inputs.fingerprint())

    /**
     * Forget every verdict probed under [driverId] — the UI process cannot
     * rebuild a fingerprint (that needs the session env builder), so the
     * manual "Re-detect" clears by the persisted `driver` component instead.
     */
    fun invalidateDriver(driverId: String) {
        store.entries()
            .filter { it.components["driver"] == driverId }
            .forEach { store.invalidate(it.fingerprint) }
    }

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
     * parse it into a [Verdict] and persist it. Single-flight through the
     * store — a concurrent caller for the same fingerprint gets this result
     * instead of running its own poll. Bounded by [timeoutMs] — a
     * missing/unreadable file yields a failure verdict, never a hang.
     */
    suspend fun awaitVerdict(
        container: AmphoraContainer,
        inputs: Inputs,
        timeoutMs: Long = REPORT_TIMEOUT_MS,
    ): Verdict {
        val fingerprint = inputs.fingerprint()
        val outcome =
            store.awaitOutcome<GuestVulkanProbeReport?>(
                kind = KIND,
                fingerprint = fingerprint,
                decode = { GuestVulkanProbeReport.parse(it.toString()) },
            ) {
                runProbe(container, inputs, timeoutMs)
            }
        return Verdict(
            key = outcome.key,
            report = outcome.result,
            failure = outcome.failure,
            durationMs = outcome.durationMs,
            fromCache = outcome.fromCache,
            driverId = outcome.components["driver"] ?: inputs.effectiveDriverId,
        )
    }

    /**
     * The actual probe run: poll the result file, classify the outcome.
     * Returns the [CapabilityProbeRun] the store records.
     */
    private suspend fun runProbe(
        container: AmphoraContainer,
        inputs: Inputs,
        timeoutMs: Long,
    ): CapabilityProbeRun<GuestVulkanProbeReport?> = withContext(dispatchers.io) {
        val started = System.nanoTime()
        val elapsed = { (System.nanoTime() - started) / 1_000_000 }
        val file = resultFile(container)
        var report: GuestVulkanProbeReport? = null
        while (elapsed() < timeoutMs) {
            report = readProbeResult(file)
            if (report != null) break
            delay(POLL_MS)
        }
        when {
            report == null -> {
                Log.w(TAG, "guest vulkan probe: no JSON within ${timeoutMs}ms")
                CapabilityProbeRun(null, null, "no probe output within ${timeoutMs}ms", elapsed())
            }
            !report.ok -> {
                val reason =
                    if (report.instanceCreate != 0) {
                        "vkCreateInstance failed (${report.instanceCreate})"
                    } else {
                        "vkEnumeratePhysicalDevices failed (${report.enumerateDevices})"
                    }
                Log.w(TAG, "guest vulkan probe: $reason")
                CapabilityProbeRun(report, reportToJson(report), reason, elapsed())
            }
            else -> {
                Log.i(
                    TAG,
                    "guest vulkan probe: ${report.devices.size} adapter(s) in ${elapsed()}ms: " +
                        report.devices.joinToString { "${it.deviceName} ${it.apiLabel}" },
                )
                CapabilityProbeRun(report, reportToJson(report), null, elapsed())
            }
        }
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

    private fun CapabilityProbeEntry.toVerdict(): Verdict = Verdict(
        key = key,
        report = payload?.let { runCatching { GuestVulkanProbeReport.parse(it.toString()) }.getOrNull() },
        failure = failure,
        durationMs = durationMs,
        fromCache = true,
        driverId = components["driver"] ?: "",
    )

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

        /** Store `kind` tag for vkprobe entries. */
        const val KIND = "vkprobe"

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

        /** A missing-file check is one stat; every interval of slack is launch latency. */
        private const val POLL_MS = 10L
    }
}

/** Env-var prefixes that can change what the guest enumerates (fingerprinted). */
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
