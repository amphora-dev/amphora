package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container

/**
 * 容器配置落到磁盘时的「做过没」标记。
 *
 * 只有两类事：
 * 1. **小事**：每次直接对照真实文件/注册表。不走这里。
 * 2. **大事**：贵；这里记下「上次按哪个想要值做过了」。
 *
 * 顶层字段 = 想要什么；这里的键 = 已经做过什么。键名全部带 `applied` 前缀。
 * 旧版散落键一律不读，只在 scrub / 前缀重建时删掉。
 */
object AppliedMarks {

    private const val APP = "appliedAppVersion"
    private const val IMG = "appliedImgVersion"
    private const val DXWRAPPER = "appliedDxwrapper"
    private const val WINCOMPONENTS = "appliedWincomponents"
    private const val SERVICES = "appliedServices"
    private const val BOX64 = "appliedBox64"
    private const val PREFIX_NEEDS_UPDATE = "wineprefixNeedsUpdate"
    private const val PREFIX_ARCH = "wineprefixArch"

    /** 前缀重建后要清的大事标记（Box64 不在前缀里）。 */
    val prefixOwnedKeys: List<String> =
        listOf(APP, IMG, DXWRAPPER, WINCOMPONENTS, SERVICES, PREFIX_NEEDS_UPDATE)

    /** 旧版散落键：不参与逻辑，只删除。 */
    val obsoleteExtraKeys: List<String> =
        listOf(
            "appVersion",
            "imgVersion",
            "dxwrapper",
            "wincomponents",
            "startupSelection",
            "box64Version",
            "audioDriver",
            "desktopTheme",
            "mono_installed",
            "mono_version",
            "graphicsDriver",
        )

    // --- app / imagefs ---

    fun appVersion(container: Container): String = container.getExtra(APP)

    fun imgVersion(container: Container): String = container.getExtra(IMG)

    fun needsAppImagePatch(container: Container, appVersion: String, imgVersion: String): Boolean =
        appVersion(container) != appVersion || imgVersion(container) != imgVersion

    fun markAppImagePatched(container: Container, appVersion: String, imgVersion: String) {
        container.putExtra(APP, appVersion)
        container.putExtra(IMG, imgVersion)
    }

    // --- DXVK / VKD3D / ddraw ---

    fun dxwrapperKey(container: Container): String = container.getExtra(DXWRAPPER)

    fun needsDxwrapper(container: Container, gateKey: String): Boolean =
        dxwrapperKey(container) != gateKey

    fun markDxwrapper(container: Container, gateKey: String) {
        container.putExtra(DXWRAPPER, gateKey)
    }

    fun invalidateDxwrapper(container: Container) {
        container.putExtra(DXWRAPPER, null)
    }

    // --- Windows 组件 ---

    fun wincomponents(container: Container): String = container.getExtra(WINCOMPONENTS)

    fun needsWincomponents(container: Container, desired: String): Boolean =
        wincomponents(container) != desired

    fun markWincomponents(container: Container, desired: String) {
        container.putExtra(WINCOMPONENTS, desired)
    }

    // --- 服务启动策略 ---

    fun services(container: Container): String = container.getExtra(SERVICES)

    fun needsServices(container: Container, desired: String): Boolean =
        services(container) != desired

    fun markServices(container: Container, desired: String) {
        container.putExtra(SERVICES, desired)
    }

    // --- Box64（imagefs，不属于前缀） ---

    fun box64(container: Container): String = container.getExtra(BOX64)

    fun needsBox64(container: Container, desired: String): Boolean =
        box64(container) != desired

    fun markBox64(container: Container, desired: String) {
        container.putExtra(BOX64, desired)
    }

    fun invalidateBox64(container: Container) {
        container.putExtra(BOX64, null)
    }

    // --- 前缀本身 ---

    fun wineprefixArch(container: Container): String = container.getExtra(PREFIX_ARCH)

    fun markWineprefixArch(container: Container, arch: String) {
        container.putExtra(PREFIX_ARCH, arch)
    }

    fun prefixNeedsUpdate(container: Container): Boolean =
        "t".equals(container.getExtra(PREFIX_NEEDS_UPDATE), ignoreCase = true)

    fun markPrefixNeedsUpdate(container: Container) {
        container.putExtra(PREFIX_NEEDS_UPDATE, "t")
    }

    fun clearPrefixNeedsUpdate(container: Container) {
        container.putExtra(PREFIX_NEEDS_UPDATE, null)
    }

    /** 删掉旧版散落键；有删掉的返回 true（调用方应 saveData）。 */
    @JvmStatic
    fun scrubObsoleteExtras(container: Container): Boolean {
        var changed = false
        for (key in obsoleteExtraKeys) {
            if (container.hasExtra(key)) {
                container.putExtra(key, null)
                changed = true
            }
        }
        return changed
    }

    /** 前缀重建成功后：大事标记作废，并清掉旧垃圾键。 */
    @JvmStatic
    fun clearOwnedByPrefix(container: Container) {
        for (key in prefixOwnedKeys) {
            container.putExtra(key, null)
        }
        for (key in obsoleteExtraKeys) {
            container.putExtra(key, null)
        }
    }
}
