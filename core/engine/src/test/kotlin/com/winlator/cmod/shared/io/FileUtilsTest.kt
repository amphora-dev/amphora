package com.winlator.cmod.shared.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUtilsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun contentEqualsComparesAcrossBufferBoundaries() {
        val payload = ByteArray(160 * 1024) { index -> (index % 251).toByte() }
        val first = temporaryFolder.newFile("first.bin").apply { writeBytes(payload) }
        val second = temporaryFolder.newFile("second.bin").apply { writeBytes(payload) }

        assertTrue(FileUtils.contentEquals(first, second))

        payload[70 * 1024] = (payload[70 * 1024] + 1).toByte()
        second.writeBytes(payload)
        assertFalse(FileUtils.contentEquals(first, second))
    }

    @Test
    fun contentEqualsRejectsDifferentLengths() {
        val first = temporaryFolder.newFile("first.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = temporaryFolder.newFile("second.bin").apply { writeBytes(byteArrayOf(1, 2)) }

        assertFalse(FileUtils.contentEquals(first, second))
    }
}
