#!/bin/bash
# Compiles ONNX graphs for the NPU of the phone connected over adb (Qualcomm Snapdragon): reads its chip, then
# converts each graph to 16-bit and builds its context binary for that chip (docs/npu-build.md).
#
#   scripts/npu/compile-for-phone.sh [options] <graph.onnx>...
#     --name <name>    output folder name (default: the first graph's folder name)
#     --soc <SM....>   compile for this chip instead of the phone's (no phone needed)
#     --conv "<args>"  extra converter flags, e.g. "--float_bitwidth 16" for 16-bit inputs and outputs
#
# Output: build/qnn/out-<name>-<soc>/binary/<graph>.bin, one per graph, named after its file.
#
# The graphs must come from the full-precision model: fixed shapes, batch 1, int32 inputs, no int8 quantization (the
# NPU's 16-bit form is made here). CPU copies (*.int8.onnx, *quant*) are refused. All graphs of one run must sit in
# one folder (it is mounted into the build container, with any external weight files beside them).
set -e
repo=$(cd "$(dirname "$0")/../.." && pwd)
q=$repo/build/qnn
name= soc= conv=
while [ $# -gt 0 ]; do
  case $1 in
    --name) name=$2; shift 2 ;;
    --soc) soc=$2; shift 2 ;;
    --conv) conv=$2; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { sed -n '2,16p' "$0"; exit 2; }
fail() { echo "error: $*" >&2; exit 1; }

# The graphs: one folder, full precision.
dir=$(cd "$(dirname "$1")" && pwd)
graphs=()
for f in "$@"; do
  [ -f "$f" ] || fail "$f: no such file"
  [ "$(cd "$(dirname "$f")" && pwd)" = "$dir" ] || fail "all graphs must be in one folder ($dir)"
  case $(basename "$f") in
    *int8*|*quant*|*q8*|*uint8*) fail "$f looks like a CPU-quantized copy: export from the full-precision checkpoint instead" ;;
  esac
  graphs+=("/model/$(basename "$f")")
done
name=${name:-$(basename "$dir")}

# The chip: from the phone unless given.
if [ -z "$soc" ]; then
  command -v adb > /dev/null || fail "adb not found (or pass --soc)"
  soc=$(adb shell getprop ro.soc.model 2>/dev/null | tr -d '\r')
  [ -n "$soc" ] || fail "no phone over adb (connect one, set ANDROID_SERIAL, or pass --soc)"
  maker=$(adb shell getprop ro.soc.manufacturer 2>/dev/null | tr -d '\r')
  echo "== phone: $(adb shell getprop ro.product.model | tr -d '\r'), chip $soc ($maker)"
  case $maker in *[Qq][Tt][Ii]*|*[Qq]ualcomm*) ;; *) fail "$soc is not a Snapdragon: QNN can't target it (see docs/npu-plan.md for other chips)" ;; esac
fi

# The tools, made once (docs/npu-build.md, section 1).
[ -d $q/sdk/qairt/2.40.0.251030 ] || fail "QAIRT SDK missing in build/qnn/sdk: see docs/npu-build.md, section 1"
docker info > /dev/null 2>&1 || fail "Docker isn't running (colima start hexagon)"
if [ ! -f $q/sherpa-onnx/scripts/qnn/generate_config.py ]; then
  git clone -q --depth 1 --branch v2.0.1 https://github.com/k2-fsa/sherpa-onnx.git $q/sherpa-onnx
fi
arch=$(grep -E "SocInfo\(Chipset\.$soc," $q/sherpa-onnx/scripts/qnn/device_info.py | grep -oE "v[0-9]+" | head -1)
[ -n "$arch" ] || fail "$soc isn't in sherpa-onnx's chip list (scripts/qnn/device_info.py): QNN context binaries need its soc id"
[ "$arch" != v68 ] || fail "$soc (Hexagon v68) has no 16-bit float on its NPU"
if ! docker image inspect qairt-build:2.40 > /dev/null 2>&1; then
  echo "== building the qairt-build:2.40 image (once)"
  docker build -q --platform linux/amd64 -t qairt-build:2.40 -f $repo/scripts/npu/Dockerfile $repo/scripts/npu > /dev/null
fi
run() {
  docker run --rm --platform linux/amd64 -u $(id -u):$(id -g) -e HOME=/tmp -e CONV_ARGS="$conv" \
    -v $q:/workspace -v $repo/scripts/npu:/scripts -v "$dir":/model qairt-build:2.40 "$@"
}
[ -f $q/nopi.so ] || run clang -O2 -shared -fPIC -o /workspace/nopi.so /scripts/nopi.c

# Fixed shapes only: the NPU runs static graphs.
run python3 - "${graphs[@]}" <<'PY'
import sys, onnx
bad = []
for path in sys.argv[1:]:
    m = onnx.load(path, load_external_data=False)
    name = path.split('/')[-1]
    for io in list(m.graph.input) + list(m.graph.output):
        free = [d.dim_param or "?" for d in io.type.tensor_type.shape.dim if not d.dim_value]
        if free: bad.append(f"{name}: {io.name} has free dims {free}")
    for io in m.graph.input:
        if io.type.tensor_type.elem_type == onnx.TensorProto.INT64:
            print(f"warning: {name}: input {io.name} is int64 (export with int32 inputs)")
if bad:
    print("error: the NPU needs fixed shapes; export again with them fixed:\n  " + "\n  ".join(bad)); sys.exit(1)
PY

echo "== compiling ${#graphs[@]} graph(s) for $soc (Hexagon $arch) into build/qnn/out-$name-$soc"
run bash /scripts/compile.sh "$soc" "out-$name-$soc" "${graphs[@]}"
A=$(echo $arch | tr v V)
cat <<MSG

Done: build/qnn/out-$name-$soc/binary/
On the phone the app needs Qualcomm's runtime for this chip: libQnnHtp.so, libQnnSystem.so,
libQnnHtp${A}Stub.so and libQnnHtp${A}Skel.so (from qnn-libs-2.40.0.251030; add them to unpackQnnLibs in
androidApp/build.gradle.kts), and the chip "$soc" in the model's SpeechModel.chips.
MSG
