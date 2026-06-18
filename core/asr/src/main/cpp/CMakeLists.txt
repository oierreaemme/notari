cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

# whisper.cpp is ~20x slower without compiler optimization. AGP compiles the debug variant
# with CMAKE_BUILD_TYPE=Debug (no -O), which makes a few seconds of audio take minutes. Force
# -O3 into the debug flags here, BEFORE building the submodule, so the native code is always
# optimized. (Set before add_subdirectory so the whisper/ggml targets inherit it.)
set(CMAKE_C_FLAGS_DEBUG   "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

# Align ELF LOAD segments to 16 KB for Android 15+ / Play Store requirement.
# Must be set BEFORE add_subdirectory so the whisper.cpp / ggml targets inherit the flags.
# Using CMAKE_SHARED_LINKER_FLAGS here (vs ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON which
# requires NDK r27b+) keeps compatibility with NDK r27 (27.0.x) used on CI.
# See https://developer.android.com/guide/practices/page-sizes
set(CMAKE_SHARED_LINKER_FLAGS
    "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)

# Build the vendored whisper.cpp (+ ggml) submodule, lean for Android: no tests, no
# examples, no server, no OpenMP (the NDK toolchain handles threads via std::thread).
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp
)

add_library(whisper_jni SHARED whisper_jni.cpp)

# whisper.cpp exports its public include dir on the `whisper` target, so <whisper.h>
# resolves through the link below.
find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib})
