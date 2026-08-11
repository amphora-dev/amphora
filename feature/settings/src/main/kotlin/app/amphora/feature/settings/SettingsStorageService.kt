package app.amphora.feature.settings

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.engine.GraphicsDiag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Runs settings storage maintenance away from the UI dispatcher.
 *
 * The injected constructor adapts the existing storage and graphics utilities,
 * while the internal constructor keeps the orchestration JVM-testable without
 * requiring Android filesystem state.
 */
@Singleton
class SettingsStorageService
internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val scanStorage: suspend () -> StorageUsage,
    private val deleteStorage: suspend (List<String>) -> StorageCleanupResult,
    private val clearStateCache: suspend () -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ) : this(
        ioDispatcher = dispatchers.io,
        scanStorage = { StorageUsageScanner.scan(context) },
        deleteStorage = { paths -> StorageUsageScanner.deleteUnusedGuestData(context, paths) },
        clearStateCache = { GraphicsDiag.clearStateCache(context) },
    )

    suspend fun scanUsage(): StorageUsage = withContext(ioDispatcher) { scanStorage() }

    suspend fun deleteUnusedGuestData(paths: List<String>): StorageCleanupResult =
        withContext(ioDispatcher) { deleteStorage(paths) }

    suspend fun clearShaderCache() {
        withContext(ioDispatcher) { clearStateCache() }
    }
}
