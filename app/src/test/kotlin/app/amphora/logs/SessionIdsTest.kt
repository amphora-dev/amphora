package app.amphora.logs

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionIdsTest {
    @Test
    fun `parses the timestamp and the name`() {
        val name = SessionName.parse("20261109-143210-notepad")
        assertEquals("notepad", name.name)
        assertEquals(LocalDateTime.of(2026, 11, 9, 14, 32, 10), name.startedAt)
    }

    @Test
    fun `keeps dashes inside the name`() {
        assertEquals("hollow-knight", SessionName.parse("20261109-143210-hollow-knight").name)
    }

    @Test
    fun `a bare timestamp is a session`() {
        val name = SessionName.parse("20261109-143210")
        assertEquals("session", name.name)
        assertEquals(LocalDateTime.of(2026, 11, 9, 14, 32, 10), name.startedAt)
    }

    @Test
    fun `ids that do not parse are shown as they are`() {
        for (id in listOf("notes", "crash-dump-1", "20261399-999999-bad", "")) {
            val name = SessionName.parse(id)
            assertEquals(id, name.name)
            assertNull(name.startedAt)
        }
    }

    @Test
    fun `classifies log lines`() {
        assertEquals(LineKind.Amphora, LineKind.of("amphora: session started"))
        assertEquals(LineKind.Error, LineKind.of("0024:err:module:import_dll Library d3d11.dll not found"))
        assertEquals(LineKind.Error, LineKind.of("E 读不了日志"))
        assertEquals(LineKind.Warning, LineKind.of("0024:warn:seh:dispatch_exception"))
        assertEquals(LineKind.Fixme, LineKind.of("0024:fixme:ntdll:NtQuerySystemInformation"))
        assertEquals(LineKind.Other, LineKind.of("wine: configuration updated"))
    }

    @Test
    fun `amphora lines win over the words inside them`() {
        assertEquals(LineKind.Amphora, LineKind.of("amphora: wine exited, :err: lines above"))
    }
}
