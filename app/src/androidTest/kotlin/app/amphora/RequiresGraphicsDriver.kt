package app.amphora

/**
 * Marks instrumented tests that need a real Adreno/Turnip/Vulkan graphics path.
 *
 * Emulator CI excludes these with:
 * `adb shell am instrument -e notAnnotation app.amphora.RequiresGraphicsDriver ...`
 *
 * Keep remote download / rootfs / Wine-less smoke coverage unmarked so it can run
 * on CNB `cnb:arch:arm64:v8` Android emulators.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class RequiresGraphicsDriver
