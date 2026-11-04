#!/bin/bash
# ONNX graphs -> QNN context binaries for one Snapdragon chip (docs/npu-build.md). Runs inside qairt-build:2.40 with
# build/qnn mounted at /workspace and this folder at /scripts:
#   compile.sh <soc> <out-name> <onnx...>
#   e.g. compile.sh SM8750 out-zipformer-SM8750 zipformer-export/encoder.onnx zipformer-export/decoder.onnx ...
# Paths are relative to /workspace. Each graph is named after its file (encoder.onnx -> encoder.bin). Activations
# 16-bit, bias 32-bit; extra converter flags in CONV_ARGS (e.g. "--float_bitwidth 16" for Whisper's decoder).
set -e
soc=$1 out=/workspace/$2; shift 2
export QNN_SDK_ROOT=/workspace/sdk/qairt/2.40.0.251030
# Rosetta (Docker on Apple silicon) lacks priority-inheritance mutexes, which QAIRT's logger asks for: scripts/npu/nopi.c.
export LD_PRELOAD=/workspace/nopi.so
source $QNN_SDK_ROOT/bin/envsetup.sh > /dev/null
export PYTHONPATH=$QNN_SDK_ROOT/lib/python:$PYTHONPATH
mkdir -p $out/logs
step() { local log=$out/logs/$1; shift; "$@" > $log 2>&1 || { tail -20 $log; exit 1; }; }
for onnx in "$@"; do
  m=$(basename $onnx .onnx)
  echo "== $m: convert $(date +%T)"
  step $m-converter.txt qnn-onnx-converter --input_network /workspace/$onnx --output_path $out/$m.cpp \
    --act_bitwidth 16 --bias_bitwidth 32 $CONV_ARGS
  echo "== $m: config"
  step $m-config.txt python3 /workspace/sherpa-onnx/scripts/qnn/generate_config.py --soc $soc --graph-name $m \
    --output-dir $out/config-$m --qnn-sdk-root $QNN_SDK_ROOT
  echo "== $m: model lib $(date +%T)"
  step $m-libgen.txt python3 $QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-model-lib-generator -c $out/$m.cpp \
    -b $out/$m.bin -o $out/model_libs -t x86_64-linux-clang
  echo "== $m: context binary $(date +%T)"
  step $m-generator.txt $QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-context-binary-generator \
    --backend $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so \
    --model $out/model_libs/x86_64-linux-clang/lib$m.so \
    --output_dir $out/binary --binary_file $m \
    --config_file $out/config-$m/htp_backend_extensions.json
done
ls -la $out/binary
echo "== done $(date +%T)"
