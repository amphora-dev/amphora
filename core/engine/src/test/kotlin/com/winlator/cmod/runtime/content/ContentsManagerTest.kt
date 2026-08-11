package com.winlator.cmod.runtime.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
