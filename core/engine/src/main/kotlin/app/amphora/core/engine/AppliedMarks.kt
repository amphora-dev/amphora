package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container

/**
 * 配置应用：一种方式。
 *
 * - **想要什么**：只在容器顶层字段（唯一真相）。
 * - **装过什么**：本对象的 `applied*` 标记。
 * - 想要的 ≠ 装过的 → 去做 → 成功后更新标记。
 * - 前缀重建 → [clearOwnedByPrefix] 清空属于前缀的标记。
 *
 * 声音、服务、DLL、组件全部同一套，没有例外路径。
 */
object AppliedMarks {

    private const val APP = "appliedAppVersion"
    private const val IMG = "appliedImgVersion"
    private const val DXWRAPPER = "appliedDxwrapper"
    private const val WINCOMPONENTS = "appliedWincomponents"
    private const val SERVICES = "appliedServices"
    private const val AUDIO = "appliedAudio"
    private const val INPUT = "appliedInput"
    private const val DRIVES = "appliedDrives"
    private const val WINEBUS = "appliedWinebus"
    private const val BOX64 = "appliedBox64"
    private const val WINE_CONTENT = "appliedWineContent"
    private const val FEX = "appliedFex"
    private const val FEX_MODE = "appliedFexMode"
    private const val PREFIX_NEEDS_UPDATE = "wineprefixNeedsUpdate"
    private const val PREFIX_ARCH = "wineprefixArch"

    /** 前缀里的配置；重建后必须清空（Box64/FEX 装在 imagefs/别路径的另算）。 */
    val prefixOwnedKeys: List<String> =
        listOf(
            APP, IMG, DXWRAPPER, WINCOMPONENTS, SERVICES,
            AUDIO, INPUT, DRIVES, WINEBUS,
            PREFIX_NEEDS_UPDATE,
        )

    /** 旧散落键，启动时删掉。 */
    val obsoleteExtraKeys: List<String> =
        listOf(
            "appVersion", "imgVersion", "dxwrapper", "wincomponents",
            "startupSelection", "box64Version", "audioDriver",
            "desktopTheme", "mono_installed", "mono_version", "graphicsDriver",
            "fexcoreVersion", "fexcoreMode",
        )

    // --- app / imagefs ---

    fun needsAppImagePatch(container: Container, appVersion: String, imgVersion: String): Boolean =
        container.getExtra(APP) != appVersion || container.getExtra(IMG) != imgVersion

    fun markAppImagePatched(container: Container, appVersion: String, imgVersion: String) {
        container.putExtra(APP, appVersion)
        container.putExtra(IMG, imgVersion)
    }

    // --- DXVK / VKD3D / ddraw ---

    fun dxwrapperKey(container: Container): String = container.getExtra(DXWRAPPER)

    fun needsDxwrapper(container: Container, gateKey: String): Boolean = dxwrapperKey(container) != gateKey

    fun markDxwrapper(container: Container, gateKey: String) {
        container.putExtra(DXWRAPPER, gateKey)
    }

    fun invalidateDxwrapper(container: Container) {
        container.putExtra(DXWRAPPER, null)
    }

    // --- Windows 组件 ---

    fun wincomponents(container: Container): String = container.getExtra(WINCOMPONENTS)

    fun needsWincomponents(container: Container, desired: String): Boolean = wincomponents(container) != desired

    fun markWincomponents(container: Container, desired: String) {
        container.putExtra(WINCOMPONENTS, desired)
    }

    // --- 服务 ---

    fun needsServices(container: Container, desired: String): Boolean = container.getExtra(SERVICES) != desired

    fun markServices(container: Container, desired: String) {
        container.putExtra(SERVICES, desired)
    }

    // --- 声音 ---

    fun needsAudio(container: Container, desired: String): Boolean = container.getExtra(AUDIO) != desired

    fun markAudio(container: Container, desired: String) {
        container.putExtra(AUDIO, desired)
    }

    // --- 输入（手柄注册表） ---

    fun inputKey(inputType: Int, exclusiveXInput: Boolean): String = "$inputType|${if (exclusiveXInput) "1" else "0"}"

    fun needsInput(container: Container, key: String): Boolean = container.getExtra(INPUT) != key

    fun markInput(container: Container, key: String) {
        container.putExtra(INPUT, key)
    }

    // --- 盘符 ---

    fun needsDrives(container: Container, desired: String): Boolean = container.getExtra(DRIVES) != desired

    fun markDrives(container: Container, desired: String) {
        container.putExtra(DRIVES, desired)
    }

    // --- winebus ---

    private const val WINEBUS_VALUE = "1"

    fun needsWinebus(container: Container): Boolean = container.getExtra(WINEBUS) != WINEBUS_VALUE

    fun markWinebus(container: Container) {
        container.putExtra(WINEBUS, WINEBUS_VALUE)
    }

    // --- Box64 ---

    fun box64(container: Container): String = container.getExtra(BOX64)

    fun needsBox64(container: Container, desired: String): Boolean = box64(container) != desired

    fun markBox64(container: Container, desired: String) {
        container.putExtra(BOX64, desired)
    }

    fun invalidateBox64(container: Container) {
        container.putExtra(BOX64, null)
    }

    // --- Proton/Wine package used to materialize the prefix ---

    fun wineContent(container: Container): String = container.getExtra(WINE_CONTENT)

    fun needsWineContent(container: Container, desired: String): Boolean =
        wineContent(container) != desired

    fun markWineContent(container: Container, desired: String) {
        container.putExtra(WINE_CONTENT, desired)
    }

    // --- FEX ---

    fun fex(container: Container): String = container.getExtra(FEX)

    fun fexMode(container: Container): String = container.getExtra(FEX_MODE)

    fun needsFex(container: Container, version: String, mode: String): Boolean =
        fex(container) != version || fexMode(container) != mode

    fun markFex(container: Container, version: String, mode: String) {
        container.putExtra(FEX, version)
        container.putExtra(FEX_MODE, mode)
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

    /** 前缀重建后：属于前缀的装过标记全部作废。 */
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
