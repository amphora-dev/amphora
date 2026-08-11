package com.winlator.cmod.runtime.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentsManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedRenameEmitsOnlyFailureCallback() {
        val installPath = File(temporaryFolder.root, "contents/wine/version-0")
        val missingTemporaryPath = File(temporaryFolder.root, "missing-tmp")
        val profile = ContentProfile()
        var failures = 0
        var successes = 0
        var repairs = 0

        ContentsManager.finishInstallContent(
            missingTemporaryPath,
            installPath,
            profile,
            object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    failures++
                }

                override fun onSucceed(profile: ContentProfile) {
                    successes++
                }
            },
            Runnable { repairs++ },
        )

        assertEquals(1, failures)
        assertEquals(0, successes)
        assertEquals(0, repairs)
        assertFalse(installPath.exists())
    }

    @Test
    fun existingInstallIsReportedWithoutDeletingRollbackContent() {
        val installPath = File(temporaryFolder.root, "contents/wine/version-0").apply {
            mkdirs()
            resolve("existing-runtime").writeText("keep")
        }
        val temporaryPath = File(temporaryFolder.root, "incoming").apply {
            mkdirs()
            resolve("new-runtime").writeText("new")
        }
        val profile = ContentProfile()
        var failureReason: ContentsManager.InstallFailedReason? = null
        var successes = 0
        var repairs = 0

        ContentsManager.finishInstallContent(
            temporaryPath,
            installPath,
            profile,
            object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    failureReason = reason
                }

                override fun onSucceed(profile: ContentProfile) {
                    successes++
                }
            },
            Runnable { repairs++ },
        )

        assertEquals(ContentsManager.InstallFailedReason.ERROR_EXIST, failureReason)
        assertEquals(0, successes)
        assertEquals(0, repairs)
        assertEquals("keep", installPath.resolve("existing-runtime").readText())
        assertTrue(temporaryPath.resolve("new-runtime").isFile)
    }
}
