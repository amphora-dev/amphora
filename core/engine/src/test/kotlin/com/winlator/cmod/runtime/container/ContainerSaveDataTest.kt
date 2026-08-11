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

    private fun newContainer(root: java.io.File): Container = Container(1).apply { rootDir = root }

    private fun loadContainer(root: java.io.File): Container = Container(1).apply {
        rootDir = root
        loadData(JSONObject(root.resolve(".container").readText()))
    }
}
