package app.amphora.sdk.internal.window

import android.view.Surface
import app.amphora.sdk.internal.driver.WineDriver

/** What the window tree sends to Wine. An interface so the tree does not depend on the natives. */
internal interface DriverPort {
    fun desktopChanged(width: Int, height: Int)

    fun configChanged(dpi: Int)

    fun surfaceChanged(hwnd: Int, surface: Surface?, opengl: Boolean)

    fun motion(hwnd: Int, action: Int, x: Int, y: Int, buttons: Int, vscroll: Int): Boolean

    fun key(hwnd: Int, action: Int, keyCode: Int, metaState: Int): Boolean

    companion object {
        val Native: DriverPort =
            object : DriverPort {
                override fun desktopChanged(width: Int, height: Int) = WineDriver.wine_desktop_changed(width, height)

                override fun configChanged(dpi: Int) = WineDriver.wine_config_changed(dpi)

                override fun surfaceChanged(hwnd: Int, surface: Surface?, opengl: Boolean) =
                    WineDriver.wine_surface_changed(hwnd, surface, opengl)

                override fun motion(hwnd: Int, action: Int, x: Int, y: Int, buttons: Int, vscroll: Int): Boolean =
                    WineDriver.wine_motion_event(hwnd, action, x, y, buttons, vscroll)

                override fun key(hwnd: Int, action: Int, keyCode: Int, metaState: Int): Boolean =
                    WineDriver.wine_keyboard_event(hwnd, action, keyCode, metaState)
            }
    }
}
