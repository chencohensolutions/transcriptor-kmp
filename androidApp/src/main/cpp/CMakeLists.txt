# whisper.cpp (third_party/whisper.cpp, a submodule pinned to a release) built into one library with our JNI bridge.
cmake_minimum_required(VERSION 3.22)
project(transcriptor_whisper C CXX)

# Always optimized: a debug build of ggml is far too slow to transcribe live.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# The pinned submodule, copied into the build folder with our patch applied (the submodule stays clean):
# third_party/whisper.cpp-external-encoder.patch lets an encoder elsewhere (the NPU) give the cross-attention K/V.
set(WHISPER_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(WHISPER_PATCH ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp-external-encoder.patch)
set(WHISPER_DIR ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp-patched)
file(SHA256 ${WHISPER_PATCH} WHISPER_PATCH_HASH)
execute_process(COMMAND git -C ${WHISPER_SRC} rev-parse HEAD OUTPUT_VARIABLE WHISPER_REV OUTPUT_STRIP_TRAILING_WHITESPACE)
set(WHISPER_STAMP "${WHISPER_REV} ${WHISPER_PATCH_HASH}")
if(EXISTS ${WHISPER_DIR}/.patched)
  file(READ ${WHISPER_DIR}/.patched WHISPER_DONE)
endif()
if(NOT "${WHISPER_DONE}" STREQUAL "${WHISPER_STAMP}")
  file(REMOVE_RECURSE ${WHISPER_DIR})
  file(COPY ${WHISPER_SRC}/ DESTINATION ${WHISPER_DIR} PATTERN .git EXCLUDE)
  execute_process(COMMAND patch -p1 -s -i ${WHISPER_PATCH} WORKING_DIRECTORY ${WHISPER_DIR} RESULT_VARIABLE WHISPER_APPLIED)
  if(NOT WHISPER_APPLIED EQUAL 0)
    message(FATAL_ERROR "whisper.cpp: ${WHISPER_PATCH} doesn't apply")
  endif()
  file(WRITE ${WHISPER_DIR}/.patched "${WHISPER_STAMP}")
endif()
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
target_link_libraries(transcriptor_whisper whisper common log dl)
target_compile_options(transcriptor_whisper PRIVATE -O3)
