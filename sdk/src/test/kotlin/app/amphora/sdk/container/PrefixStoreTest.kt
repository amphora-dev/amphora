package app.amphora.sdk.container

import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.D3dInfo
import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.engine.RuntimeArtifact
import app.amphora.sdk.engine.RuntimeManifest
import app.amphora.sdk.engine.WineInfo
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.container.PrefixInitializer
import app.amphora.sdk.internal.container.PrepareStep
import app.amphora.sdk.internal.container.linkDirect3d
import app.amphora.sdk.internal.container.linkIdenticalFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrefixStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: AmphoraPaths
    private lateinit var runtime: InstalledRuntime
    private lateinit var gameDir: File
    private val wine = FakeWine()

    @Before
    fun setUp() {
        paths = AmphoraPaths(temp.newFolder("files"), temp.newFolder("cache"), null)
        runtime = installRuntime(RUNTIME)
        gameDir = temp.newFolder("Games", "Hollow Knight")
    }

    @Test
    fun `the template is what Wine made, with runtime files replaced by links`() = runTest {
        val store = store()
        assertNull(store.template())

        store.prepare("default", runtime, null)

        val tree = File(paths.template("test"), "tree")
        for ((path, source) in SYSTEM_FILES) {
            val file = File(tree, path)
            assertTrue(path, Files.isSymbolicLink(file.toPath()))
            assertEquals(path, File(runtime.dir, source).canonicalFile, file.canonicalFile)
            assertFalse("$path is a relative link", Files.readSymbolicLink(file.toPath()).isAbsolute)
        }
        for (path in listOf("system.reg", "user.reg", "userdef.reg", ".update-timestamp", "drive_c/windows/win.ini")) {
            assertTrue(path, Files.isRegularFile(File(tree, path).toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        assertFalse("the server's socket directory is not part of it", File(tree, ".wineserver").exists())
        assertTrue(File(paths.template("test"), "wineboot.log").isFile)

        val info = store.template()!!
        assertEquals("test", info.recipe)
        assertEquals(RUNTIME, info.runtime)
        assertEquals(SYSTEM_FILES.size + 2, info.links)
        assertEquals(5, info.files)
    }

    @Test
    fun `a prefix is a copy of the template that links into the runtime`() = runTest {
        val store = store()
        val steps = ArrayList<PrepareStep>()

        val dir = store.prepare("g1", runtime, gameDir) { steps += it }

        assertEquals(paths.prefix("g1"), dir)
        assertEquals(listOf(PrepareStep.Template, PrepareStep.Prefix), steps)
        for ((path, source) in SYSTEM_FILES) {
            val file = File(dir, path)
            assertTrue(path, Files.isSymbolicLink(file.toPath()))
            assertEquals(path, File(runtime.dir, source).canonicalFile, file.canonicalFile)
            assertFalse("$path is a relative link", Files.readSymbolicLink(file.toPath()).isAbsolute)
        }
        assertEquals("REGEDIT4 system", File(dir, "system.reg").readText())
        assertEquals("[windows]", File(dir, "drive_c/windows/win.ini").readText())
        assertEquals(
            "C: is this prefix's",
            File(dir, "drive_c").canonicalFile,
            File(dir, "dosdevices/c:").canonicalFile,
        )
        assertEquals("/", Files.readSymbolicLink(File(dir, "dosdevices/z:").toPath()).toString())
        assertEquals(gameDir.canonicalFile, File(dir, "dosdevices/d:").canonicalFile)
        assertFalse(File(dir, ".wineserver").exists())
        assertEquals("nothing is left of the staging directory", listOf("g1"), paths.prefixes.list()!!.toList())

        val record = AmphoraJson.decodeFromString(PrefixRecord.serializer(), File(dir, PrefixRecord.FILE).readText())
        assertEquals("g1", record.id)
        assertEquals("test", record.recipe)
        assertEquals(RUNTIME, record.runtime)
        assertTrue(record.thin)
    }

    @Test
    fun `users live outside the prefix`() = runTest {
        val dir = store().prepare("g1", runtime, gameDir)

        val users = File(dir, "drive_c/users")
        assertTrue(Files.isSymbolicLink(users.toPath()))
        assertEquals(paths.userdata("g1").canonicalFile, users.canonicalFile)
        assertTrue(File(paths.userdata("g1"), "steamuser/Documents").isDirectory)
        assertTrue(File(paths.userdata("g1"), "Public").isDirectory)
    }

    @Test
    fun `Wine runs once, however many prefixes are made`() = runTest {
        val store = store()
        store.prepare("default", runtime, null)
        store.prepare("g1", runtime, gameDir)
        store.prepare("g2", runtime, gameDir)
        store.prepare("g1", runtime, gameDir)

        assertEquals(1, wine.runs)
        assertEquals(setOf("default", "g1", "g2"), paths.prefixes.list()!!.toSet())
    }

    @Test
    fun `preparing a current prefix leaves it alone`() = runTest {
        val store = store()
        val dir = store.prepare("g1", runtime, gameDir)
        File(dir, "system.reg").writeText("REGEDIT4 system, changed by the game")
        File(dir, "drive_c/Program Files").mkdirs()
        File(dir, "drive_c/Program Files/installed.dll").writeText("x")
        val steps = ArrayList<PrepareStep>()

        store.prepare("g1", runtime, null) { steps += it }

        assertEquals(emptyList<PrepareStep>(), steps)
        assertEquals("REGEDIT4 system, changed by the game", File(dir, "system.reg").readText())
        assertTrue(File(dir, "drive_c/Program Files/installed.dll").isFile)
        assertFalse("D: goes away for a built-in tool", Files.exists(File(dir, "dosdevices/d:").toPath()))
    }

    @Test
    fun `reset drops the prefix and keeps the saves`() = runTest {
        val store = store()
        val dir = store.prepare("g1", runtime, gameDir)
        File(dir, "drive_c/users/steamuser/Documents/save.dat").writeText("level 7")
        File(dir, "system.reg").writeText("changed")

        store.reset("g1")
        assertFalse(dir.exists())
        assertEquals("level 7", File(paths.userdata("g1"), "steamuser/Documents/save.dat").readText())

        store.prepare("g1", runtime, gameDir)
        assertEquals("REGEDIT4 system", File(dir, "system.reg").readText())
        assertEquals("level 7", File(dir, "drive_c/users/steamuser/Documents/save.dat").readText())
        assertTrue("the runtime is untouched", File(runtime.dir, "lib/wine/aarch64-windows/kernel32.dll").isFile)
    }

    @Test
    fun `delete removes the prefix and its users`() = runTest {
        val store = store()
        val dir = store.prepare("g1", runtime, gameDir)
        File(gameDir, "Game.exe").writeText("MZ")

        store.delete("g1")

        assertFalse(dir.exists())
        assertFalse(paths.userdata("g1").exists())
        assertTrue("the game's own files are not ours to delete", File(gameDir, "Game.exe").isFile)
        assertTrue(File(runtime.dir, "lib/wine/aarch64-windows/kernel32.dll").isFile)
    }

    @Test
    fun `a new recipe rebuilds the template and the prefix, and keeps the saves`() = runTest {
        val dir = store().prepare("g1", runtime, gameDir)
        File(dir, "drive_c/users/steamuser/Documents/save.dat").writeText("level 7")
        File(dir, "system.reg").writeText("changed")
        val next = installRuntime("amphora-wine-11.0-next")

        val newer = store(recipe("test-2", next.manifest.name))
        assertTrue("made for another recipe", newer.info("g1").stale)
        val steps = ArrayList<PrepareStep>()
        newer.prepare("g1", next, gameDir) { steps += it }

        assertEquals(listOf(PrepareStep.Template, PrepareStep.Prefix), steps)
        assertEquals(2, wine.runs)
        assertEquals("the old template is gone", listOf("test-2"), paths.templates.list()!!.toList())
        assertEquals("REGEDIT4 system", File(dir, "system.reg").readText())
        assertEquals(
            File(next.dir, "lib/wine/aarch64-windows/kernel32.dll").canonicalFile,
            File(dir, "drive_c/windows/system32/kernel32.dll").canonicalFile,
        )
        assertEquals("level 7", File(dir, "drive_c/users/steamuser/Documents/save.dat").readText())
        assertFalse(newer.info("g1").stale)
    }

    @Test
    fun `reinstalling the runtime rebuilds the template and the prefix`() = runTest {
        val store = store()
        val dir = store.prepare("g1", runtime, gameDir)
        File(dir, "system.reg").writeText("changed")
        File(runtime.dir, "share/wine/wine.inf").setLastModified(1_800_000_000_000)

        store.prepare("g1", runtime, gameDir)

        assertEquals(2, wine.runs)
        assertEquals("REGEDIT4 system", File(dir, "system.reg").readText())
    }

    @Test
    fun `a prefix Wine filled itself is replaced, and its users are kept`() = runTest {
        val dir = paths.prefix("default")
        wine.initialize(dir.apply { mkdirs() }, runtime, File(temp.root, "old.log"))
        File(dir, "drive_c/users/steamuser/Documents/save.dat").writeText("level 7")
        File(dir, PrefixRecord.FILE).apply { parentFile!!.mkdirs() }
            .writeText("""{"id":"default","recipe":"2026.11-m1","runtime":"$RUNTIME","createdAt":1}""")
        val store = store()
        assertTrue(store.info("default").stale)
        assertTrue(store.info("default").bytes > 100)

        store.prepare("default", runtime, null)

        assertTrue(Files.isSymbolicLink(File(dir, "drive_c/windows/system32/kernel32.dll").toPath()))
        assertEquals("level 7", File(paths.userdata("default"), "steamuser/Documents/save.dat").readText())
        assertEquals("level 7", File(dir, "drive_c/users/steamuser/Documents/save.dat").readText())
        assertFalse(store.info("default").stale)
        assertTrue("the old prefix is gone, not parked", paths.trash.list().isNullOrEmpty())
    }

    @Test
    fun `existing user data is never overwritten by the template`() = runTest {
        File(paths.userdata("g1"), "steamuser/Documents").mkdirs()
        File(paths.userdata("g1"), "steamuser/Documents/save.dat").writeText("level 7")
        // Something the user put where the template has a directory.
        File(paths.userdata("g1"), "Public").writeText("not a directory")

        val dir = store().prepare("g1", runtime, gameDir)

        assertEquals("level 7", File(dir, "drive_c/users/steamuser/Documents/save.dat").readText())
        assertEquals("not a directory", File(paths.userdata("g1"), "Public").readText())
        assertTrue("missing directories are added", File(paths.userdata("g1"), "steamuser/Saved Games").isDirectory)
    }

    @Test
    fun `a failed initialization leaves no template and keeps Wine's log`() = runTest {
        wine.failure = "wineboot 退出码 1"
        val store = store()

        val error = assertThrows(IOException::class.java) { runBlockingPrepare(store) }

        assertEquals("初始化 Windows 环境失败：wineboot 退出码 1", error.message)
        assertNull(store.template())
        assertEquals(listOf("wineboot-failed.log"), paths.templates.list()!!.toList())
        assertEquals("wine output", File(paths.templates, "wineboot-failed.log").readText())
        assertFalse(paths.prefix("default").exists())

        wine.failure = null
        store.prepare("default", runtime, null)
        assertNotNull(store.template())
        assertEquals(listOf("test"), paths.templates.list()!!.toList())
    }

    @Test
    fun `the template is not started without room for Wine's copies`() = runTest {
        val store = store(freeSpace = (5L shl 20) - 1)
        val big = runtime.copy(manifest = runtime.manifest.copy(bytes = 5L shl 20))

        val error = assertThrows(IOException::class.java) { runBlocking { store.prepare("default", big, null) } }

        assertEquals("初始化 Windows 环境失败：存储空间不足，要临时占用约 5 MiB，现在只有 4 MiB", error.message)
        assertEquals(0, wine.runs)
        assertNull(store.template())
    }

    @Test
    fun `info describes a prefix before and after it exists`() = runTest {
        val store = store()
        val before = store.info("g1")
        assertNull(before.record)
        assertFalse(before.initialized)
        assertFalse(before.stale)
        assertEquals(0, before.files)

        val dir = store.prepare("g1", runtime, gameDir)
        File(dir, "drive_c/users/steamuser/Documents/save.dat").writeText("level 7")
        val after = store.info("g1")

        assertTrue(after.initialized)
        assertFalse(after.stale)
        assertEquals("registry, timestamp, win.ini and the record", 6, after.files)
        // System files, C:, Z:, D: and users.
        assertEquals(SYSTEM_FILES.size + 4, after.links)
        assertEquals(paths.userdata("g1"), after.userdata)
        assertEquals(1, after.userdataFiles)
        assertEquals(7L, after.userdataBytes)
    }

    @Test
    fun `list shows the default prefix first, and user data without a prefix`() = runTest {
        val store = store()
        assertEquals(emptyList<String>(), store.list().map { it.id })

        store.prepare("g2", runtime, gameDir)
        store.prepare("default", runtime, null)
        store.prepare("g1", runtime, gameDir)
        store.reset("g1")

        val list = store.list()
        assertEquals(listOf("default", "g1", "g2"), list.map { it.id })
        assertEquals(listOf(true, false, true), list.map { it.initialized })
    }

    @Test
    fun `ids that are not a plain name are refused`() = runTest {
        val store = store()
        for (id in listOf("", ".", "..", ".hidden", "a/b", "../x", "a b")) {
            assertThrows(id, IllegalArgumentException::class.java) { runBlockingPrepare(store, id) }
        }
    }

    @Test
    fun `linking prefers the store file of the same name and leaves the rest alone`() = runTest {
        val storeDir = temp.newFolder("store")
        File(storeDir, "a").mkdirs()
        File(storeDir, "a/first.dll").writeText("same bytes")
        File(storeDir, "a/wanted.dll").writeText("same bytes")
        File(storeDir, "a/other.dll").writeText("some bytes")
        File(storeDir, "a/empty").writeText("")
        val tree = temp.newFolder("tree")
        File(tree, "sub").mkdirs()
        File(tree, "sub/Wanted.DLL").writeText("same bytes")
        File(tree, "sub/renamed.dll").writeText("some bytes")
        File(tree, "sub/unique.dll").writeText("mine bytes")
        File(tree, "sub/empty").writeText("")
        File(tree, "top.reg").writeText("same bytes")

        assertEquals(2, linkIdenticalFiles(tree, storeDir))

        assertEquals(File(storeDir, "a/wanted.dll").canonicalFile, File(tree, "sub/Wanted.DLL").canonicalFile)
        assertEquals(File(storeDir, "a/other.dll").canonicalFile, File(tree, "sub/renamed.dll").canonicalFile)
        for (kept in listOf("sub/unique.dll", "sub/empty", "top.reg")) {
            assertFalse(kept, Files.isSymbolicLink(File(tree, kept).toPath()))
        }
        assertEquals("same bytes", File(storeDir, "a/wanted.dll").readText())
    }

    @Test
    fun `Direct3D DLLs of the runtime take the place of Wine's in system32 and syswow64`() {
        val dxvk = D3dInfo("v3.1.1", "a78a467", "lib/wine/dxvk", listOf("d3d11", "dxgi"))
        val vkd3d = D3dInfo("v3.0.1", "cae2e00", "lib/wine/vkd3d-proton", listOf("d3d12"))
        val with = runtime.copy(manifest = runtime.manifest.copy(dxvk = dxvk, vkd3d = vkd3d))
        for (info in listOf(dxvk, vkd3d)) {
            for (arch in listOf("aarch64-windows", "i386-windows")) {
                for (name in info.dlls) {
                    File(with.dir, "${info.dir}/$arch/$name.dll").apply {
                        parentFile!!.mkdirs()
                    }.writeText("$arch $name")
                }
            }
        }
        val tree = temp.newFolder("tree")
        File(tree, "drive_c/windows/system32").mkdirs()
        File(tree, "drive_c/windows/syswow64").mkdirs()
        File(tree, "drive_c/windows/system32/d3d11.dll").writeText("Wine's")
        File(tree, "drive_c/windows/system32/user32.dll").writeText("Wine's")

        assertEquals(6, linkDirect3d(tree, with))

        assertEquals("aarch64-windows d3d11", File(tree, "drive_c/windows/system32/d3d11.dll").readText())
        assertEquals("i386-windows dxgi", File(tree, "drive_c/windows/syswow64/dxgi.dll").readText())
        assertEquals("i386-windows d3d12", File(tree, "drive_c/windows/syswow64/d3d12.dll").readText())
        val link = File(tree, "drive_c/windows/system32/d3d12.dll").toPath()
        assertFalse("a relative link", Files.readSymbolicLink(link).isAbsolute)
        assertEquals("Wine's", File(tree, "drive_c/windows/system32/user32.dll").readText())
    }

    @Test
    fun `a runtime without Direct3D implementations links none, and one that lacks a file it names fails`() {
        val tree = temp.newFolder("tree")
        File(tree, "drive_c/windows/system32").mkdirs()
        assertEquals(0, linkDirect3d(tree, runtime))

        val dxvk = D3dInfo("v3.1.1", "a78a467", "lib/wine/dxvk", listOf("d3d11"))
        val broken = runtime.copy(manifest = runtime.manifest.copy(dxvk = dxvk))
        assertThrows(IOException::class.java) { linkDirect3d(tree, broken) }
    }

    private fun runBlockingPrepare(store: PrefixStore, id: String = "default") {
        runBlocking { store.prepare(id, runtime, null) }
    }

    private fun store(recipe: Recipe = recipe("test", RUNTIME), freeSpace: Long = Long.MAX_VALUE) =
        PrefixStore(paths, recipe, EmptyCoroutineContext, wine) { freeSpace }

    private fun recipe(id: String, runtime: String) = Recipe(
        format = Recipe.FORMAT,
        id = id,
        runtime = RuntimeArtifact(runtime, "https://example.invalid/$runtime.tar.gz", "0".repeat(64), 1, 1, 1),
        components = setOf(Component.Wine),
    )

    /** A runtime tree with a few system files of distinct content. */
    private fun installRuntime(name: String): InstalledRuntime {
        val dir = paths.runtime(name)
        for (source in SYSTEM_FILES.values.toSet() + "lib/wine/i386-windows/kernel32.dll") {
            File(dir, source).apply { parentFile!!.mkdirs() }.writeText("$name:$source")
        }
        File(dir, "share/wine/wine.inf").apply { parentFile!!.mkdirs() }.writeText("[Version]")
        val manifest =
            RuntimeManifest(
                format = RuntimeManifest.FORMAT,
                name = name,
                abi = "arm64-v8a",
                minSdk = 30,
                wine = WineInfo("11.0", "0", "wine-11.0", listOf("aarch64")),
                loader = "lib/wine/aarch64-unix/wine",
                server = "bin/wineserver",
                libraryPath = listOf("lib"),
                files = 1,
                bytes = 1,
            )
        return InstalledRuntime(manifest, dir)
    }

    /** Fills a prefix the way wineboot does: copies of runtime files, a registry, a few files of its own. */
    private class FakeWine : PrefixInitializer {
        var runs = 0
        var failure: String? = null

        override suspend fun initialize(prefix: File, runtime: InstalledRuntime, log: File) {
            runs++
            log.writeText("wine output")
            failure?.let { throw IOException(it) }
            for ((path, source) in SYSTEM_FILES) {
                File(runtime.dir, source).copyTo(File(prefix, path).apply { parentFile!!.mkdirs() })
            }
            File(prefix, "system.reg").writeText("REGEDIT4 system")
            File(prefix, "user.reg").writeText("REGEDIT4 user")
            File(prefix, "userdef.reg").writeText("REGEDIT4 userdef")
            File(prefix, ".update-timestamp").writeText("1794516340\n")
            File(prefix, "drive_c/windows/win.ini").writeText("[windows]")
            for (dir in listOf("steamuser/Documents", "steamuser/Saved Games", "Public")) {
                File(prefix, "drive_c/users/$dir").mkdirs()
            }
            File(prefix, "dosdevices").mkdirs()
            Files.createSymbolicLink(File(prefix, "dosdevices/c:").toPath(), File("../drive_c").toPath())
            Files.createSymbolicLink(File(prefix, "dosdevices/z:").toPath(), File("/").toPath())
            File(prefix, ".wineserver/server-1-2").mkdirs()
            File(prefix, ".wineserver/server-1-2/lock").writeText("")
        }
    }

    private companion object {
        const val RUNTIME = "amphora-wine-11.0-test"

        /** Prefix path → the runtime file Wine copies there. */
        val SYSTEM_FILES =
            mapOf(
                "drive_c/windows/system32/kernel32.dll" to "lib/wine/aarch64-windows/kernel32.dll",
                "drive_c/windows/system32/drivers/winebus.sys" to "lib/wine/aarch64-windows/winebus.sys",
                "drive_c/windows/system32/l_intl.nls" to "share/wine/nls/l_intl.nls",
                "drive_c/windows/winsxs/arm64_common-controls/comctl32.dll" to
                    "lib/wine/aarch64-windows/comctl32_v6.dll",
            )
    }
}
