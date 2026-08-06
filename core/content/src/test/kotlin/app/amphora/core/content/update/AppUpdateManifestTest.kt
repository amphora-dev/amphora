package app.amphora.core.content.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestTest {
    @Test
    fun parsesRequiredFields() {
        val m = AppUpdateManifest.parse(SAMPLE)
        assertEquals(20000042, m.versionCode)
        assertEquals("0.1.0+42.deadbeef", m.versionName)
        assertEquals(
            "https://github.com/amphora-dev/amphora/releases/download/apk/amphora-debug.apk",
            m.apkUrl,
        )
        assertEquals("a".repeat(64), m.sha256)
        assertEquals(12_345_678L, m.size)
        assertEquals("ci", m.channel)
        assertEquals("CI build", m.notes)
    }

    @Test
    fun sizeAndNotesAreOptional() {
        val json =
            """
            {
              "versionCode": 2,
              "versionName": "0.1.1",
              "apkUrl": "https://example.com/a.apk",
              "sha256": "${"b".repeat(64)}"
            }
            """.trimIndent()
        val m = AppUpdateManifest.parse(json)
        assertNull(m.size)
        assertNull(m.notes)
        assertEquals("ci", m.channel)
    }

    @Test
    fun isNewerThanComparesVersionCode() {
        val m = AppUpdateManifest.parse(SAMPLE)
        assertTrue(m.isNewerThan(1))
        assertTrue(m.isNewerThan(20000041))
        assertFalse(m.isNewerThan(20000042))
        assertFalse(m.isNewerThan(20000043))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsApkUrl() {
        AppUpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "x",
              "apkUrl": "http://example.com/a.apk",
              "sha256": "${"c".repeat(64)}"
            }
            """.trimIndent(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadSha() {
        AppUpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "x",
              "apkUrl": "https://example.com/a.apk",
              "sha256": "deadbeef"
            }
            """.trimIndent(),
        )
    }

    private companion object {
        val SAMPLE =
            """
            {
              "versionCode": 20000042,
              "versionName": "0.1.0+42.deadbeef",
              "apkUrl": "https://github.com/amphora-dev/amphora/releases/download/apk/amphora-debug.apk",
              "sha256": "${"a".repeat(64)}",
              "size": 12345678,
              "channel": "ci",
              "notes": "CI build"
            }
            """.trimIndent()
    }
}
