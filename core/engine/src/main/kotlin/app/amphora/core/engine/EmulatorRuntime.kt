package app.amphora.core.engine

import android.util.Log
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File

/**
 * arm64ec 路径下的 WowBox64 / FEX 安装。与 [Box64Runtime] 同一套：
 * 容器想要什么 → AppliedMarks 装过什么 → 需要才装。
 */
object EmulatorRuntime {
    private const val TAG = "EmulatorRuntime"

    data class Result(val changed: Boolean, val fexUnixLibsActive: Boolean)

    @JvmStatic
    fun ensureApplied(
        container: Container,
        imageFs: ImageFs,
        contentsManager: ContentsManager,
    ): Result {
        val rootDir = imageFs.getRootDir()
        val system32 = File(rootDir, "home/xuser/.wine/drive_c/windows/system32")
        var changed = false

        var emulator = container.getEmulator() ?: ""
        var emulator64 = container.getEmulator64() ?: ""
        var wowbox64Version = container.getBox64Version() ?: ""
        var fexcoreVersion = container.getFEXCoreVersion() ?: ""
        val unixLibsPref = container.isUseUnixLibs()

        val usesWowbox64 = emulator.equals("wowbox64", ignoreCase = true)
        val usesFexcore =
            emulator.equals("fexcore", ignoreCase = true) ||
                emulator64.equals("fexcore", ignoreCase = true) ||
                !usesWowbox64

        val fexUnixLibsActive =
            usesFexcore && unixLibsPref && contentsManager.fexcoreVersionHasUnixLibs(fexcoreVersion)

        val fexMissing =
            !File(system32, "libwow64fex.dll").exists() ||
                !File(system32, "libarm64ecfex.dll").exists()
        val wowMissing = !File(system32, "wowbox64.dll").exists()

        if (usesWowbox64 &&
            (wowMissing || AppliedMarks.needsBox64(container, wowbox64Version))
        ) {
            if (wowbox64Version.isEmpty()) {
                Log.w(TAG, "未选择 WowBox64 版本，跳过")
            } else {
                val profile = contentsManager.getProfileByEntryName("wowbox64-$wowbox64Version")
                if (profile != null) {
                    Log.i(TAG, "安装 WowBox64: $wowbox64Version")
                    contentsManager.applyContent(profile)
                } else {
                    Log.w(TAG, "WowBox64 未安装: $wowbox64Version")
                }
            }
            AppliedMarks.markBox64(container, wowbox64Version)
            changed = true
        }

        if (usesFexcore) {
            val wantMode = if (fexUnixLibsActive) "unixlibs" else "dll"
            val needs =
                AppliedMarks.needsFex(container, fexcoreVersion, wantMode) ||
                    (!fexUnixLibsActive && fexMissing)
            val profile =
                if (fexcoreVersion.isEmpty()) {
                    null
                } else {
                    contentsManager.getProfileByEntryName("fexcore-$fexcoreVersion")
                }
            if (needs) {
                if (fexcoreVersion.isEmpty()) {
                    Log.w(TAG, "未选择 FEXCore 版本，跳过")
                } else if (profile != null) {
                    Log.i(TAG, "安装 FEXCore: $fexcoreVersion mode=$wantMode")
                    contentsManager.applyContent(profile)
                    if (!fexUnixLibsActive) contentsManager.removeAppliedUnixLibs(profile)
                } else {
                    Log.w(TAG, "FEXCore 未安装: $fexcoreVersion")
                }
                AppliedMarks.markFex(container, fexcoreVersion, wantMode)
                changed = true
            }
            if (profile != null) {
                val wineUnixDir = File(imageFs.getWinePath(), "lib/wine/aarch64-unix")
                if (fexUnixLibsActive) {
                    contentsManager.copyUnixLibsToDir(profile, wineUnixDir)
                } else {
                    contentsManager.deleteUnixLibsFromDir(profile, wineUnixDir)
                }
            }
        }

        return Result(changed = changed, fexUnixLibsActive = fexUnixLibsActive)
    }
}
