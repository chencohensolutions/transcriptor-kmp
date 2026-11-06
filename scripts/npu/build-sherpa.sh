#!/bin/bash
# sherpa-onnx v2.0.1 with our QNN patch -> androidApp/libs/sherpa-onnx-2.0.1-qnn.aar (docs/npu-build.md), on the Mac.
# Needs build/qnn/sdk (QAIRT 2.40.0.251030) and the Android SDK's NDK and cmake. Prints the AAR's sha256 for
# androidApp/build.gradle.kts.
set -e
repo=$(cd "$(dirname "$0")/../.." && pwd)
q=$repo/build/qnn
sdk=$HOME/Library/Android/sdk
if [ ! -d $q/sherpa-onnx ]; then
  git clone -q --depth 1 --branch v2.0.1 https://github.com/k2-fsa/sherpa-onnx.git $q/sherpa-onnx
  git -C $q/sherpa-onnx apply $repo/scripts/npu/sherpa-onnx-v2.0.1-qnn.patch
fi
cd $q/sherpa-onnx
if [ ! -d ort/jni ]; then
  mkdir -p ort
  curl -sL --retry 4 -o ort/ort.zip https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip
  (cd ort && unzip -q -o ort.zip)
fi
export PATH=$(ls -d $sdk/cmake/*/bin | tail -1):$PATH ANDROID_NDK=$sdk/ndk/30.0.16248370 \
  QNN_SDK_ROOT=$q/sdk/qairt/2.40.0.251030 SHERPA_ONNX_ONNXRUNTIME_ROOT=$PWD/ort SHERPA_ONNX_ENABLE_QNN=ON \
  SHERPA_ONNX_ENABLE_BINARY=ON SHERPA_ONNX_ENABLE_TTS=OFF SHERPA_ONNX_ENABLE_JNI=ON SHERPA_ONNX_ENABLE_C_API=OFF \
  SHERPA_ONNX_ANDROID_PLATFORM=android-31 CMAKE_BUILD_PARALLEL_LEVEL=10
./build-android-arm64-v8a.sh > build.log 2>&1 || { tail -20 build.log; exit 1; }
# The AAR project takes the release's JNI libraries for every ABI; ours replaces arm64's.
aar=android/SherpaOnnxAar
if [ ! -f $aar/sherpa_onnx/src/main/jniLibs/arm64-v8a/libonnxruntime.so ]; then  # a fresh clone has the folders (.gitkeep), not the libraries
  rel=$q/sherpa-onnx-2.0.1.aar
  curl -sL --retry 4 -o $rel https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
  echo "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  $rel" | shasum -a 256 -c -
  rm -rf $q/release-aar && unzip -q $rel 'jni/*' -d $q/release-aar
  mkdir -p $aar/sherpa_onnx/src/main/jniLibs && cp -R $q/release-aar/jni/* $aar/sherpa_onnx/src/main/jniLibs/
fi
$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip --strip-unneeded \
  -o $aar/sherpa_onnx/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so
(cd $aar && JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home} ./gradlew -q :sherpa_onnx:assembleRelease)
cp $aar/sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar $repo/androidApp/libs/sherpa-onnx-2.0.1-qnn.aar
shasum -a 256 $repo/androidApp/libs/sherpa-onnx-2.0.1-qnn.aar
