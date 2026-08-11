package app.amphora.core.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilePublisherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun replacesExistingDestinationAndConsumesSource() {
        val directory = temporaryFolder.newFolder("replace")
        val source = File(directory, "replacement.tmp").apply { writeText("new") }
        val destination = File(directory, "asset.bin").apply { writeText("old") }

        AtomicFilePublisher.replace(source, destination)

        assertEquals("new", destination.readText())
        assertFalse(source.exists())
    }

    @Test
    fun publishesWhenDestinationDoesNotExist() {
        val directory = temporaryFolder.newFolder("publish")
        val source = File(directory, "replacement.tmp").apply { writeText("payload") }
        val destination = File(directory, "nested-name.bin")

        AtomicFilePublisher.replace(source, destination)

        assertTrue(destination.isFile)
        assertEquals("payload", destination.readText())
    }

    @Test
    fun rejectsCrossDirectoryReplacementAndPreservesBothFiles() {
        val source = File(temporaryFolder.newFolder("source"), "replacement.tmp").apply { writeText("new") }
        val destination = File(temporaryFolder.newFolder("destination"), "asset.bin").apply { writeText("old") }

        assertThrows(IllegalArgumentException::class.java) {
            AtomicFilePublisher.replace(source, destination)
        }

        assertEquals("new", source.readText())
        assertEquals("old", destination.readText())
    }

    @Test
    fun rejectsMissingSourceAndPreservesDestination() {
        val directory = temporaryFolder.newFolder("missing")
        val source = File(directory, "missing.tmp")
        val destination = File(directory, "asset.bin").apply { writeText("old") }

        assertThrows(IllegalArgumentException::class.java) {
            AtomicFilePublisher.replace(source, destination)
        }

        assertEquals("old", destination.readText())
    }
}
