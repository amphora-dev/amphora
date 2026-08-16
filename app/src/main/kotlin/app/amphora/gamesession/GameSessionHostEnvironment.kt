package app.amphora.gamesession

import android.content.Context
import android.util.Log
import app.amphora.core.engine.AdvancedRuntimePreferences
import app.amphora.core.engine.GraphicsDiag
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Android host boundary used while constructing a guest launch.
 *
 * Keeping preferences, filesystem-backed diagnostic setup and Android logging behind this
 * interface lets the ViewModel remain a deterministic coordinator in local JVM tests.
 */
interface GameSessionHostEnvironment {
    val hostPerformanceHudEnabled: Boolean

    /** The frame limit chosen in settings, as the runtime drawer's initial value. */
    val frameRateLimit: Int

    fun prepareGraphicsDiagnostics(): Map<String, String>
}

internal class AndroidGameSessionHostEnvironment
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
) : GameSessionHostEnvironment {
    override val hostPerformanceHudEnabled: Boolean
        get() = AdvancedRuntimePreferences.hostPerformanceHudEnabled(appContext)

    override val frameRateLimit: Int
        get() = AdvancedRuntimePreferences.frameRateLimit(appContext)

    override fun prepareGraphicsDiagnostics(): Map<String, String> {
        GraphicsDiag.clearStateCache(appContext)
        return GraphicsDiag.launchEnv(appContext).also { env ->
            Log.i(
                GraphicsDiag.TAG,
                "Graphics diag ON; DXVK logs → ${env["DXVK_LOG_PATH"]}",
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GameSessionHostEnvironmentModule {
    @Binds
    abstract fun bindGameSessionHostEnvironment(
        implementation: AndroidGameSessionHostEnvironment,
    ): GameSessionHostEnvironment
}
