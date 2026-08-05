# Amphora native build (RFC §7 / §11 / D5).
#
# Ported wholesale from WinNative `cpp/winlator/` (zero edits -- the
# `com.winlator.cmod` package name is preserved so the JNI exports
# `Java_com_winlator_cmod_*` resolve without touching the C). Produces:
#   - libwinlator.so : Vulkan X-server renderer + X compositor (JNI exports)
#   - (libfakeinput.so not built — MVP input is XServer inject; see end of file)
#
# Trim vs upstream (RFC §7): `native_content_io.cpp` is included for asset
# extraction (zstd + xz), but its HTTP download path (curl) is stubbed out --
# MVP bundles fixed binaries and does no remote fetch (RFC §10 D4). The two
# download JNI symbols are retained as no-op stubs so NativeContentIO.java's
# native decls resolve without UnsatisfiedLinkError; restore the curl-backed
# bodies from WinNative upstream when v0.3 adds RemoteContentSource download.
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
# SPIR-V shader compilation
# Each .glsl is compiled by glslc (shipped with the NDK) into a .spv binary,
# then converted to a C uint32_t array via bin2c.cmake. Headers are emitted
# under ${CMAKE_CURRENT_BINARY_DIR}/shaders/*.spv.h and included from vk_renderer.c.
# ----------------------------------------------------------------------------
if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK not defined; this project must be built via the Android Gradle plugin")
endif()

if(CMAKE_HOST_WIN32)
    set(GLSLC_HOST_TAG "windows-x86_64")
    set(GLSLC_EXE_NAME "glslc.exe")
elseif(CMAKE_HOST_APPLE)
    set(GLSLC_HOST_TAG "darwin-x86_64")
    set(GLSLC_EXE_NAME "glslc")
else()
    set(GLSLC_HOST_TAG "linux-x86_64")
    set(GLSLC_EXE_NAME "glslc")
endif()

set(GLSLC "${ANDROID_NDK}/shader-tools/${GLSLC_HOST_TAG}/${GLSLC_EXE_NAME}")
if(NOT EXISTS "${GLSLC}")
    message(FATAL_ERROR "glslc not found at ${GLSLC}; check NDK installation")
endif()

set(SHADER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/winlator/vk/shaders")
set(SHADER_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/shaders")
file(MAKE_DIRECTORY "${SHADER_OUT_DIR}")

set(BIN2C_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/winlator/vk/bin2c.cmake")

# (input filename without extension, var name, stage)
set(SHADER_LIST
    "window:vert:window_vert"
    "window:frag:window_frag"
    "cursor:frag:cursor_frag"
    "quad:vert:quad_vert"
    "blit:frag:blit_frag"
    "effect_crt:frag:effect_crt_frag"
    "effect_vivid:frag:effect_vivid_frag"
    "effect_hdr:frag:effect_hdr_frag"
    "effect_natural:frag:effect_natural_frag"
    "effect_toon:frag:effect_toon_frag"
    "effect_ntsc:frag:effect_ntsc_frag"
    "effect_ntsc2:frag:effect_ntsc2_frag"
    "effect_coloradj:frag:effect_coloradj_frag"
    "effect_colorgrade:frag:effect_colorgrade_frag"
    "effect_sharpen:frag:effect_sharpen_frag"
    "effect_scanlines:frag:effect_scanlines_frag"
    "effect_colorblind:frag:effect_colorblind_frag"
    "effect_pixelate:frag:effect_pixelate_frag"
    "sgsr1:frag:sgsr1_frag"
)

set(SHADER_HEADERS "")
foreach(entry ${SHADER_LIST})
    string(REPLACE ":" ";" parts ${entry})
    list(GET parts 0 base)
    list(GET parts 1 stage)
    list(GET parts 2 var)
    set(input "${SHADER_SRC_DIR}/${base}.${stage}")
    set(spv   "${SHADER_OUT_DIR}/${var}.spv")
    set(hdr   "${SHADER_OUT_DIR}/${var}.spv.h")

    add_custom_command(
        OUTPUT  "${hdr}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${input}" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}"
            -DINPUT_FILE=${spv}
            -DOUTPUT_FILE=${hdr}
            -DVAR_NAME=${var}
            -P "${BIN2C_SCRIPT}"
        DEPENDS "${input}" "${BIN2C_SCRIPT}"
        COMMENT "Compiling shader ${base}.${stage} -> ${var}.spv.h"
        VERBATIM
    )
    list(APPEND SHADER_HEADERS "${hdr}")
endforeach()

add_custom_target(winlator_shaders DEPENDS ${SHADER_HEADERS})

# ----------------------------------------------------------------------------
# Winlator native library (X-server, AHB, Vulkan compositor, helpers)
# ----------------------------------------------------------------------------
add_library(winlator SHARED
        winlator/drawable.c
        winlator/native_content_io.cpp
        winlator/gpu_image.c
        winlator/sync_fence.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/process_lifecycle.c
        winlator/vulkan.c
        winlator/vk/vk_dispatch.c
        winlator/vk/vk_image.c
        winlator/vk/vk_renderer.c
)

add_dependencies(winlator winlator_shaders)

target_compile_options(winlator PRIVATE -Wall -Wextra)
target_compile_definitions(winlator PRIVATE
        VK_USE_PLATFORM_ANDROID_KHR
)
target_include_directories(winlator PRIVATE
        ${xz_SOURCE_DIR}/src/liblzma/api
        winlator/vk
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

# fakeinput LD_PRELOAD shim intentionally not built (MVP input is XServer inject).
# Source removed from tree; reintroduce from WinNative when native gamepad returns.
