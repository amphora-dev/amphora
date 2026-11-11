package app.amphora.settings

import app.amphora.sdk.library.Game
import app.amphora.sdk.library.PeArch
import app.amphora.sdk.settings.Availability
import app.amphora.sdk.settings.ResolvedSetting
import app.amphora.sdk.settings.ResolvedSettings
import app.amphora.sdk.settings.SettingLevel
import app.amphora.sdk.settings.SettingSpec
import app.amphora.sdk.settings.SettingType
import app.amphora.sdk.settings.SettingsCatalog
import app.amphora.sdk.settings.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiTest {
    private val game =
        Game(
            id = "g1",
            title = "Game",
            exePath = "/storage/emulated/0/Games/Game/Game.exe",
            exeSha256 = "00",
            exeSize = 1,
            arch = PeArch.X64,
            addedAt = 0,
        )

    /** Every catalog setting at its default, plus the given user values. */
    private fun resolved(
        global: Map<String, String> = emptyMap(),
        perGame: Map<String, String> = emptyMap(),
        gameId: String? = null,
    ): ResolvedSettings = ResolvedSettings(
        SettingsCatalog.all.map { spec ->
            val g = global[spec.id]
            val p = perGame[spec.id]
            ResolvedSetting(
                spec = spec,
                value = p ?: g ?: spec.default,
                source =
                when {
                    p != null -> ValueSource.Game
                    g != null -> ValueSource.Global
                    else -> ValueSource.Default
                },
                globalValue = g,
                gameValue = p,
                fix = null,
                invalid = emptyList(),
                availability = Availability.Available,
            )
        },
        gameId,
    )

    private fun SettingsUi.Ready.ids(): Set<String> = groups.flatMap { g -> g.items.map { it.spec.id } }.toSet()

    private fun visibleAt(level: SettingLevel, scope: (SettingSpec) -> Boolean): Set<String> {
        val all = resolved()
        return SettingsCatalog.all
            .filter { scope(it) && it.level <= level && all.visible(it) }
            .map { it.id }
            .toSet()
    }

    @Test
    fun `global lists only settings with a global scope`() {
        val ui = SettingsUi.build(SettingsContext.Global, SettingLevel.Developer, resolved())
        assertEquals(visibleAt(SettingLevel.Developer) { it.scope.global }, ui.ids())
        assertTrue(ui.ids().all { SettingsCatalog[it]!!.scope.global })
    }

    @Test
    fun `a game lists only settings with a game scope`() {
        val ui = SettingsUi.build(SettingsContext.ForGame(game), SettingLevel.Developer, resolved(gameId = game.id))
        assertEquals(visibleAt(SettingLevel.Developer) { it.scope.game }, ui.ids())
    }

    @Test
    fun `the level hides settings above it and counts them`() {
        val basic = SettingsUi.build(SettingsContext.Global, SettingLevel.Basic, resolved())
        assertTrue(basic.groups.flatMap { it.items }.all { it.spec.level == SettingLevel.Basic })
        val developer = SettingsUi.build(SettingsContext.Global, SettingLevel.Developer, resolved())
        assertEquals(developer.shown, basic.shown + basic.groups.sumOf { it.hidden })
    }

    @Test
    fun `a changed setting is shown whatever its level`() {
        val spec = SettingsCatalog.all.first {
            it.level == SettingLevel.Developer && it.scope.global &&
                it.visibleWhen == null
        }
        val other =
            (spec.type as? SettingType.Choice)?.options?.firstOrNull { it.key != spec.default }?.key
                ?: if (spec.default == SettingType.Toggle.ON) SettingType.Toggle.OFF else SettingType.Toggle.ON
        val ui = SettingsUi.build(
            SettingsContext.Global,
            SettingLevel.Basic,
            resolved(global = mapOf(spec.id to other)),
        )
        assertTrue(spec.id in ui.ids())
        assertEquals(1, ui.setHere)
    }

    @Test
    fun `groups come in catalog order and are never empty of settings in scope`() {
        val ui = SettingsUi.build(SettingsContext.Global, SettingLevel.Developer, resolved())
        assertEquals(ui.groups.sortedBy { it.group.ordinal }, ui.groups)
        assertFalse(ui.groups.any { it.items.isEmpty() && it.hidden == 0 })
    }

    @Test
    fun `item lookups respect the scope`() {
        val gameOnly = SettingsCatalog.all.first { !it.scope.global }
        val global = SettingsUi.build(SettingsContext.Global, SettingLevel.Developer, resolved())
        assertEquals(null, global.item(gameOnly.id))
        val perGame = SettingsUi.build(
            SettingsContext.ForGame(game),
            SettingLevel.Developer,
            resolved(gameId = game.id),
        )
        assertEquals(gameOnly, perGame.item(gameOnly.id)?.spec)
    }

    @Test
    fun `a game inherits fix, then global, then default`() {
        val spec = SettingsCatalog.all.first { it.scope.global && it.scope.game }
        val context = SettingsContext.ForGame(game)
        val plain = resolved(gameId = game.id)[spec.id]
        assertEquals(spec.default, context.inherited(plain))
        val withGlobal = resolved(global = mapOf(spec.id to "x"), gameId = game.id)[spec.id]
        assertEquals("x", context.inherited(withGlobal))
        assertFalse(context.setHere(withGlobal))
        assertTrue(SettingsContext.Global.setHere(withGlobal))
    }
}
