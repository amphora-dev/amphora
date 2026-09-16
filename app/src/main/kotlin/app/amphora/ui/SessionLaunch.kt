package app.amphora.ui

import android.content.Context
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.SessionActivity
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
        width: Int = 1280,
        height: Int = 720,
        graphicsDiag: Boolean = false,
        displayBackend: DisplayBackend = resolveDisplayBackend(),
        debugImeUnicodeText: String? = null,
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
                )
            DisplayBackend.WINEANDROID ->
                WineAndroidSessionActivity.launch(
                    context = context,
                    exePath = exePath,
                    width = width,
                    height = height,
                    graphicsDiag = graphicsDiag,
                    debugImeUnicodeText = debugImeUnicodeText,
                )
        }
    }

    fun explorer(
        context: Context,
        width: Int = 1280,
        height: Int = 720,
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
