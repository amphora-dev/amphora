package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container

/**
 * 容器配置落到磁盘时的「做过没」标记。
 *
 * 只有两类事：
 * 1. **小事**：便宜，每次直接对照真实文件/注册表（声音、盘符、手柄注册表）。不走这里。
 * 2. **大事**：贵（解压 DLL、装 Box64、改一大批服务）。这里记下「上次按哪个想要值做过了」；
 *    想要值变了、或前缀整份重建了，才重做。
 *
 * 顶层字段（如 [Container.getDXWrapper]）是「想要什么」；
 * 本对象读写的是「已经按什么做过」——刻意用不同的存储键，避免和想要值同名搞混。
 */
object AppliedMarks {

    // --- 存储键（新）与旧键（只读兼容） ---

    private const val APP = "appliedAppVersion"
    private const val APP_LEGACY = "appVersion"
    private const val IMG = "appliedImgVersion"
    private const val IMG_LEGACY = "imgVersion"
    private const val DXWRAPPER = "appliedDxwrapper"
    private const val DXWRAPPER_LEGACY = "dxwrapper"
    private const val WINCOMPONENTS = "appliedWincomponents"
    private const val WINCOMPONENTS_LEGACY = "wincomponents"
    private const val SERVICES = "appliedServices"
    private const val SERVICES_LEGACY = "startupSelection"
    private const val BOX64 = "appliedBox64"
    private const val BOX64_LEGACY = "box64Version"
    private const val PREFIX_NEEDS_UPDATE = "wineprefixNeedsUpdate"
    private const val PREFIX_ARCH = "wineprefixArch"

    /** 前缀整份换新后要清掉的标记（Box64 不在前缀里，不在此列）。 */
    val prefixOwnedKeys: List<String> =
        listOf(
            APP, APP_LEGACY,
            IMG, IMG_LEGACY,
            DXWRAPPER, DXWRAPPER_LEGACY,
            WINCOMPONENTS, WINCOMPONENTS_LEGACY,
            SERVICES, SERVICES_LEGACY,
            PREFIX_NEEDS_UPDATE,
            // 历史残留，旧容器可能还有
            "desktopTheme",
            "mono_installed",
            "mono_version",
            "graphicsDriver",
        )

    // --- app / imagefs 通用补丁 ---

    fun appVersion(container: Container): String = read(container, APP, APP_LEGACY)

    fun imgVersion(container: Container): String = read(container, IMG, IMG_LEGACY)

    fun needsAppImagePatch(container: Container, appVersion: String, imgVersion: String): Boolean =
        appVersion(container) != appVersion || imgVersion(container) != imgVersion

    fun markAppImagePatched(container: Container, appVersion: String, imgVersion: String) {
        write(container, APP, APP_LEGACY, appVersion)
        write(container, IMG, IMG_LEGACY, imgVersion)
    }

    // --- DXVK / VKD3D / ddraw ---

    fun dxwrapperKey(container: Container): String = read(container, DXWRAPPER, DXWRAPPER_LEGACY)

    fun needsDxwrapper(container: Container, gateKey: String): Boolean =
        dxwrapperKey(container) != gateKey

    fun markDxwrapper(container: Container, gateKey: String) {
        write(container, DXWRAPPER, DXWRAPPER_LEGACY, gateKey)
    }

    fun invalidateDxwrapper(container: Container) {
        clear(container, DXWRAPPER, DXWRAPPER_LEGACY)
    }

    // --- Windows 组件 ---

    fun wincomponents(container: Container): String =
        read(container, WINCOMPONENTS, WINCOMPONENTS_LEGACY)

    fun needsWincomponents(container: Container, desired: String): Boolean =
        wincomponents(container) != desired

    fun markWincomponents(container: Container, desired: String) {
        write(container, WINCOMPONENTS, WINCOMPONENTS_LEGACY, desired)
    }

    // --- 服务启动策略（原 startupSelection） ---

    fun services(container: Container): String = read(container, SERVICES, SERVICES_LEGACY)

    fun needsServices(container: Container, desired: String): Boolean =
        services(container) != desired

    fun markServices(container: Container, desired: String) {
        write(container, SERVICES, SERVICES_LEGACY, desired)
    }

    // --- Box64（装在 imagefs，不属于前缀） ---

    fun box64(container: Container): String = read(container, BOX64, BOX64_LEGACY)

    fun needsBox64(container: Container, desired: String): Boolean =
        box64(container) != desired

    fun markBox64(container: Container, desired: String) {
        write(container, BOX64, BOX64_LEGACY, desired)
    }

    fun invalidateBox64(container: Container) {
        clear(container, BOX64, BOX64_LEGACY)
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

    /** 前缀重建成功后调用：大事标记全部作废，下次启动重做。 */
    @JvmStatic
    fun clearOwnedByPrefix(container: Container) {
        for (key in prefixOwnedKeys) {
            container.putExtra(key, null)
        }
    }

    // --- 内部 ---

    private fun read(container: Container, primary: String, legacy: String): String {
        val v = container.getExtra(primary)
        if (v.isNotEmpty()) return v
        return container.getExtra(legacy)
    }

    private fun write(container: Container, primary: String, legacy: String, value: String) {
        container.putExtra(primary, value)
        container.putExtra(legacy, null)
    }

    private fun clear(container: Container, primary: String, legacy: String) {
        container.putExtra(primary, null)
        container.putExtra(legacy, null)
    }
}
