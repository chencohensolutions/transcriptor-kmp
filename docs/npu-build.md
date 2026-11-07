# Building the NPU models (Qualcomm Snapdragon)

How the app's NPU builds are made, step by step. They are made from the original full-precision checkpoints, and
the NPU's 16-bit form comes from those, never from the 8-bit CPU copies. Why, and the results:
[npu-plan.md](npu-plan.md). The general method for any model: the `qnn-npu-compile` skill.

What gets built, per chip (SM8750 = Snapdragon 8 Elite, Hexagon v79, the only chip so far):

| Model | Graphs | Source checkpoint | App catalog |
|---|---|---|---|
| ivrit.ai Whisper large-v3 turbo | encoder, decoder | `ivrit-ai/whisper-large-v3-turbo` safetensors, commit f33172a8 | `ivritTurboNpu` |
| ivrit.ai Whisper large-v3 (refine only) | encoder, decoder | `ivrit-ai/whisper-large-v3` safetensors (sharded), commit 766847c9; converted with `--no_simplification --float_bitwidth 16` (too large for the S25's memory in the app; a w8a16 build is in progress, section 3) | `ivritLargeV3Npu` |
| ivrit.ai Zipformer2 66M streaming | encoder, decoder, joiner | `ivrit-ai/zipformer2-66m-streaming-he` `model.pt`, commit ee769a2a | `ivritZipformerNpu` |
| WeSpeaker SimAM-ResNet100 (VoxBlink2 + VoxCeleb2; non-commercial) | t150, t300, t600 | WeSpeaker's runtime ONNX `voxblink2_samresnet100_ft` `avg_model.onnx`, sha256 0d92ee34 | none: copied by hand (`voicePrintsSimAmResNet100`) |
| WeSpeaker ResNet293-LM (VoxCeleb) | t150, t300, t600 | sherpa-onnx's `wespeaker_en_voxceleb_resnet293_LM.onnx` | none: copied by hand (`voicePrintsResNet293`) |

Plus one runtime: the sherpa-onnx AAR with our QNN patch, which also runs speaker embeddings on the NPU (any model
compiled as fixed-length graphs, see section 2).

Everything happens in `build/qnn/` (not in git; tens of GB with the SDK and exports). The scripts are in
[scripts/npu/](../scripts/npu/).

## Quick: any model for the connected phone

```bash
scripts/npu/compile-for-phone.sh [--name <name>] [--soc <SM....>] [--conv "<flags>"] <graph.onnx>...
```
[`compile-for-phone.sh`](../scripts/npu/compile-for-phone.sh) does:
1. Reads the chip from the phone over adb (`ro.soc.model`). `--soc` compiles for another chip, with no phone
   needed.
2. Checks that the chip is a Snapdragon with a 16-bit NPU (Hexagon v69 or later).
3. Refuses CPU-quantized copies (`*int8*`, `*quant*`).
4. Checks that every input and output has a fixed shape.
5. Runs `compile.sh` for each graph.

The binaries go to `build/qnn/out-<name>-<soc>/binary/`. It prints which Qualcomm runtime libraries the app needs
for that chip.

It needs the SDK and Docker from section 1. It builds the image and `nopi.so` itself if they are missing. The ONNX
export (section 2) stays per model. Tested Nov 4 with the Zipformer decoder on the S25: 48 s, and identical to the
app's `decoder.bin`.

## 1. Tools (once)

- **Docker on the Mac:** Colima with an Apple-silicon VM that runs x86-64 containers through Rosetta, because QAIRT
  is Linux x86-64 only:
  ```bash
  colima start hexagon --vm-type vz --vz-rosetta --arch aarch64 --cpu 8 --memory 22 --disk 60
  docker context use colima-hexagon
  ```
  The Whisper encoder's context-binary step needs about 20 GB of memory in the VM.
- **The QAIRT SDK, version 2.40.0.251030:** the same version as the runtime libraries the app ships. Get it from
  Qualcomm (free account), or as the copy sherpa-onnx's CI uses:
  ```bash
  mkdir -p build/qnn/sdk && cd build/qnn/sdk
  curl -sL -o qairt.zip https://huggingface.co/csukuangfj/qnn-toolkit/resolve/main/v2.40.0.251030.zip && unzip -q qairt.zip
  ```
  Its license limits redistribution: check it before publishing anything built with it.
- **The build image, and the Rosetta shim:**
  ```bash
  cd build/qnn
  docker build --platform linux/amd64 -t qairt-build:2.40 -f ../../scripts/npu/Dockerfile ../../scripts/npu
  docker run --rm --platform linux/amd64 -v $PWD:/workspace -v $PWD/../../scripts/npu:/scripts qairt-build:2.40 \
    clang -O2 -shared -fPIC -o /workspace/nopi.so /scripts/nopi.c
  ```
  `nopi.so` exists because Rosetta has no priority-inheritance mutexes, and QAIRT's logger asks for one. Without it
  every QAIRT tool fails on start.
- **sherpa-onnx v2.0.1 with our patch:** clone it into `build/qnn/sherpa-onnx`. `build-sherpa.sh` (step 4) does this
  when the folder is missing. `compile.sh` uses its `scripts/qnn/generate_config.py`.
- **A Python 3.10 venv for the Whisper export, on the Mac:**
  ```bash
  uv venv --python 3.10 build/qnn/venv-export
  VIRTUAL_ENV=build/qnn/venv-export uv pip install "torch==2.6.0" "numpy<2" openai-whisper onnx==1.17.0 onnxruntime onnxsim safetensors
  ```

## 2. Export ONNX from the full-precision checkpoint

Every graph needs fixed shapes, batch 1, int32 inputs (no int64) and **no int8 quantization**: QNN does the 16-bit
quantization in step 3.

**Whisper turbo:**
```bash
cd build/qnn
R=https://huggingface.co/ivrit-ai/whisper-large-v3-turbo/resolve/f33172a8c3c6efbc040a7200e00835257cac0447
mkdir -p ivrit-turbo && for f in config.json model.safetensors; do curl -sL -o ivrit-turbo/$f $R/$f; done
venv-export/bin/python ../../scripts/npu/hf_to_openai.py ivrit-turbo ivrit-turbo.pt   # Transformers -> openai-whisper
mkdir -p scripts && cp sherpa-onnx/scripts/whisper/qnn/export_onnx.py scripts/
patch -p1 -d . < ../../scripts/npu/export_onnx.patch                                 # load the .pt from WHISPER_CKPT
mkdir -p export && cd export && WHISPER_CKPT=../ivrit-turbo.pt ../venv-export/bin/python ../scripts/export_onnx.py --model turbo
```
The output is `export/turbo-encoder.onnx` (its weights in separate files beside it), `turbo-decoder.onnx` and
`turbo-tokens.txt`. Check the ONNX on the CPU against the PyTorch model on a few Hebrew clips: it should give the
same text.

**Zipformer:** icefall's exporter, run in icefall's own CPU image. It is downloaded code, so the owner approves the
run.
```bash
mkdir -p build/zipformer/ivrit && cd build/zipformer
R=https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he/resolve/ee769a2a13b887f91572eec349458bfb0fe79e7a
(cd ivrit && for f in model.pt bpe.model sherpa-onnx/tokens.txt; do curl -sSL -o $(basename $f) $R/$f; done && ln -sf model.pt epoch-99.pt)
git clone -q --depth 1 https://github.com/k2-fsa/icefall.git   # used: commit 9c96676
docker run --rm --platform linux/amd64 -v $PWD/icefall:/icefall -v $PWD/ivrit:/exp \
  ghcr.io/k2-fsa/icefall:cpu-py3.10-torch2.6.0-v20250630 bash -c '
export PYTHONPATH=/icefall:$PYTHONPATH; cd /icefall/egs/librispeech/ASR
./zipformer/export-onnx-streaming.py --use-int32-inputs 1 --dynamic-batch 0 --enable-int8-quantization 0 \
  --epoch 99 --avg 1 --use-averaged-model 0 --exp-dir /exp --tokens /exp/tokens.txt \
  --num-encoder-layers 2,2,3,4,3,2 --feedforward-dim 512,768,1024,1536,1024,768 \
  --encoder-dim 192,256,384,512,384,256 --encoder-unmasked-dim 192,192,256,256,256,192 \
  --causal 1 --chunk-size 64 --left-context-frames 256'
mkdir -p ../qnn/zipformer-export
for m in encoder decoder joiner; do cp ivrit/$m-epoch-99-avg-1-chunk-64-left-256.onnx ../qnn/zipformer-export/$m.onnx; done
```
The sizes come from the model card. Chunk 64 is 1.28 s of audio, with 256 frames of left context.

**Voice-print models (WeSpeaker):** from the full-precision ONNX, three batch-1 graphs of 150, 300 and 600 frames
(1.5, 3 and 6 s of 80-dim fbank). Each has the per-utterance mean normalization WeSpeaker trains with put in front
(`ReduceMean` over time, then `Sub`), and is simplified with onnxsim.
- **Script:** [`voiceprint_graphs.py`](../scripts/npu/voiceprint_graphs.py)` <model.onnx> <out dir> <name>`; it needs
  onnx and onnxsim.
- **Check:** each graph against the original on the CPU, on lecture clips. It should give cosine 1.0 (`check_cpu.py`).
- **Compile:**
  `scripts/npu/compile-for-phone.sh --soc SM8750 --name samresnet100 <dir>/samresnet100_t150.onnx …_t300.onnx …_t600.onnx`,
  then copy the binaries as `t150.bin`, `t300.bin` and `t600.bin` into the model's folder on the phone.
- **Input layout:** QNN's converter makes the input [1, 80, T] (mel bins first). sherpa-onnx's QNN extractor (our
  patch) feeds it that way.
- **Time:** each graph takes about 12 minutes to compile.

## 3. Compile for the chip

[`compile.sh`](../scripts/npu/compile.sh) runs, for each graph:
1. The converter: ONNX to a QNN graph, with 16-bit activations and 32-bit bias.
2. The chip's HTP config.
3. The x86-64 model library.
4. The context binary, which is what the phone loads.

The logs are in `<out>/logs/`. Read the converter logs for unsupported ops and for operations that fall back to
the CPU.

```bash
cd build/qnn
run() { docker run --rm --platform linux/amd64 -u $(id -u):$(id -g) -e HOME=/tmp -e CONV_ARGS="$CONV_ARGS" \
  -v $PWD:/workspace -v $PWD/../../scripts/npu:/scripts qairt-build:2.40 bash /scripts/compile.sh "$@"; }
for m in encoder decoder; do ln -sf turbo-$m.onnx export/$m.onnx; done   # graph names; weights stay beside them
CONV_ARGS= run SM8750 out-SM8750 export/encoder.onnx
CONV_ARGS="--float_bitwidth 16" run SM8750 out-SM8750 export/decoder.onnx
CONV_ARGS= run SM8750 out-zipformer-SM8750 zipformer-export/encoder.onnx zipformer-export/decoder.onnx zipformer-export/joiner.onnx
```
- Each graph is named after its file. The links make the Whisper graphs `encoder` and `decoder`, as the app's build
  has them, and keep the encoder's separate weight files beside the ONNX that refers to them.
- The decoder gets 16-bit inputs and outputs (`--float_bitwidth 16`) so its attention caches stay 16-bit.
- For another chip, pass its SoC name: the list is in `sherpa-onnx/scripts/qnn/device_info.py`. Then add the chip to
  `SpeechModel.chips` and the matching `libQnnHtpV<arch>Skel.so`/`Stub.so` to `unpackQnnLibs` in
  `androidApp/build.gradle.kts`.

Results for SM8750: Whisper encoder.bin 1.35 GB, decoder.bin 453 MB. Zipformer encoder 155 MB, decoder 2.2 MB,
joiner 1.6 MB.

### w8a16: 8-bit weights, 16-bit activations (Whisper large-v3)

Without calibration data the converter can't quantize, so the builds above stay float (16-bit with
`--float_bitwidth 16`). For a model too large for the phone's memory as float, `CALIB=<folder>` quantizes it: 8-bit
per-channel weights, every activation 16-bit, attention's run-time MatMul inputs too (`--use_dynamic_16_bit_weights`, a
hidden flag; without it the K and V caches go 8-bit). The float inputs and outputs stay float16, as the float builds have
them, so sherpa-onnx's runtime reads both alike (its in-place self-attention cache needs float16).
`compile-for-phone.sh --calib <folder>` does the same.

1. **Calibration data:** public speech only, not the owner's recordings (owner's rule, Nov 7, 2026). For Hebrew:
   Google's [FLEURS](https://huggingface.co/datasets/google/fleurs) `he_il` dev and test (commit 70bb2e84, CC-BY-4.0, not
   gated), its sentences joined into one long recording per split with 0.3 s gaps (they are float WAVs: decode with
   ffmpeg). ivrit.ai's datasets are gated and their license allows only training models or research.
2. **Calibration inputs:** [`whisper_calib.py`](../scripts/npu/whisper_calib.py) cuts the audio into 100 windows of
   30 s (log-mel, 128 bins), runs the ONNX encoder and decoder on the CPU (greedy, Hebrew, transcribe) over 25 of them
   and keeps the decoder's real inputs at 8 steps each: 200 samples. The raw files are in the converted graph's layout
   (mel `(3000, 128)`, caches `(1280, ctx)`), int32 inputs as int32, and the lists are named after the graphs.
3. **The decoder graph, rewritten first:** [`whisper_decoder_w8a16.py`](../scripts/npu/whisper_decoder_w8a16.py). The
   export's masked-score fill, -60000, becomes -100 (quantized, the scores shared a range with -60000, a step of 0.92,
   and the text came out empty), and each layer's current-token score MatMul becomes Mul and ReduceSum (QAIRT 2.40
   cuts a MatMul's run-time second input to 8 bits when its calibrated range is exactly symmetric, and the HTP then
   can't build it). The float model's text is unchanged.
4. **Compile:**
   ```bash
   cd build/qnn
   mkdir -p export-large-w8 && ln -sf ../export-large/large-v3-decoder.weights export-large-w8/
   venv-export/bin/python ../../scripts/npu/whisper_decoder_w8a16.py export-large/large-v3-decoder.onnx \
     export-large-w8/large-v3-decoder.onnx && ln -sf large-v3-decoder.onnx export-large-w8/decoder.onnx
   venv-export/bin/python ../../scripts/npu/whisper_calib.py --export export-large --prefix large-v3 \
     --out calib-large-fleurs calib-large-fleurs/fleurs-he-dev.wav calib-large-fleurs/fleurs-he-test.wav
   runq() { docker run --rm --platform linux/amd64 -u $(id -u):$(id -g) -e HOME=/tmp -e CALIB=/workspace/calib-large-fleurs \
     -e CONV_ARGS="$CONV_ARGS" -v $PWD:/workspace -v $PWD/../../scripts/npu:/scripts qairt-build:2.40 bash /scripts/compile.sh "$@"; }
   CONV_ARGS="--no_simplification --float_bitwidth 16" runq SM8750 out-large-w8a16-fleurs-SM8750 export-large/encoder.onnx
   CONV_ARGS="--float_bitwidth 16" runq SM8750 out-large-w8a16-fleurs-SM8750 export-large-w8/decoder.onnx
   ```
   `compile.sh` stops right after the converter if any activation came out 8-bit.
5. **Check on the Mac before the phone:** `qnn-net-run` with the x86 `libQnnHtp.so` and `--retrieve_context` runs a
   context binary in Qualcomm's simulator (about 1.5 min per decoder step). Give int32 inputs as float32 files (with
   `--native_input_tensor_names` it fails with "Unable to find composed graph"), name every input (`name:=path`; the
   compiled graph orders them differently from the ONNX), and compare the logits with onnxruntime's.

Time and memory, under Rosetta: the encoder's calibration about 2 min per window (3.5 h), its context binary 45 min
but over 22 GB (the VM's out-of-memory killer stopped it twice; a 24 GB swap file inside the VM got it through); the
decoder 25 min, then 10.

**Status (Nov 7, 2026): not working on the phone yet.** Both graphs match the float model in the simulator, and the
w8a16 encoder with the 16-bit decoder gives good text on the phone, but the w8a16 decoder's text on the phone is empty
or looping. Loading the pair (1.95 GB of files) took about 5 GB of the phone's memory at its peak. Details and what to
try next: [npu-plan.md](npu-plan.md).

## 4. The sherpa-onnx AAR

```bash
scripts/npu/build-sherpa.sh
```
This does:
1. Clones sherpa-onnx v2.0.1 and applies
   [sherpa-onnx-v2.0.1-qnn.patch](../scripts/npu/sherpa-onnx-v2.0.1-qnn.patch).
2. Builds the arm64 library with QNN, using the app's NDK.
3. Puts it in sherpa-onnx's AAR project beside the release's libraries for the other ABIs.
4. Copies the AAR to `androidApp/libs/` and prints its sha256. Put the sha256 in `androidApp/build.gradle.kts`.

What the patch adds to v2.0.1:
- Upstream #4126: Whisper's `qnnConfig` in Kotlin and the JNI, plus `QnnConfig.htp_perf_mode`.
- Whisper turbo: its task tokens, and the `turbo` model type.
- Decoder speed: the cross-attention cache is set once, and the self-attention cache is written in place. Both are
  on unless `SHERPA_ONNX_QNN_CROSS_ONCE=0` or `SHERPA_ONNX_QNN_SELF_INPLACE=0`.
- QNN errors throw, and the JNI turns them into Java exceptions, instead of `_Exit` closing the app.
- QNN's log goes to logcat with tag `sherpa-onnx-qnn`.
- The streaming decode JNI call is wrapped in `SafeJNI`.
- Speaker embeddings on the NPU: `SpeakerEmbeddingExtractorConfig.qnnConfig` (Kotlin, JNI, C++) with the model's
  fixed-length context binaries, comma separated (`csrc/qnn/speaker-embedding-extractor-qnn-impl.*`).
  - **Shorter than the smallest graph:** the frames repeat.
  - **Longer:** windows of the largest graph that fits, spread evenly, their prints averaged.
  - **Speaker pass:** sherpa-onnx's speaker diarization takes it too.
  - **Errors:** extractor and diarization errors throw Java exceptions instead of crashing.
- pyannote's segmentation alone, one window at a time, for live speaker turns: `SpeakerSegmentation` (Kotlin, in
  `OfflineSpeakerDiarization.kt`) over `csrc/speaker-segmentation-window.*`.
  - **Where it runs:** on the CPU (ONNX Runtime), or on the NPU given a context binary compiled for the 10 s window
    (`[1, 1, 160000]`).
  - **Output:** each frame's powerset log-probabilities.

Qualcomm's runtime libraries aren't in the AAR. Gradle takes them from sherpa-onnx's `qnn-libs-2.40.0.251030`
(pinned in `androidApp/build.gradle.kts`).

## 5. Check on the phone, then put the model in the app

- **Check first, from `adb shell`:** run sherpa-onnx's CLI from `build/qnn/sherpa-onnx/build-android-arm64-v8a/install/bin`.
  - For Whisper: `sherpa-onnx-offline`.
  - For the Zipformer: `sherpa-onnx` with `--provider=qnn --transducer.qnn-backend-lib=… --transducer.qnn-system-lib=…
    --transducer.qnn-context-binary=encoder.bin,decoder.bin,joiner.bin`.
  - Put the QNN libraries beside it and set `ADSP_LIBRARY_PATH` to their folder.
  - Compare the text with the CPU model on the owner's recordings. Measure time and energy as in npu-plan.md, with
    the screen on, the phone untouched, and the phone at 37 °C or below before each run.
- **In the catalog:** add the files' sha256 and sizes to `ModelCatalog` in `SpeechModel.kt`, with the chip in
  `chips`.
- **Until the models are on Hugging Face:** copy them by hand to
  `/sdcard/Android/data/com.chencohensolution.transcriptor/files/models/<model id>/`, and add an empty `.ok` file.
  The app treats a folder with `.ok` as downloaded.
- **Publishing:** the models and the AAR go to a Hugging Face repo of the owner's. Ask before each upload. Then the
  `HF_USER`/`COMMIT` placeholders in `SpeechModel.kt` and `build.gradle.kts` get the real repo and commit.

## Pitfalls met

- **The NPU fails only inside the app** (`deviceCreate` 14001): the manifest is missing
  `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`.
- **The NPU can't load the Skel library:** native libraries must be unpacked on install
  (`useLegacyPackaging = true`), and `ADSP_LIBRARY_PATH` must start with `nativeLibraryDir`
  (`SherpaNpuWhisperEngine.useNpuLibraries`).
- **The Skel isn't an ARM library:** keep it out of stripping (`keepDebugSymbols += "**/libQnnHtpV*Skel.so"`).
- **Context binaries are tied to the SDK version:** compile with the same QAIRT version as the runtime libraries.
- **Small streaming models can be slower on the NPU:** the Zipformer took 2.2 s per 30 s of audio, against 0.98 s
  for int8 on the CPU. Its decoder and joiner are called every 40 ms, and each call pays the NPU's round trip.
  Measure before choosing the NPU.
- **Energy tests must run with the screen on.** With the screen off, jobs started over ADB get killed. `pkill -f`
  inside `adb shell` can kill its own shell.
