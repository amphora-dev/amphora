package com.winlator.cmod.runtime.container

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerSaveDataTest {
    @Test
    fun newContainersEnablePresentWaitAndRequestVulkan14() {
        val config = Container.DEFAULT_GRAPHICSDRIVERCONFIG

        assertTrue(config.contains("vulkanVersion=1.4"))
        assertTrue(config.contains("disablePresentWait=0"))
    }

    @Test
    fun staleWritersMergeIndependentFields() {
        val root = Files.createTempDirectory("container-save-").toFile()
        try {
            val initial = newContainer(root)
            assertTrue(initial.saveData())
            val first = loadContainer(root)
            val second = loadContainer(root)

            first.name = "First writer"
            second.screenSize = "1920x1080"

            assertTrue(first.saveData())
            assertTrue(second.saveData())
            val saved = JSONObject(root.resolve(".container").readText())
            assertEquals("First writer", saved.getString("name"))
            assertEquals("1920x1080", saved.getString("screenSize"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleWriterCannotOverwriteSameField() {
        val root = Files.createTempDirectory("container-conflict-").toFile()
        try {
            assertTrue(newContainer(root).saveData())
            val first = loadContainer(root)
            val stale = loadContainer(root)

            first.name = "newer"
            stale.name = "stale"

            assertTrue(first.saveData())
            assertFalse(stale.saveData())
            assertEquals("newer", JSONObject(root.resolve(".container").readText()).getString("name"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadedContainerPersistsExtraDataChanges() {
        val root = Files.createTempDirectory("container-extra-").toFile()
        try {
            assertTrue(newContainer(root).saveData())
            val container = loadContainer(root)

            container.putExtra("appliedAppVersion", "20000156")
            assertTrue(container.saveData())

            assertEquals("20000156", loadContainer(root).getExtra("appliedAppVersion"))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Regression: a config whose extraData is already populated (every real
     * container — creation + pin-sync write extras before the preparer runs)
     * must keep accepting applied-mark saves. loadData used to run
     * checkObsoleteOrMissingProperties(extraData), injecting a default
     * "wincomponents" key nobody reads; the in-memory baseline then differed
     * from the on-disk extraData forever, and mergeWithCurrent rejected every
     * later save that touched extraData — so AppliedMarks never persisted and
     * each launch re-ran the full firstTimeBoot path.
     */
    @Test
    fun loadedContainerWithPopulatedExtraDataStillSavesMarks() {
        val root = Files.createTempDirectory("container-extra-marks-").toFile()
        try {
            val seeded = newContainer(root).apply {
                putExtra("appliedWineContent", "Proton-11|sha=abc")
                putExtra("dxvkTrustAugment", "1")
            }
            assertTrue(seeded.saveData())

            val container = loadContainer(root)
            container.putExtra("appliedAppVersion", "1")
            container.putExtra("appliedDxwrapper", "dxvk-a;vkd3d-b;dd7to9|arch=x86_64")
            assertTrue(container.saveData())

            val reloaded = loadContainer(root)
            assertEquals("1", reloaded.getExtra("appliedAppVersion"))
            assertEquals("dxvk-a;vkd3d-b;dd7to9|arch=x86_64", reloaded.getExtra("appliedDxwrapper"))
            val savedExtras = JSONObject(root.resolve(".container").readText()).getJSONObject("extraData")
            assertFalse("loadData must not inject an unused wincomponents key into extraData", savedExtras.has("wincomponents"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readersNeverObserveEmptyOrTruncatedConfig() {
        val root = Files.createTempDirectory("container-atomic-").toFile()
        try {
            val container = newContainer(root)
            assertTrue(container.saveData())
            val running = AtomicBoolean(true)
            val readFailure = AtomicReference<Throwable?>()
            val reader =
                Thread {
                    while (running.get() && readFailure.get() == null) {
                        try {
                            val text = root.resolve(".container").readText()
                            check(text.isNotEmpty())
                            JSONObject(text)
                        } catch (error: Throwable) {
                            readFailure.compareAndSet(null, error)
                        }
                    }
                }.apply { start() }

            repeat(100) {
                container.name = "container-$it-${"x".repeat(256)}"
                assertTrue(container.saveData())
            }
            running.set(false)
            reader.join(5_000)

            assertFalse(reader.isAlive)
            readFailure.get()?.let { throw AssertionError("Observed non-atomic config", it) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savePreservesUnknownFieldsFromNewerSchema() {
        val root = Files.createTempDirectory("container-forward-").toFile()
        try {
            assertTrue(newContainer(root).saveData())
            val config = root.resolve(".container")
            val futureData = JSONObject(config.readText()).put("futureSetting", "preserve-me")
            config.writeText(futureData.toString())
            val container = loadContainer(root)

            container.name = "updated"
            assertTrue(container.saveData())

            val saved = JSONObject(config.readText())
            assertEquals("updated", saved.getString("name"))
            assertEquals("preserve-me", saved.getString("futureSetting"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saveRefusesToReplaceEmptyOrBlankConfig() {
        val root = Files.createTempDirectory("container-empty-").toFile()
        try {
            assertTrue(newContainer(root).saveData())
            val container = loadContainer(root)
            val config = root.resolve(".container")
            listOf("", " \n\t").forEachIndexed { index, invalid ->
                config.writeText(invalid)
                container.name = "must-not-replace-$index"

                assertFalse(container.saveData())
                assertEquals(invalid, config.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saveRefusesToReplaceMalformedConfig() {
        val root = Files.createTempDirectory("container-malformed-").toFile()
        try {
            assertTrue(newContainer(root).saveData())
            val container = loadContainer(root)
            val config = root.resolve(".container")
            config.writeText("{broken")

            container.name = "must-not-replace"

            assertFalse(container.saveData())
            assertEquals("{broken", config.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun newContainer(root: java.io.File): Container = Container(1).apply { rootDir = root }

    private fun loadContainer(root: java.io.File): Container = Container(1).apply {
        rootDir = root
        loadData(JSONObject(root.resolve(".container").readText()))
    }
}
