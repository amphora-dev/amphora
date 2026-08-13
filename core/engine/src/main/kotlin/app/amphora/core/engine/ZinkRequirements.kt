package app.amphora.core.engine

/**
 * Device extensions zink refuses to start without.
 *
 * Mesa's zink marks these `required=True` in `zink_device_info.py`, and one more
 * (`VK_EXT_robustness2`, for `nullDescriptor`) is asserted right after feature
 * detection in `zink_screen.c`. A device missing any of them fails
 * `zink_get_physical_device_info()` and screen creation returns NULL.
 *
 * That failure is *silent* in a release Mesa: zink suppresses its own
 * `mesa_loge` calls whenever the driver name was inferred rather than chosen by
 * the user — which is exactly what happens when EGL picks zink for kopper — and
 * the missing-extension path only uses `debug_printf`, compiled out under
 * `b_ndebug`. All that reaches the log is one line from the EGL layer:
 *
 * ```
 * MESA-EGL: warning: egl: failed to create dri2 screen
 * ```
 *
 * followed by OpenGL quietly running on softpipe. Checking the list up front is
 * the only way to tell the difference between "zink is driving the GPU" and
 * "OpenGL is a slideshow", so [XServerWineSessionPreparer] probes before it
 * arms the zink knobs and the settings screen can say so out loud.
 *
 * Observed on Mali-G76 with the r34 blob (Vulkan 1.1.191): it has maintenance1,
 * create_renderpass2, imageless_framebuffer and descriptor_update_template, but
 * neither `VK_KHR_dynamic_rendering` nor `VK_EXT_robustness2`. Adreno blobs and
 * Turnip carry the whole list.
 */
object ZinkRequirements {
    /**
     * Promoted-to-core extensions are listed too. Vulkan 1.1/1.2 drivers keep
     * advertising them, and zink reads the advertised list rather than deriving
     * support from the API version.
     */
    private val REQUIRED_DEVICE_EXTENSIONS = listOf(
        "VK_KHR_maintenance1",
        "VK_KHR_create_renderpass2",
        "VK_KHR_imageless_framebuffer",
        "VK_KHR_descriptor_update_template",
        "VK_KHR_dynamic_rendering",
        "VK_EXT_robustness2",
    )

    /**
     * Which requirements [deviceExtensions] is missing, in the order zink checks
     * them. Empty means zink can start.
     *
     * An empty [deviceExtensions] means the probe itself failed, not that the
     * device supports nothing — the caller decides whether to trust that, so
     * this returns no blockers and leaves zink enabled.
     */
    fun missingExtensions(deviceExtensions: Collection<String>): List<String> {
        if (deviceExtensions.isEmpty()) return emptyList()
        val present = deviceExtensions.toSet()
        return REQUIRED_DEVICE_EXTENSIONS.filterNot { it in present }
    }
}
