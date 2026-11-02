# Design

## What it is

A notebook for classes. A **notebook** is a course; a **lecture** is one recorded class in it. During the lecture
the app records, transcribes on the phone, and the user writes **notes** without stopping either. After the lecture
a larger model **refines** the transcript from the saved audio and tells the **speakers** apart. Everything stays on
the phone.

Android is built first, then iOS (SwiftUI) on the same shared logic (owner's decision, Nov 2, 2026).

## Decisions (Nov 2, 2026)

### Speech models

- Engine: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT, a submodule in `third_party/`, pinned to a
  release) with ggml files, the same native code on Android (JNI) and iOS (cinterop, Metal/Core ML for the encoder).
  Kotlin sees one engine interface (`SpeechEngine`); switching models loads another file.
- Files: downloaded from the publisher at a pinned commit and checked against their sha256 (`ModelCatalog`). ivrit.ai
  publishes only full precision (f16, 1.6 GB for turbo), so the phone quantizes it to q8_0 after the download
  (whisper.cpp's own quantizer, built into the app): 874 MB, and on a Galaxy S25 twice as fast, with the same text.
  q5_0 was no faster than f16 there.
- Measured on a Galaxy S25 (6 threads, ivrit.ai turbo q8_0): 6.5 s of Hebrew in about 2.3 s, with the encoder's
  window fitted to the line (`audio_ctx`) instead of whisper's fixed 30 s, which alone is 5× faster. Loading the
  model takes about 2 s, once per lecture.
- Two passes per lecture, each with its own model, chosen **per language** in Settings › Models:
  - **Live**, during the lecture: the turbo model (809M parameters, about 570–870 MB), fast enough on recent phones.
  - **Refine**, after the lecture: large-v3 (1.55B, about 1.1–1.7 GB) over the whole recording, slower than real time; it
    replaces the live lines, except lines the user edited.
- Hebrew: ivrit.ai's fine-tunes of Whisper (Apache-2.0), the strongest open Hebrew models known as of Nov 2026. Other
  languages: OpenAI's Whisper large-v3 and turbo (MIT). Every model offered must be free for commercial use. Models
  under non-commercial licenses (Meta's MMS, SeamlessM4T: CC-BY-NC) were not better for Hebrew and are left out.
  Compare new Hebrew models on the [ivrit.ai leaderboard](https://ivrit-ai-hebrew-transcription-leaderboard.hf.space/).
- Live, streaming (an option for Hebrew, Nov 2, 2026): ivrit.ai's mirror of Yakov Kolani's
  [zipformer2-66m-streaming-he](https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he) (CC-BY-4.0: commercial
  use with credit, shown with the model), 66M parameters, 74 MB int8, on [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
  (Apache-2.0; its release AAR is fetched by Gradle, pinned and hash-checked). Its own card gives 5.1% WER on
  ivrit.ai's eval-d1 against 5.3% for ivrit.ai Whisper turbo (8.9% against 7.1% on WhatsApp audio). Words appear as
  they are said (1.28 s chunks), a pause (0.8 s after words) ends the line, and as a transducer it doesn't invent
  text from noise as Whisper does; no punctuation. Chosen per language on the start screen ("Use for live"); without
  a choice, the first downloaded live model in the catalog runs. sherpa-onnx can also run it on Qualcomm's NPU (QNN),
  not tried yet.
- Lines, live with Whisper: the recording is downsampled to 16 kHz mono and split at pauses by loudness against the room's noise
  floor (`Utterances`: a pause of 0.6 s ends a line; past 15 s a short pause does; 25 s at most). Each line becomes a
  **segment** with its start and end in the recording; the line being spoken is transcribed again every 1.5 s of new
  audio for the grey partial line. Lines waiting go first. Silero VAD (built into whisper.cpp) may replace the
  loudness test if classrooms need it.

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

- **During the lecture** (owner's request, Nov 2, 2026): when a line ends, a voice print of its audio (3D-Speaker
  CAM++, Apache-2.0, 28 MB, on sherpa-onnx; `SpeakerLabeler`) joins the speaker whose average print is closest, if
  at least 0.5 alike (cosine), else starts a new "Speaker N" (`SpeakerTracker`). Lines under 1.5 s get no label.
  Each line is one speaker, as lines end at pauses, which is usually where the speaker changes.
- Names: tapping a speaker's chip names them; the name and their average print go to the notebook's
  `voices.json` (`VoiceStore`), and its next lectures start with those voices, labeled by name from their first line.
- After the lecture, the refine pass can regroup with the whole recording (pyannote segmentation, MIT) and split a
  line where two people spoke; lines the user edited stay. Not built yet.
- Expect it to work well for lecturer vs. students and less well for students far from the phone. Overlapping
  speech (two at once) gets one speaker, the louder.

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
  screen off too, transcribes live with the first downloaded live model for the language, and saves lines, notes and
  `lecture.json`. Stop waits for the last lines. The app opens on a temporary start screen (audio format, live model
  downloads, Start lecture) until the Notebooks and Settings › Models screens are designed.
- Speaker labels during the lecture, with names the notebook remembers (above). The voice-print model downloads on
  the start screen; labels are on once it is there.

## Open questions

- The oldest phone to support (decides whether turbo can run live everywhere or a smaller fallback is needed).
- License of this repository (it is public; none chosen yet).
