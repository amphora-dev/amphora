package app.amphora.sdk.internal.session

import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.session.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSpecTest {
    @Test
    fun `argv runs the loader through the linker with explorer hosting the command`() {
        val spec = sampleSpec(command = listOf("""D:\Game.exe""", "-windowed"))
        assertEquals(
            listOf(
                SessionSpec.LINKER,
                "/rt/lib/wine/aarch64-unix/wine",
                """c:\windows\system32\explorer.exe""",
                "/desktop=shell,1280x720,android",
                """D:\Game.exe""",
                "-windowed",
            ),
            spec.argv(),
        )
        assertEquals("/system/bin/linker64", SessionSpec.LINKER)
    }

    @Test
    fun `argv keeps the command exactly, including empty arguments`() {
        val command = listOf("winecfg.exe", "", "two words")
        assertEquals(command, sampleSpec(command = command).argv().drop(4))
    }

    @Test
    fun `a spec survives the JSON hand-over to the session process`() {
        val spec = sampleSpec()
        assertEquals(spec, AmphoraJson.decodeFromString(SessionSpec.serializer(), encode(spec)))

        val tool = sampleSpec(gameId = null)
        val json = encode(tool)
        assertFalse("nulls are left out", json.contains("gameId"))
        assertEquals(tool, AmphoraJson.decodeFromString(SessionSpec.serializer(), json))
    }

    @Test
    fun `a session record survives a round trip, with the desktop unknown at first`() {
        val starting = SessionRecord(sampleSpec(), pid = 4242, startedAt = 1, lastSeen = 2, SessionPhase.Starting)
        assertNull(starting.desktop)
        val running = starting.copy(lastSeen = 3, phase = SessionPhase.Running, desktop = "1280x720")
        for (record in listOf(starting, running)) {
            val json = AmphoraJson.encodeToString(SessionRecord.serializer(), record)
            assertEquals(record, AmphoraJson.decodeFromString(SessionRecord.serializer(), json))
        }
    }

    private fun encode(spec: SessionSpec) = AmphoraJson.encodeToString(SessionSpec.serializer(), spec)
}

/** A complete spec with fixed values, shared by the session tests. */
internal fun sampleSpec(command: List<String> = listOf("notepad.exe"), gameId: String? = "g1") = SessionSpec(
    id = "20261109-103000-test",
    title = "Test Game",
    gameId = gameId,
    runtimeName = "amphora-wine-11.0-test",
    loader = "/rt/lib/wine/aarch64-unix/wine",
    server = "/rt/bin/wineserver",
    unixLibDir = "/rt/lib/wine/aarch64-unix",
    prefixDir = "/data/prefixes/default",
    workingDir = "/sdcard/Games/Test",
    command = command,
    env = linkedMapOf("WINEPREFIX" to "/data/prefixes/default", "WINEDEBUG" to "-all"),
    desktopWidth = 1280,
    desktopHeight = 720,
    scale = "fit",
    dpi = 96,
    logFile = "/data/logs/20261109-103000-test.log",
    recordFile = "/data/sessions/current.json",
    createdAt = 1_762_655_400_000,
)
