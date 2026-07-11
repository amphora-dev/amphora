# Amphora native build (RFC §7 / §11 / D5).
#
# Final shape: a single CMakeLists.txt produces libwinlator.so + libfakeinput.so,
# FetchContent-links zstd v1.5.6 / xz v5.4.6 statically, add_subdirectory's the
# adrenotools submodule, and embeds 19 GLSL shaders via NDK glslc + bin2c.cmake.
# The C sources are ported wholesale from WinNative cpp/winlator/ (zero edits -
# the com.winlator.cmod package name is preserved so the 48 JNI exports resolve).
#
# This scaffold builds stub targets so the CMake + NDK pipeline is exercised
# end-to-end before the port lands.

cmake_minimum_required(VERSION 3.22.1)
project(amphora_native LANGUAGES C CXX)

# libwinlator.so - Vulkan X-server renderer + X compositor (stub).
add_library(winlator SHARED
    winlator/winlator_stub.c
)
target_compile_options(winlator PRIVATE -Wall -Wno-unused-parameter -O2)

# libfakeinput.so - LD_PRELOAD input shim, no JNI (stub).
add_library(fakeinput SHARED
    fakeinput/fakeinput_stub.c
)
target_compile_options(fakeinput PRIVATE -Wall -O2)
