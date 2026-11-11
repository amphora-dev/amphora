package app.amphora.sdk.settings

import app.amphora.sdk.engine.Recipe
import app.amphora.sdk.library.Game
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Read and change settings. Everything the settings screen shows comes from
 * [global] or [game]; launching uses the same resolution, so what the screen
 * says is what the game gets.
 */
public class SettingsRepository internal constructor(private val store: SettingsStore, private val recipe: Recipe) {
    public val specs: List<SettingSpec> get() = SettingsCatalog.all

    /** Global context: recipe default < user global. */
    public fun global(): Flow<ResolvedSettings> =
        store.global().map { SettingsResolver.resolve(specs, recipe.components, it, null) }

    /** One game: all four layers. */
    public fun game(game: Game): Flow<ResolvedSettings> =
        combine(store.global(), store.game(game.id)) { global, values ->
            SettingsResolver.resolve(specs, recipe.components, global, layer(game, values))
        }

    /** One-shot resolution, for launching. */
    public suspend fun resolve(game: Game?): ResolvedSettings {
        val global = store.globalNow()
        val layer = game?.let { layer(it, store.gameNow(it.id)) }
        return SettingsResolver.resolve(specs, recipe.components, global, layer)
    }

    /**
     * Sets (or with null, clears) the user's global value. Returns why the
     * value was refused, or null. Choosing the default clears the entry, so
     * "modified" keeps meaning "differs from the recipe".
     */
    public suspend fun setGlobal(id: String, value: String?): String? {
        val spec = SettingsCatalog[id] ?: return "未知设置 $id"
        if (!spec.scope.global) return "「${spec.doc.title}」只能按游戏设置"
        if (value != null) spec.type.validate(value)?.let { return it }
        store.updateGlobal { values ->
            if (value == null || value == spec.default) values - id else values + (id to value)
        }
        return null
    }

    /** Per-game counterpart of [setGlobal]; choosing what the game would get anyway clears the entry. */
    public suspend fun setGame(game: Game, id: String, value: String?): String? {
        val spec = SettingsCatalog[id] ?: return "未知设置 $id"
        if (!spec.scope.game) return "「${spec.doc.title}」只能全局设置"
        if (value != null) spec.type.validate(value)?.let { return it }
        // The game's other values count: its AppID setting decides which fixes apply.
        val others = store.gameNow(game.id) - id
        val inherited =
            SettingsResolver
                .resolve(listOf(spec), recipe.components, store.globalNow(), layer(game, others))
                .value(id)
        store.updateGame(game.id) { values ->
            if (value == null || value == inherited) values - id else values + (id to value)
        }
        return null
    }

    /** Clears global values: all, or only [ids] (one setting, one group). */
    public suspend fun resetGlobal(ids: Collection<String>? = null) {
        store.updateGlobal { values -> if (ids == null) emptyMap() else values - ids.toSet() }
    }

    public suspend fun resetGame(game: Game, ids: Collection<String>? = null) {
        store.updateGame(game.id) { values -> if (ids == null) emptyMap() else values - ids.toSet() }
    }

    internal suspend fun forget(gameId: String) {
        store.deleteGame(gameId)
    }

    private fun layer(game: Game, values: Map<String, String>): GameLayer {
        val appId = effectiveAppId(values[SettingsCatalog.STEAM_APP_ID].orEmpty(), game.detectedAppId)
        val fixes =
            recipe.fixes.filter { fix ->
                val m = fix.match
                (m.exeSha256 != null && m.exeSha256.equals(game.exeSha256, ignoreCase = true)) ||
                    (m.exeName != null && m.exeName.equals(game.exeName, ignoreCase = true)) ||
                    (m.steamAppId != null && m.steamAppId == appId)
            }
        return GameLayer(game.id, values, fixes)
    }

    internal companion object {
        /** The AppID setting: empty = detected, "none" = none, digits = that. */
        fun effectiveAppId(setting: String, detected: Long?): Long? = when {
            setting.isEmpty() -> detected
            setting == "none" -> null
            else -> setting.toLongOrNull()
        }
    }
}
