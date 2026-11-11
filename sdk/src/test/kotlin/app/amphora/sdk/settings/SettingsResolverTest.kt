package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.FixMatch
import app.amphora.sdk.engine.GameFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResolverTest {
    @Test
    fun `with nothing stored the default applies`() {
        val r = resolve(spec())
        assertEquals("a", r.value)
        assertEquals(ValueSource.Default, r.source)
        assertFalse(r.modified)
        assertFalse(r.fixOverridesGlobal)
        assertNull(r.globalValue)
        assertNull(r.gameValue)
        assertNull(r.fix)
        assertEquals(emptyList<ValueSource>(), r.invalid)
        assertEquals(Availability.Available, r.availability)
    }

    @Test
    fun `default is overridden by global, global by a fix, a fix by the game value`() {
        val fix = fix("c")

        val global = resolve(spec(), global = mapOf(ID to "b"))
        assertEquals("b" to ValueSource.Global, global.value to global.source)
        assertTrue(global.modified)

        val fixed = resolve(spec(), global = mapOf(ID to "b"), game = emptyMap(), fixes = listOf(fix))
        assertEquals("c" to ValueSource.Fix(fix), fixed.value to fixed.source)
        assertTrue(fixed.modified)

        val game = resolve(spec(), global = mapOf(ID to "b"), game = mapOf(ID to "d"), fixes = listOf(fix))
        assertEquals("d" to ValueSource.Game, game.value to game.source)
        assertTrue(game.modified)
        assertEquals("b", game.globalValue)
        assertEquals("d", game.gameValue)
        assertSame("the fix is reported even when the game value wins", fix, game.fix)
    }

    @Test
    fun `each layer applies on its own`() {
        val fix = fix("c")
        assertEquals(ValueSource.Fix(fix), resolve(spec(), game = emptyMap(), fixes = listOf(fix)).source)
        assertEquals(ValueSource.Game, resolve(spec(), game = mapOf(ID to "d")).source)
    }

    @Test
    fun `a fix overrides the global value only when they differ`() {
        val fixes = listOf(fix("c"))
        assertTrue(resolve(spec(), global = mapOf(ID to "b"), game = emptyMap(), fixes = fixes).fixOverridesGlobal)
        assertFalse(
            "fix agrees with the user",
            resolve(spec(), global = mapOf(ID to "c"), game = emptyMap(), fixes = fixes).fixOverridesGlobal,
        )
        assertFalse(
            "no global value to override",
            resolve(spec(), game = emptyMap(), fixes = fixes).fixOverridesGlobal,
        )
        assertFalse(
            "the game value wins",
            resolve(spec(), global = mapOf(ID to "b"), game = mapOf(ID to "d"), fixes = fixes).fixOverridesGlobal,
        )
    }

    @Test
    fun `the last fix that sets a setting wins and fixes for other settings are skipped`() {
        val first = fix("b")
        val second = fix("c")
        val unrelated = GameFix(FixMatch(exeName = "game.exe"), mapOf("other" to "d"), "other", "test")

        val r = resolve(spec(), game = emptyMap(), fixes = listOf(first, second, unrelated))
        assertEquals("c", r.value)
        assertSame(second, r.fix)
    }

    @Test
    fun `values outside the scope of a setting are ignored`() {
        val gameOnly = resolve(spec(scope = SettingScope.Game), global = mapOf(ID to "b"))
        assertEquals(ValueSource.Default, gameOnly.source)
        assertNull(gameOnly.globalValue)

        val globalOnly =
            resolve(
                spec(scope = SettingScope.Global),
                global = mapOf(ID to "b"),
                game = mapOf(ID to "d"),
                fixes = listOf(fix("c")),
            )
        assertEquals("b" to ValueSource.Global, globalOnly.value to globalOnly.source)
        assertNull(globalOnly.gameValue)
        assertNull(globalOnly.fix)
    }

    @Test
    fun `invalid stored values are reported by layer and never applied`() {
        val fix = fix("nonsense")
        // "x" is a known option that is not provided yet: also refused.
        val r = resolve(spec(), global = mapOf(ID to "nonsense"), game = mapOf(ID to "x"), fixes = listOf(fix))
        assertEquals("a" to ValueSource.Default, r.value to r.source)
        assertEquals(listOf(ValueSource.Global, ValueSource.Fix(fix), ValueSource.Game), r.invalid)
        assertNull(r.globalValue)
        assertNull(r.gameValue)
    }

    @Test
    fun `an invalid layer falls through to the next valid one`() {
        val badFix = fix("nonsense")
        val r = resolve(spec(), global = mapOf(ID to "b"), game = emptyMap(), fixes = listOf(badFix))
        assertEquals("b" to ValueSource.Global, r.value to r.source)
        assertEquals(listOf<ValueSource>(ValueSource.Fix(badFix)), r.invalid)

        val goodFix = fix("c")
        val g = resolve(spec(), game = mapOf(ID to "nonsense"), fixes = listOf(goodFix))
        assertEquals("c" to ValueSource.Fix(goodFix), g.value to g.source)
        assertEquals(listOf<ValueSource>(ValueSource.Game), g.invalid)
    }

    @Test
    fun `text values are checked by the rule of the setting`() {
        val digits = SettingType.Text("digits", check = { if (it.all(Char::isDigit)) null else "digits only" })
        val text = spec(type = digits, default = "")
        assertEquals("42", resolve(text, global = mapOf(ID to "42")).value)

        val bad = resolve(text, global = mapOf(ID to "4x2"))
        assertEquals("", bad.value)
        assertEquals(listOf<ValueSource>(ValueSource.Global), bad.invalid)
    }

    @Test
    fun `a missing component or planned work makes a setting unavailable but keeps its value`() {
        val needsDxvk = resolve(spec(requires = Component.Dxvk), global = mapOf(ID to "b"))
        assertEquals(Availability.NeedsComponent(Component.Dxvk), needsDxvk.availability)
        assertEquals("b", needsDxvk.value)
        assertTrue(needsDxvk.modified)

        val withDxvk = resolve(spec(requires = Component.Dxvk), components = setOf(Component.Wine, Component.Dxvk))
        assertEquals(Availability.Available, withDxvk.availability)

        assertEquals(Availability.Planned("M9"), resolve(spec(planned = "M9")).availability)
    }

    @Test
    fun `a missing component is reported before planned work`() {
        val spec = spec(requires = Component.Fex, planned = "M9")
        assertEquals(
            Availability.NeedsComponent(Component.Fex),
            SettingsResolver.availability(spec, setOf(Component.Wine)),
        )
        assertEquals(Availability.Planned("M9"), SettingsResolver.availability(spec, setOf(Component.Fex)))
    }

    @Test
    fun `resolved settings keep spec order and know their context`() {
        val specs = listOf(spec("one"), spec("two"), spec("three"))
        val global = SettingsResolver.resolve(specs, setOf(Component.Wine), mapOf("two" to "b"), null)
        assertNull(global.gameId)
        assertEquals(listOf("one", "two", "three"), global.items.map { it.spec.id })
        assertEquals("b", global.value("two"))
        assertEquals("a", global["three"].value)

        val layer = GameLayer("g1", emptyMap(), emptyList())
        assertEquals("g1", SettingsResolver.resolve(specs, setOf(Component.Wine), emptyMap(), layer).gameId)
    }

    @Test
    fun `looking up an unknown id is an error`() {
        val resolved = SettingsResolver.resolve(listOf(spec()), setOf(Component.Wine), emptyMap(), null)
        val e = assertThrows(IllegalStateException::class.java) { resolved["nope"] }
        assertEquals("unknown setting nope", e.message)
    }

    @Test
    fun `visibleWhen follows the resolved value of the other setting`() {
        val mode = spec("mode")
        val detail = spec("mode.detail", visibleWhen = "mode" to "c")
        val dangling = spec("dangling", visibleWhen = "missing" to "c")
        val specs = listOf(mode, detail, dangling)

        val hidden = SettingsResolver.resolve(specs, setOf(Component.Wine), emptyMap(), null)
        assertFalse(hidden.visible(detail))
        assertTrue(hidden.visible(mode))
        assertFalse(hidden.visible(dangling))

        val shown = SettingsResolver.resolve(specs, setOf(Component.Wine), mapOf("mode" to "c"), null)
        assertTrue(shown.visible(detail))
    }

    private fun spec(
        id: String = ID,
        type: SettingType = OPTIONS,
        default: String = "a",
        scope: SettingScope = SettingScope.Both,
        requires: Component? = null,
        planned: String? = null,
        visibleWhen: Pair<String, String>? = null,
    ) = SettingSpec(
        id = id,
        group = SettingGroup.Display,
        level = SettingLevel.Basic,
        type = type,
        default = default,
        scope = scope,
        doc = SettingDoc(id, "summary", "rationale"),
        targets = listOf("target"),
        requires = requires,
        planned = planned,
        visibleWhen = visibleWhen,
    )

    /** A maintained fix that sets [ID] to [value]. */
    private fun fix(value: String) = GameFix(FixMatch(exeName = "game.exe"), mapOf(ID to value), "reason", "test")

    private fun resolve(
        spec: SettingSpec,
        global: Map<String, String> = emptyMap(),
        game: Map<String, String>? = null,
        fixes: List<GameFix> = emptyList(),
        components: Set<Component> = setOf(Component.Wine),
    ): ResolvedSetting {
        val layer = game?.let { GameLayer("g1", it, fixes) }
        return SettingsResolver.resolve(listOf(spec), components, global, layer).items.single()
    }

    private companion object {
        const val ID = "test.setting"

        /** a to d are selectable; x is shown but not provided yet. */
        val OPTIONS =
            SettingType.Choice(
                listOf("a", "b", "c", "d").map { ChoiceOption(it, it.uppercase()) } +
                    ChoiceOption("x", "X", available = false),
            )
    }
}
