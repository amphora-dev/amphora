# Amphora ProGuard / R8 rules.

# Ported com.winlator.cmod kernel (RFC §7 - frozen, reused as-is).
# libwinlator.so resolves kernel classes and members by literal JNI names:
#   FindClass("com/winlator/cmod/shared/util/OnExtractFileListener")
#   GetMethodID(..., "setStride"/"addAncillaryFd"/"handleExistingConnection"...)
#   NewObject(AdrenotoolsManager <init>, ...)
#   ImageFs.getLibDir / AdrenotoolsManager.getLibraryName ...
# R8 would otherwise rename or strip entry points that are only reachable from
# native code, so the whole kernel tree keeps its names and members. The kernel
# is the engine itself, so there was little shrinkage to be had here anyway;
# shrinking and optimization still apply to app/feature/Hilt/AndroidX code.
-keep class com.winlator.cmod.** { *; }
