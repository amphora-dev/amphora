package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveConfigTest {
    @Test
    fun `engine variables come first and are marked as coming from the engine`() {
        val config = build()
        val engine = config.env.take(ENGINE_ENV.size)
        assertEquals(ENGINE_ENV.map { (name, value) -> ConfigEntry(name, value, ConfigEntry.ENGINE) }, engine)
        assertTrue(engine.all { it.fromEngine })
        assertTrue(config.env.drop(ENGINE_ENV.size).none { it.fromEngine })
        assertEquals("/prefix", config.envMap()["WINEPREFIX"])
    }

    @Test
    fun `settings generate variables with the setting as origin`() {
        val config = build()
        val wineDebug = config.env.single { it.name == "WINEDEBUG" }
        assertEquals(ConfigEntry("WINEDEBUG", "-all", SettingsCatalog.WINE_LOG), wineDebug)
        assertFalse(wineDebug.fromEngine)
        assertEquals("en_US.UTF-8", config.envMap()["LC_ALL"])
        assertEquals("en_US.UTF-8", config.envMap()["LANG"])
        assertEquals("wine.locale", config.env.single { it.name == "LANG" }.origin)
    }

    @Test
    fun `user environment cannot override engine or generated variables`() {
        val env = "WINEPREFIX=/elsewhere\nWINEDEBUG=+all\nWINEDLLOVERRIDES=d3d11=n\nSteamGameId=1\nMY_FLAG=1\n"
        val config = build(game = mapOf(SettingsCatalog.ENV to env, "wine.hideD3d11" to "on"), detectedAppId = 70)

        assertEquals(
            listOf(
                ConfigEntry("WINEPREFIX", "/elsewhere", SettingsCatalog.ENV),
                ConfigEntry("WINEDEBUG", "+all", SettingsCatalog.ENV),
                ConfigEntry("WINEDLLOVERRIDES", "d3d11=n", SettingsCatalog.ENV),
                ConfigEntry("SteamGameId", "1", SettingsCatalog.ENV),
            ),
            config.rejectedEnv,
        )
        val effective = config.envMap()
        assertEquals("/prefix", effective["WINEPREFIX"])
        assertEquals("-all", effective["WINEDEBUG"])
        assertEquals("d3d11=", effective["WINEDLLOVERRIDES"])
        assertEquals("70", effective["SteamGameId"])
        assertEquals(ConfigEntry("MY_FLAG", "1", SettingsCatalog.ENV), config.env.last())
    }

    @Test
    fun `user environment adds new variables in order, and the last of a repeated name wins`() {
        val config = build(game = mapOf(SettingsCatalog.ENV to "# tuning\nA=1\n\nB=x=y\nA=2"))
        assertEquals(emptyList<ConfigEntry>(), config.rejectedEnv)
        val user = config.env.filter { it.origin == SettingsCatalog.ENV }
        assertEquals(listOf("A" to "2", "B" to "x=y"), user.map { it.name to it.value })
    }

    @Test
    fun `DLL overrides from settings merge, and the override setting wins on the same name`() {
        val config =
            build(
                game =
                mapOf(
                    "wine.hideD3d11" to "on",
                    "wine.hideD3d10" to "on",
                    SettingsCatalog.DLL_OVERRIDES to "D3D11.dll=n;xinput1_3,XInput9_1_0=b",
                ),
            )
        assertEquals(
            listOf(
                ConfigEntry("d3d11", "n", SettingsCatalog.DLL_OVERRIDES),
                ConfigEntry("d3d10", "", "wine.hideD3d10"),
                ConfigEntry("d3d10_1", "", "wine.hideD3d10"),
                ConfigEntry("d3d10core", "", "wine.hideD3d10"),
                ConfigEntry("xinput1_3", "b", SettingsCatalog.DLL_OVERRIDES),
                ConfigEntry("xinput9_1_0", "b", SettingsCatalog.DLL_OVERRIDES),
            ),
            config.dllOverrides,
        )
        assertEquals(
            ConfigEntry(
                "WINEDLLOVERRIDES",
                "d3d11=n;d3d10=;d3d10_1=;d3d10core=;xinput1_3=b;xinput9_1_0=b",
                SettingsCatalog.DLL_OVERRIDES,
            ),
            config.env.single { it.name == "WINEDLLOVERRIDES" },
        )
    }

    @Test
    fun `without DLL overrides there is no WINEDLLOVERRIDES`() {
        val config = build()
        assertEquals(emptyList<ConfigEntry>(), config.dllOverrides)
        assertNull(config.envMap()["WINEDLLOVERRIDES"])
    }

    @Test
    fun `a malformed override setting adds nothing`() {
        // Refused by validation, so it never reaches the effect.
        val config = build(game = mapOf(SettingsCatalog.DLL_OVERRIDES to "d3d11=x"))
        assertEquals(emptyList<ConfigEntry>(), config.dllOverrides)
    }

    @Test
    fun `settings the recipe cannot apply are listed as inactive when changed`() {
        val withoutDxvk = build(global = mapOf("d3d.d3d11" to "wined3d", "display.frameRate" to "60"))
        assertEquals(listOf("display.frameRate", "d3d.d3d11"), withoutDxvk.inactive.map { it.spec.id })
        assertEquals(emptyList<ConfigEntry>(), withoutDxvk.dllOverrides)

        val withDxvk =
            build(global = mapOf("d3d.d3d11" to "wined3d"), components = setOf(Component.Wine, Component.Dxvk))
        assertEquals(emptyList<ResolvedSetting>(), withDxvk.inactive)
        assertEquals(
            listOf("d3d9", "d3d10core", "d3d11", "dxgi").map { ConfigEntry(it, "b", "d3d.d3d11") },
            withDxvk.dllOverrides,
        )
    }

    @Test
    fun `unchanged unavailable settings are not inactive and generate nothing`() {
        val config = build()
        assertEquals(emptyList<ResolvedSetting>(), config.inactive)
        assertTrue(config.env.none { it.name.startsWith("FEX_") || it.name.startsWith("MESA_") })
    }

    @Test
    fun `the Steam AppID comes from the setting, else from detection`() {
        val detected = build(detectedAppId = 70)
        assertEquals(70L, detected.steamAppId)
        assertEquals(
            listOf(
                ConfigEntry("SteamGameId", "70", SettingsCatalog.STEAM_APP_ID),
                ConfigEntry("SteamAppId", "70", SettingsCatalog.STEAM_APP_ID),
            ),
            detected.env.filter { it.name.startsWith("Steam") },
        )

        val set = build(game = mapOf(SettingsCatalog.STEAM_APP_ID to "440"), detectedAppId = 70)
        assertEquals(440L, set.steamAppId)
        assertEquals("440", set.envMap()["SteamGameId"])
        assertEquals("440", set.envMap()["SteamAppId"])

        val none = build(game = mapOf(SettingsCatalog.STEAM_APP_ID to "none"), detectedAppId = 70)
        assertNull(none.steamAppId)
        assertTrue(none.env.none { it.name.startsWith("Steam") })

        assertNull(build().steamAppId)
    }

    @Test
    fun `Wine log levels map to WINEDEBUG`() {
        fun wineDebug(vararg values: Pair<String, String>) = build(global = mapOf(*values)).envMap()["WINEDEBUG"]
        assertEquals("-all", wineDebug())
        assertEquals("-all,err+all", wineDebug(SettingsCatalog.WINE_LOG to "err"))
        assertEquals("-all,err+all,warn+all", wineDebug(SettingsCatalog.WINE_LOG to "warn"))
        assertEquals("-all", wineDebug(SettingsCatalog.WINE_LOG to "custom"))
    }

    @Test
    fun `a custom field applies before the setting it parameterizes`() {
        val custom = mapOf(SettingsCatalog.WINE_LOG to "custom", SettingsCatalog.WINE_LOG_CUSTOM to "+relay,+seh")
        val config = build(global = custom)
        assertEquals("+relay,+seh", config.envMap()["WINEDEBUG"])
        assertEquals(SettingsCatalog.WINE_LOG, config.env.single { it.name == "WINEDEBUG" }.origin)
    }

    @Test
    fun `desktop settings and arguments pass through`() {
        val config =
            build(
                global =
                mapOf(
                    SettingsCatalog.RESOLUTION to "custom",
                    SettingsCatalog.RESOLUTION_CUSTOM to "1000x600",
                    SettingsCatalog.SCALE to "integer",
                    SettingsCatalog.DPI to "144",
                ),
                game = mapOf(SettingsCatalog.ARGS to """-windowed "-config=C:\My Games\a.ini" ''"""),
            )
        assertEquals("custom", config.resolution)
        assertEquals("1000x600", config.customResolution)
        assertEquals("integer", config.scale)
        assertEquals(144, config.dpi)
        assertEquals(listOf("-windowed", """-config=C:\My Games\a.ini""", ""), config.args)

        val defaults = build()
        assertEquals("1280x720", defaults.resolution)
        assertEquals("", defaults.customResolution)
        assertEquals("fit", defaults.scale)
        assertEquals(96, defaults.dpi)
        assertEquals(emptyList<String>(), defaults.args)
    }

    @Test
    fun `describe lists the environment, refusals, desktop, arguments and inactive settings`() {
        val config =
            build(
                global = mapOf(SettingsCatalog.RESOLUTION to "custom", SettingsCatalog.RESOLUTION_CUSTOM to "1000x600"),
                game =
                mapOf(
                    SettingsCatalog.ENV to "HOME=/root",
                    SettingsCatalog.ARGS to "-a -b",
                    "d3d.vendor" to "nvidia",
                ),
            )
        val lines = config.describe().lines()
        assertEquals("# env", lines.first())
        assertTrue(lines.toString(), "WINEPREFIX=/prefix    # engine" in lines)
        assertTrue(lines.toString(), "WINEDEBUG=-all    # debug.wineLog" in lines)
        val rejected = lines.indexOf("# rejected (engine-owned)")
        assertTrue(lines.toString(), rejected > 0)
        assertEquals("# HOME=/root", lines[rejected + 1])
        assertTrue(lines.toString(), "# desktop custom (1000x600) scale=fit dpi=96" in lines)
        assertTrue(lines.toString(), "# args -a -b" in lines)
        assertEquals("# inactive: d3d.vendor", lines.dropLast(1).last())
        assertEquals("ends with a newline", "", lines.last())
    }

    @Test
    fun `describe leaves out empty sections`() {
        val lines = build().describe().lines()
        assertTrue("# desktop 1280x720 scale=fit dpi=96" in lines)
        assertTrue(lines.none { it.startsWith("# rejected") || it.startsWith("# args") || it.startsWith("# inactive") })
    }

    @Test
    fun `every selectable value of every setting can be applied`() {
        for (spec in SettingsCatalog.all) {
            val values =
                when (val type = spec.type) {
                    is SettingType.Choice -> type.options.filter { it.available }.map { it.key }
                    SettingType.Toggle -> listOf(SettingType.Toggle.ON, SettingType.Toggle.OFF)
                    is SettingType.Text -> listOf(spec.default)
                }
            for (value in values) {
                val layer = mapOf(spec.id to value)
                val config =
                    build(
                        global = if (spec.scope.global) layer else emptyMap(),
                        game = if (spec.scope.game) layer else emptyMap(),
                        components = Component.entries.toSet(),
                    )
                val engine = config.env.filter { it.name in ENGINE_ENV }
                assertEquals(
                    "${spec.id}=$value changed an engine variable",
                    ENGINE_ENV.map { (name, v) -> ConfigEntry(name, v, ConfigEntry.ENGINE) },
                    engine,
                )
            }
        }
    }

    private fun build(
        global: Map<String, String> = emptyMap(),
        game: Map<String, String> = emptyMap(),
        components: Set<Component> = setOf(Component.Wine),
        detectedAppId: Long? = null,
    ): EffectiveConfig {
        // An explicit locale keeps the result independent of the JVM's default locale.
        val withLocale = if ("wine.locale" in global) global else global + ("wine.locale" to "en_US")
        val resolved =
            SettingsResolver.resolve(SettingsCatalog.all, components, withLocale, GameLayer("g1", game, emptyList()))
        return EffectiveConfig.build(resolved, ENGINE_ENV, detectedAppId)
    }

    private companion object {
        /** The variables SessionPlanner gives every launch. */
        val ENGINE_ENV =
            linkedMapOf(
                "WINEPREFIX" to "/prefix",
                "LD_LIBRARY_PATH" to "/runtime/lib",
                "HOME" to "/home",
                "USER" to "steamuser",
                "TMPDIR" to "/tmp",
            )
    }
}
