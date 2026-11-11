package app.amphora.sdk.engine

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File

/** What the SDK knows about the device, for the support check and bug reports. */
public data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    /** SoC model ("SM8750"), when the platform reports it. */
    val soc: String?,
    /** GL_RENDERER, e.g. "Adreno (TM) 830". */
    val gpu: String?,
    /** Adreno model number parsed from [gpu]. */
    val adreno: Int?,
    val android: String,
    val sdk: Int,
    val kernel: String,
    val pageSize: Long,
    val totalRam: Long,
    val storageFree: Long,
    val storageTotal: Long,
    val abis: List<String>,
) {
    /** D4: Adreno 7xx/8xx only; development guarantees 8xx (Y700). */
    val support: Support
        get() =
            when {
                "arm64-v8a" !in abis -> Support.Unsupported("需要 64 位 ARM（arm64-v8a）")
                adreno == null -> Support.Unsupported("只支持高通 Adreno GPU" + (gpu?.let { "，这台设备是 $it" } ?: ""))
                adreno in 800..899 -> Support.Supported
                adreno in 700..799 -> Support.Untested("Adreno 7xx 在支持范围内，但还没有设备验证")
                else -> Support.Unsupported("Adreno $adreno 不在支持范围（只支持 7xx / 8xx）")
            }
}

public sealed interface Support {
    public data object Supported : Support

    public data class Untested(val reason: String) : Support

    public data class Unsupported(val reason: String) : Support
}

/** Collects [DeviceInfo]. Creates a throwaway EGL context; call off the main thread. */
internal object DeviceProbe {
    fun probe(context: Context, storage: File): DeviceInfo {
        val gpu = runCatching { glRenderer() }.getOrNull()
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val stat = StatFs(storage.path)
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }
            } else {
                null
            },
            gpu = gpu,
            adreno = gpu?.let(::parseAdreno),
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            kernel = System.getProperty("os.version").orEmpty(),
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
            totalRam = memory.totalMem,
            storageFree = stat.availableBytes,
            storageTotal = stat.totalBytes,
            abis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    /** "Adreno (TM) 830" → 830. */
    fun parseAdreno(renderer: String): Int? = Regex("""Adreno\D*(\d{3})""").find(renderer)?.groupValues?.get(1)?.toInt()

    private fun glRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val attribs =
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val config = configs[0]
            val ctx = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
