package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLibandroidShimTest {
    @Test
    fun replacesThePlatformSymlinkWithTheBundledStub() {
        withTempDirectory { root ->
            val nativeLibDir = stageShim(root, "stub-bytes")
            val rootDir = root.resolve("imagefs")
            val target = platformSymlink(rootDir)

            assertTrue(GuestLibandroidShim.install(nativeLibDir, rootDir))

            assertFalse(Files.isSymbolicLink(target.toPath()))
            assertEquals("stub-bytes", target.readText())
        }
    }

    @Test
    fun reinstallsWhenTheStagedStubChanged() {
        withTempDirectory { root ->
            val rootDir = root.resolve("imagefs")
            platformSymlink(rootDir)
            GuestLibandroidShim.install(stageShim(root, "old"), rootDir)

            assertTrue(GuestLibandroidShim.install(stageShim(root, "rebuilt-stub"), rootDir))

            assertEquals("rebuilt-stub", rootDir.resolve(IMAGEFS_LIBANDROID).readText())
        }
    }

    @Test
    fun reportsFailureWhenTheApkDoesNotCarryTheStub() {
        withTempDirectory { root ->
            val rootDir = root.resolve("imagefs")
            val target = platformSymlink(rootDir)

            assertFalse(GuestLibandroidShim.install(root.resolve("empty-libs"), rootDir))

            assertTrue(Files.isSymbolicLink(target.toPath()))
        }
    }

    @Test
    fun restoreSwapsTheStubBackForThePlatformSymlink() {
        withTempDirectory { root ->
            val rootDir = root.resolve("imagefs")
            platformSymlink(rootDir)
            GuestLibandroidShim.install(stageShim(root, "stub"), rootDir)

            assertTrue(GuestLibandroidShim.restore(rootDir))

            val target = rootDir.resolve(IMAGEFS_LIBANDROID)
            assertTrue(Files.isSymbolicLink(target.toPath()))
            assertEquals(PLATFORM_LIBANDROID, Files.readSymbolicLink(target.toPath()).toString())
        }
    }

    @Test
    fun restoreLeavesAnUntouchedImagefsAlone() {
        withTempDirectory { root ->
            val rootDir = root.resolve("imagefs")
            platformSymlink(rootDir)

            assertFalse(GuestLibandroidShim.restore(rootDir))
        }
    }

    private fun stageShim(root: File, contents: String): File {
        val nativeLibDir = root.resolve("lib/arm64")
        nativeLibDir.mkdirs()
        nativeLibDir.resolve(GuestLibandroidShim.SHIM_LIBRARY).writeText(contents)
        return nativeLibDir
    }

    /** Mirrors the imagefs rootfs, which ships this name as a symlink into /system. */
    private fun platformSymlink(rootDir: File): File {
        val target = rootDir.resolve(IMAGEFS_LIBANDROID)
        requireNotNull(target.parentFile).mkdirs()
        Files.createSymbolicLink(target.toPath(), File(PLATFORM_LIBANDROID).toPath())
        return target
    }

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mali-libandroid-shim-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val IMAGEFS_LIBANDROID = "usr/lib/libandroid.so"
        const val PLATFORM_LIBANDROID = "/system/lib64/libandroid.so"
    }
}
