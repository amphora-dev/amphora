package app.amphora

import android.app.Application
import com.winlator.cmod.runtime.system.ApplicationLogGate
import com.winlator.cmod.runtime.system.ProcessHelper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AmphoraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Anchor ProcessHelper wine-debug capture to this process's filesDir
        // (replaces the deleted PluviaApp singleton).
        ProcessHelper.init(this)
        // Renderer/DRI3 verbose logging stays off unless `enable_app_debug` is set.
        ApplicationLogGate.refresh(this)
    }
}
