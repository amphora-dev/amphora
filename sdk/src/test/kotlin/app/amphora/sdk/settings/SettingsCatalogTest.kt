package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component
import app.amphora.sdk.internal.window.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    private val all = SettingsCatalog.all

    @Test
    fun `ids are unique and lookups find the same spec`() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (spec in all) assertSame(spec, SettingsCatalog[spec.id])
        assertNull(SettingsCatalog["no.such.setting"])
    }

    @Test
    fun `every default passes the validation of its own type`() {
        for (spec in all) assertNull("${spec.id} default '${spec.default}'", spec.type.validate(spec.default))
    }

    @Test
    fun `every choice default is an available option`() {
        for (spec in all) {
            val type = spec.type as? SettingType.Choice ?: continue
            val option = type.option(spec.default)
            assertNotNull("${spec.id}: default '${spec.default}' is not an option", option)
            assertTrue("${spec.id}: default '${spec.default}' is not available", option!!.available)
        }
    }

    @Test
    fun `choices have unique, labelled keys`() {
        for (spec in all) {
            val type = spec.type as? SettingType.Choice ?: continue
            val keys = type.options.map { it.key }
            assertEquals("${spec.id}: duplicate option keys $keys", keys.size, keys.toSet().size)
            for (option in type.options) {
                assertTrue("${spec.id}: option without a key", option.key.isNotEmpty())
                assertTrue("${spec.id}: option ${option.key} has no label", option.label.isNotBlank())
            }
        }
    }

    @Test
    fun `visibleWhen names an existing choice and one of its available options`() {
        val conditional = all.filter { it.visibleWhen != null }
        assertTrue(conditional.isNotEmpty())
        for (spec in conditional) {
            val (id, value) = spec.visibleWhen!!
            val parent = SettingsCatalog[id]
            assertNotNull("${spec.id}: visibleWhen names unknown setting $id", parent)
            assertTrue("${spec.id}: refers to itself", parent !== spec)
            assertTrue("${spec.id}: $id is not a choice", parent!!.type is SettingType.Choice)
            assertNull("${spec.id}: '$value' is not a valid value of $id", parent.type.validate(value))
            // Otherwise the field could be set where its parent can never select the value that shows it.
            assertTrue("${spec.id}: global but $id is not", !spec.scope.global || parent.scope.global)
            assertTrue("${spec.id}: per-game but $id is not", !spec.scope.game || parent.scope.game)
        }
    }

    @Test
    fun `the ids the SDK reads exist`() {
        val ids =
            listOf(
                SettingsCatalog.RESOLUTION,
                SettingsCatalog.RESOLUTION_CUSTOM,
                SettingsCatalog.SCALE,
                SettingsCatalog.DPI,
                SettingsCatalog.STEAM_APP_ID,
                SettingsCatalog.ARGS,
                SettingsCatalog.ENV,
                SettingsCatalog.DLL_OVERRIDES,
                SettingsCatalog.WINE_LOG,
                SettingsCatalog.WINE_LOG_CUSTOM,
            )
        for (id in ids) assertNotNull(id, SettingsCatalog[id])
    }

    @Test
    fun `groups partition the catalog in display order`() {
        val regrouped = SettingGroup.entries.flatMap { SettingsCatalog.group(it) }
        assertEquals(all.map { it.id }.toSet(), regrouped.map { it.id }.toSet())
        assertEquals(all.size, regrouped.size)
        for (group in SettingGroup.entries) {
            assertTrue("$group is empty", SettingsCatalog.group(group).isNotEmpty())
            assertEquals(all.filter { it.group == group }, SettingsCatalog.group(group))
        }
    }

    @Test
    fun `every setting is documented`() {
        for (spec in all) {
            assertTrue("${spec.id}: title", spec.doc.title.isNotBlank())
            assertTrue("${spec.id}: summary", spec.doc.summary.isNotBlank())
            assertTrue("${spec.id}: rationale", spec.doc.rationale.isNotBlank())
            assertTrue("${spec.id}: targets", spec.targets.isNotEmpty() && spec.targets.all { it.isNotBlank() })
            spec.planned?.let { assertTrue("${spec.id}: planned note", it.isNotBlank()) }
        }
    }

    @Test
    fun `scale options are exactly the host scale modes`() {
        val type = SettingsCatalog[SettingsCatalog.SCALE]!!.type as SettingType.Choice
        assertEquals(ScaleMode.entries.map { it.key }.toSet(), type.options.map { it.key }.toSet())
        for (option in type.options) assertEquals(option.key, ScaleMode.of(option.key).key)
    }

    @Test
    fun `fixed resolution options parse as desktop sizes`() {
        val type = SettingsCatalog[SettingsCatalog.RESOLUTION]!!.type as SettingType.Choice
        for (option in type.options) {
            if (option.key == "screen" || option.key == "custom") continue
            val parsed = Resolution.parse(option.key)
            assertNotNull(option.key, parsed)
            assertEquals(option.key, parsed.toString())
        }
        assertNotNull(Resolution.parse(SettingsCatalog[SettingsCatalog.RESOLUTION]!!.default))
    }

    @Test
    fun `dpi options are numbers`() {
        val type = SettingsCatalog[SettingsCatalog.DPI]!!.type as SettingType.Choice
        for (option in type.options) assertNotNull(option.key, option.key.toIntOrNull())
    }

    @Test
    fun `resolving the catalog with nothing stored modifies nothing`() {
        val resolved = SettingsResolver.resolve(all, Component.entries.toSet(), emptyMap(), null)
        for (item in resolved.items) {
            assertFalse(item.spec.id, item.modified)
            assertEquals(item.spec.id, item.spec.default, item.value)
            assertEquals(item.spec.id, emptyList<ValueSource>(), item.invalid)
        }
    }

    @Test
    fun `availability follows the components of the recipe`() {
        val wineOnly = SettingsResolver.resolve(all, setOf(Component.Wine), emptyMap(), null)
        for (item in wineOnly.items) {
            val requires = item.spec.requires
            if (requires != null && requires != Component.Wine) {
                assertEquals(item.spec.id, Availability.NeedsComponent(requires), item.availability)
            }
        }
        val everything = SettingsResolver.resolve(all, Component.entries.toSet(), emptyMap(), null)
        for (item in everything.items) {
            val expected = item.spec.planned?.let { Availability.Planned(it) } ?: Availability.Available
            assertEquals(item.spec.id, expected, item.availability)
        }
    }
}
