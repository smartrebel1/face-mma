cmake_minimum_required(VERSION 3.22.1)
project(ffnative)

# The same four objects the headless CLI links (work/native/ffswap_main.cpp), plus the JNI
# bridge.  Keeping them identical is what makes the CLI a valid harness for the app.
add_library(ffnative SHARED ffjni.cpp ffpipe.cpp ffcv.cpp ffqnn.cpp
                            ffnn.cpp ffnn_qnn.cpp ffaudio.cpp ffportrait.cpp)

# Both roots are needed: ffqnn.cpp includes "QNN/QnnBackend.h", while the SDK's own
# sub-headers include their siblings unqualified.
target_include_directories(ffnative PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include/QNN)

# The QNN backend is dlopen'd at runtime from the app's nativeLibraryDir, never linked, so
# no QNN .so is needed at build time and the build is independent of the HTP variant.
# jnigraphics is for AndroidBitmap_lockPixels: the live pump writes its ARGB output
# straight into the Bitmap the compositor draws, rather than through an IntArray and
# setPixels. See liveFrame in ffjni.cpp.
target_link_libraries(ffnative android log dl jnigraphics)
target_compile_options(ffnative PRIVATE -O3 -fvisibility=hidden -Wall)

# ---------------------------------------------------------------------------
# Optional ncnn backend (roadmap 6): CPU (NEON) or GPU (Vulkan), for parts with no Hexagon.
# ---------------------------------------------------------------------------
# Turned on by build.gradle.kts when `work/android/ncnn/` exists, which is a STAGED copy of
# the WSL build -- see work/android/stage_ncnn.sh for why it is copied rather than
# referenced in place. Delete that directory and the app builds QNN-only, unchanged.
#
# `NCNN_DIR` holds the flattened staging: lib/*.a and one include/ carrying BOTH ncnn header
# roots (the source tree's net.h and the build tree's generated platform.h / layer
# registries, which ncnn's own headers include unqualified).
option(FF_NCNN "Link the ncnn backend for non-Qualcomm parts" OFF)
set(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../ncnn" CACHE PATH "staged ncnn tree")
if(FF_NCNN)
    if(NOT EXISTS "${NCNN_DIR}/lib/libncnn.a")
        message(FATAL_ERROR "FF_NCNN=ON but no ${NCNN_DIR}/lib/libncnn.a -- run work/android/stage_ncnn.sh from WSL")
    endif()
    target_sources(ffnative PRIVATE ffnn_ncnn.cpp)
    target_compile_definitions(ffnative PRIVATE FFNN_HAVE_NCNN)
    # ncnn installs headers to <prefix>/include/ncnn/*.h. Add BOTH locations so
    # `#include "net.h"` (ffnn_ncnn.cpp) and `#include "ncnn/platform.h"` (ncnn's
    # own internal includes) both resolve.
    target_include_directories(ffnative PRIVATE "${NCNN_DIR}/include" "${NCNN_DIR}/include/ncnn")
    # libncnn first. Vulkan glslang archives only exist when ncnn was built with
    # NCNN_VULKAN=ON; skip them for the CPU build.
    target_link_libraries(ffnative "${NCNN_DIR}/lib/libncnn.a")
    foreach(_gllib IN ITEMS MachineIndependent GenericCodeGen OSDependent SPIRV glslang)
        if(EXISTS "${NCNN_DIR}/lib/lib${_gllib}.a")
            target_link_libraries(ffnative "${NCNN_DIR}/lib/lib${_gllib}.a")
        endif()
    endforeach()
    # OpenMP: libncnn.a built with NCNN_OPENMP=ON carries __kmpc_* symbols; if we
    # chose NCNN_OPENMP=OFF (as we do in CI) there is no such dependency and libomp.a
    # may be absent. Link it only when present.
    if(EXISTS "${NCNN_DIR}/lib/libomp.a")
        target_link_libraries(ffnative "${NCNN_DIR}/lib/libomp.a")
    endif()
    target_link_libraries(ffnative android)
endif()
