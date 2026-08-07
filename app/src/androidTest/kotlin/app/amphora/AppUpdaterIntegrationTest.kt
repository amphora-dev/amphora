package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.content.update.AppUpdater
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppUpdaterIntegrationTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var updater: AppUpdater

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun localBuildSkipsStartupAndValidatesItsOwnApk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apk = File(context.applicationInfo.sourceDir)
        val versionCode = updater.installedVersionCode().toInt()
        assertFalse(updater.shouldCheckAtStartup())

        updater.validateDownloadedApk(
            apk,
            AppUpdateManifest(
                versionCode = versionCode,
                versionName = updater.installedVersionName(),
                apkUrl = "https://example.invalid/amphora.apk",
                sha256 = "0".repeat(64),
                size = apk.length(),
            ),
        )
    }
}
