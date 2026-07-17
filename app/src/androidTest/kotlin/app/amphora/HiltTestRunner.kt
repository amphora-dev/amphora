package app.amphora

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps in [HiltTestApplication] so `@HiltAndroidTest` classes can `@Inject` the
 * real Hilt graph (`EngineModule` -> `WineEngineImpl`, `WinlatorContainerManager`,
 * `ImageFsRootfsInstaller`, `XServerWineSessionPreparer`, …). Non-Hilt
 * `AndroidJUnit4` tests (`BundledContentSourceTest`, `PreparerGraphicsDriverTest`,
 * …) keep working unchanged -- [HiltTestApplication] is a plain `Application`.
 *
 * Wired in `app/build.gradle.kts`:
 * `android.defaultConfig.testInstrumentationRunner = "app.amphora.HiltTestRunner"`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
