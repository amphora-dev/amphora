package app.amphora.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import app.amphora.sdk.container.PrefixStore
import app.amphora.sdk.engine.DeviceInfo
import app.amphora.sdk.engine.DeviceProbe
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.engine.RuntimeManager
import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.engine.Downloader
import app.amphora.sdk.internal.engine.RuntimeInstaller
import app.amphora.sdk.internal.io.emptyTrash
import app.amphora.sdk.internal.session.ScreenSize
import app.amphora.sdk.internal.session.SessionPlanner
import app.amphora.sdk.library.GameLibrary
import app.amphora.sdk.logs.LogStore
import app.amphora.sdk.session.SessionController
import app.amphora.sdk.settings.SettingsRepository
import app.amphora.sdk.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The SDK, one per app process. Everything an app needs to run Windows
 * programs: the runtime, the library, settings, prefixes, sessions and logs.
 *
 * Construction is cheap and does no I/O on the caller's thread. Do not touch
 * it from the `:wine` session process, which has its own minimal state.
 */
public class AmphoraSdk private constructor(context: Context) {
    private val app: Context = context.applicationContext
    private val paths = AmphoraPaths(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Dispatchers.IO

    /** The single combination of runtime and fixes this build runs (12 §2.1). */
    public val recipe: Recipe = loadRecipe(app)

    public val runtime: RuntimeManager =
        RuntimeManager(recipe, paths, scope, io, Downloader(userAgent(app), io), RuntimeInstaller(paths.runtimes))

    public val settings: SettingsRepository = SettingsRepository(SettingsStore(paths, io), recipe)

    public val library: GameLibrary = GameLibrary(paths, io, scope) { game -> settings.forget(game.id) }

    public val prefixes: PrefixStore = PrefixStore(paths, recipe, io)

    public val logs: LogStore = LogStore(app, paths, io)

    public val sessions: SessionController =
        SessionController(
            context = app,
            paths = paths,
            runtime = runtime,
            planner = SessionPlanner(paths, recipe, prefixes, settings) { screenSize(app) },
            prefixes = prefixes,
            library = library,
            logs = logs,
            scope = scope,
            io = io,
        )

    /** Where the SDK keeps its data. */
    public val dataDir: File get() = paths.root

    /** Where a runtime archive can be side-loaded (`adb push`), if external storage is available. */
    public val sideloadDir: File? get() = paths.sideload

    init {
        scope.launch {
            withContext(io) { emptyTrash(paths.trash) }
            sessions.housekeeping()
        }
    }

    /** Probes the device (GPU through EGL, memory, storage). Takes a few milliseconds. */
    public suspend fun device(): DeviceInfo = withContext(io) {
        paths.root.mkdirs()
        DeviceProbe.probe(app, paths.root)
    }

    public companion object {
        private const val RECIPE_ASSET = "amphora/recipe.json"

        // Holds only the application context.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AmphoraSdk? = null

        public fun get(context: Context): AmphoraSdk = instance ?: synchronized(this) {
            instance ?: AmphoraSdk(context).also { instance = it }
        }

        private fun loadRecipe(context: Context): Recipe {
            val text = context.assets.open(RECIPE_ASSET).use { it.readBytes().decodeToString() }
            val recipe = AmphoraJson.decodeFromString(Recipe.serializer(), text)
            check(recipe.format == Recipe.FORMAT) { "recipe format ${recipe.format}, expected ${Recipe.FORMAT}" }
            AmphoraLog.i("recipe ${recipe.id}, runtime ${recipe.runtime.name}")
            return recipe
        }

        /** The panel's native size: what a full-screen session gets, in either orientation. */
        private fun screenSize(context: Context): ScreenSize {
            val mode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
            return ScreenSize(mode.physicalWidth, mode.physicalHeight)
        }

        private fun userAgent(context: Context): String {
            val version =
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                    ?: "dev"
            return "Amphora/$version"
        }
    }
}
