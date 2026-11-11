package app.amphora.sdk.internal.session

import app.amphora.sdk.engine.Component
import app.amphora.sdk.settings.Availability
import app.amphora.sdk.settings.ConfigEntry
import app.amphora.sdk.settings.EffectiveConfig
import app.amphora.sdk.settings.ResolvedSetting
import app.amphora.sdk.settings.SettingsCatalog
import app.amphora.sdk.settings.ValueSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `plain arguments are not quoted`() {
        val plain =
            listOf(
                "notepad.exe",
                """D:\Game.exe""",
                """c:\windows\system32\explorer.exe""",
                "/desktop=shell,1280x720,android",
                "-dx11",
            )
        for (arg in plain) assertEquals(arg, SessionLog.quote(arg))
    }

    @Test
    fun `empty arguments, whitespace and quotes are single-quoted`() {
        assertEquals("''", SessionLog.quote(""))
        assertEquals("'My Games'", SessionLog.quote("My Games"))
        assertEquals("'a\tb'", SessionLog.quote("a\tb"))
        assertEquals("""'say "hi"'""", SessionLog.quote("""say "hi""""))
        assertEquals("""'it'\''s'""", SessionLog.quote("it's"))
    }

    @Test
    fun `start creates the log, truncating an old one, and lines are appended with the prefix`() {
        val file = File(temp.root, "logs/session.log")
        val log = SessionLog(file)
        log.start()
        assertEquals("", file.readText())

        log.line("first")
        file.appendText("wine output\n")
        log.line("second")
        assertEquals("amphora: first\nwine output\namphora: second\n", file.readText())

        log.start()
        assertEquals("", file.readText())
    }

    @Test
    fun `the header records the session, its configuration and the command line`() {
        val file = File(temp.root, "session.log")
        val log = SessionLog(file)
        log.start()
        val spec = sampleSpec(command = listOf("""D:\Game.exe""", "-windowed", "My Arg"))
        val d3d11 = SettingsCatalog["d3d.d3d11"]!!
        val config =
            EffectiveConfig(
                env =
                listOf(
                    ConfigEntry("WINEPREFIX", "/data/prefixes/default", ConfigEntry.ENGINE),
                    ConfigEntry("WINEDEBUG", "-all", SettingsCatalog.WINE_LOG),
                ),
                rejectedEnv = listOf(ConfigEntry("HOME", "/root", SettingsCatalog.ENV)),
                dllOverrides = emptyList(),
                resolution = "1280x720",
                customResolution = "",
                scale = "fit",
                dpi = 96,
                args = listOf("-windowed", "My Arg"),
                steamAppId = null,
                inactive =
                listOf(
                    ResolvedSetting(
                        spec = d3d11,
                        value = "wined3d",
                        source = ValueSource.Global,
                        globalValue = "wined3d",
                        gameValue = null,
                        fix = null,
                        invalid = emptyList(),
                        availability = Availability.NeedsComponent(Component.Dxvk),
                    ),
                ),
            )

        log.header(spec, config, listOf("x64 needs FEX"))

        assertEquals(
            listOf(
                "amphora: session 20261109-103000-test · Test Game",
                "amphora: runtime amphora-wine-11.0-test",
                "amphora: prefix /data/prefixes/default",
                "amphora: cwd /sdcard/Games/Test",
                "amphora: desktop 1280x720 scale=fit dpi=96",
                "amphora: env WINEPREFIX=/data/prefixes/default  [engine]",
                "amphora: env WINEDEBUG=-all  [debug.wineLog]",
                "amphora: env refused HOME (engine-owned)",
                "amphora: inactive d3d.d3d11=wined3d",
                "amphora: warning x64 needs FEX",
                """$ /system/bin/linker64 /rt/lib/wine/aarch64-unix/wine c:\windows\system32\explorer.exe """ +
                    """/desktop=shell,1280x720,android D:\Game.exe -windowed 'My Arg'""",
                "",
            ),
            file.readText().split('\n'),
        )
    }

    @Test
    fun `a log that cannot be written is not an error`() {
        val dir = temp.newFolder("not-a-file")
        val log = SessionLog(dir)
        log.start()
        log.line("ignored")
        assertTrue(dir.isDirectory)
    }
}
