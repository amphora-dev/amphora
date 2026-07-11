/* Amphora native scaffold stub.
 * Replaced by the ported winlator C sources (48 JNI exports, 9 files, 7 groups:
 * VulkanRenderer/Texture/GPUImage/Drawable-Pixmap/XConnectorEpoll-ClientSocket/
 * SyncFenceFd/SysVSharedMemory/ProcessHelper/NativeContentIO) per RFC §7 / D5.
 * Kept here so the CMake + NDK pipeline is verified before the port lands. */
#include <jni.h>

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    /* Real JNI_OnLoad calls prctl(PR_SET_CHILD_SUBREAPER, 1) so the app process
     * reaps orphaned Wine children (RFC D5). */
    return JNI_VERSION_1_6;
}
