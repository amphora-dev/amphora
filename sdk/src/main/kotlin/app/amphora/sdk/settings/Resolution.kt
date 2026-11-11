package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.GameFix

/** Which layer a value came from (13 §2, D15), lowest first. */
public sealed interface ValueSource {
    public val label: String

    public data object Default : ValueSource {
        override val label: String get() = "配方默认"
    }

    public data object Global : ValueSource {
        override val label: String get() = "你的全局设置"
    }

    public data class Fix(val fix: GameFix) : ValueSource {
        override val label: String get() = "本游戏内置修正"
    }

    public data object Game : ValueSource {
        override val label: String get() = "你为本游戏设置"
    }
}

/** Whether a setting can do anything with the installed recipe. */
public sealed interface Availability {
    public data object Available : Availability

    /** The recipe lacks a component; the value is kept but has no effect yet. */
    public data class NeedsComponent(val component: Component) : Availability

    /** The SDK side is not built yet. */
    public data class Planned(val note: String) : Availability
}

/** A setting with its final value and how it got there. */
public data class ResolvedSetting(
    val spec: SettingSpec,
    val value: String,
    val source: ValueSource,
    /** The user's raw values, when set (and valid for the scope). */
    val globalValue: String?,
    val gameValue: String?,
    /** A maintained fix that applies to this game, even if the user's game value wins. */
    val fix: GameFix?,
    /** Stored values that failed validation and were ignored, by layer. */
    val invalid: List<ValueSource>,
    val availability: Availability,
) {
    /** Differs from the recipe default because of any layer. */
    val modified: Boolean get() = source != ValueSource.Default

    /**
     * True when our fix overrides the user's global value: the UI must say so,
     * with the fix's reason (D15).
     */
    val fixOverridesGlobal: Boolean get() = source is ValueSource.Fix && globalValue != null && globalValue != value
}

/** Every setting resolved for one context: global, or one game. */
public data class ResolvedSettings(
    val items: List<ResolvedSetting>,
    /** Null for the global context. */
    val gameId: String?,
) {
    private val byId = items.associateBy { it.spec.id }

    public operator fun get(id: String): ResolvedSetting = byId[id] ?: error("unknown setting $id")

    public fun value(id: String): String = get(id).value

    /** Whether [spec] should be shown, given visibleWhen conditions. */
    public fun visible(spec: SettingSpec): Boolean = spec.visibleWhen?.let { (id, v) -> byId[id]?.value == v } ?: true
}

/**
 * D15: recipe default < user global < our per-game fix < user per-game.
 * Values outside a setting's scope are ignored, as are values that fail
 * validation (reported, never applied). Pure, so it is tested directly.
 */
internal object SettingsResolver {
    fun resolve(
        specs: List<SettingSpec>,
        components: Set<Component>,
        global: Map<String, String>,
        game: GameLayer?,
    ): ResolvedSettings {
        val items =
            specs.map { spec ->
                val invalid = ArrayList<ValueSource>()

                fun accept(raw: String?, source: ValueSource): String? {
                    if (raw == null) return null
                    if (spec.type.validate(raw) != null) {
                        invalid += source
                        return null
                    }
                    return raw
                }

                val g = if (spec.scope.global) accept(global[spec.id], ValueSource.Global) else null
                val fix = if (game != null && spec.scope.game) game.fixes.lastOrNull { spec.id in it.values } else null
                val fixValue = fix?.let { accept(it.values[spec.id], ValueSource.Fix(it)) }
                val gv = if (game != null && spec.scope.game) accept(game.values[spec.id], ValueSource.Game) else null

                val (value, source) =
                    when {
                        gv != null -> gv to ValueSource.Game
                        fix != null && fixValue != null -> fixValue to ValueSource.Fix(fix)
                        g != null -> g to ValueSource.Global
                        else -> spec.default to ValueSource.Default
                    }
                ResolvedSetting(
                    spec = spec,
                    value = value,
                    source = source,
                    globalValue = g,
                    gameValue = gv,
                    fix = fix,
                    invalid = invalid,
                    availability = availability(spec, components),
                )
            }
        return ResolvedSettings(items, game?.gameId)
    }

    fun availability(spec: SettingSpec, components: Set<Component>): Availability = when {
        spec.requires != null && spec.requires !in components -> Availability.NeedsComponent(spec.requires)
        spec.planned != null -> Availability.Planned(spec.planned)
        else -> Availability.Available
    }
}

/** The per-game layers: the user's values and the fixes that match the game. */
internal data class GameLayer(val gameId: String, val values: Map<String, String>, val fixes: List<GameFix>)
