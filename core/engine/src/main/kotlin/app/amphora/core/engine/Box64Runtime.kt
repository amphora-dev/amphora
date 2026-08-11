package app.amphora.core.engine

import android.util.Log
import app.amphora.core.content.InstalledContentPin
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import java.io.File

/**
 * 把想要的 Box64 版本装进 imagefs（`usr/bin/box64`）。
 *
 * 启动准备和 Guest 启动器共用这一处，避免两套逻辑各写一遍。
 */
object Box64Runtime {
    private const val TAG = "Box64Runtime"

    /**
     * @return 是否改过容器数据（调用方决定是否 [Container.saveData]）
     */
    @JvmStatic
    fun ensureApplied(container: Container, imageFs: ImageFs, contentsManager: ContentsManager): Boolean {
        val rootDir = imageFs.getRootDir()
        val box64File = File(rootDir, "usr/bin/box64")
        val missing = !box64File.exists()

        var version = container.getBox64Version() ?: ""
        if (version.isEmpty()) {
            version =
                ContentPinResolver.pickNewestInstalled(
                    contentsManager,
                    ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                )?.let { ContentPinResolver.versionIdentity(ContentPinResolver.entryName(it)) }
                    ?: ""
            if (version.isNotEmpty()) {
                container.setBox64Version(version)
            }
        }

        if (version.isEmpty()) {
            Log.w(TAG, "未选择 Box64 版本，跳过安装")
            return false
        }

        var profile =
            ContentPinResolver.resolveInstalledProfile(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                version,
            )
        if (profile != null) {
            val resolved = ContentPinResolver.versionIdentity(ContentPinResolver.entryName(profile))
            if (resolved != version) {
                Log.w(TAG, "想要的 Box64='$version' 未安装，改用已装的 '$resolved'")
                version = resolved
                container.setBox64Version(version)
            }
        }
        if (profile == null) {
            Log.w(TAG, "Box64 内容未安装: version=$version")
            return false
        }
        val installDir =
            File(
                File(requireNotNull(rootDir.parentFile), "contents/${profile.type}"),
                "${profile.verName}-${profile.verCode}",
            )
        val contentState = "$version|sha=${InstalledContentPin.read(installDir) ?: "missing"}"
        if (!missing && !AppliedMarks.needsBox64(container, contentState)) {
            ensureExecutable(box64File)
            return false
        }

        Log.i(TAG, "安装 Box64: version=$version")
        // Persist invalidation before applyContent starts replacing targets. If
        // it copies usr/bin/box64 and then fails on a later file, the next launch
        // must retry instead of trusting the previous successful state.
        AppliedMarks.invalidateBox64(container)
        container.saveData()
        if (!contentsManager.applyContent(profile)) {
            throw IllegalStateException(
                "Box64 content apply failed: ${ContentPinResolver.entryName(profile)}",
            )
        }
        if (!box64File.isFile) {
            throw IllegalStateException("Box64 content apply completed without usr/bin/box64")
        }
        AppliedMarks.markBox64(container, contentState)
        ensureExecutable(box64File)
        return true
    }

    private fun ensureExecutable(box64File: File) {
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0b111_101_101) // 0755
        }
    }
}
