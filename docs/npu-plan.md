# Plan: the phone's NPU (Qualcomm Hexagon)

Goal (owner, Nov 2, 2026): less power and more speed by running the speech models on the NPU of Snapdragon phones,
first the owner's Galaxy S25 (Snapdragon 8 Elite, **SM8750**, Hexagon **v79**), with the CPU path kept for every other
phone. The owner asked for **the larger Hebrew model** on the NPU. To be run in its own session.

The general procedure (tools, commands, checks) is the user skill **`qnn-npu-compile`**
(`~/.claude/skills/qnn-npu-compile/SKILL.md`); this file is what it means for this app.

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
