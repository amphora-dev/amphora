package app.amphora.sdk.internal.engine

import app.amphora.sdk.engine.RuntimeArtifact
import app.amphora.sdk.engine.RuntimeManifest
import app.amphora.sdk.engine.WineInfo
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.engine.TarWriter.Companion.MODE_EXEC
import app.amphora.sdk.internal.engine.TarWriter.Companion.MODE_FILE
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeInstallerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var runtimes: File
    private lateinit var installer: RuntimeInstaller

    @Before
    fun setUp() {
        runtimes = temp.newFolder("runtimes")
        installer = RuntimeInstaller(runtimes)
    }

    @Test
    fun `installs a matching archive into a directory named after the runtime`() = runTest {
        val archive = archive()
        val progress = mutableListOf<Long>()

        val installed = installer.install(archive, artifact()) { progress += it }

        val dir = File(runtimes, NAME)
        assertEquals(dir, installed.dir)
        assertEquals(goodManifest(), installed.manifest)
        assertEquals("loader", installed.loader.readText())
        assertEquals("server", installed.server.readText())
        assertEquals(File(dir, "lib/wine/aarch64-unix"), installed.unixLibDir)
        assertEquals(listOf(File(dir, "lib"), File(dir, "lib/wine/aarch64-unix")), installed.libraryPath)
        for ((path, text) in PAYLOAD) assertEquals(path, text, File(dir, path).readText())
        assertTrue(File(dir, RuntimeManifest.FILE_NAME).isFile)
        assertEquals("the staging directory is gone", listOf(NAME), runtimes.list()!!.toList())

        assertTrue(progress.isNotEmpty())
        assertEquals("progress only grows", progress.distinct().sorted(), progress)
        assertTrue(progress.last() <= archive.length())
    }

    @Test
    fun `installed files are read-only and keep their execute bit`() = runTest {
        installer.install(archive(), artifact()) {}
        val dir = File(runtimes, NAME)
        val write =
            setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
        for (path in PAYLOAD.keys) {
            val permissions = Files.getPosixFilePermissions(File(dir, path).toPath())
            assertTrue(path, permissions.none { it in write })
            assertEquals(path, path in EXECUTABLES, PosixFilePermission.OWNER_EXECUTE in permissions)
        }
    }

    @Test
    fun `installed finds a completed install`() = runTest {
        assertNull(installer.installed(artifact()))
        val installed = installer.install(archive(), artifact()) {}
        assertEquals(installed, installer.installed(artifact()))
    }

    @Test
    fun `an archive without a manifest is refused`() = runTest {
        val e = installError(archive(manifest = null))
        assertTrue(e is RuntimeMismatchException)
        assertEquals("archive has no ${RuntimeManifest.FILE_NAME}", e.message)
        assertNothingInstalled()
    }

    @Test
    fun `a manifest that disagrees with the recipe is refused`() = runTest {
        val wrong =
            listOf(
                goodManifest().copy(format = 2) to "format 2",
                goodManifest().copy(name = "amphora-wine-other") to "name amphora-wine-other",
                goodManifest().copy(files = PAYLOAD.size + 1) to "file count ${PAYLOAD.size + 1}",
                goodManifest().copy(bytes = PAYLOAD_BYTES + 1) to "size ${PAYLOAD_BYTES + 1}",
                goodManifest().copy(abi = "x86_64") to "abi x86_64",
                goodManifest().copy(loader = "../../system/bin/sh") to "path ../../system/bin/sh",
                goodManifest().copy(server = "/system/bin/sh") to "path /system/bin/sh",
                goodManifest().copy(libraryPath = listOf("lib", "lib//wine")) to "path lib//wine",
            )
        for ((manifest, what) in wrong) {
            val e = installError(archive(manifest = manifest))
            assertTrue(what, e is RuntimeMismatchException)
            assertEquals("manifest $what does not match the recipe", e.message)
            assertNothingInstalled()
        }
    }

    @Test
    fun `a tree that does not add up to the manifest is refused`() = runTest {
        val missingFile = PAYLOAD - "share/wine/wine.inf"
        val e = installError(archive(files = missingFile))
        assertTrue(e is RuntimeMismatchException)
        assertEquals(
            "archive holds 3 files / ${PAYLOAD_BYTES - "[Version]".length} bytes, " +
                "manifest says 4 / $PAYLOAD_BYTES",
            e.message,
        )
        assertNothingInstalled()

        val longerFile = PAYLOAD + ("share/wine/wine.inf" to "[Version]!")
        assertTrue(installError(archive(files = longerFile)) is RuntimeMismatchException)
        assertNothingInstalled()
    }

    @Test
    fun `extraction stops once the archive holds more files than the recipe`() = runTest {
        val e = installError(archive(extra = { file("extra1", "a").file("extra2", "b") }))
        assertTrue(e is RuntimeMismatchException)
        assertEquals("archive is larger than the recipe says", e.message)
        assertNothingInstalled()
    }

    @Test
    fun `extraction stops once the archive holds more bytes than the recipe`() = runTest {
        // One extra file stays within the file ceiling, so only the byte ceiling can trip.
        val e = installError(archive(extra = { file("share/big.bin", ByteArray((1 shl 20) + PAYLOAD_BYTES.toInt())) }))
        assertTrue(e is RuntimeMismatchException)
        assertEquals("archive is larger than the recipe says", e.message)
        assertNothingInstalled()
    }

    @Test
    fun `entries that escape the staging directory are refused before anything is written`() = runTest {
        val e = installError(archive(extra = { file("../escape", "x") }))
        assertTrue(e is TarFormatException)
        assertFalse(File(runtimes, "escape").exists())
        assertNothingInstalled()

        assertTrue(installError(archive(extra = { entry("lib/sh", type = '2') })) is TarFormatException)
        assertNothingInstalled()
    }

    @Test
    fun `a file that is not gzip is refused`() = runTest {
        val bogus = temp.newFile().apply { writeText("not an archive") }
        assertTrue(installError(bogus) is IOException)
        assertNothingInstalled()
    }

    @Test
    fun `reinstalling replaces the previous tree`() = runTest {
        val dir = File(runtimes, NAME).apply { mkdirs() }
        File(dir, "leftover.txt").writeText("old")

        installer.install(archive(), artifact()) {}

        assertFalse(File(dir, "leftover.txt").exists())
        assertEquals("loader", File(dir, "lib/wine/aarch64-unix/wine").readText())
    }

    @Test
    fun `a failed reinstall keeps the installed runtime`() = runTest {
        val installed = installer.install(archive(), artifact()) {}

        installError(archive(manifest = null))

        assertEquals(installed, installer.installed(artifact()))
        assertEquals(listOf(NAME), runtimes.list()!!.toList())
    }

    @Test
    fun `installed rejects a missing, unreadable or mismatched manifest`() = runTest {
        val dir = File(runtimes, NAME)
        File(dir, "lib/wine/aarch64-unix").mkdirs()
        File(dir, "bin").mkdirs()
        File(dir, "lib/wine/aarch64-unix/wine").writeText("loader")
        File(dir, "bin/wineserver").writeText("server")
        val manifestFile = File(dir, RuntimeManifest.FILE_NAME)

        assertNull("no manifest", installer.installed(artifact()))
        manifestFile.writeText("{ not json")
        assertNull("not JSON", installer.installed(artifact()))
        manifestFile.writeText("{}")
        assertNull("missing fields", installer.installed(artifact()))
        manifestFile.writeText(encode(goodManifest().copy(name = "amphora-wine-other")))
        assertNull("other name", installer.installed(artifact()))
        manifestFile.writeText(encode(goodManifest()))
        assertNotNull("matching", installer.installed(artifact()))
        assertNull("other recipe", installer.installed(artifact(bytes = PAYLOAD_BYTES + 1)))
    }

    private fun encode(manifest: RuntimeManifest) = AmphoraJson.encodeToString(RuntimeManifest.serializer(), manifest)

    @Test
    fun `installed requires the loader and the server to exist`() = runTest {
        installer.install(archive(), artifact()) {}
        val dir = File(runtimes, NAME)

        assertTrue(File(dir, "bin/wineserver").delete())
        assertNull(installer.installed(artifact()))
    }

    @Test
    fun `stale lists everything but the kept runtime`() {
        assertEquals(emptyList<File>(), RuntimeInstaller(File(temp.root, "absent")).stale(NAME))
        File(runtimes, NAME).mkdirs()
        File(runtimes, "amphora-wine-10.0-old").mkdirs()
        File(runtimes, ".staging-$NAME").mkdirs()

        assertEquals(
            listOf(".staging-$NAME", "amphora-wine-10.0-old"),
            installer.stale(NAME).map { it.name }.sorted(),
        )
    }

    private suspend fun installError(archive: File, artifact: RuntimeArtifact = artifact()): Throwable =
        runCatching { installer.install(archive, artifact) {} }.exceptionOrNull()
            ?: throw AssertionError("install succeeded")

    private fun assertNothingInstalled() {
        assertEquals("staging cleaned up, nothing installed", emptyList<String>(), runtimes.list()!!.toList())
    }

    private fun artifact(files: Int = PAYLOAD.size, bytes: Long = PAYLOAD_BYTES, name: String = NAME) = RuntimeArtifact(
        name = name,
        url = "https://example.invalid/$name.tar.gz",
        sha256 = "0".repeat(64),
        size = 1,
        files = files,
        bytes = bytes,
    )

    private fun goodManifest() = RuntimeManifest(
        format = RuntimeManifest.FORMAT,
        name = NAME,
        abi = "arm64-v8a",
        minSdk = 28,
        wine = WineInfo("11.0", "378407243a94", "wine-11.0", listOf("x86_64", "i386")),
        loader = "lib/wine/aarch64-unix/wine",
        server = "bin/wineserver",
        libraryPath = listOf("lib", "lib/wine/aarch64-unix"),
        files = PAYLOAD.size,
        bytes = PAYLOAD_BYTES,
    )

    /** A gzipped runtime archive: directories, [files], the manifest (unless null), then [extra] entries. */
    private fun archive(
        manifest: RuntimeManifest? = goodManifest(),
        files: Map<String, String> = PAYLOAD,
        extra: TarWriter.() -> Unit = {},
    ): File {
        val tar = TarWriter().dir("bin/").dir("lib/wine/aarch64-unix/")
        if (manifest != null) {
            tar.file(RuntimeManifest.FILE_NAME, AmphoraJson.encodeToString(RuntimeManifest.serializer(), manifest))
        }
        for ((path, text) in files) tar.file(path, text, if (path in EXECUTABLES) MODE_EXEC else MODE_FILE)
        tar.extra()
        tar.end()
        val file = temp.newFile()
        GZIPOutputStream(file.outputStream()).use { it.write(tar.bytes()) }
        return file
    }

    private companion object {
        const val NAME = "amphora-wine-11.0-test"

        /** Regular files of the runtime, excluding the manifest; share/wine has no directory entry. */
        val PAYLOAD =
            linkedMapOf(
                "bin/wineserver" to "server",
                "lib/wine/aarch64-unix/wine" to "loader",
                "lib/wine/aarch64-unix/ntdll.so" to "ntdll",
                "share/wine/wine.inf" to "[Version]",
            )
        val PAYLOAD_BYTES = PAYLOAD.values.sumOf { it.length.toLong() }
        val EXECUTABLES = setOf("bin/wineserver", "lib/wine/aarch64-unix/wine")
    }
}
