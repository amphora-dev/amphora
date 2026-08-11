package app.amphora.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GraphicsDiagTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun launchEnvironmentUsesProvidedDiagnosticDirectories() {
        val logDir = temporaryFolder.newFolder("logs")
        val dumpDir = temporaryFolder.newFolder("dumps")

        val env = GraphicsDiag.launchEnv(logDir, dumpDir)

        assertEquals("fps,devinfo,api,version,memory,gpuload", env["DXVK_HUD"])
        assertEquals("info", env["DXVK_LOG_LEVEL"])
        assertEquals(logDir.absolutePath, env["DXVK_LOG_PATH"])
        assertEquals(dumpDir.absolutePath, env["DXVK_SHADER_DUMP_PATH"])
        assertEquals("+err", env["WINEDEBUG"])
        assertEquals("1", env["DXVK_DISABLE_TIMELINE_SEMAPHORES"])
    }

    @Test
    fun ensureLogDirectoryKeepsDiagnosticsAndRemovesOnlyStrayFiles() {
        val logDir = temporaryFolder.newFolder("dxvk-logs")
        val dxvkLog = File(logDir, "d3d9.LOG").apply { writeText("log") }
        val shaderDump = File(logDir, "shader.SpV").apply { writeText("shader") }
        val stray = File(logDir, "libvulkan.so").apply { writeText("binary") }
        val nested = File(logDir, "nested").apply { mkdirs() }

        assertEquals(logDir, GraphicsDiag.ensureLogDir(logDir))

        assertTrue(dxvkLog.isFile)
        assertTrue(shaderDump.isFile)
        assertFalse(stray.exists())
        assertTrue(nested.isDirectory)
    }

    @Test
    fun ensureShaderDumpDirectoryCreatesMissingPath() {
        val dumpDir = File(temporaryFolder.root, "nested/dxvk-shader-dumps")

        assertEquals(dumpDir, GraphicsDiag.ensureShaderDumpDir(dumpDir))
        assertTrue(dumpDir.isDirectory)
    }

    @Test
    fun clearStateCacheDeletesFilesButPreservesDirectoryTreeAndOutsideData() {
        val cache = temporaryFolder.newFolder("cache")
        val nested = File(cache, "mesa_shader_cache").apply { mkdirs() }
        val cacheFile = File(nested, "entry").apply { writeText("cached") }
        val topLevelFile = File(cache, "state.bin").apply { writeText("cached") }
        val outside = File(temporaryFolder.root, "keep.bin").apply { writeText("keep") }

        GraphicsDiag.clearStateCache(cache)

        assertFalse(cacheFile.exists())
        assertFalse(topLevelFile.exists())
        assertTrue(cache.isDirectory)
        assertTrue(nested.isDirectory)
        assertEquals("keep", outside.readText())
    }

    @Test
    fun logPathsIncludeWineStderrThenSortedDxvkEntries() {
        val filesDir = temporaryFolder.newFolder("files")
        val logDir = File(filesDir, GraphicsDiag.LOG_DIR_NAME).apply { mkdirs() }
        val second = File(logDir, "dxgi.log").apply { writeText("2") }
        val first = File(logDir, "d3d9.log").apply { writeText("1") }

        assertEquals(
            listOf(
                File(filesDir, GraphicsDiag.WINE_STDERR_NAME),
                first,
                second,
            ),
            GraphicsDiag.logPaths(filesDir),
        )
    }
}
