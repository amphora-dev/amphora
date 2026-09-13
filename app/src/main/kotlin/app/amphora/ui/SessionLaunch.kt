package app.amphora.ui

import android.content.Context
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.gamesession.SessionActivity
import app.amphora.gamesession.wineandroid.WineAndroidSessionActivity

/**
 * Single place for "open a Wine session" from UI surfaces (NavHost, PC desktop).
 * Honors [WineAndroidLaunchGate]; does not flip the committed default off X11.
 */
object SessionLaunch {
    fun program(
        context: Context,
        exePath: String,
        width: Int = 1280,
        height: Int = 720,
        graphicsDiag: Boolean = false,
    ) {
        if (WineAndroidLaunchGate.FORCE_WINEANDROID_HOST) {
            WineAndroidSessionActivity.launch(
                context = context,
                exePath = exePath,
                width = width,
                height = height,
                graphicsDiag = graphicsDiag,
            )
        } else {
            SessionActivity.launch(
                context = context,
                exePath = exePath,
                width = width,
                height = height,
                graphicsDiag = graphicsDiag,
                displayBackend = DisplayBackend.X11,
            )
        }
    }

    fun explorer(
        context: Context,
        width: Int = 1280,
        height: Int = 720,
    ) {
        if (WineAndroidLaunchGate.FORCE_WINEANDROID_HOST) {
            WineAndroidSessionActivity.launch(
                context = context,
                exePath = "",
                width = width,
                height = height,
                target = LaunchTarget.EXPLORER,
            )
        } else {
            SessionActivity.launch(
                context = context,
                exePath = "",
                width = width,
                height = height,
                target = LaunchTarget.EXPLORER,
                displayBackend = DisplayBackend.X11,
            )
        }
    }
}
