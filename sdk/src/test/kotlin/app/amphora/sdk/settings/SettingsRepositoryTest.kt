package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.FixMatch
import app.amphora.sdk.engine.GameFix
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.engine.RuntimeArtifact
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.library.Game
import app.amphora.sdk.library.PeArch
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: AmphoraPaths
    private lateinit var store: SettingsStore
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        paths = AmphoraPaths(temp.newFolder("files"), temp.newFolder("cache"), null)
        store = SettingsStore(paths, EmptyCoroutineContext)
        repo = SettingsRepository(store, recipe(BY_SHA, BY_NAME, BY_APP_ID))
    }

    @Test
    fun `effectiveAppId - empty means detected, none means none, digits mean that id`() {
        assertEquals(70L, SettingsRepository.effectiveAppId("", 70))
        assertNull(SettingsRepository.effectiveAppId("", null))
        assertNull(SettingsRepository.effectiveAppId("none", 70))
        assertEquals(440L, SettingsRepository.effectiveAppId("440", 70))
        assertEquals(440L, SettingsRepository.effectiveAppId("440", null))
        assertNull(SettingsRepository.effectiveAppId("abc", 70))
    }

    @Test
    fun `setGlobal refuses unknown ids, per-game settings and invalid values`() = runTest {
        assertEquals("未知设置 no.such", repo.setGlobal("no.such", "x"))
        val appId = SettingsCatalog[SettingsCatalog.STEAM_APP_ID]!!
        assertEquals("「${appId.doc.title}」只能按游戏设置", repo.setGlobal(appId.id, "440"))
        val scale = SettingsCatalog[SettingsCatalog.SCALE]!!
        assertEquals(scale.type.validate("huge"), repo.setGlobal(scale.id, "huge"))
        assertEquals(emptyMap<String, String>(), store.globalNow())
        assertFalse(paths.globalSettings.exists())
    }

    @Test
    fun `setGlobal stores a value and choosing the default clears it`() = runTest {
        assertNull(repo.setGlobal(SettingsCatalog.SCALE, "integer"))
        assertNull(repo.setGlobal(SettingsCatalog.DPI, "120"))
        assertEquals(mapOf(SettingsCatalog.SCALE to "integer", SettingsCatalog.DPI to "120"), store.globalNow())
        assertTrue(paths.globalSettings.isFile)
        val resolved = repo.global().first()
        assertEquals("integer", resolved.value(SettingsCatalog.SCALE))
        assertEquals(ValueSource.Global, resolved[SettingsCatalog.SCALE].source)

        assertNull(repo.setGlobal(SettingsCatalog.SCALE, "fit"))
        assertEquals(mapOf(SettingsCatalog.DPI to "120"), store.globalNow())
        assertNull(repo.setGlobal(SettingsCatalog.DPI, null))
        assertEquals(emptyMap<String, String>(), store.globalNow())
        assertFalse("an empty layer has no file", paths.globalSettings.exists())
        assertFalse(repo.global().first()[SettingsCatalog.SCALE].modified)
    }

    @Test
    fun `setGame refuses global-only settings`() = runTest {
        val gl = SettingsCatalog.all.first { it.scope == SettingScope.Global }
        assertEquals("「${gl.doc.title}」只能全局设置", repo.setGame(game(), gl.id, gl.default))
        assertEquals("未知设置 no.such", repo.setGame(game(), "no.such", "x"))
        assertEquals(emptyMap<String, String>(), store.gameNow(GAME_ID))
    }

    @Test
    fun `setGame stores what differs from the inherited value and clears what does not`() = runTest {
        val game = game(sha = "ff", name = "other.exe", appId = null)
        repo.setGlobal(SettingsCatalog.SCALE, "stretch")

        assertNull(repo.setGame(game, SettingsCatalog.SCALE, "stretch"))
        assertEquals("same as global", emptyMap<String, String>(), store.gameNow(GAME_ID))
        assertNull(repo.setGame(game, SettingsCatalog.SCALE, "integer"))
        assertEquals(mapOf(SettingsCatalog.SCALE to "integer"), store.gameNow(GAME_ID))
        assertTrue(paths.gameSettings(GAME_ID).isFile)
        assertNull(repo.setGame(game, SettingsCatalog.SCALE, "stretch"))
        assertEquals(emptyMap<String, String>(), store.gameNow(GAME_ID))

        assertNull(repo.setGame(game, SettingsCatalog.SCALE, "fit"))
        assertEquals("the default differs from global", mapOf(SettingsCatalog.SCALE to "fit"), store.gameNow(GAME_ID))
        assertNull(repo.setGame(game, SettingsCatalog.SCALE, null))
        assertEquals(emptyMap<String, String>(), store.gameNow(GAME_ID))
    }

    @Test
    fun `choosing the value a fix gives clears the game entry`() = runTest {
        val game = game()
        assertNull(repo.setGame(game, SettingsCatalog.SCALE, "integer"))
        assertEquals(emptyMap<String, String>(), store.gameNow(GAME_ID))
        assertEquals(ValueSource.Fix(BY_SHA), repo.resolve(game)[SettingsCatalog.SCALE].source)
    }

    @Test
    fun `a value chosen after the game AppID was changed is kept`() = runTest {
        val game = game(sha = "ff", name = "other.exe", appId = 70)
        assertNull(repo.setGame(game, SettingsCatalog.STEAM_APP_ID, "none"))
        assertEquals("the AppID fix no longer applies", "auto", repo.resolve(game).value("wine.locale"))

        assertNull(repo.setGame(game, "wine.locale", "ja_JP"))

        assertEquals("ja_JP", repo.resolve(game).value("wine.locale"))
        assertEquals(ValueSource.Game, repo.resolve(game)["wine.locale"].source)
    }

    @Test
    fun `a fix that applies through an AppID set by hand counts as inherited`() = runTest {
        val game = game(sha = "ff", name = "other.exe", appId = null)
        assertNull(repo.setGame(game, SettingsCatalog.STEAM_APP_ID, "70"))

        assertNull(repo.setGame(game, "wine.locale", "ja_JP"))

        assertEquals(mapOf(SettingsCatalog.STEAM_APP_ID to "70"), store.gameNow(GAME_ID))
        assertEquals(ValueSource.Fix(BY_APP_ID), repo.resolve(game)["wine.locale"].source)
    }

    @Test
    fun `fixes match by exe hash ignoring case`() = runTest {
        val resolved = repo.resolve(game(sha = SHA.uppercase(), name = "other.exe", appId = null))
        assertEquals("integer", resolved.value(SettingsCatalog.SCALE))
        assertEquals(ValueSource.Fix(BY_SHA), resolved[SettingsCatalog.SCALE].source)
        assertEquals("96", resolved.value(SettingsCatalog.DPI))
    }

    @Test
    fun `fixes match by exe name ignoring case`() = runTest {
        val resolved = repo.resolve(game(sha = "ff", name = "game.EXE", appId = null))
        assertEquals("120", resolved.value(SettingsCatalog.DPI))
        assertEquals("fit", resolved.value(SettingsCatalog.SCALE))
    }

    @Test
    fun `fixes match by the effective AppID`() = runTest {
        val detected = game(sha = "ff", name = "other.exe", appId = 70)
        assertEquals("ja_JP", repo.resolve(detected).value("wine.locale"))

        val other = game(id = "g2", sha = "ff", name = "other.exe", appId = null)
        assertEquals("auto", repo.resolve(other).value("wine.locale"))
        repo.setGame(other, SettingsCatalog.STEAM_APP_ID, "70")
        assertEquals("set by hand", "ja_JP", repo.resolve(other).value("wine.locale"))

        repo.setGame(detected, SettingsCatalog.STEAM_APP_ID, "440")
        assertEquals("overridden", "auto", repo.resolve(detected).value("wine.locale"))
    }

    @Test
    fun `the global context ignores fixes`() = runTest {
        val resolved = repo.resolve(null)
        assertNull(resolved.gameId)
        assertEquals(ValueSource.Default, resolved[SettingsCatalog.SCALE].source)
    }

    @Test
    fun `resetting clears some or all values of a layer`() = runTest {
        repo.setGlobal(SettingsCatalog.SCALE, "integer")
        repo.setGlobal(SettingsCatalog.DPI, "120")
        repo.resetGlobal(listOf(SettingsCatalog.SCALE))
        assertEquals(mapOf(SettingsCatalog.DPI to "120"), store.globalNow())
        repo.resetGlobal()
        assertEquals(emptyMap<String, String>(), store.globalNow())

        val game = game(sha = "ff", name = "other.exe", appId = null)
        repo.setGame(game, SettingsCatalog.SCALE, "integer")
        repo.setGame(game, SettingsCatalog.ARGS, "-windowed")
        repo.resetGame(game, listOf(SettingsCatalog.ARGS))
        assertEquals(mapOf(SettingsCatalog.SCALE to "integer"), store.gameNow(GAME_ID))
        repo.resetGame(game)
        assertEquals(emptyMap<String, String>(), store.gameNow(GAME_ID))
        assertFalse(paths.gameSettings(GAME_ID).exists())
    }

    @Test
    fun `forgetting a game deletes its settings`() = runTest {
        val game = game(sha = "ff", name = "other.exe", appId = null)
        repo.setGame(game, SettingsCatalog.SCALE, "integer")
        assertTrue(paths.gameSettings(GAME_ID).isFile)

        repo.forget(GAME_ID)

        assertFalse(paths.gameSettings(GAME_ID).exists())
        assertEquals(emptyMap<String, String>(), store.gameNow(GAME_ID))
        assertEquals("fit", repo.resolve(game).value(SettingsCatalog.SCALE))
    }

    @Test
    fun `values survive a new store reading the same files`() = runTest {
        repo.setGlobal(SettingsCatalog.SCALE, "integer")
        repo.setGame(game(sha = "ff", name = "other.exe", appId = null), SettingsCatalog.ARGS, "-windowed")

        val reopened = SettingsStore(paths, EmptyCoroutineContext)
        assertEquals(mapOf(SettingsCatalog.SCALE to "integer"), reopened.globalNow())
        assertEquals(mapOf(SettingsCatalog.ARGS to "-windowed"), reopened.gameNow(GAME_ID))
    }

    @Test
    fun `an unreadable settings file is moved aside and treated as empty`() = runTest {
        paths.globalSettings.parentFile!!.mkdirs()
        paths.globalSettings.writeText("{ broken")

        assertEquals(emptyMap<String, String>(), store.globalNow())

        assertFalse(paths.globalSettings.exists())
        val aside = paths.settings.listFiles()!!.map { it.name }
        assertTrue(aside.toString(), aside.single().startsWith("global.json.corrupt-"))
    }

    private fun game(id: String = GAME_ID, sha: String = SHA, name: String = "Game.exe", appId: Long? = null) = Game(
        id = id,
        title = "Test",
        exePath = "/storage/emulated/0/Games/Test/$name",
        exeSha256 = sha,
        exeSize = 1,
        arch = PeArch.X64,
        detectedAppId = appId,
        addedAt = 0,
    )

    private fun recipe(vararg fixes: GameFix) = Recipe(
        format = Recipe.FORMAT,
        id = "test",
        runtime = RuntimeArtifact("runtime", "https://example.invalid/runtime.tar.gz", "0".repeat(64), 1, 1, 1),
        components = setOf(Component.Wine),
        fixes = fixes.toList(),
    )

    private companion object {
        const val GAME_ID = "g1"
        val SHA = "ab12".repeat(16)
        val BY_SHA = GameFix(FixMatch(exeSha256 = SHA), mapOf(SettingsCatalog.SCALE to "integer"), "pixel art", "test")
        val BY_NAME = GameFix(FixMatch(exeName = "GAME.exe"), mapOf(SettingsCatalog.DPI to "120"), "tiny UI", "test")
        val BY_APP_ID = GameFix(FixMatch(steamAppId = 70), mapOf("wine.locale" to "ja_JP"), "mojibake", "test")
    }
}
