# Plan: the phone's NPU (Qualcomm Hexagon)

Goal (owner, Nov 2, 2026): less power and more speed by running the speech models on the NPU of Snapdragon phones,
first the owner's Galaxy S25 (Snapdragon 8 Elite, **SM8750**, Hexagon **v79**), with the CPU path kept for every other
phone. The owner asked for **the larger Hebrew model** on the NPU. To be run in its own session.

**How to build the NPU models and the AAR, step by step: [npu-build.md](npu-build.md)** (scripts in
`scripts/npu/`). The general method for any model is the user skill **`qnn-npu-compile`**. This file is the plan and
the results.

## What is known (Nov 2, 2026)

- sherpa-onnx (already in the app, 2.0.1) runs models on the NPU through QNN: `QnnConfig(backendLib, contextBinary,
  systemLib)` on a model config. It publishes NPU builds of its own models for SM8750, including Whisper tiny to
  **medium** and streaming Zipformer transducers, and the QNN runtime libraries (`qnn-libs-2.40.0.251030`).
- It publishes **no** NPU build of ivrit.ai's models: we compile them. Its CI shows how
  (`export-streaming-zipformer-transducer-to-qnn.yaml`, `export-x-asr-qnn.yaml` for Zipformer2,
  `export-whisper-qnn.yaml`).
- Its Whisper builds stop at medium (769M): large-v3 (1.55B) and turbo (809M, but large-v3's 32-layer encoder) are
  not built there, most likely for the NPU's size limits. **That is the unproven part of this plan.**
- The QNN SDK runs on Linux x86-64 (or Windows), not on the Mac: a Linux machine, or Docker with
  `--platform linux/amd64` on the Mac (slow).

## Order

### 0. Measure (on the S25, before compiling anything)

Per model, CPU % (process and per thread), wall time, and battery over a fixed 30-minute task, screen off:
- live: ivrit.ai Zipformer streaming (2 threads) and Whisper turbo q8_0 (6 threads);
- after the lecture: the speaker pass (segmentation 3.0 + CAM++), and Whisper large-v3 q8_0 over 30 s windows
  (the refine pass, not built yet: measure with whisper.cpp's CLI on a recording).

The order of the next steps follows these numbers: the NPU goes where the cost is.

### 1. Tools

QAIRT 2.40.0.251030 (same version as sherpa-onnx's runtime libraries), Python 3.10 venv, NDK 30 (the app's), icefall
(for Zipformer2), openai-whisper and sherpa-onnx's `scripts/whisper/qnn/` (for Whisper). See the skill, section 1.

### 2. The larger Hebrew model: ivrit.ai Whisper (owner's request)

Where it runs in the app: the **refine pass after the lecture** (Whisper over the recording in 30 s windows, the
encoder's fixed input). The NPU fits it: static 30 s windows, big steady encoder work. Live stays on the Zipformer.

1. **Turbo first** (`ivrit-ai/whisper-large-v3-turbo`, 809M: large-v3's encoder, a 4-layer decoder), then large-v3
   (`ivrit-ai/whisper-large-v3`, 1.55B).
2. Weights are Hugging Face Transformers safetensors: convert to openai-whisper's checkpoint format (the key names
   map one to one), so sherpa-onnx's `scripts/whisper/qnn/export_onnx.py` can export it (encoder and decoder as
   separate ONNX graphs, fixed shapes). Check the ONNX on CPU against the original: same Hebrew text on the test clips.
3. Compile for SM8750 with fp16 activations. If the encoder is too large for one context: **8-bit weights, 16-bit
   activations (w8a16)** with a calibration list of about 100 real 30 s Hebrew windows from the owner's recordings,
   and/or split the encoder into two graphs (layers 1–16, 17–32).
4. Acceptance, on 20+ clips of the owner's recordings: word error rate within 0.5 points of large-v3 q8_0 on the CPU
   (whisper.cpp), and faster and cheaper in battery than it. If large-v3 can't fit, record why; turbo on the NPU
   may still beat large-v3 on the CPU for the refine pass.

### 3. The live model: ivrit.ai Zipformer streaming

`ivrit-ai/zipformer2-66m-streaming-he` (CC-BY-4.0): its repo has the icefall checkpoint (`model.pt`, config and
tokenizer inside) and `bpe.model`. Export with icefall's `zipformer/export-onnx-streaming.py`, the model card's
settings (encoder layers 2,2,3,4,3,2; feedforward 512,768,1024,1536,1024,768; encoder dims 192,256,384,512,384,256;
unmasked 192,192,256,256,256,192; causal, chunk 64 = 1.28 s, left context 256; decoder/joiner 512, context 2), plus
`--dynamic-batch 0 --enable-int8-quantization 0 --use-int32-inputs 1`. Then encoder, decoder and joiner each through
the skill's section 3 for SM8750. Acceptance: the same text as the CPU int8 model on the owner's recordings, lower
CPU and battery. Worth it only if step 0 shows the live model costs enough.

### 4. Speaker models (only if step 0 points there)

pyannote segmentation 3.0 and CAM++ are small; check whether sherpa-onnx's speaker diarization accepts a QNN
provider before spending time.

**Done for the larger voice-print models (Nov 6, 2026).** sherpa-onnx had no QNN speaker path. Our patch adds one
(`SpeakerEmbeddingExtractorQnnImpl`): fixed-length graphs, any length covered by windows. The extractor and the
speaker pass both use it.
- **SimAM-ResNet100 on the S25's NPU:** 16 ms per 1.5 s print, 29 ms per 3 s, 59 ms per 6 s. Its prints match the
  CPU model (cosine ≥ 0.99996).
- **On the CPU (Mac, one thread):** about 0.3 s per second of audio.
- **CAM++ and segmentation** stay on the CPU: they are small (CAM++ 0.02 s per second of audio).

See design.md (Speakers) and npu-build.md.

### 5. In the app

- Choose per phone: `ro.soc.model` = SM8750 and the QNN libraries load → NPU build; otherwise the CPU models as
  today. A setting to force the CPU, for comparison.
- Ship the QNN runtime libraries for arm64 (`libQnnHtp.so`, `libQnnHtpV79Skel.so`, its stub, `libQnnSystem.so`) and
  point the DSP at them (`OnlineRecognizer.prependAdspLibraryPath`).
- Add a `kind` for NPU builds to `ModelCatalog` (context binaries per chip), downloaded like the other models.
- Whisper on the NPU runs through sherpa-onnx (its offline Whisper recognizer with `QnnConfig`), not whisper.cpp.

## Decisions for the owner

1. **Where the compiled files live.** The rule so far: models are public files from their publishers, nothing hosted
   by us. NPU builds of ivrit.ai's models exist nowhere: we would have to publish them (a Hugging Face repo of the
   owner's, with the licenses' credit: Apache-2.0 for Whisper, CC-BY-4.0 for the Zipformer), or ask ivrit.ai to.
2. **The QNN runtime libraries' license** (Qualcomm) for redistribution in the app: check before shipping.
3. **A Linux x86-64 machine** for the SDK, or Docker emulation on the Mac.

## Built (Nov 3–4, 2026)

**Measured** on the S25, ivrit.ai Whisper turbo, both graphs on the NPU (16-bit decoder) against whisper.cpp q8_0 on
6 CPU threads, over a 41-minute conversation in 30 s pieces, screen on and the phone untouched, idle current
subtracted: the NPU took **8.0 s and 9.2 J per piece** (670 s, 776 J in all, never above 36.4 °C); on the first 12
pieces, where both ran, the CPU took 2.2× the time and **5× the energy** (575 J against 114 J) and warmed the phone
to 39 °C. Text: greedy decoding without whisper.cpp's fallback, so 5 of 84 pieces repeat a phrase to the 180-token
limit; to fix next (beam search and a fallback on repetition, in sherpa-onnx's QNN Whisper decoder).

**In the app** (step 5): `ModelKind.WhisperNpu` and `SpeechModel.chips`; `ModelCatalog.ivritTurboNpu` (SM8750 only,
first for Hebrew on that chip) runs on `SherpaNpuWhisperEngine` for live lines and the refine pass. Gradle takes
Qualcomm's runtime from sherpa-onnx's `qnn-libs-2.40.0.251030` (pinned) and packs the Hexagon v79 libraries
unpacked into the APK; the DSP finds its Skel through `ADSP_LIBRARY_PATH`.

**Large-v3** (Nov 5, 2026): `ModelCatalog.ivritLargeV3Npu`, refine pass only (`fitsLive = false`). ivrit.ai
large-v3 (commit 766847c9, sharded safetensors; `hf_to_openai.py` reads the index) exported as turbo was; the ONNX
gives the same Hebrew text as PyTorch on a 30 s clip. Two compile fixes for its 2.97 GB encoder: `--no_simplification`
(the simplifier breaks over 2 GB, leaving `Erf` the converter can't take) and `--float_bitwidth 16` (at 32-bit the
model library is over 2 GB: "relocation truncated to fit"). encoder.bin 1.53 GB, decoder.bin 1.75 GB, built in
`build/qnn/out-large-SM8750/binary/`, on the phone in `…/models/ivrit-whisper-large-v3-npu-sm8750/`. Not timed or
checked against the acceptance bar (step 4) yet.

**Not published yet** (owner's decision: on the owner's Hugging Face; the Zipformer's NPU build is published, see
below): the three model files (encoder.bin,
decoder.bin, tokens.txt; `ModelCatalog` has their sha256) and the sherpa-onnx AAR. Until then both are built here
and put in place by hand (the procedure: [npu-build.md](npu-build.md)):
- **Model:** sherpa-onnx's `scripts/whisper/qnn/` export of ivrit.ai turbo (commit f33172a8) through QAIRT
  2.40.0.251030 for SM8750, decoder with 16-bit inputs and outputs (`--float_bitwidth 16`). Files in
  `build/qnn/out-SM8750/binary/`; on the phone in `Android/data/<app>/files/models/ivrit-whisper-large-v3-turbo-npu-sm8750/`
  with an empty `.ok`.
- **sherpa-onnx AAR** (`androidApp/libs/sherpa-onnx-2.0.1-qnn.aar`): sherpa-onnx v2.0.1 in `build/qnn/sherpa-onnx`
  with upstream #4126 (Whisper's `qnnConfig` in Kotlin and the JNI; `QnnConfig.htp_perf_mode` added to the C++ for
  it), our fixes (turbo's task tokens, the turbo model type, the cross-attention cache set once and the
  self-attention cache written in place, both on unless `SHERPA_ONNX_QNN_*=0`), built for arm64 with QNN
  (`build-android-arm64-v8a`), its JNI library stripped into `android/SherpaOnnxAar` beside the release's libraries
  for the other ABIs, then `./gradlew :sherpa_onnx:assembleRelease`.

**Next:** ExecuTorch's Qualcomm backend as the route to other chips (MediaTek Dimensity 9300/9400 and Exynos
2500/2600 have ExecuTorch NPU backends; Tensor only LiteRT, in beta), the GPU (Vulkan) for phones with no public NPU
access, the CPU last.

### In the app: the crash and its fix (Nov 4, 2026)

The NPU didn't open inside the app (`deviceCreate` 14001) although it did from `adb shell`: Qualcomm's stub library
needs the phone's `libcdsprpc.so`, a vendor library an app sees only when its manifest asks for it
(`<uses-native-library android:name="libcdsprpc.so" android:required="false" />`). Also in our sherpa-onnx: QNN's log
goes to logcat (tag `sherpa-onnx-qnn`; it went to stdout, lost in an app), and QNN failures throw (the JNI turns them
into Java exceptions the live screen shows) instead of `_Exit`, which closed the app. With that, live Whisper turbo on
the NPU works in the app.

### The Zipformer on the NPU (Nov 4, 2026)

`ivritZipformerNpu` (`ModelKind.StreamingTransducerNpu`, SM8750): ivrit.ai's `model.pt` (commit ee769a2a, not the int8
files) through icefall's `export-onnx-streaming.py` (commit 9c96676, in sherpa-onnx CI's icefall image; the model's
sizes, `--chunk-size 64 --left-context-frames 256 --dynamic-batch 0 --use-int32-inputs 1
--enable-int8-quantization 0`), then `build/qnn/compile-zipformer.sh SM8750`: encoder 155 MB, decoder 2 MB, joiner
1.6 MB. Text against the CPU int8 model on 9 recordings: close (1 identical, the rest a few words apart, the NPU's
often the better). **Speed: slower than the CPU** for this small model: 2.2 s against 0.98 s per 30 s of audio (one
stream), both far faster than real time. Likely the decoder and joiner, called for every 40 ms of audio, each paying
the NPU's round trip; to try: the encoder on the NPU and the decoder and joiner on the CPU, and measure the battery
before choosing it over the CPU.

**Published** (Nov 6, 2026): [hencohen83/zipformer2-66m-streaming-he-qnn-sm8750](https://huggingface.co/hencohen83/zipformer2-66m-streaming-he-qnn-sm8750)
(commit 284f0a33, public, CC-BY-4.0 with credit to Yakov Kolani and ivrit.ai). `ivritZipformerNpu` downloads from it.
