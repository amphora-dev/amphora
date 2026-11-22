package app.amphora.sdk.library

import app.amphora.sdk.internal.AmphoraPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a meaningful exe name is the title`() {
        assertEquals("HollowKnight", title("/sdcard/Games/Hollow Knight/HollowKnight.exe"))
        assertEquals("Celeste", title("/sdcard/Games/Celeste/Celeste.EXE"))
        assertEquals("kept as is", "MyGame-Win64-Shipping", title("/g/MyGame/MyGame-Win64-Shipping.exe"))
        assertEquals("game2", title("/sdcard/Games/X/game2.exe"))
    }

    @Test
    fun `a generic exe name takes the name of its folder`() {
        assertEquals("Hollow Knight", title("/sdcard/Games/Hollow Knight/Game.exe"))
        assertEquals("Terraria", title("/sdcard/Games/Terraria/LAUNCHER.exe"))
        assertEquals("Outer Wilds", title("/sdcard/Games/Outer Wilds/Game-Win64-Shipping.exe"))
        assertEquals("Doom", title("/sdcard/Games/Doom/start_x86.exe"))
        assertEquals("Doom", title("/sdcard/Games/Doom/main.x64.exe"))
    }

    @Test
    fun `generic folders are skipped on the way up`() {
        assertEquals("Satisfactory", title("/sdcard/Games/Satisfactory/bin/x64/Game.exe"))
        assertEquals("Satisfactory", title("/sdcard/Games/Satisfactory/Win64/Shipping/Client.exe"))
    }

    @Test
    fun `an exe name made only of platform tags is generic`() {
        assertEquals("Outer Wilds", title("/sdcard/Games/Outer Wilds/Win64.exe"))
        assertEquals("Outer Wilds", title("/sdcard/Games/Outer Wilds/x64.exe"))
        assertEquals("Outer Wilds", title("/sdcard/Games/Outer Wilds/Win64-Shipping.exe"))
    }

    @Test
    fun `without a meaningful folder the exe name stays`() {
        assertEquals("Game", title("/Game.exe"))
        assertEquals("Game", title("/bin/x64/Game.exe"))
        assertEquals("Game", title("Game.exe"))
    }

    @Test
    fun `the AppID is read from steam_appid next to the exe`() {
        assertEquals(440L, appId("440"))
        assertEquals("whitespace and newlines are trimmed", 1_245_620L, appId("  1245620\r\n"))
        assertEquals("64 bytes are still read", 440L, appId("440".padEnd(64)))
    }

    @Test
    fun `invalid AppIDs are ignored`() {
        for (text in listOf("", "0", "-1", "abc", "440 extra", "1e3", "99999999999999999999")) {
            assertNull(text, appId(text))
        }
        assertNull("more than 64 bytes", appId("440".padEnd(65)))
    }

    @Test
    fun `no directory, no file or a directory in its place means no AppID`() {
        assertNull(GameLibrary.detectAppId(null))
        assertNull(GameLibrary.detectAppId(temp.newFolder("empty")))
        val dir = temp.newFolder("odd")
        File(dir, "steam_appid.txt").mkdir()
        assertNull(GameLibrary.detectAppId(dir))
        assertNull(GameLibrary.detectAppId(File(temp.root, "missing")))
    }

    @Test
    fun `the same file under another spelling of its path is the same game`() = runTest {
        val dir = temp.newFolder("storage", "Games")
        val exe = File(dir, "Game.exe").apply { writeBytes(pe()) }
        val link = File(temp.root, "sdcard")
        Files.createSymbolicLink(link.toPath(), dir.parentFile.toPath())
        val library = library(backgroundScope)

        val added = library.add(exe) as AddResult.Added
        assertEquals(exe.canonicalPath, added.game.exePath)
        for (other in listOf(File(link, "Games/Game.exe"), File(dir, "../Games/./Game.exe"))) {
            assertEquals(AddResult.AlreadyAdded(added.game), library.add(other))
        }
        assertEquals(1, library.games.value?.size)
    }

    private fun library(scope: CoroutineScope) = GameLibrary(
        AmphoraPaths(temp.newFolder("files"), temp.newFolder("cache"), null),
        EmptyCoroutineContext,
        scope,
    ) {
            _,
            _,
        ->
    }

    /** The least PeInfo takes for an x86_64 executable. */
    private fun pe(): ByteArray {
        val buffer = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte()).putInt(0x3c, 0x80)
        buffer.put(0x80, 'P'.code.toByte()).put(0x81, 'E'.code.toByte()).putShort(0x84, 0x8664.toShort())
        return buffer.array()
    }

    private fun title(path: String) = GameLibrary.suggestTitle(File(path))

    private fun appId(content: String): Long? {
        val dir = temp.newFolder()
        File(dir, "steam_appid.txt").writeText(content)
        return GameLibrary.detectAppId(dir)
    }
}
