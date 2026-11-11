package app.amphora.sdk.settings

/**
 * What a launch will actually use (13 §2, "生效配置"): every environment
 * variable and DLL override with the setting it came from, the desktop, the
 * arguments; plus what was refused and what has no effect yet. Built from
 * [ResolvedSettings] by the same code the launcher uses.
 */
public data class EffectiveConfig(
    val env: List<ConfigEntry>,
    /** User environment entries refused because the engine owns the name. */
    val rejectedEnv: List<ConfigEntry>,
    val dllOverrides: List<ConfigEntry>,
    val resolution: String,
    val customResolution: String,
    val scale: String,
    val dpi: Int,
    val args: List<String>,
    val steamAppId: Long?,
    /** Settings changed from the default that the current recipe cannot apply. */
    val inactive: List<ResolvedSetting>,
) {
    public fun envMap(): Map<String, String> = env.associate { it.name to it.value }

    /** Copyable text for bug reports. */
    public fun describe(): String = buildString {
        appendLine("# env")
        env.forEach { appendLine("${it.name}=${it.value}    # ${it.origin}") }
        if (rejectedEnv.isNotEmpty()) {
            appendLine("# rejected (engine-owned)")
            rejectedEnv.forEach { appendLine("# ${it.name}=${it.value}") }
        }
        appendLine(
            "# desktop $resolution${if (resolution == "custom") " ($customResolution)" else ""} scale=$scale dpi=$dpi",
        )
        if (args.isNotEmpty()) appendLine("# args ${args.joinToString(" ")}")
        if (inactive.isNotEmpty()) appendLine("# inactive: ${inactive.joinToString { it.spec.id }}")
    }

    internal companion object {
        /**
         * Applies [resolved] on top of [engineEnv]. Engine variables and every
         * variable a setting generates are protected: user environment lines
         * cannot override them (13 §6.2).
         */
        fun build(resolved: ResolvedSettings, engineEnv: Map<String, String>, detectedAppId: Long?): EffectiveConfig {
            val builder = ConfigBuilder()
            val inactive = ArrayList<ResolvedSetting>()
            // Fields that parameterize another setting ("custom" values) apply first.
            val ordered = resolved.items.sortedBy { if (it.spec.visibleWhen != null) 0 else 1 }
            for (item in ordered) {
                if (item.availability != Availability.Available) {
                    if (item.modified) inactive += item
                    continue
                }
                builder.origin = item.spec.id
                item.spec.effect(builder, item.value)
            }

            builder.origin = SettingsCatalog.STEAM_APP_ID
            val appId = SettingsRepository.effectiveAppId(builder.steamAppId, detectedAppId)
            if (appId != null) {
                builder.env("SteamGameId", appId.toString())
                builder.env("SteamAppId", appId.toString())
            }
            if (builder.dlls.isNotEmpty()) {
                builder.origin = SettingsCatalog.DLL_OVERRIDES
                builder.env("WINEDLLOVERRIDES", builder.dlls.values.joinToString(";") { "${it.name}=${it.value}" })
            }

            val env = LinkedHashMap<String, ConfigEntry>()
            engineEnv.forEach { (name, value) -> env[name] = ConfigEntry(name, value, ConfigEntry.ENGINE) }
            // The engine's variables stay: a setting cannot move the prefix or the libraries.
            builder.env.forEach { (name, entry) -> env.putIfAbsent(name, entry) }
            val protected = env.keys.toSet()
            val rejected = ArrayList<ConfigEntry>()
            for ((name, value) in builder.userEnv) {
                val entry = ConfigEntry(name, value, SettingsCatalog.ENV)
                if (name in protected) rejected += entry else env[name] = entry
            }
            return EffectiveConfig(
                env = env.values.toList(),
                rejectedEnv = rejected,
                dllOverrides = builder.dlls.values.toList(),
                resolution = builder.resolution,
                customResolution = builder.customResolution,
                scale = builder.scale,
                dpi = builder.dpi,
                args = builder.args.toList(),
                steamAppId = appId,
                inactive = inactive,
            )
        }
    }
}
