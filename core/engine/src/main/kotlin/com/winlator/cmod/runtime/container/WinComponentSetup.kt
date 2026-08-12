package com.winlator.cmod.runtime.container

import android.content.Context
import android.util.Log
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.WinComponentCache
import com.winlator.cmod.runtime.content.SharedDllLinker
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.util.KeyValueSet
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * Applies native/builtin Windows component DLLs for a container's
 * `wincomponents` setting. Native archives are extracted once into a shared
 * cache and bound with [SharedDllLinker]; builtin restores Proton's matching
 * DLLs the same way. Wine DllOverrides and COM registration stay here.
 */
object WinComponentSetup {
    private const val TAG = "WinComponentSetup"

    @JvmStatic
    fun applyWinComponents(
        context: Context,
        imageFs: ImageFs,
        wineInfo: WineInfo,
        container: Container,
        wincomponents: String,
        previousWincomponents: String,
        firstTimeBoot: Boolean,
    ) {
        Log.d(TAG, "Applying WinComponents")

        val rootDir = imageFs.rootDir
        val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")

        try {
            val wincomponentsStr = FileUtils.readString(context, "wincomponents/wincomponents.json")
            val wincomponentsJson = JSONObject(wincomponentsStr ?: "{}")
            val dlls = ArrayList<String>()
            val oldValues = HashMap<String, String>()
            for (old in KeyValueSet(previousWincomponents.ifEmpty { Container.FALLBACK_WINCOMPONENTS })) {
                oldValues[old[0]] = old[1]
            }

            for (wincomponent in KeyValueSet(wincomponents)) {
                val identifier = wincomponent[0]
                val useNative = wincomponent[1] == "1"
                val oldValue = oldValues[identifier]
                if (wincomponent[1] == oldValue && !firstTimeBoot) continue

                if (useNative) {
                    linkNativeWinComponent(context, identifier, windowsDir)
                } else {
                    dlls.addAll(wineDllsForComponentRestore(wincomponentsJson, identifier))
                }

                Log.d(TAG, "Setting wincomponent $identifier to $useNative")
                WineUtils.overrideWinComponentDlls(context, container, identifier, useNative)
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, context)
            }

            if (dlls.isNotEmpty()) restoreWineBuiltinDllFiles(imageFs, wineInfo, *dlls.toTypedArray())
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse WinComponents metadata", e)
        }
    }

    @JvmStatic
    fun restoreWineBuiltinDllFiles(imageFs: ImageFs, wineInfo: WineInfo, vararg dlls: String) {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

        // Pick the Wine DLL directory that matches system32 for this prefix:
        // x86_64 Wine uses x86_64-windows, ARM64EC Wine uses aarch64-windows.
        val wineSystem32Dlls = wineSystem32DllDir(imageFs, wineInfo)
        val wineSyswow64Dlls = File(imageFs.winePath + "/lib/wine/i386-windows")
        val contentsRoot = File(requireNotNull(imageFs.rootDir.parentFile), "contents")

        for (dll in dlls) {
            restoreOneWineDll(contentsRoot, File(wineSystem32Dlls, dll), File(windowsDir, "system32/$dll"))
            restoreOneWineDll(contentsRoot, File(wineSyswow64Dlls, dll), File(windowsDir, "syswow64/$dll"))
        }
    }

    private fun linkNativeWinComponent(context: Context, identifier: String, windowsDir: File) {
        val archive =
            File(
                RuntimeAssetProvisioner.runtimeAssetsDir(context),
                "wincomponents/$identifier.tzst",
            )
        if (!archive.isFile) {
            // Some toggles are registry-only. dinput8 intentionally has no
            // package: "native" means prefer a game/prefix PE, then Wine builtin.
            Log.d(TAG, "Native WinComponent '$identifier' has no archive; applying override only")
            return
        }
        WinComponentCache.linkComponent(context, identifier, windowsDir)
    }

    private fun wineDllsForComponentRestore(wincomponentsJson: JSONObject, identifier: String): List<String> {
        val dlnames = wincomponentsJson.getJSONArray(identifier)
        val dlls = ArrayList<String>(dlnames.length())
        for (i in 0 until dlnames.length()) {
            val dlname = dlnames.getString(i)
            dlls.add(if (dlname.endsWith(".exe")) dlname else "$dlname.dll")
        }
        return dlls
    }

    private fun wineSystem32DllDir(imageFs: ImageFs, wineInfo: WineInfo): File =
        // ARM64EC Wine keeps ARM64/ARM64EC DLLs in aarch64-windows; regular
        // x86_64 Wine keeps x64 DLLs in x86_64-windows.
        if (wineInfo.isArm64EC) {
            File(imageFs.winePath + "/lib/wine/aarch64-windows")
        } else {
            File(imageFs.winePath + "/lib/wine/x86_64-windows")
        }

    private fun restoreOneWineDll(contentsRoot: File, srcFile: File, dstFile: File) {
        if (srcFile.exists()) {
            if (!SharedDllLinker.link(contentsRoot, srcFile, dstFile)) {
                Log.w(TAG, "restoreWineBuiltinDllFiles: link failed $srcFile -> $dstFile")
            }
            return
        }
        if (dstFile.exists()) {
            if (dstFile.delete()) {
                Log.w(TAG, "restoreWineBuiltinDllFiles: no source for $srcFile, deleted stale $dstFile")
            } else {
                Log.e(TAG, "restoreWineBuiltinDllFiles: no source for $srcFile and failed to delete stale $dstFile")
            }
        }
    }
}
