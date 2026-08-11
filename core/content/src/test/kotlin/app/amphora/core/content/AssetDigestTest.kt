package app.amphora.core.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AssetDigest] decides whether a provisioned asset counts as verified, so a bug
 * here either re-downloads everything on every launch or trusts the wrong bytes.
 */
class AssetDigestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test fun hashesKnownVector() {
        assertEquals(emptySha, AssetDigest.of(tmp.newFile("empty.bin")))
        val abc = tmp.newFile("abc.bin").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AssetDigest.of(abc),
        )
    }

    @Test fun streamingAndFileHashesAgree() {
        val file = tmp.newFile("payload.bin").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        val streamed = file.inputStream().use { AssetDigest.of(it) }
        assertEquals(AssetDigest.of(file), streamed)
    }

    @Test fun markerSitsNextToTheAsset() {
        val asset = File("/tmp/graphics_driver/wrapper.tzst")
        assertEquals("/tmp/graphics_driver/wrapper.tzst.sha256", AssetDigest.markerFor(asset).path)
    }

    @Test fun pinRoundTripsLowercase() {
        val asset = tmp.newFile("asset.bin")
        AssetDigest.writePin(asset, emptySha.uppercase())
        assertEquals(emptySha, AssetDigest.pinnedSha(asset))
        assertEquals(0L, AssetDigest.pinnedSize(asset))
        assertTrue("pin comparison must be case-insensitive", AssetDigest.matchesPin(asset, emptySha.uppercase()))
    }

    @Test fun fastPinCheckRequiresExistingTargetAndRecordedSize() {
        val asset = tmp.newFile("sized.bin").apply { writeText("known") }
        val sha = AssetDigest.of(asset)
        AssetDigest.writePin(asset, sha)
        assertTrue(AssetDigest.matchesPin(asset, sha))

        asset.appendText(" changed")
        assertFalse("size change must invalidate sidecar fast path", AssetDigest.matchesPin(asset, sha))

        asset.delete()
        assertFalse("missing destination must invalidate sidecar", AssetDigest.matchesPin(asset, sha))
    }

    @Test fun absentPinIsNullNotEmpty() {
        assertNull(AssetDigest.pinnedSha(tmp.newFile("unpinned.bin")))
        assertFalse(AssetDigest.matchesPin(tmp.newFile("other.bin"), emptySha))
    }

    @Test fun malformedPinIsRejectedRatherThanTrusted() {
        val asset = tmp.newFile("tampered.bin")
        // A truncated / non-hex marker must not be read back as a valid pin, or a
        // half-written sidecar would make a mismatched asset look verified.
        AssetDigest.markerFor(asset).writeText("not-a-digest")
        assertNull(AssetDigest.pinnedSha(asset))
        assertFalse(AssetDigest.matchesPin(asset, emptySha))
    }

    @Test fun trailingWhitespaceInMarkerIsTolerated() {
        // push-device.sh and sha256sum both leave a newline.
        val asset = tmp.newFile("from-script.bin")
        AssetDigest.markerFor(asset).writeText("$emptySha\n")
        assertEquals(emptySha, AssetDigest.pinnedSha(asset))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writingAnInvalidPinFailsLoudly() {
        AssetDigest.writePin(tmp.newFile("bad.bin"), "cafe")
    }
}
