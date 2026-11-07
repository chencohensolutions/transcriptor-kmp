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
  - Both FLAC formats encode at the **compression effort** set in Settings (`SettingsStore.flacLevel`, 0–8, 5 by
    default; Nov 6, 2026). Every level is lossless; higher ones make the file a few percent smaller and take longer.
    The encoder is Android's MediaCodec FLAC, which runs on the CPU: the NPU and GPU have no FLAC encoder.
  - FLAC is about 350–450 MB per hour (stereo from one phone microphone compresses better, as the channels are alike).
- `lecture.json`'s `"audio"` names the file, and `"pendingFlac"` marks a WAV waiting to be compressed.
- The models get a copy downmixed to mono and resampled to 16 kHz, in memory.
- Recording goes on with the screen off: a foreground service with the microphone type on Android, the background
  audio mode on iOS.

### Speakers

- **During the lecture** (owner's request, Nov 2, 2026): when a line ends, a voice print of its audio (3D-Speaker
  CAM++, Apache-2.0, 28 MB, on sherpa-onnx; `SpeakerLabeler`) joins the speaker whose average print is closest, if
  at least 0.5 alike (cosine), else starts a new "Speaker N" (`SpeakerTracker`). Lines under 1.5 s get no label.
  A line where the voice changes without a pause (a TV debate, an interruption) is split: prints of 1.5 s windows
  every 0.75 s are grouped by voice, a change lasting two windows splits the line at the nearest word (the streaming
  model's word times), and each part is labeled as a line. On a test recording this split an 18 s TV line into its
  two voices; the user stayed one speaker but for one line. Whisper lines aren't split yet (no word times).
- Names: tapping a speaker's chip names them; the name and their average print go to the notebook's
  `voices.json` (`VoiceStore`), and its next lectures start with those voices, labeled by name from their first line.
- **After the lecture, the speaker pass** (`SpeakerPass`, built Nov 2, 2026): pyannote's 3.1 pipeline as sherpa-onnx
  runs it on the phone (`SherpaDiarizer`): segmentation 3.0 (MIT, 6 MB, sherpa-onnx's ONNX export) finds who speaks
  when, overlaps included, then CAM++ prints and clustering (distance 0.85) group the turns. pyannote's own Python
  pipeline can't run on a phone, and on a test recording it was 10× slower and merged every voice (its settings
  expect long meetings). The recording is read in 5-minute pieces (`RecordingAudio`: WAV, or FLAC through the system
  decoder); each piece's speakers are matched across pieces and to the notebook's voices by print, so named voices
  keep their names. Lines are then relabeled (`withTurns`): a line with word times (streaming model) splits where the
  turn changes, one stray word staying put; a line without them gets the speaker who spoke most of it; edited lines
  keep their text. It runs after Stop when both models are downloaded (before FLAC compression), and again from the
  start screen. Overlapping speech still gives one speaker's words: separating voices is a later step.
- **Live speaker turns** (owner's request, Nov 6, 2026; diart's method): when pyannote's segmentation is on the phone,
  speakers are found during the lecture instead of by each line's print (`LiveSpeakers`).
  - **Each step:** every 2.5 s, segmentation reads the last 10 s (pyannote's window) and settles the part that is
    2.5 s old or older and wasn't settled before: who speaks there, overlaps included.
  - **Prints:** each of the window's voices there gets one print of its single-voice audio in that part (when it has
    1 s or more) from the picked voice-print model. It joins the closest of the lecture's speakers (`SpeakerTracker`,
    known voices first), or starts a new one, at the model's `VoicePrintThresholds.live` (short prints are noisier,
    so it is lower than for whole lines). A voice with too little audio keeps the speaker it had in the previous
    window, where the windows overlap.
  - **Lines:** they split where the settled turns change, at a word (`liveLines`, `followTurns`); a line without word
    times gets the speaker who spoke most of it.
  - **Delay:** labels follow the text by 2.5–5 s; each moment is decided once.
  - **The pass after Stop is off by default:** `SettingsStore.findSpeakersOnStop`, now `speakerPassOnStop`; the old
    switch is ignored. It can still be run from the lecture's menu.
  - **Without segmentation:** each line's print, as below.
  - **What live decided is kept** with the lecture (`speakers-live.json`: the model, and each voice's stretch per
    settled part with its print, or the stretch it continues; `LectureStore.saveLiveSpeakers`).
  - **"Tune speakers on this lecture"** (the lecture's menu, with the number of lines the user gave another speaker
    than live did) replays those prints across the model's "fewer ↔ more speakers" range (`SpeakerTuning`). It shows
    the setting whose speakers match the user's labels best, and Apply sets it. It isn't offered after a speaker pass,
    which regroups every line.
  - **The segmentation model is picked** too (`SettingsStore.segmentationModel`):
    - pyannote's (default);
    - BUT-FIT's DiariZen (WavLM + Conformer, 4 speakers a window, 20 ms frames), copied by hand: base-s80 and
      large-s80 (CC-BY-NC-4.0, 16 s windows), and meeting-base (8 s). meeting-base's Hugging Face page says MIT, its
      repository CC-BY-NC-4.0, so it is treated as non-commercial.

    They are exported with a fixed window and pyannote's metadata, so the same sherpa-onnx class runs them
    (`build/diarizen/`, not in git).
  - **Replay A/B** of the three lectures through this path (`build/speaker-ab/live_ab*.log`): pyannote costs 0.03 s
    per second of audio on one Mac CPU core, CAM++'s prints 0.02.
- **The voice-print model is picked** in Settings › Models › Speakers (owner's request, Nov 6, 2026), for both the live
  labels and the speaker pass; a change applies from the next lecture or speaker pass (`SettingsStore.voiceModel`,
  `Recording.voiceModel`). CAM++ stays the default.
  - **The choices** (`ModelCatalog.voicePrintModels`):
    - CAM++, CPU.
    - 3D-Speaker ERes2NetV2 (Apache-2.0, downloadable), CPU.
    - WeSpeaker ResNet293-LM (CC-BY-4.0).
    - WeSpeaker SimAM-ResNet100, VoxBlink2 + VoxCeleb2 (CC-BY-NC-SA-4.0, non-commercial).

    The two WeSpeaker models are copied to the phone by hand (`SpeechModel.manual`, listed once their folder has `.ok`),
    with `feature_normalize_type=global-mean` added to their ONNX. sherpa-onnx's release lacks it, and without the mean
    normalization WeSpeaker trains with, ResNet293's EER on the owner's lectures was 31.8% instead of 18.2%.
  - **On the NPU:** on the Snapdragon 8 Elite they run on the NPU when their folder has the NPU build (`t150.bin`,
    `t300.bin`, `t600.bin`; [npu-build.md](npu-build.md)). sherpa-onnx's extractor windows any length over those
    graphs; if the NPU fails, the same model runs on the CPU (`SherpaVoiceEmbedder`, `SherpaDiarizer`). On the NPU a
    1.5 s print takes 16 ms with ResNet100 and 6 ms with ResNet293.
  - **Thresholds:** each model has its own (`VoicePrintThresholds`): the tracker's cosine and the speaker pass's
    clustering distance, set from an A/B on the owner's lectures (`build/speaker-ab/results.md`, not in git).
  - **Saved voices:** `voices.json` keeps a print per model (`"model"`; prints saved before are CAM++'s). Only the
    picked model's prints are compared; a voice without one is learned again the next time it is named.
  - **The A/B (Nov 6):** lines of three lectures, same-vs-different-speaker EER.
    - ResNet100 + mean normalization 17.9%, ResNet293 + mean normalization 18.2%, CAM++ 21.0%, ERes2NetV2 23.1%.
    - The labels were CAM++'s own (no line was edited), so this favors CAM++.
- Expect it to work well for lecturer vs. students and less well for students far from the phone. Overlapping
  speech (two at once) gets one speaker, the louder.

### Data

- **Power per lecture** (owner's request, Nov 6, 2026): while a lecture records, `PowerMeter` samples the battery every
  10 s (`AndroidPowerProbe`):
  - BatteryManager's CURRENT_NOW, whose size is used: the S25 reports discharge as negative;
  - the voltage, the temperature, whether it is plugged in, and the level, all from ACTION_BATTERY_CHANGED;
  - whether the screen is on;
  - the app's own CPU time, from `/proc/self/stat`.

  At Stop it writes `power.json` (version 1) beside the audio, and adds a row to `power-log.csv` in the app's own
  storage. The report holds:
  - duration, average mW, mWh in all, mWh and J per minute;
  - battery % and °C at the start and end;
  - the app's CPU seconds per minute, and the screen-on share;
  - the models used and where (NPU or CPU).

  The battery's power is the whole phone's (screen and other apps included). The app's own share shows in its CPU
  time. On the charger the current isn't the phone's use, so the power figures are null and `charging` is true.

One folder per lecture: `notebooks/<notebook id>/<lecture id>/` with `audio.flac`, `lecture.json` and
`transcript.md`. `lecture.json` is the data (times, text, notes); `transcript.md` is the lecture to read beside its
audio (owner's request, Nov 4, 2026): its title, date and length, then each speaker's turn with its time and the notes
after the turn they were written in, rewritten on every save (`transcriptMarkdown`). A Word file of the same reading
(`transcriptDocx`, Nov 7, 2026) is made on demand: shared from the lecture, or saved beside the audio as
`<title>.docx`. Other exports (PDF, subtitles) come later from `lecture.json`.

**Where the files are** (owner's decision, Nov 4, 2026): a lecture records in the app's own storage, and once it is
finished (stopped, its passes done, compressed to FLAC if that is the setting, not open) it moves to the **lectures
folder** outside the app, when the user picked one: `Documents/Transcriptor` is suggested (the system's folder picker
opens on Documents, and the app makes `Transcriptor` in the folder picked unless it is one). The folder is reached
through Android's documents provider (`SafFileSystem`, a persisted tree permission), so it outlives the app: after a
reinstall, picking it again brings back every notebook and lecture. An empty `.nomedia` at its root keeps music and
gallery apps from listing the recordings. Moving a lecture copies its folder, checks every file's size and that
`lecture.json` reads back, then deletes it from the app (`LectureStore.archive`); a failed copy leaves it in the app
for next time. `notebooks.json` and the voices live in the folder too; settings and models stay in the app. Playback
and the passes read a lecture's audio from either place (`Recording.openAudio`). If the folder can't be reached, new
lectures stay in the app and Notebooks asks to pick it again.

The notebooks are in `notebooks.json` (`NotebookStore`, v1: id, title, language, color, createdAt). Lectures recorded
without a notebook are in `notebooks/unfiled/` until they are filed (`LectureStore.move`); the notebook a lecture's
weekday and time suggest is `guessNotebook`'s (same weekday, within an hour, at least two earlier lectures). Before
notebooks, every lecture was in `notebooks/default/`: those became unfiled on the first launch with notebooks.

```json
{
  "v": 1,
  "speakerPass": true,
  "refinedWith": "ivrit-whisper-large-v3",
  "versions": [{ "id": "v1793633774646", "model": "ivrit-zipformer2-66m-streaming", "keptAt": 1793633774646,
                 "lines": 21, "edited": 0, "refined": false }],
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

### NPU

ivrit.ai Whisper turbo runs on the Snapdragon 8 Elite's NPU (Nov 4, 2026): `ModelKind.WhisperNpu`, offered only on
its chip (`SpeechModel.chips`, Android's `Build.SOC_MODEL`), on sherpa-onnx's QNN backend (`SherpaNpuWhisperEngine`),
live and for the refine pass; other phones keep whisper.cpp on the CPU. On a 41-minute recording it was about 2× as
fast as the CPU and used about a fifth of the energy. ivrit.ai large-v3 has an NPU build too (Nov 5), for the refine pass only. Its files and the QNN-enabled sherpa-onnx AAR are ours to
publish (owner: on Hugging Face); not yet. Details and next steps: [npu-plan.md](npu-plan.md).

## Screens

See [screens/](screens/README.md). Designed in the UI lab first, one screen at a time.

## Built so far

- The project, the shared data types and the model catalog, the Android UI lab.
- Lecture (while recording): option a of round 1 (one timeline), in the app. It records to WAV or FLAC, with the
  screen off too, transcribes live with the first downloaded live model for the language, and saves lines, notes and
  `lecture.json`. Stop waits for the last lines.
- Settings › Models (round 1's b4), in the app: the models per language in use, models added from Hugging Face
  (`HubClient`: only files the app's engines read; pinned to the repo's commit, checked by sha256 or git blob id;
  licenses marked for commercial use, not, or unknown, and asked for), and each NPU model tried on the NPU once
  downloaded. Since Nov 6 (round 2's b) Settings has a page per language, the one place its live and improve models
  are chosen, and two switches after a lecture: find the speakers when it stops, improve automatically (while
  charging). Added models are kept in `models.json` in the app's storage.
- Notebooks (round 1's c2), in the app: the first screen, lectures first, recording with or without a notebook, filing
  unfiled lectures, the notebook guessed from when its lectures are. The gear opens a temporary Settings page (the old
  start screen's audio format and model downloads) until Settings › Models was promoted.
- Lecture, after (round 1's c2), in the app: playback (MediaPlayer, WAV or FLAC), editing lines and their speakers,
  naming speakers, notes after the lecture, and the speaker pass and the refine pass ("Improve transcription…", always
  offered, with an estimate per model from its last run) from its menu. Stop opens it.
- **Refine pass** (`RefinePass`): each line's audio (a little before and after it, at most 30 s) through the refine
  model (ivrit.ai large-v3 for Hebrew, q8_0 on the phone), with live transcription's filters (`transcribeLine`: no
  non-speech pieces, no impossible word counts, loops collapsed). Lines keep their times and speakers; edited lines and
  lines the model gives nothing for keep their text. `lecture.json` records `refinedWith`. The UI calls it Improve.
  Any downloaded Whisper model for the language can improve (`ModelChoices`; the Zipformer could, a line at a time with
  `transcribeLineStreaming`, but is no longer offered since Nov 6: it writes the live text again). Before a pass, the current transcript is kept as a **version**
  (`versions/<id>.json` in the lecture's folder: its lines, speakers and edits; `lecture.json` lists them under
  `versions`); the review screen switches between them, keeping the one it replaces. Not timed on the phone yet;
  large-v3 is expected to run several times slower than turbo there (the NPU plan targets it).
- Speaker labels during the lecture, with names the notebook remembers (above). The voice-print model downloads on
  the start screen; labels are on once it is there.
- Fixing a line while recording (the session keeps the user's words over live lines), deleting lectures (hold to
  select, Undo), and the Word export (Nov 7, 2026).

## Open questions

- The oldest phone to support (decides whether turbo can run live everywhere or a smaller fallback is needed).
- License of this repository (it is public; none chosen yet).
