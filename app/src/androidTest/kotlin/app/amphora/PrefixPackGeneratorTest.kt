package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentSource
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import app.amphora.core.engine.Box64Runtime
import app.amphora.core.engine.ContentPinResolver
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent
import com.winlator.cmod.runtime.display.environment.components.XServerComponent
import com.winlator.cmod.runtime.display.xserver.ScreenInfo
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.io.FileUtils
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates Amphora's prefixPack source tree from the currently pinned Proton
 * runtime without extracting any existing prefixPack.
 *
 * Run through scripts/generate-prefix-pack.sh. The script exports
 * filesDir/prefix-generator/.wine and creates a deterministic txz on the host.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PrefixPackGeneratorTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var contentSource: ContentSource

    @Inject
    lateinit var catalog: ContentCatalog

    @Inject
    lateinit var rootfsInstaller: RootfsInstaller

    @Inject
    lateinit var runtimeAssets: RuntimeAssetProvisioner

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun generateCleanX8664Prefix() = runBlocking {
        val manifest = catalog.require()
        val imageFs = ImageFs.find(context)
        assertTrue(
            rootfsInstaller.ensureInstalled(
                RootfsSpec(
                    targetRoot = imageFs.rootDir.absolutePath,
                    imagefsVersion = manifest.entry(ContentComponent.ROOTFS)!!.version,
                    termuxfsSha256 = "",
                ),
            ),
        )
        runtimeAssets.ensureAvailable()
        contentSource.resolve(ContentComponent.WINE.id)
        contentSource.resolve(ContentComponent.BOX64.id)

        val contents = ContentsManager(context).apply { syncContents() }
        val wineEntry = manifest.entry(ContentComponent.WINE)!!
        val boxEntry = manifest.entry(ContentComponent.BOX64)!!
        val wineProfile = requireNotNull(contents.getProfileByEntryName(wineEntry.version))
        val boxProfile = requireNotNull(contents.getProfileByEntryName(boxEntry.version))
        val wineInfo = WineInfo.fromIdentifier(context, contents, wineEntry.version)
        imageFs.setWinePath(wineInfo.path)

        val outputRoot = File(context.filesDir, OUTPUT_DIRECTORY)
        deleteTreeWithoutFollowingLinks(outputRoot)
        assertTrue(outputRoot.mkdirs())

        val container =
            Container(GENERATOR_CONTAINER_ID).apply {
                rootDir = outputRoot
                wineVersion = wineEntry.version
                emulator = "box64"
                emulator64 = "box64"
                box64Version =
                    ContentPinResolver.versionIdentity(
                        ContentsManager.getEntryName(boxProfile),
                    )
            }
        Box64Runtime.ensureApplied(container, imageFs, contents)

        runGuestCommand(
            imageFs = imageFs,
            contents = contents,
            wineProfile = wineProfile,
            wineInfo = wineInfo,
            container = container,
            outputRoot = outputRoot,
            command = "wineboot -i",
        )
        runGuestCommand(
            imageFs = imageFs,
            contents = contents,
            wineProfile = wineProfile,
            wineInfo = wineInfo,
            container = container,
            outputRoot = outputRoot,
            command = "wineserver -w",
        )

        assertTrue("wineboot did not produce a valid prefix", WineUtils.isPrefixValid(outputRoot))
        sanitizePrefix(outputRoot, wineInfo)
        assertTrue("sanitized prefix is invalid", WineUtils.isPrefixValid(outputRoot))

        val prefix = File(outputRoot, ".wine")
        val summary =
            JSONObject().apply {
                put("wineVersion", wineEntry.version)
                put("winePackageSha256", wineEntry.sha256)
                put("files", countRegularFiles(prefix))
                put("bytes", sizeWithoutFollowingLinks(prefix))
                put("machineGuid", FIXED_MACHINE_GUID)
            }
        File(outputRoot, "prefix-generation.json").writeText(summary.toString(2))
        println("PREFIX_PACK_SOURCE=${prefix.absolutePath}")
        println("PREFIX_PACK_SUMMARY=$summary")
    }

    private fun runGuestCommand(
        imageFs: ImageFs,
        contents: ContentsManager,
        wineProfile: com.winlator.cmod.runtime.content.ContentProfile,
        wineInfo: WineInfo,
        container: Container,
        outputRoot: File,
        command: String,
    ) {
        val latch = CountDownLatch(1)
        val status = AtomicInteger(Int.MIN_VALUE)
        val launcher =
            GuestProgramLauncherComponent(contents, wineProfile).apply {
                setContainer(container)
                setWineInfo(wineInfo)
                setGuestExecutable(command)
                setBox64Preset(Box64Preset.STABILITY)
                setWorkingDir(outputRoot)
                setEnvVars(
                    EnvVars().apply {
                        put("HOME", outputRoot.absolutePath)
                        put("WINEPREFIX", File(outputRoot, ".wine").absolutePath)
                        put("WINEARCH", "win64")
                        put("WINEDEBUG", "-all")
                        put("WINEDLLOVERRIDES", "winemenubuilder.exe=d;explorer.exe=d")
                        put("LC_ALL", "C.UTF-8")
                    },
                )
                setTerminationCallback {
                    status.set(it)
                    latch.countDown()
                }
            }
        val xServer = XServer(ScreenInfo(800, 600))
        val environment = XEnvironment(context, imageFs)
        environment.addComponent(
            XServerComponent(
                xServer,
                UnixSocketConfig.createSocket(imageFs.rootDir.absolutePath, UnixSocketConfig.XSERVER_PATH),
            ),
        )
        environment.addComponent(launcher)
        try {
            environment.startEnvironmentComponents()
            assertTrue("$command timed out", latch.await(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("$command failed", 0, status.get())
        } finally {
            environment.stopEnvironmentComponents()
        }
    }

    private fun sanitizePrefix(outputRoot: File, wineInfo: WineInfo) {
        val prefix = File(outputRoot, ".wine")
        deleteTreeWithoutFollowingLinks(File(prefix, ".update-timestamp"))
        listOf(
            File(prefix, "drive_c/windows/temp"),
            File(prefix, "drive_c/users/xuser/AppData/Local/Temp"),
            File(prefix, "drive_c/users/xuser/AppData/Roaming/Microsoft/Windows/Recent"),
        ).forEach(::clearContents)

        // Container startup recreates drive mappings for the target device.
        val dosdevices = File(prefix, "dosdevices")
        dosdevices.listFiles().orEmpty().forEach { if (it.name != "c:") it.delete() }
        val cDrive = File(dosdevices, "c:")
        if (!FileUtils.isSymlink(cDrive)) {
            cDrive.delete()
            FileUtils.symlink("../drive_c", cDrive.absolutePath)
        }

        removeSharedWineBuiltins(
            source = File(wineInfo.path, "lib/wine/x86_64-windows"),
            target = File(prefix, "drive_c/windows/system32"),
        )
        removeSharedWineBuiltins(
            source = File(wineInfo.path, "lib/wine/i386-windows"),
            target = File(prefix, "drive_c/windows/syswow64"),
        )

        listOf("system.reg", "user.reg", "userdef.reg").forEach { name ->
            val file = File(prefix, name)
            if (!file.isFile) return@forEach
            val normalized =
                file
                    .readLines()
                    .joinToString("\n", postfix = "\n") { line ->
                        when {
                            line.startsWith("\"MachineGuid\"=") ->
                                "\"MachineGuid\"=\"$FIXED_MACHINE_GUID\""
                            line.startsWith("[") && line.contains("] ") ->
                                line.substringBeforeLast("] ") + "] 0"
                            else -> line.replace(outputRoot.absolutePath, "/data/data/app.amphora-prefix")
                        }
                    }
            file.writeText(normalized)
        }
    }

    private fun removeSharedWineBuiltins(source: File, target: File) {
        source.listFiles().orEmpty().filter(File::isFile).forEach { shared ->
            val candidate = File(target, shared.name)
            if (candidate.isFile && FileUtils.contentEquals(shared, candidate)) candidate.delete()
        }
    }

    private fun clearContents(directory: File) {
        directory.listFiles().orEmpty().forEach(::deleteTreeWithoutFollowingLinks)
        if (!directory.exists()) directory.mkdirs()
    }

    private fun deleteTreeWithoutFollowingLinks(file: File): Boolean {
        if (FileUtils.isSymlink(file)) return file.delete()
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteTreeWithoutFollowingLinks)
        return !file.exists() || file.delete()
    }

    private fun countRegularFiles(root: File): Long =
        root.walkTopDown().onEnter { !FileUtils.isSymlink(it) }.count { it.isFile }.toLong()

    private fun sizeWithoutFollowingLinks(root: File): Long = root
        .walkTopDown()
        .onEnter { !FileUtils.isSymlink(it) }
        .filter(File::isFile)
        .sumOf(File::length)

    private companion object {
        const val OUTPUT_DIRECTORY = "prefix-generator"
        const val GENERATOR_CONTAINER_ID = 9999
        const val COMMAND_TIMEOUT_SECONDS = 180L
        const val FIXED_MACHINE_GUID = "00000000-0000-4000-8000-000000000001"
    }
}
