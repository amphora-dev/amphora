package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentReconcilerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reconcileVisitsEveryNonRootComponentAndSumsRemovedSiblings() {
        val manifest = ContentManifest.parse(ContentManifestTest.SAMPLE)
        val installer = FakeInstaller(siblingsRemoved = 2)

        val report = ContentReconciler(temporaryFolder.newFolder("packages"), installer).reconcile(manifest)

        val expected = manifest.all().filter { it.kind != ManifestEntry.Kind.ROOTFS }
        assertEquals(expected.map { it.component }, installer.reconciled.map { it.component })
        assertEquals(expected.size * 2, report.siblingDirsRemoved)
        assertEquals(0, report.packageFilesRemoved)
    }

    @Test
    fun reconcilePrunesUnpinnedPackagesAndKeepsPinnedSidecars() {
        val packageRoot = temporaryFolder.newFolder("packages")
        val manifest = ContentManifest.parse(ContentManifestTest.SAMPLE)
        val winePackage = File(packageRoot, "Proton-10.0-4-x86_64.wcp").apply { writeText("current") }
        val wineDigest = File(packageRoot, "${winePackage.name}.sha256").apply { writeText("a".repeat(64)) }
        val runtimePackage = File(packageRoot, "wrapper.tzst").apply { writeText("runtime") }
        val runtimePart = File(packageRoot, "${runtimePackage.name}.part").apply { writeText("partial") }
        val stalePackage = File(packageRoot, "Proton-9.0.wcp").apply { writeText("stale") }
        val rootfsPackage = File(packageRoot, "imagefs.txz").apply { writeText("rootfs") }

        val report = ContentReconciler(packageRoot, FakeInstaller()).reconcile(manifest)

        assertEquals(2, report.packageFilesRemoved)
        assertTrue(report.changed)
        assertTrue(winePackage.isFile)
        assertTrue(wineDigest.isFile)
        assertTrue(runtimePackage.isFile)
        assertTrue(runtimePart.isFile)
        assertFalse(stalePackage.exists())
        assertFalse(rootfsPackage.exists())
    }

    @Test
    fun reconcileFailurePreservesPackageCache() {
        val packageRoot = temporaryFolder.newFolder("packages")
        val stalePackage = File(packageRoot, "stale.wcp").apply { writeText("rollback") }
        val manifest = ContentManifest.parse(ContentManifestTest.SAMPLE)
        val failOn = manifest.all().first { it.kind != ManifestEntry.Kind.ROOTFS }.component
        val installer = FakeInstaller(failOn = failOn)
        val reconciler = ContentReconciler(packageRoot, installer)

        assertThrows(IllegalStateException::class.java) {
            reconciler.reconcile(manifest)
        }

        assertTrue(stalePackage.isFile)
        assertEquals(failOn, installer.reconciled.last().component)
    }

    private class FakeInstaller(private val siblingsRemoved: Int = 0, private val failOn: ContentComponent? = null) :
        ContentAssetInstaller {
        val reconciled = mutableListOf<ManifestEntry>()

        override fun resolvedPath(entry: ManifestEntry): File = File(entry.assetPath)

        override fun isInstalled(entry: ManifestEntry): Boolean = false

        override suspend fun install(entry: ManifestEntry, archiveFile: File): File =
            error("install is not used by reconciliation")

        override fun reconcileToPin(entry: ManifestEntry, pinnedEntries: Collection<ManifestEntry>): Int {
            reconciled += entry
            check(entry in pinnedEntries) { "reconcile must see the whole manifest, got $pinnedEntries" }
            check(entry.component != failOn) { "reconcile failed for ${entry.component}" }
            return siblingsRemoved
        }
    }
}
