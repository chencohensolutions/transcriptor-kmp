# whisper.cpp (third_party/whisper.cpp, a submodule pinned to a release) built into one library with our JNI bridge.
cmake_minimum_required(VERSION 3.22)
project(transcriptor_whisper C CXX)

# Always optimized: a debug build of ggml is far too slow to transcribe live.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The examples' `common` library has the quantizer; only what we link is built.
set(WHISPER_BUILD_EXAMPLES ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Every phone that runs Android 15 (our minSdk) has ARMv8.2 with half-precision math and dot products.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

# whisper.cpp's quantize tool, its main() renamed, to shrink a downloaded f16 model on the phone (ModelFiles.kt).
set(QUANTIZE ${WHISPER_DIR}/examples/quantize/quantize.cpp)
set_source_files_properties(${QUANTIZE} PROPERTIES COMPILE_DEFINITIONS main=whisper_quantize_main)

add_library(transcriptor_whisper SHARED whisper_jni.cpp ${QUANTIZE})
target_include_directories(transcriptor_whisper PRIVATE ${WHISPER_DIR}/examples)
target_link_libraries(transcriptor_whisper whisper common log)
target_compile_options(transcriptor_whisper PRIVATE -O3)
