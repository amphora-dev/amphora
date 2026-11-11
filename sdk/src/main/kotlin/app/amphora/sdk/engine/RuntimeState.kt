package app.amphora.sdk.engine

import java.io.File

/** Where the runtime is in its life: missing, being installed, ready, or broken. */
public sealed interface RuntimeState {
    /** Looking at what is on disk. */
    public data object Checking : RuntimeState

    /**
     * Not installed. [sideloaded] is true when a matching archive was found in
     * the app's external files directory and will be used instead of a download;
     * [downloaded] is what an interrupted download already has on disk.
     */
    public data class Missing(val artifact: RuntimeArtifact, val sideloaded: Boolean, val downloaded: Long) :
        RuntimeState

    public data class Working(
        val artifact: RuntimeArtifact,
        val phase: InstallPhase,
        /** Progress within the phase, in the phase's unit (bytes). */
        val done: Long,
        val total: Long,
        /** Smoothed throughput of the phase, bytes per second; 0 when unknown. */
        val bytesPerSecond: Long,
    ) : RuntimeState {
        val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    public data class Ready(val runtime: InstalledRuntime) : RuntimeState

    public data class Failed(val artifact: RuntimeArtifact, val error: RuntimeError) : RuntimeState
}

public enum class InstallPhase(public val label: String) {
    Downloading("下载"),
    Verifying("校验"),
    Extracting("解包"),
    Finalizing("收尾"),
}

/** A runtime on disk whose manifest matched the recipe. */
public data class InstalledRuntime(val manifest: RuntimeManifest, val dir: File) {
    val loader: File get() = File(dir, manifest.loader)
    val server: File get() = File(dir, manifest.server)
    val libraryPath: List<File> get() = manifest.libraryPath.map { File(dir, it) }

    /** Directory with ntdll.so, win32u.so and wineandroid.so. */
    val unixLibDir: File get() = loader.parentFile!!
}

/** Why an install failed, typed so the UI can say something specific. */
public sealed interface RuntimeError {
    public val message: String

    /** Connection problems; retrying resumes the download. */
    public data class Network(override val message: String) : RuntimeError

    /** The archive is not the one the recipe pins. It is deleted. */
    public data class Integrity(val expected: String, val actual: String) : RuntimeError {
        override val message: String get() = "校验失败：期望 ${expected.take(12)}…，实际 ${actual.take(12)}…"
    }

    public data class Storage(val needed: Long, val available: Long) : RuntimeError {
        override val message: String get() = "存储空间不足"
    }

    /** The archive or its manifest is malformed or does not match the recipe. */
    public data class Corrupt(override val message: String) : RuntimeError

    public data class Io(override val message: String) : RuntimeError
}
