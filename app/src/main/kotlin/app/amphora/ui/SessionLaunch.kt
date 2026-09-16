package app.amphora.ui

import android.content.Context
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.SessionActivity
import app.amphora.core.engine.WineAndroidGuestResolution
import app.amphora.gamesession.wineandroid.WineAndroidSessionActivity

/**
 * Single place for "open a Wine session" from UI surfaces (NavHost, PC desktop).
 *
 * Default backend is [resolveDisplayBackend] (wineandroid when
 * [WineAndroidLaunchGate.FORCE_WINEANDROID_HOST] is true). Callers may pass
 * [DisplayBackend.X11] explicitly for the legacy Java X11 host.
 */
object SessionLaunch {
    fun resolveDisplayBackend(): DisplayBackend = if (WineAndroidLaunchGate.FORCE_WINEANDROID_HOST) {
        DisplayBackend.WINEANDROID
    } else {
        DisplayBackend.X11
    }

    fun program(
        context: Context,
        exePath: String,
        width: Int = WineAndroidGuestResolution.DEFAULT.width,
        height: Int = WineAndroidGuestResolution.DEFAULT.height,
        graphicsDiag: Boolean = false,
        displayBackend: DisplayBackend = resolveDisplayBackend(),
        exeArgs: String = "",
        debugImeUnicodeText: String? = null,
        debugImeComposingText: String? = null,
        debugImeShow: Boolean? = null,
        debugCaptureHwnd: Int? = null,
        debugDumpZOrder: Boolean? = null,
        debugZOrderTopHwnd: Int? = null,
    ) {
        when (displayBackend) {
            DisplayBackend.X11 ->
                SessionActivity.launch(
                    context = context,
                    exePath = exePath,
                    width = width,
                    height = height,
                    graphicsDiag = graphicsDiag,
                    displayBackend = DisplayBackend.X11,
                    exeArgs = exeArgs,
                )
            DisplayBackend.WINEANDROID ->
                WineAndroidSessionActivity.launch(
                    context = context,
                    exePath = exePath,
                    width = width,
                    height = height,
                    graphicsDiag = graphicsDiag,
                    exeArgs = exeArgs,
                    debugImeUnicodeText = debugImeUnicodeText,
                    debugImeComposingText = debugImeComposingText,
                    debugImeShow = debugImeShow,
                    debugCaptureHwnd = debugCaptureHwnd,
                    debugDumpZOrder = debugDumpZOrder,
                    debugZOrderTopHwnd = debugZOrderTopHwnd,
                )
        }
    }

    fun explorer(
        context: Context,
        width: Int = WineAndroidGuestResolution.DEFAULT.width,
        height: Int = WineAndroidGuestResolution.DEFAULT.height,
        displayBackend: DisplayBackend = resolveDisplayBackend(),
    ) {
        when (displayBackend) {
            DisplayBackend.X11 ->
                SessionActivity.launch(
                    context = context,
                    exePath = "",
                    width = width,
                    height = height,
                    target = LaunchTarget.EXPLORER,
                    displayBackend = DisplayBackend.X11,
                )
            DisplayBackend.WINEANDROID ->
                WineAndroidSessionActivity.launch(
                    context = context,
                    exePath = "",
                    width = width,
                    height = height,
                    target = LaunchTarget.EXPLORER,
                )
        }
    }
}
