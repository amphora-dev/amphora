# Amphora native build (RFC §7 / §11 / D5).
#
# Ported wholesale from WinNative `cpp/winlator/` (zero edits -- the
# `com.winlator.cmod` package name is preserved so the JNI exports
# `Java_com_winlator_cmod_*` resolve without touching the C). Produces:
#   - libwinlator.so : wineandroid host IPC/ANW, X connector, GPU probes (JNI exports)
#   - (libfakeinput.so not built — MVP input is XServer inject; see end of file)
#
# Trim vs upstream (RFC §7): `native_content_io.cpp` is included for archive
# extraction (zstd + xz) only; the HTTP download path is removed entirely --
# MVP bundles fixed binaries and does no remote fetch (RFC §10 D4). The old
# X11 vk/ compositor renderer, drawable/sync_fence/gpu_image JNI pairs are
# removed with the deleted Java X server (see tag x11-reference).
#
# adrenotools is vendored as a git submodule (pinned 8483dfd, recursive ->
# linkernsbypass b10d485) and statically linked via add_subdirectory.

cmake_minimum_required(VERSION 3.22.1)
project(amphora_native LANGUAGES C CXX)

# Optional compile accelerator (CI + local). Prefer launcher set by Gradle
# (-DCMAKE_C_COMPILER_LAUNCHER=ccache); fall back to PATH discovery.
if(NOT CMAKE_C_COMPILER_LAUNCHER)
    find_program(AMPHORA_CCACHE ccache)
    if(AMPHORA_CCACHE)
        set(CMAKE_C_COMPILER_LAUNCHER "${AMPHORA_CCACHE}" CACHE STRING "" FORCE)
        set(CMAKE_CXX_COMPILER_LAUNCHER "${AMPHORA_CCACHE}" CACHE STRING "" FORCE)
        message(STATUS "amphora native: using ccache (${AMPHORA_CCACHE})")
    endif()
endif()

# Match WinNative's global C flags. NDK r28 / clang 21 treats
# -Wimplicit-function-declaration as an error by default in newer C modes; the
# explicit flag keeps it a warning so the verbatim C sources compile untouched.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

include(FetchContent)

# ----------------------------------------------------------------------------
# zstd + liblzma (xz) -- statically linked for asset archive extraction
# (.tzst / .txz) in winlator/native_content_io.cpp (RFC §7 / §11). HTTP download
# (curl) is intentionally NOT added: MVP bundles binaries (D4) and the download
# JNIs are stubbed. Pattern verbatim from WinNative cpp/CMakeLists.txt.
# ----------------------------------------------------------------------------
FetchContent_Declare(
    zstd
    GIT_REPOSITORY https://github.com/facebook/zstd.git
    GIT_TAG        v1.5.6
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  build/cmake
)
set(ZSTD_BUILD_STATIC    ON  CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED    OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_PROGRAMS  OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT  OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(zstd)

FetchContent_Declare(
    xz
    GIT_REPOSITORY https://github.com/tukaani-project/xz.git
    GIT_TAG        v5.4.6
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(xz)
if(NOT xz_POPULATED)
    FetchContent_Populate(xz)
    set(_amphora_saved_bsl "${BUILD_SHARED_LIBS}")
    set(BUILD_SHARED_LIBS OFF)
    set(XZ_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    add_subdirectory(${xz_SOURCE_DIR} ${xz_BINARY_DIR} EXCLUDE_FROM_ALL)
    set(BUILD_SHARED_LIBS "${_amphora_saved_bsl}")
endif()

# ----------------------------------------------------------------------------
# adrenotools (GPU driver loading) -- statically linked into libwinlator.so.
#
# Do NOT add_subdirectory(adrenotools): that would also build the four SHARED
# hook .so targets. Those are compiled by amphora-dev/imagefs wrapper CI and
# ship in wrapper.tzst → imagefs/usr/lib (host + guest). APK only needs the
# static adrenotools + linkernsbypass used by vulkan.c.
# ----------------------------------------------------------------------------
set(ADRENOTOOLS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/adrenotools")
if(NOT EXISTS "${ADRENOTOOLS_DIR}/src/driver.cpp")
    message(FATAL_ERROR
        "adrenotools submodule is missing under ${ADRENOTOOLS_DIR}.\n"
        "Initialize it with: git submodule update --init --recursive")
endif()

add_subdirectory("${ADRENOTOOLS_DIR}/lib/linkernsbypass"
                 "${CMAKE_CURRENT_BINARY_DIR}/linkernsbypass")

add_library(adrenotools STATIC
        "${ADRENOTOOLS_DIR}/src/bcenabler.cpp"
        "${ADRENOTOOLS_DIR}/src/driver.cpp"
)
target_include_directories(adrenotools
        PUBLIC "${ADRENOTOOLS_DIR}/include"
        PRIVATE "${ADRENOTOOLS_DIR}" "${ADRENOTOOLS_DIR}/src"
)
target_compile_options(adrenotools PRIVATE -Wall -Wextra)
target_link_libraries(adrenotools PRIVATE android linkernsbypass)

# ----------------------------------------------------------------------------
# Winlator native library (X connector, wineandroid host IPC/ANW, helpers)
# ----------------------------------------------------------------------------
add_library(winlator SHARED
        winlator/native_content_io.cpp
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/process_lifecycle.c
        winlator/vulkan.c
)

target_compile_options(winlator PRIVATE -Wall -Wextra)
target_compile_definitions(winlator PRIVATE
        VK_USE_PLATFORM_ANDROID_KHR
)
target_include_directories(winlator PRIVATE
        ${xz_SOURCE_DIR}/src/liblzma/api
        ${CMAKE_CURRENT_BINARY_DIR}
)

target_compile_features(winlator PRIVATE cxx_std_17)

target_link_libraries(winlator
        log
        android
        jnigraphics
        vulkan
        adrenotools
        libzstd_static
        liblzma
)

# Android 10+ blocks direct execve() of files below the writable app data
# directory for targetSdk >= 29. This LD_PRELOAD shim rewrites native child
# execs to `/system/bin/linker64 <absolute-elf-path>`, including Box64's
# self-reexec when Wine starts another process.
add_library(amphora-exec SHARED
        winlator/amphora_exec.c
)
target_compile_options(amphora-exec PRIVATE -Wall -Wextra)
target_link_libraries(amphora-exec PRIVATE dl)

# Replaces the imagefs `usr/lib/libandroid.so` symlink for the Mali (Leegao)
# ICD. The SONAME has to stay `libandroid.so` so the guest linker matches the
# DT_NEEDED of libandroid-shmem.
add_library(amphora-android-shim SHARED
        winlator/android_shim.c
)
target_compile_options(amphora-android-shim PRIVATE -Wall -Wextra)
target_link_libraries(amphora-android-shim PRIVATE dl log)
target_link_options(amphora-android-shim PRIVATE "-Wl,-soname,libandroid.so")

# aarch64 PE Win32 WSI helper. Box64 guests cannot dlopen this; host LD_PRELOAD
# of nativeLibraryDir/libamphora_wsi.so runs the ctor (unix-socket present bridge).
add_library(amphora_wsi SHARED
        winlator/amphora_wsi.c
)
target_compile_options(amphora_wsi PRIVATE -Wall -Wextra)
target_link_libraries(amphora_wsi PRIVATE log dl)

# fakeinput LD_PRELOAD shim intentionally not built (MVP input is XServer inject).
# Source removed from tree; reintroduce from WinNative when native gamepad returns.

# wineandroid host: SEQPACKET ioctl server + tip AMPHORA_BUF sock serve
# (host_anw serves dequeue/queue over per-HWND socketpair for WSI Present).
target_sources(winlator PRIVATE
        winlator/wineandroid_host_ipc.c
        winlator/wineandroid_host_anw.c)
