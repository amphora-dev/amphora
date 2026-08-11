package app.amphora.feature.settings

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsStorageServiceTest {
    @Test
    fun scanUsageDelegatesOnIoDispatcher() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val expected = StorageUsage(totalBytes = 42)
        val service =
            service(
                ioDispatcher = ioDispatcher,
                scanStorage = {
                    assertSame(ioDispatcher, currentCoroutineContext()[ContinuationInterceptor])
                    expected
                },
            )

        assertSame(expected, service.scanUsage())
    }

    @Test
    fun deleteUnusedGuestDataPassesPathsAndReturnsCleanupResult() = runTest {
        val requestedPaths = listOf("/cache/first.tmp", "/cache/second.tmp")
        val expected = StorageCleanupResult(bytesFreed = 120, failedPaths = listOf(requestedPaths.last()))
        var receivedPaths: List<String>? = null
        val service =
            service(
                ioDispatcher = StandardTestDispatcher(testScheduler),
                deleteStorage = { paths ->
                    receivedPaths = paths
                    expected
                },
            )

        assertSame(expected, service.deleteUnusedGuestData(requestedPaths))
        assertEquals(requestedPaths, receivedPaths)
    }

    @Test
    fun clearShaderCacheDelegatesOnce() = runTest {
        var clearCalls = 0
        val service =
            service(
                ioDispatcher = StandardTestDispatcher(testScheduler),
                clearStateCache = { clearCalls++ },
            )

        service.clearShaderCache()

        assertEquals(1, clearCalls)
    }

    private fun service(
        ioDispatcher: CoroutineDispatcher,
        scanStorage: suspend () -> StorageUsage = { error("Unexpected storage scan") },
        deleteStorage: suspend (List<String>) -> StorageCleanupResult = {
            error("Unexpected storage cleanup")
        },
        clearStateCache: suspend () -> Unit = { error("Unexpected shader cache cleanup") },
    ): SettingsStorageService = SettingsStorageService(
        ioDispatcher = ioDispatcher,
        scanStorage = scanStorage,
        deleteStorage = deleteStorage,
        clearStateCache = clearStateCache,
    )
}
