package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DxvkLinksNeedRefreshTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var mainInstall: File
    private lateinit var splitInstall: File
    private lateinit var system32: File
    private lateinit var syswow64: File

    /**
     * Two "installed" DXVK trees plus prefix dirs whose DLL names are links
     * into them — the shape [dxvkLinksNeedRefresh] walks on the device.
     */
    private fun fixture(): Pair<File, File> {
        mainInstall = temporaryFolder.newFolder("DXVK-3.0.2-0")
        splitInstall = temporaryFolder.newFolder("DXVK-1.11.0-async-sarek-0")
        system32 = temporaryFolder.newFolder("system32")
        syswow64 = temporaryFolder.newFolder("syswow64")
        return mainInstall to splitInstall
    }

    private fun link(dll: String, from: File, to: File) {
        val source = File(to, dll)
        source.writeBytes(byteArrayOf(1))
        for (dir in arrayOf(system32, syswow64)) {
            val link = File(dir, dll).toPath()
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, source.toPath())
        }
    }

    private fun allFiveInto(install: File) {
        for (dll in DXVK_SHARED_DLLS) link(dll, system32, install)
    }

    private fun frontends() {
        for (dll in arrayOf("d3d10.dll", "d3d10_1.dll")) link(dll, system32, mainInstall)
    }

    @Test
    fun aCompleteUnsplitLayoutNeedsNoRefresh() {
        fixture()
        allFiveInto(mainInstall)
        frontends()
        assertFalse(
            dxvkLinksNeedRefresh(
                system32,
                syswow64,
                dxvkInstallDir = mainInstall,
                d3d89Armed = false,
                d3d89InstallDir = null,
            ),
        )
    }

    @Test
    fun splitLayoutPassesOnlyWithD3d89OnTheSplitBuild() {
        fixture()
        allFiveInto(mainInstall)
        frontends()
        // Armed split but d3d8/d3d9 still point at the main build -> refresh.
        assertTrue(
            dxvkLinksNeedRefresh(
                system32,
                syswow64,
                dxvkInstallDir = mainInstall,
                d3d89Armed = true,
                d3d89InstallDir = splitInstall,
            ),
        )
        for (dll in D3D89_SPLIT_DLLS) link(dll, system32, splitInstall)
        assertFalse(
            dxvkLinksNeedRefresh(
                system32,
                syswow64,
                dxvkInstallDir = mainInstall,
                d3d89Armed = true,
                d3d89InstallDir = splitInstall,
            ),
        )
    }

    @Test
    fun armedSplitWithAnUnresolvableProfileNeedsRefresh() {
        fixture()
        allFiveInto(mainInstall)
        frontends()
        // d3d89 armed, split profile missing -> refresh so the extract stage
        // fails loudly rather than shipping the broken fixed-function path.
        assertTrue(
            dxvkLinksNeedRefresh(
                system32,
                syswow64,
                dxvkInstallDir = mainInstall,
                d3d89Armed = true,
                d3d89InstallDir = null,
            ),
        )
    }

    @Test
    fun linksPointedElsewhereOrPlainFilesNeedRefresh() {
        fixture()
        allFiveInto(mainInstall)
        frontends()
        for (dll in D3D89_SPLIT_DLLS) link(dll, system32, splitInstall)
        // dxgi symlinked into the split build instead of the main one.
        val stray = File(splitInstall, "dxgi.dll")
        Files.delete(File(system32, "dxgi.dll").toPath())
        Files.createSymbolicLink(File(system32, "dxgi.dll").toPath(), stray.toPath())
        assertTrue(
            dxvkLinksNeedRefresh(
                system32,
                syswow64,
                dxvkInstallDir = mainInstall,
                d3d89Armed = true,
                d3d89InstallDir = splitInstall,
            ),
        )
        // A regular file where a link belongs is not a healthy link.
        Files.delete(File(system32, "dxgi.dll").toPath())
        File(system32, "dxgi.dll").writeBytes(byteArrayOf(1))
        assertTrue(
            dxvkLinksNeedRefresh(
                system32,
                syswow64,
                dxvkInstallDir = null,
                d3d89Armed = false,
                d3d89InstallDir = null,
            ),
        )
    }
}
