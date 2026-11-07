#!/bin/bash
# ONNX graphs -> QNN context binaries for one Snapdragon chip (docs/npu-build.md). Runs inside qairt-build:2.40 with
# build/qnn mounted at /workspace and this folder at /scripts:
#   compile.sh <soc> <out-name> <onnx...>
#   e.g. compile.sh SM8750 out-zipformer-SM8750 zipformer-export/encoder.onnx zipformer-export/decoder.onnx ...
# Paths are relative to /workspace, or absolute inside the container. Each graph is named after its file (encoder.onnx -> encoder.bin). Activations
# 16-bit, bias 32-bit; extra converter flags in CONV_ARGS (e.g. "--float_bitwidth 16" for Whisper's decoder).
#
# Without CALIB there is no calibration data, so the graph stays float (16-bit with --float_bitwidth 16). With
# CALIB=<folder> (inside the container) it is quantized w8a16: 8-bit per-channel weights, all activations 16-bit, calibrated
# on <folder>/<graph>.txt (one line per sample, its raw files in the graph's input order, paths relative to <folder>;
# int32 inputs as int32, the rest float32, in the converted graph's layout; e.g. scripts/npu/whisper_calib.py). The
# graph's float inputs and outputs stay float16, as the 16-bit builds have them, so the runtime reads both alike.
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
  case $onnx in /*) in=$onnx ;; *) in=/workspace/$onnx ;; esac
  quant=()
  if [ -n "$CALIB" ]; then
    [ -f $CALIB/$m.txt ] || { echo "error: no calibration list $CALIB/$m.txt"; exit 1; }
    awk -v d=$CALIB '{for (i = 1; i <= NF; i++) $i = d "/" $i; print}' $CALIB/$m.txt > $out/calib-$m.txt
    python3 - $in $out/custom-io-$m.yaml <<'PY'
import sys, onnx
m = onnx.load(sys.argv[1], load_external_data=False)
with open(sys.argv[2], "w") as f:
    for io in list(m.graph.input) + list(m.graph.output):
        if io.type.tensor_type.elem_type == onnx.TensorProto.FLOAT:
            f.write(f"- IOName: {io.name}\n  Datatype: float16\n")
PY
    # --use_dynamic_16_bit_weights (a hidden converter flag): a MatMul's second input computed at run time (attention's
    # K and V) stays 16-bit; without it the converter cuts each one to 8 bits.
    quant=(--input_list $out/calib-$m.txt --use_native_input_files --weights_bitwidth 8 --use_per_channel_quantization
      --use_dynamic_16_bit_weights --custom_io $out/custom-io-$m.yaml)
    echo "== $m: w8a16, calibrated on $(wc -l < $out/calib-$m.txt) samples"
  fi
  echo "== $m: convert $(date +%T)"
  step $m-converter.txt qnn-onnx-converter --input_network $in --output_path $out/$m.cpp \
    --act_bitwidth 16 --bias_bitwidth 32 "${quant[@]}" $CONV_ARGS
  if [ -n "$CALIB" ]; then
    # Every activation should be 16-bit. The converter sometimes still makes an 8-bit copy of a run-time MatMul input
    # (seen when its calibrated range came out exactly symmetric), which the HTP then can't build ("could not create
    # op"). Nudge that tensor's range with --quantization_overrides (docs/npu-build.md).
    python3 - $out/${m}_net.json <<'PY' || exit 1
import json, sys
t = json.load(open(sys.argv[1]))["graph"]["tensors"]
bad = [n for n, v in t.items() if v["type"] == 3 and v["data_type"] in (0x308, 0x408)]
if bad:
    sys.exit("error: 8-bit activations in a w8a16 graph: " + ", ".join(bad[:5]))
PY
  fi
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
