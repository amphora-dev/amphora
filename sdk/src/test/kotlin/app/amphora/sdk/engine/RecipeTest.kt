package app.amphora.sdk.engine

import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.settings.SettingsCatalog
import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTest {
    @Test
    fun `parses a recipe with components by their serial names and fixes`() {
        val recipe = decode(
            """
            {
              "format": 1,
              "id": "test-1",
              "runtime": {
                "name": "amphora-wine-test",
                "url": "https://example.invalid/amphora-wine-test.tar.gz",
                "sha256": "00ff",
                "size": 10,
                "files": 2,
                "bytes": 30
              },
              "components": ["wine", "fex", "dxvk", "vkd3d", "mesa", "audio"],
              "fixes": [
                {
                  "match": { "exeName": "Game.exe" },
                  "values": { "display.resolution": "1920x1080" },
                  "reason": "needs 1080p",
                  "source": "test run"
                }
              ]
            }
            """,
        )

        assertEquals(Recipe.FORMAT, recipe.format)
        assertEquals("test-1", recipe.id)
        assertEquals(
            RuntimeArtifact(
                name = "amphora-wine-test",
                url = "https://example.invalid/amphora-wine-test.tar.gz",
                sha256 = "00ff",
                size = 10,
                files = 2,
                bytes = 30,
            ),
            recipe.runtime,
        )
        assertEquals(Component.entries.toSet(), recipe.components)
        val fix = recipe.fixes.single()
        assertEquals(FixMatch(exeName = "Game.exe"), fix.match)
        assertNull(fix.match.exeSha256)
        assertNull(fix.match.steamAppId)
        assertEquals(mapOf("display.resolution" to "1920x1080"), fix.values)
    }

    @Test
    fun `fixes default to none and unknown keys are ignored`() {
        val recipe = decode(
            """
            {
              "format": 1, "id": "x", "comment": "for humans",
              "runtime": { "name": "n", "url": "u", "sha256": "s", "size": 1, "files": 1, "bytes": 1, "extra": true },
              "components": ["wine"]
            }
            """,
        )
        assertEquals(emptyList<GameFix>(), recipe.fixes)
        assertEquals(setOf(Component.Wine), recipe.components)
    }

    @Test
    fun `missing fields and unknown components are errors`() {
        assertThrows(SerializationException::class.java) {
            decode("""{ "format": 1, "id": "x", "components": ["wine"] }""")
        }
        assertThrows(SerializationException::class.java) {
            decode(
                """
                {
                  "format": 1, "id": "x", "components": ["dosbox"],
                  "runtime": { "name": "n", "url": "u", "sha256": "s", "size": 1, "files": 1, "bytes": 1 }
                }
                """,
            )
        }
    }

    @Test
    fun `a recipe survives an encode and decode round trip`() {
        val recipe =
            Recipe(
                format = Recipe.FORMAT,
                id = "round-trip",
                runtime = RuntimeArtifact("n", "https://example.invalid/n.tar.gz", "ab".repeat(32), 1, 2, 3),
                components = setOf(Component.Wine, Component.Fex),
                fixes =
                listOf(
                    GameFix(FixMatch(steamAppId = 70), mapOf("wine.locale" to "ja_JP"), "reason", "source"),
                ),
            )
        assertEquals(recipe, decode(AmphoraJson.encodeToString(Recipe.serializer(), recipe)))
    }

    @Test
    fun `the shipped recipe is in the supported format and pins a complete runtime`() {
        val recipe = shippedRecipe()
        assertEquals(Recipe.FORMAT, recipe.format)
        assertTrue(recipe.id.isNotBlank())
        assertTrue(Component.Wine in recipe.components)

        val runtime = recipe.runtime
        assertTrue(runtime.sha256, Regex("[0-9a-f]{64}").matches(runtime.sha256))
        assertTrue(runtime.url, runtime.url.startsWith("https://"))
        assertTrue(runtime.url, runtime.url.endsWith("/${runtime.name}.tar.gz"))
        assertTrue(runtime.size > 0)
        assertTrue(runtime.files > 0)
        assertTrue(runtime.bytes > 0)
    }

    @Test
    fun `fixes in the shipped recipe name per-game settings with valid values`() {
        for (fix in shippedRecipe().fixes) {
            val match = fix.match
            assertTrue(
                "a fix must match something: $fix",
                match.exeSha256 != null || match.exeName != null || match.steamAppId != null,
            )
            assertTrue("a fix needs values: $fix", fix.values.isNotEmpty())
            assertTrue("a fix needs a reason: $fix", fix.reason.isNotBlank())
            for ((id, value) in fix.values) {
                val spec = SettingsCatalog[id]
                checkNotNull(spec) { "fix names unknown setting $id" }
                assertTrue("$id is not a per-game setting", spec.scope.game)
                assertNull("$id=$value", spec.type.validate(value))
            }
        }
    }

    private fun decode(json: String): Recipe = AmphoraJson.decodeFromString(Recipe.serializer(), json.trimIndent())

    private fun shippedRecipe(): Recipe {
        // Unit tests run in the module directory; fall back to the repository root.
        val file =
            listOf("src/main/assets/amphora/recipe.json", "sdk/src/main/assets/amphora/recipe.json")
                .map(::File)
                .first { it.isFile }
        return decode(file.readText())
    }
}
