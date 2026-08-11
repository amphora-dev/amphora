package app.amphora.core.engine.update

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightCoordinatorTest {
    @Test
    fun sameKeySharesOneOperation(): Unit = runBlocking {
        val coordinator = SingleFlightCoordinator<Any>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expected = Any()
        val calls = AtomicInteger()

        val first =
            async {
                coordinator.run("same") {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    expected
                }
            }
        started.await()
        val second =
            async {
                coordinator.run("same") {
                    calls.incrementAndGet()
                    Any()
                }
            }

        release.complete(Unit)

        assertSame(expected, first.await())
        assertSame(expected, second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun differentKeysRunSerially(): Unit = runBlocking {
        val coordinator = SingleFlightCoordinator<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val executionOrder = mutableListOf<String>()

        val first =
            async {
                coordinator.run("first") {
                    executionOrder += "first"
                    val current = active.incrementAndGet()
                    maximumActive.updateAndGet { maxOf(it, current) }
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    active.decrementAndGet()
                    "first-result"
                }
            }
        firstStarted.await()
        val second =
            async {
                coordinator.run("second") {
                    executionOrder += "second"
                    val current = active.incrementAndGet()
                    maximumActive.updateAndGet { maxOf(it, current) }
                    active.decrementAndGet()
                    "second-result"
                }
            }

        releaseFirst.complete(Unit)

        assertEquals("first-result", first.await())
        assertEquals("second-result", second.await())
        assertEquals(listOf("first", "second"), executionOrder)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun sameKeySharesOwnerFailureWithoutRunningFollower(): Unit = runBlocking {
        supervisorScope {
            val coordinator = SingleFlightCoordinator<String>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val expected = IllegalStateException("download failed")
            val calls = AtomicInteger()

            val first =
                async {
                    coordinator.run("same") {
                        calls.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        throw expected
                    }
                }
            started.await()
            val second =
                async {
                    coordinator.run("same") {
                        calls.incrementAndGet()
                        "unexpected"
                    }
                }

            release.complete(Unit)

            assertFailureMatches(expected, failureOf(first))
            assertFailureMatches(expected, failureOf(second))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun failedOlderKeyDoesNotBlockNewKey(): Unit = runBlocking {
        supervisorScope {
            val coordinator = SingleFlightCoordinator<String>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val expected = IllegalStateException("old update failed")

            val first =
                async {
                    coordinator.run("old") {
                        started.complete(Unit)
                        release.await()
                        throw expected
                    }
                }
            started.await()
            val second = async { coordinator.run("new") { "new-result" } }

            release.complete(Unit)

            assertFailureMatches(expected, failureOf(first))
            assertEquals("new-result", second.await())
        }
    }

    @Test
    fun cancelledOwnerReleasesDifferentKey(): Unit = runBlocking {
        supervisorScope {
            val coordinator = SingleFlightCoordinator<String>()
            val started = CompletableDeferred<Unit>()
            val first =
                async {
                    coordinator.run("cancelled") {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            val second = async { coordinator.run("next") { "next-result" } }

            first.cancelAndJoin()

            assertTrue(first.isCancelled)
            assertEquals("next-result", second.await())
        }
    }

    private suspend fun failureOf(task: Deferred<*>): Throwable = try {
        task.await()
        AssertionError("Expected task to fail")
    } catch (failure: Throwable) {
        failure
    }

    private fun assertFailureMatches(expected: Throwable, actual: Throwable) {
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
    }
}
