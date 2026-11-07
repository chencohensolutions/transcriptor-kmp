#!/usr/bin/env python3
"""Calibration data for a w8a16 (8-bit weights, 16-bit activations) QNN build of a Whisper export from
sherpa-onnx's scripts/whisper/qnn/export_onnx.py (docs/npu-build.md, section 3).

  venv-export/bin/python whisper_calib.py --export export-large --prefix large-v3 --out calib-large \
      [--language he --hop 30 --max-windows 100 --decoder-windows 25 --decoder-steps 8] audio.flac|audio.wav ...

Audio is cut into 30 s windows (--hop seconds apart; quiet windows skipped). For each window:
- encoder: Whisper's log-mel, written as float32 in the converted QNN graph's layout (frames, mels), and a line in
  <out>/encoder.txt;
- decoder (on --decoder-windows of them, spread evenly): the ONNX encoder and decoder run on the CPU (greedy,
  <language>, transcribe, no timestamps, as sherpa-onnx's QNN Whisper does: one token per call), and the decoder's
  actual inputs are written at --decoder-steps steps, in the QNN graph's layout (caches transposed to (state, ctx)),
  with a line per step in <out>/decoder.txt (its inputs' files in the ONNX input order, space-separated). A
  window's cross caches are written once and shared by its lines.
The lists' paths are relative to <out>, as compile.sh's CALIB expects (one list per graph, named after it).
The CPU text of each decoded window goes to <out>/cpu-text.txt, a check of the export and of the language/task setup.

Raw files are in the converted graph's layout, as sherpa-onnx's own QNN calibration data is: QNN puts the mel input
channel-last, (1, 3000, n_mels), and the decoder's caches as (1, n_state, ctx). int32 inputs (tokens, offset, mask)
are int32, the rest float32: convert with --use_native_input_files.
"""
import argparse
import subprocess
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import whisper

SR = 16000
WIN = 30 * SR


def load_audio(path):
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(pcm, np.int16).astype(np.float32) / 32768


def windows(audio, hop, min_rms):
    """30 s windows every hop seconds (the last one padded), skipping quiet ones."""
    starts = list(range(0, max(len(audio) - WIN, 0) + 1, hop * SR)) or [0]
    if len(audio) > WIN and starts[-1] + WIN < len(audio):
        starts.append(len(audio) - WIN)
    for s in starts:
        w = audio[s:s + WIN]
        if np.sqrt(np.mean(w ** 2)) >= min_rms:
            yield s / SR, np.pad(w, (0, WIN - len(w)))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--export", required=True, help="folder with <prefix>-encoder.onnx, -decoder.onnx")
    p.add_argument("--prefix", required=True, help="the export's model name, e.g. large-v3")
    p.add_argument("--out", required=True)
    p.add_argument("--language", default="he")
    p.add_argument("--hop", type=int, default=30, help="seconds between window starts")
    p.add_argument("--min-rms", type=float, default=0.003)
    p.add_argument("--max-windows", type=int, default=100)
    p.add_argument("--decoder-windows", type=int, default=25)
    p.add_argument("--decoder-steps", type=int, default=8)
    p.add_argument("--max-tokens", type=int, default=180)
    p.add_argument("audio", nargs="+")
    a = p.parse_args()

    out = Path(a.out).resolve()
    listed = lambda f: str(Path(f).relative_to(out))  # compile.sh makes them absolute inside the container
    (out / "encoder").mkdir(parents=True, exist_ok=True)
    (out / "decoder").mkdir(parents=True, exist_ok=True)

    wins = []
    for f in a.audio:
        for t, w in windows(load_audio(f), a.hop, a.min_rms):
            wins.append((f"{Path(f).stem}@{t:.0f}", w))
    if len(wins) > a.max_windows:
        idx = np.linspace(0, len(wins) - 1, a.max_windows).round().astype(int)
        wins = [wins[i] for i in idx]
    print(f"{len(wins)} windows")

    enc = ort.InferenceSession(f"{a.export}/{a.prefix}-encoder.onnx", providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(f"{a.export}/{a.prefix}-decoder.onnx", providers=["CPUExecutionProvider"])
    meta = enc.get_modelmeta().custom_metadata_map
    n_mels, n_layer = int(meta["n_mels"]), int(meta["n_text_layer"])
    n_ctx, n_state = int(meta["n_text_ctx"]), int(meta["n_text_state"])
    eot = int(meta["eot"])
    tok = whisper.tokenizer.get_tokenizer(True, num_languages=int(meta["n_vocab"]) - 51765 - 1,
                                          language=a.language, task="transcribe")
    prompt = list(tok.sot_sequence_including_notimestamps)
    print("prompt", prompt, tok.decode_with_timestamps(prompt))
    dec_inputs = [i.name for i in dec.get_inputs()]

    dec_pick = set(np.linspace(0, len(wins) - 1, min(a.decoder_windows, len(wins))).round().astype(int).tolist())
    enc_lines, dec_lines, texts = [], [], []
    for wi, (name, w) in enumerate(wins):
        mel = whisper.log_mel_spectrogram(torch.from_numpy(w), n_mels=n_mels).numpy()  # (n_mels, 3000)
        f = out / "encoder" / f"{wi:03d}-mel.raw"
        np.ascontiguousarray(mel.T, dtype=np.float32).tofile(f)  # QNN layout: (3000, n_mels)
        enc_lines.append(listed(f))
        if wi not in dec_pick:
            continue

        cross = enc.run(None, {enc.get_inputs()[0].name: mel[None].astype(np.float32)})  # k0, v0, k1, v1, ...
        feeds = {f"{a.prefix}-cross_{kv}_{i}": cross[2 * i + (kv == "v")] for i in range(n_layer) for kv in "kv"}
        cross_files = {}
        for k, v in feeds.items():
            cf = out / "decoder" / f"{wi:03d}-{k.removeprefix(a.prefix + '-')}.raw"
            np.ascontiguousarray(v[0].T, dtype=np.float32).tofile(cf)  # (n_state, 1500)
            cross_files[k] = cf
        self_kv = {f"{a.prefix}-self_{kv}_{i}": np.zeros((1, n_ctx, n_state), np.float32)
                   for i in range(n_layer) for kv in "kv"}

        tokens, token = [], prompt[0]
        n_steps = len(prompt) + a.max_tokens
        steps = []
        offset = 0
        while offset < n_steps:
            mask = np.ones(n_ctx, np.int32)
            mask[:offset] = 0
            step_feeds = {f"{a.prefix}-tokens": np.array([[token]], np.int32), f"{a.prefix}-offset":
                          np.array([offset], np.int32), f"{a.prefix}-mask": mask, **self_kv, **feeds}
            outs = dec.run(None, step_feeds)
            steps.append((offset, token, {k: v.copy() for k, v in self_kv.items()}, mask))
            logits, this = outs[0], outs[1:]
            for i in range(n_layer):
                self_kv[f"{a.prefix}-self_k_{i}"][0, offset] = this[2 * i][0, 0]
                self_kv[f"{a.prefix}-self_v_{i}"][0, offset] = this[2 * i + 1][0, 0]
            offset += 1
            if offset < len(prompt):
                token = prompt[offset]
                continue
            token = int(np.argmax(logits[0, -1]))
            if token == eot:
                break
            tokens.append(token)
            if len(tokens) >= 24 and len(set(tokens[-24:])) <= 3:  # stuck repeating: keep calibration out of the loop
                break
        text = tok.decode(tokens).strip()
        texts.append(f"{name}\t{text}")
        print(f"[{wi}] {name}: {len(steps)} steps: {text[:80]}", flush=True)

        # Steps to keep: the prompt's last step and the rest spread over the text.
        pick = sorted(set(np.linspace(len(prompt) - 1, len(steps) - 1, a.decoder_steps).round().astype(int).tolist()))
        for si in pick:
            offset, token, cache, mask = steps[si]
            files = {}
            for n in dec_inputs:
                if n in cross_files:
                    files[n] = cross_files[n]
                    continue
                df = out / "decoder" / f"{wi:03d}-{si:03d}-{n.removeprefix(a.prefix + '-')}.raw"
                if n.endswith("-tokens"):
                    np.array([token], np.int32).tofile(df)
                elif n.endswith("-offset"):
                    np.array([offset], np.int32).tofile(df)
                elif n.endswith("-mask"):
                    mask.astype(np.int32).tofile(df)
                else:
                    np.ascontiguousarray(cache[n][0].T, dtype=np.float32).tofile(df)  # (n_state, n_ctx)
                files[n] = df
            dec_lines.append(" ".join(listed(files[n]) for n in dec_inputs))

    (out / "encoder.txt").write_text("\n".join(enc_lines) + "\n")
    (out / "decoder.txt").write_text("\n".join(dec_lines) + "\n")
    (out / "cpu-text.txt").write_text("\n".join(texts) + "\n")
    print(f"encoder: {len(enc_lines)} inputs, decoder: {len(dec_lines)} inputs, in {out}")


if __name__ == "__main__":
    main()
