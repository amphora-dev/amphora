package app.amphora.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PulseAudioProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sixteenKilobytePagesFallBackRegardlessOfWinepulse() {
        val probe =
            PulseAudioProbe(
                platformSupported = false,
                wineDriver = PulseAudioProbe.WineDriver.PRESENT,
            )
        assertTrue(probe.fallsBackToAlsa())
        assertTrue(probe.fallbackReason()!!.contains("16 KB"))
    }

    @Test
    fun missingWinepulseFallsBackOnSupportedPages() {
        val probe =
            PulseAudioProbe(
                platformSupported = true,
                wineDriver = PulseAudioProbe.WineDriver.MISSING,
            )
        assertTrue(probe.fallsBackToAlsa())
        assertTrue(probe.fallbackReason()!!.contains("winepulse"))
    }

    @Test
    fun unknownWinepulseStillTriesPulseOnSupportedPages() {
        val probe =
            PulseAudioProbe(
                platformSupported = true,
                wineDriver = PulseAudioProbe.WineDriver.UNKNOWN,
            )
        assertFalse(probe.fallsBackToAlsa())
        assertNull(probe.fallbackReason())
    }

    @Test
    fun wineDriverFindsWinepulseUnderAnInstallTree() {
        val parent = temporaryFolder.newFolder("opt")
        val install = File(parent, "proton-11.0-x86_64").apply { check(mkdirs()) }
        File(install, PulseAudioProbe.WINEPULSE_RELATIVE).apply {
            check(parentFile!!.mkdirs())
            check(createNewFile())
        }
        assertEquals(PulseAudioProbe.WineDriver.PRESENT, PulseAudioProbe.wineDriver(listOf(parent)))
    }

    @Test
    fun wineTreeWithoutWinepulseIsMissing() {
        val parent = temporaryFolder.newFolder("opt")
        val install = File(parent, "proton-old").apply { check(mkdirs()) }
        File(install, "lib/wine").apply { check(mkdirs()) }
        assertEquals(PulseAudioProbe.WineDriver.MISSING, PulseAudioProbe.wineDriver(listOf(parent)))
    }

    @Test
    fun emptyRootsAreUnknown() {
        assertEquals(
            PulseAudioProbe.WineDriver.UNKNOWN,
            PulseAudioProbe.wineDriver(listOf(temporaryFolder.newFolder("empty"))),
        )
    }
}
