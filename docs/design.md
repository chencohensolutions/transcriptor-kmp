# Design

## What it is

A notebook for classes. A **notebook** is a course; a **lecture** is one recorded class in it. During the lecture
the app records, transcribes on the phone, and the user writes **notes** without stopping either. After the lecture
a larger model **refines** the transcript from the saved audio and tells the **speakers** apart. Everything stays on
the phone.

Android is built first, then iOS (SwiftUI) on the same shared logic (owner's decision, Nov 2, 2026).

## Decisions (Nov 2, 2026)

### Speech models

- Engine: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) with quantized ggml files, the same native
  code on Android (JNI) and iOS (cinterop, Metal/Core ML for the encoder). Kotlin sees one engine interface; switching
  models loads another file.
- Two passes per lecture, each with its own model, chosen **per language** in Settings › Models:
  - **Live**, during the lecture: the turbo model (809M parameters, about 570 MB), fast enough on recent phones.
  - **Refine**, after the lecture: large-v3 (1.55B, about 1.1 GB) over the whole FLAC, slower than real time; it
    replaces the live lines, except lines the user edited.
- Hebrew: ivrit.ai's fine-tunes of Whisper (Apache-2.0), the strongest open Hebrew models known as of Nov 2026. Other
  languages: OpenAI's Whisper large-v3 and turbo (MIT). Every model offered must be free for commercial use. Models
  under non-commercial licenses (Meta's MMS, SeamlessM4T: CC-BY-NC) were not better for Hebrew and are left out.
  Compare new Hebrew models on the [ivrit.ai leaderboard](https://ivrit-ai-hebrew-transcription-leaderboard.hf.space/).
- Lines: a voice activity detector (Silero VAD, MIT; built into whisper.cpp) splits the audio at pauses; each piece
  of speech becomes a **segment** with its start and end.

### Audio

- Recorded at **48 kHz, stereo, 16 bit**, written while recording so a crash loses at most the last seconds. The
  audio format is a setting (`AudioFormat`; owner's decision, Nov 2, 2026):
  - **WAV** (default): `audio.wav`, about 690 MB per hour. The safest, with no encoder to fail. A crash leaves the
    header's sizes stale; `WavWriter.repair` fixes them from the file's length.
  - **WAV, then FLAC**: WAV while recording, compressed to `audio.flac` after Stop (in the recording service, as a
    media-processing foreground service), checked to hold every sample, then the WAV is deleted. An interrupted
    compression stays `pendingFlac` in `lecture.json` and is redone when the app next opens.
  - **FLAC live**: `audio.flac` as it records, with Android's built-in FLAC encoder.
  - FLAC is about 350–450 MB per hour (stereo from one phone microphone compresses better, as the channels are alike).
- `lecture.json`'s `"audio"` names the file, and `"pendingFlac"` marks a WAV waiting to be compressed.
- The models get a copy downmixed to mono and resampled to 16 kHz, in memory.
- Recording goes on with the screen off: a foreground service with the microphone type on Android, the background
  audio mode on iOS.

### Speakers

Yes, after the lecture: the refine pass runs speaker diarization (speaker segmentation plus voice embeddings, for
example sherpa-onnx with pyannote's segmentation model, MIT, and a 3D-Speaker embedding model, Apache-2.0), and
labels each segment "Speaker 1", "Speaker 2"… The user renames them ("Lecturer") once per lecture. Not live: telling
voices apart needs the whole recording to be reliable. Expect it to work well for lecturer vs. students and less
well for students far from the phone.

### Data

One folder per lecture: `notebooks/<notebook id>/<lecture id>/` with `audio.flac` and `lecture.json`. The first
format is a simple data file of times, text and notes; exports (Word, PDF, Markdown, subtitles) come later from it.

```json
{
  "v": 1,
  "id": "lecture-7",
  "title": "זיכרון עבודה",
  "language": "he",
  "startedAt": "2026-11-02T10:00:00+02:00",
  "durationMs": 2832000,
  "audio": "audio.flac",
  "pendingFlac": false,
  "speakers": [{ "id": "speaker-1", "name": "המרצה" }],
  "segments": [
    { "id": "s1", "startMs": 2000, "endMs": 14500, "text": "טוב, בוקר טוב לכולם…",
      "model": "ivrit-whisper-large-v3", "edited": false, "speaker": "speaker-1" }
  ],
  "notes": [{ "id": "n1", "atMs": 33000, "text": "פסיבי ↔ אקטיבי" }]
}
```

A note's time is when the user **started** typing it, so it points at what was being said then.

## Screens

See [screens/](screens/README.md). Designed in the UI lab first, one screen at a time.

## Built so far

- The project, the shared data types and the model catalog, the Android UI lab.
- Lecture (while recording): option a of round 1 (one timeline), in the app. It records to WAV or FLAC, with the
  screen off too, and saves notes and `lecture.json`. There are no lines yet; live transcription (whisper.cpp) is
  next. The app opens on a temporary start screen (audio format, Start lecture) until the Notebooks screen is
  designed.

## Open questions

- The oldest phone to support (decides whether turbo can run live everywhere or a smaller fallback is needed).
- License of this repository (it is public; none chosen yet).
