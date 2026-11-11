package app.amphora.sdk.internal.session

import app.amphora.sdk.container.PrefixStore
import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.engine.RuntimeArtifact
import app.amphora.sdk.engine.RuntimeManifest
import app.amphora.sdk.engine.WineInfo
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.library.BuiltinTool
import app.amphora.sdk.library.Game
import app.amphora.sdk.library.PeArch
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.settings.ConfigEntry
import app.amphora.sdk.settings.Resolution
import app.amphora.sdk.settings.SettingsCatalog
import app.amphora.sdk.settings.SettingsRepository
import app.amphora.sdk.settings.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionPlannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: AmphoraPaths
    private lateinit var store: SettingsStore
    private lateinit var settings: SettingsRepository
    private lateinit var runtime: InstalledRuntime
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        paths = AmphoraPaths(temp.newFolder("files"), temp.newFolder("cache"), null)
        store = SettingsStore(paths, EmptyCoroutineContext)
        settings = SettingsRepository(store, recipe())
        runtime = InstalledRuntime(manifest(), paths.runtime(RUNTIME))
        gameDir = temp.newFolder("Games", "Hollow Knight")
    }

    @Test
    fun `a built-in tool runs in the default prefix from HOME`() = runTest {
        val plan = planner().plan(LaunchTarget.Tool(BuiltinTool.Notepad), runtime, NOW)
        val spec = plan.spec

        assertEquals(BuiltinTool.Notepad.title, spec.title)
        assertNull(spec.gameId)
        assertEquals(listOf("notepad.exe"), spec.command)
        assertEquals(paths.home.path, spec.workingDir)
        assertEquals(paths.prefix(Game.DEFAULT_PREFIX).path, spec.prefixDir)
        assertEquals(emptyList<String>(), plan.warnings)
        assertEquals(
            listOf(
                SessionSpec.LINKER,
                runtime.loader.path,
                """c:\windows\system32\explorer.exe""",
                "/desktop=shell,1280x720,android",
                "notepad.exe",
            ),
            spec.argv(),
        )
    }

    @Test
    fun `the spec names the runtime, the log and the session record`() = runTest {
        val spec = planner().plan(LaunchTarget.Tool(BuiltinTool.Winecfg), runtime, NOW).spec

        assertEquals(RUNTIME, spec.runtimeName)
        assertEquals(File(paths.runtime(RUNTIME), "lib/wine/aarch64-unix/wine").path, spec.loader)
        assertEquals(File(paths.runtime(RUNTIME), "bin/wineserver").path, spec.server)
        assertEquals(File(paths.runtime(RUNTIME), "lib/wine/aarch64-unix").path, spec.unixLibDir)
        assertEquals(paths.log(spec.id).path, spec.logFile)
        assertEquals(paths.currentSession.path, spec.recordFile)
        assertEquals(NOW, spec.createdAt)
    }

    @Test
    fun `the engine owns the prefix, library path, home, user and temp directory`() = runTest {
        val game = game(prefixId = "shared")
        settings.setGame(game, SettingsCatalog.ENV, "HOME=/sdcard\nWINEPREFIX=/sdcard/prefix\nDXVK_HUD=fps")

        val plan = planner().plan(LaunchTarget.Play(game), runtime, NOW)
        val env = plan.spec.env

        assertEquals(paths.prefix("shared").path, env["WINEPREFIX"])
        assertEquals(paths.prefix("shared").path, plan.spec.prefixDir)
        val lib = paths.runtime(RUNTIME)
        assertEquals("${File(lib, "lib").path}:${File(lib, "lib/wine/aarch64-unix").path}", env["LD_LIBRARY_PATH"])
        assertEquals(paths.home.path, env["HOME"])
        assertEquals("steamuser", env["USER"])
        assertEquals(paths.tmp.path, env["TMPDIR"])
        assertEquals("fps", env["DXVK_HUD"])
        assertEquals(listOf("HOME", "WINEPREFIX"), plan.config.rejectedEnv.map { it.name })
        assertTrue(plan.config.env.take(5).all { it.origin == ConfigEntry.ENGINE })
        assertEquals("the spec carries exactly the effective environment", plan.config.envMap(), env)
    }

    @Test
    fun `a game runs from its directory with drive D and its arguments`() = runTest {
        val game = game()
        settings.setGame(game, SettingsCatalog.ARGS, """-windowed "-name=Two Words"""")
        settings.setGlobal(SettingsCatalog.SCALE, "integer")

        val plan = planner().plan(LaunchTarget.Play(game), runtime, NOW)

        assertEquals("""D:\Game.exe""", plan.spec.command.first())
        assertEquals(listOf("""D:\Game.exe""", "-windowed", "-name=Two Words"), plan.spec.command)
        assertEquals(gameDir.path, plan.spec.workingDir)
        assertEquals("g1", plan.spec.gameId)
        assertEquals("Hollow Knight", plan.spec.title)
        assertEquals("integer", plan.spec.scale)
        assertEquals(Resolution(1280, 720), plan.spec.desktop)
        assertEquals(96, plan.spec.dpi)
    }

    @Test
    fun `the detected AppID reaches the environment`() = runTest {
        val plan = planner().plan(LaunchTarget.Play(game(appId = 367520)), runtime, NOW)
        assertEquals("367520", plan.spec.env["SteamGameId"])
        assertEquals("367520", plan.spec.env["SteamAppId"])
    }

    @Test
    fun `session ids are a local time stamp and a slug of the title`() = runTest {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(NOW))
        assertTrue(Regex("""\d{8}-\d{6}""").matches(stamp))

        suspend fun id(target: LaunchTarget) = planner().plan(target, runtime, NOW).spec.id
        assertEquals("$stamp-hollow-knight", id(LaunchTarget.Play(game())))
        val punctuated = game(title = "  Hollow Knight: Silksong!! ")
        assertEquals("$stamp-hollow-knight-silksong", id(LaunchTarget.Play(punctuated)))
        val long = game(title = "The Elder Scrolls V Skyrim Special Edition")
        assertEquals("at most 24 characters", "$stamp-the-elder-scrolls-v-skyr", id(LaunchTarget.Play(long)))
        assertEquals("a title without ASCII letters", stamp, id(LaunchTarget.Tool(BuiltinTool.Notepad)))
    }

    @Test
    fun `the desktop is resolved against the screen when planned`() = runTest {
        suspend fun desktop() = planner().plan(LaunchTarget.Tool(BuiltinTool.Notepad), runtime, NOW).spec.desktop

        assertEquals(Resolution(1280, 720), desktop())
        settings.setGlobal(SettingsCatalog.RESOLUTION, "screen")
        // 720 × 3040 / 1904 = 1149.6, down to a multiple of 8; the orientation does not matter.
        assertEquals(Resolution(1144, 720), desktop())
    }

    @Test
    fun `a missing exe is a warning, not an error`() = runTest {
        val missing = game(exe = File(gameDir, "Gone.exe"), arch = PeArch.Arm64)
        val plan = planner().plan(LaunchTarget.Play(missing), runtime, NOW)
        val path = File(gameDir, "Gone.exe").path
        assertEquals(listOf("找不到 $path（文件被移动，或没有存储权限）"), plan.warnings)
        assertEquals("""D:\Gone.exe""", plan.spec.command.single())
    }

    @Test
    fun `x86 programs warn while the recipe has no FEX`() = runTest {
        suspend fun warnings(arch: PeArch, recipe: Recipe = recipe()) =
            planner(recipe).plan(LaunchTarget.Play(game(arch = arch)), runtime, NOW).warnings

        assertEquals(listOf("x86 程序需要 FEX（M2），当前配方还没有"), warnings(PeArch.X86))
        assertEquals(listOf("x64 程序需要 FEX（M2），当前配方还没有"), warnings(PeArch.X64))
        assertEquals(emptyList<String>(), warnings(PeArch.Arm64))
        assertEquals(emptyList<String>(), warnings(PeArch.Arm64ec))
        assertEquals(emptyList<String>(), warnings(PeArch.X64, recipe(Component.Wine, Component.Fex)))
    }

    /** A planner over the same stored settings as [settings], for [recipe]. */
    private fun planner(recipe: Recipe = recipe()): SessionPlanner {
        val prefixes = PrefixStore(paths, recipe, EmptyCoroutineContext)
        return SessionPlanner(paths, recipe, prefixes, SettingsRepository(store, recipe)) { SCREEN }
    }

    private fun game(
        title: String = "Hollow Knight",
        exe: File = File(gameDir, "Game.exe").apply { if (!exists()) writeText("MZ") },
        arch: PeArch = PeArch.Arm64,
        appId: Long? = null,
        prefixId: String = Game.DEFAULT_PREFIX,
    ) = Game(
        id = "g1",
        title = title,
        exePath = exe.path,
        exeSha256 = "0".repeat(64),
        exeSize = exe.length(),
        arch = arch,
        detectedAppId = appId,
        addedAt = 0,
        prefixId = prefixId,
    )

    private fun recipe(vararg components: Component = arrayOf(Component.Wine)) = Recipe(
        format = Recipe.FORMAT,
        id = "test",
        runtime = RuntimeArtifact(RUNTIME, "https://example.invalid/$RUNTIME.tar.gz", "0".repeat(64), 1, 1, 1),
        components = components.toSet(),
    )

    private fun manifest() = RuntimeManifest(
        format = RuntimeManifest.FORMAT,
        name = RUNTIME,
        abi = "arm64-v8a",
        minSdk = 28,
        wine = WineInfo("11.0", "0", "wine-11.0", listOf("x86_64")),
        loader = "lib/wine/aarch64-unix/wine",
        server = "bin/wineserver",
        libraryPath = listOf("lib", "lib/wine/aarch64-unix"),
        files = 1,
        bytes = 1,
    )

    private companion object {
        const val RUNTIME = "amphora-wine-11.0-test"
        const val NOW = 1_762_655_400_000L
        val SCREEN = ScreenSize(1904, 3040)
    }
}
