package app.amphora.core.engine

import java.io.File
import java.nio.file.Files

/**
 * Swaps imagefs' `usr/lib/libandroid.so` between the platform symlink and the
 * bundled [SHIM_LIBRARY] stub for the Leegao guest ICD.
 *
 * imagefs points that name at `/system/lib64/libandroid.so`, which pulls in
 * libhwui and through it the platform Vulkan loader and BoringSSL. The guest
 * `LD_LIBRARY_PATH` starts with imagefs, so both resolve to the image's own
 * Khronos loader and OpenSSL 3 and fail to relocate — taking down every dlopen
 * that reaches libandroid, `libvulkan_wrapper.so` included. The stub exports
 * only the ASharedMemory entry points libandroid-shmem needs, so the chain
 * stops there.
 *
 * Scoped to the Leegao path: the Adreno wrapper never loads an ICD that needs
 * libandroid, and the platform library is the more faithful choice whenever
 * something else in the guest does want it.
 */
internal object GuestLibandroidShim {
    const val SHIM_LIBRARY = "libamphora-android-shim.so"
    private const val IMAGEFS_LIBANDROID = "usr/lib/libandroid.so"
    private const val PLATFORM_LIBANDROID = "/system/lib64/libandroid.so"

    /** True when [rootDir] now carries the stub. */
    fun install(nativeLibraryDir: File, rootDir: File): Boolean {
        val source = File(nativeLibraryDir, SHIM_LIBRARY)
        if (!source.isFile) return false
        val target = File(rootDir, IMAGEFS_LIBANDROID)
        if (isInstalled(target, source)) return true

        // The symlink resolves into /system, so writing through it fails with
        // EROFS instead of replacing the entry.
        Files.deleteIfExists(target.toPath())
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        // Owner-only is enough: the guest is forked from this app via
        // ProcessBuilder, so it shares our uid (no proot on the Bionic route).
        target.setReadable(true)
        target.setExecutable(true)
        return true
    }

    /** Restores the platform symlink when the container leaves the Leegao path. */
    fun restore(rootDir: File): Boolean {
        val target = File(rootDir, IMAGEFS_LIBANDROID)
        if (Files.isSymbolicLink(target.toPath())) return false
        Files.deleteIfExists(target.toPath())
        Files.createSymbolicLink(target.toPath(), File(PLATFORM_LIBANDROID).toPath())
        return true
    }

    private fun isInstalled(target: File, source: File): Boolean = !Files.isSymbolicLink(target.toPath()) &&
        target.isFile &&
        target.length() == source.length()
}
