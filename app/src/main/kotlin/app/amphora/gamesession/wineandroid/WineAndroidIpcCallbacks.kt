package app.amphora.gamesession.wineandroid

/**
 * JNI callbacks from the native SEQPACKET ioctl server into Kotlin UI.
 * Method signatures mirror WineActivity.java entry points used by device.c.
 */
interface WineAndroidIpcCallbacks {
    fun createDesktopView()

    fun createWindow(hwnd: Int, isDesktop: Boolean, opengl: Boolean, parent: Int)

    fun destroyWindow(hwnd: Int)

    fun windowPosChanged(
        hwnd: Int,
        flags: Int,
        insertAfter: Int,
        owner: Int,
        style: Int,
        windowLeft: Int,
        windowTop: Int,
        windowRight: Int,
        windowBottom: Int,
        clientLeft: Int,
        clientTop: Int,
        clientRight: Int,
        clientBottom: Int,
        visibleLeft: Int,
        visibleTop: Int,
        visibleRight: Int,
        visibleBottom: Int,
    )

    fun setParent(hwnd: Int, parent: Int)

    fun setCapture(hwnd: Int)

    fun setCursor(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, bits: IntArray?)
}
