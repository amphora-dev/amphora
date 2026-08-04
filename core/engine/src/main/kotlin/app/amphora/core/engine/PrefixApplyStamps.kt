package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container

/**
 * 重建 wine 前缀后，要清掉的「做过没」标记。
 *
 * 装 DLL、改一堆服务这类重活，用这些标记避免每次启动都重做。
 * 前缀整份换新后，旧标记作废，下次启动必须重做。
 *
 * 声音不在这里：每次直接看注册表，不记「做过没」。
 */
object PrefixApplyStamps {

    val prefixRepairClearKeys: List<String> =
        listOf(
            "wineprefixNeedsUpdate",
            "appVersion",
            "imgVersion",
            "dxwrapper",
            "wincomponents",
            "desktopTheme",
            "startupSelection",
            "mono_installed",
            "mono_version",
        )

    fun clearForPrefixRepair(container: Container) {
        for (key in prefixRepairClearKeys) {
            container.putExtra(key, null)
        }
    }
}
