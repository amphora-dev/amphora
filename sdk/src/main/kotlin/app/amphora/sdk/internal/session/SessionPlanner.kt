package app.amphora.sdk.internal.session

import app.amphora.sdk.container.PrefixStore
import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.library.Game
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.settings.EffectiveConfig
import app.amphora.sdk.settings.Resolution
import app.amphora.sdk.settings.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A launch, worked out: the spec for the session process and what it will use. */
internal data class Plan(val spec: SessionSpec, val config: EffectiveConfig, val warnings: List<String>)

/** The display a session goes full screen on, in pixels, either orientation. */
internal data class ScreenSize(val width: Int, val height: Int)

/** Turns a launch target into a [SessionSpec] using the settings, the runtime and the prefix. */
internal class SessionPlanner(
    private val paths: AmphoraPaths,
    private val recipe: Recipe,
    private val prefixes: PrefixStore,
    private val settings: SettingsRepository,
    private val screen: () -> ScreenSize,
) {
    suspend fun plan(target: LaunchTarget, runtime: InstalledRuntime, now: Long = System.currentTimeMillis()): Plan {
        val game = (target as? LaunchTarget.Play)?.game
        val resolved = settings.resolve(game)
        val prefixId = game?.prefixId ?: Game.DEFAULT_PREFIX
        val prefixDir = paths.prefix(prefixId)
        val config = EffectiveConfig.build(resolved, engineEnv(runtime, prefixDir), game?.detectedAppId)

        val warnings = ArrayList<String>()
        val command: List<String>
        val workingDir: File
        val title: String
        when (target) {
            is LaunchTarget.Play -> {
                val exe = File(target.game.exePath)
                if (!exe.isFile) warnings += "找不到 ${exe.path}（文件被移动，或没有存储权限）"
                if (!target.game.arch.native && Component.Fex !in recipe.components) {
                    warnings += "${target.game.arch.label} 程序需要 FEX（${Component.Fex.milestone}），当前配方还没有"
                }
                command = listOf("D:\\${exe.name}") + config.args
                workingDir = exe.parentFile ?: paths.home
                title = target.game.title
            }
            is LaunchTarget.Tool -> {
                command = target.tool.command
                workingDir = paths.home
                title = target.tool.title
            }
        }
        val id = sessionId(now, title)
        val display = screen()
        val desktop = Resolution.resolve(config.resolution, config.customResolution, display.width, display.height)
        val spec =
            SessionSpec(
                id = id,
                title = title,
                gameId = game?.id,
                runtimeName = runtime.manifest.name,
                loader = runtime.loader.path,
                server = runtime.server.path,
                unixLibDir = runtime.unixLibDir.path,
                prefixDir = prefixDir.path,
                workingDir = workingDir.path,
                command = command,
                env = config.envMap(),
                desktopWidth = desktop.width,
                desktopHeight = desktop.height,
                scale = config.scale,
                dpi = config.dpi,
                logFile = paths.log(id).path,
                recordFile = paths.currentSession.path,
                createdAt = now,
            )
        return Plan(spec, config, warnings)
    }

    /** Variables the engine owns; settings and user lines cannot override them. */
    private fun engineEnv(runtime: InstalledRuntime, prefixDir: File): Map<String, String> = linkedMapOf(
        "WINEPREFIX" to prefixDir.path,
        "LD_LIBRARY_PATH" to runtime.libraryPath.joinToString(":") { it.path },
        "HOME" to paths.home.path,
        // Proton's user name, so save paths under drive_c/users match Proton's.
        "USER" to "steamuser",
        "TMPDIR" to paths.tmp.path,
    )

    private fun sessionId(now: Long, title: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val slug = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).trimEnd('-')
        return if (slug.isEmpty()) stamp else "$stamp-$slug"
    }
}
