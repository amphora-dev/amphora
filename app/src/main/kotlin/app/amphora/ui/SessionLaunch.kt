package app.amphora.ui

import android.content.Context
import app.amphora.core.engine.WineAndroidGuestResolution
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.wineandroid.WineAndroidSessionActivity

/**
 * Single place for "open a Wine session" from UI surfaces (NavHost, PC desktop).
 * Sessions always run on the wineandroid host.
 */
object SessionLaunch {
    fun program(
        context: Context,
        exePath: String,
        width: Int = WineAndroidGuestResolution.DEFAULT.width,
        height: Int = WineAndroidGuestResolution.DEFAULT.height,
        graphicsDiag: Boolean = false,
        perfHud: Boolean = false,
        exeArgs: String = "",
        debugImeUnicodeText: String? = null,
        debugImeComposingText: String? = null,
        debugImeShow: Boolean? = null,
        debugCaptureHwnd: Int? = null,
        debugDumpZOrder: Boolean? = null,
        debugZOrderTopHwnd: Int? = null,
    ) {
        WineAndroidSessionActivity.launch(
            context = context,
            exePath = exePath,
            width = width,
            height = height,
            graphicsDiag = graphicsDiag,
            perfHud = perfHud,
            exeArgs = exeArgs,
            debugImeUnicodeText = debugImeUnicodeText,
            debugImeComposingText = debugImeComposingText,
            debugImeShow = debugImeShow,
            debugCaptureHwnd = debugCaptureHwnd,
            debugDumpZOrder = debugDumpZOrder,
            debugZOrderTopHwnd = debugZOrderTopHwnd,
        )
    }

    fun explorer(
        context: Context,
        width: Int = WineAndroidGuestResolution.DEFAULT.width,
        height: Int = WineAndroidGuestResolution.DEFAULT.height,
    ) {
        WineAndroidSessionActivity.launch(
            context = context,
            exePath = "",
            width = width,
            height = height,
            target = LaunchTarget.EXPLORER,
        )
    }
}
