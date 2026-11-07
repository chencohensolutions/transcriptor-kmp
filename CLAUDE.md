# Audio Logs (Kotlin Multiplatform; the code is still named Transcriptor)

The app is **Audio Logs**; a recording is a **moment** (Hebrew רגע) in everything the user sees. The code keeps
`Lecture` for it (classes, files, `lecture.json`, string keys).

An app for classes and conversations on Android and iOS: it records the moment, transcribes it on the phone with a local speech
model (Hebrew first, other languages by model), and lets the user write notes while it keeps transcribing. Every line
of speech and every note carries its time in the recording, and the audio is kept as FLAC so lines can be refined,
re-transcribed and corrected later. **Read [docs/design.md](docs/design.md) first**: the decisions, the models, the
audio and data formats, and what is built. Each screen is described in [docs/screens/](docs/screens/README.md);
update those docs whenever a screen changes.

## Layout

- `sharedLogic/` — all logic, shared. `notebook/`: `Label`, `Lecture`, `Segment` (a line of speech with start
  and end), `Note`, `Speaker`. `models/`: `SpeechModel` and `ModelCatalog` (the models offered, by language).
  `lab/LabData.kt`: made-up labels, moments and a Hebrew lecture for the UI labs. `notebook/LectureStore.kt`: moments
  on disk (`moments/<id>/`: `lecture.json` with its labels, and `transcript.md` from `Transcript.kt`).
  `notebook/LabelStore.kt`: `labels.json`. `notebook/Migration.kt`: `migrateToLabels`, notebooks became labels.
  `notebook/NotebooksController.kt` and `LabelGuess.kt`: the first screen's state and the labels guessed from when
  moments are. `notebook/Place.kt` and `LibraryFolder.kt`: the lectures folder outside the app (finished lectures
  move there; `LectureStore.library`). `recording/`: `LectureSession` (the controller while recording), `AudioRecorder` (per platform;
  Android's in androidMain with `FlacEncoder`), `WavWriter`, `FlacCompression`. `settings/`:
  `SettingsStore`, `AudioFormat`. `speech/`: `LiveTranscriber` (live lines) with `UtteranceTranscriber`
  (Whisper, a line at a time: `SpeechEngine`, `Utterances` splitting at pauses) and `StreamingTranscriber`
  (Zipformer: `StreamingEngine`), `Downsampler`, `Speakers.kt` (`SpeakerTracker`, `SpeakerLabeler`:
  speaker labels from voice prints). `notebook/VoiceStore.kt`: the voices the app knows (`voices.json`, one list). `review/`:
  `LectureReview` (a recorded lecture opened: playback, edits, speakers, notes), `AudioPlayer`, `ReviewState`.
- `androidApp/` — Jetpack Compose UI, package `com.chencohensolution.transcriptor.ui`; `Theme.kt` has
  `TranscriptorTheme`, `LocalTranscriptorColors`, `layoutDirectionOf`, `titleDirection`, `isolated` and `formatClock`.
  `NotebooksScreen.kt` is the first screen; `ModelsScreen.kt` is Settings and Settings › Models (fed by `Settings.kt`).
  `storage/SafFileSystem.kt`: the lectures folder the user picks, as an okio file system under `/library`. Strings in
  `res/values/` (Hebrew in `values-iw/`). The UI lab is in `src/debug/`. whisper.cpp's JNI bridge is
  `src/main/cpp/` with `speech/WhisperEngine.kt`; `speech/ModelFiles.kt` downloads models; `speech/SherpaStreamingEngine.kt`
  runs streaming models on sherpa-onnx (its AAR is downloaded into `androidApp/libs/` by the build).
- `scripts/npu/` — building the NPU models (Snapdragon) and the patched sherpa-onnx AAR:
  [docs/npu-build.md](docs/npu-build.md).
- `third_party/whisper.cpp` — a git submodule pinned to a release: `git submodule update --init` after cloning.
- `iosApp/` — native SwiftUI, not created yet: iOS is built after the Android app is finished. The shared logic
  already builds for iOS.

## Conventions

- Logic goes in `sharedLogic` (with tests); the UIs only render state and call controller methods.
- Android first. When Android gets something iOS will need, add it to [docs/ios-todo.md](docs/ios-todo.md): the
  shared state and controller methods SwiftUI should call, the Android files to mirror, the strings.
- Hebrew is first-class: a moment's content (lines, notes) is laid out in its language's direction
  (`layoutDirectionOf`), the app's chrome in the phone's. Check every screen in Hebrew and with an English moment.
- Times are milliseconds from the start of the recording, everywhere.
- Models are public files only, pinned to a commit with their sha256; nothing is hosted by us. Only models free for
  commercial use go in `ModelCatalog`; models the user adds from Hugging Face may have any license, marked and
  asked for in Settings › Models.
- Files other tools will read (the lecture's data file) are versioned (`"v": 1`) and written to a temp file, then
  renamed.

## UX suggestions: the UI lab

UX/UI suggestions follow the `ui-lab` skill (plugin `ui-lab@chencohen-skills`, enabled in `.claude/settings.json`;
this project's notes for it: [.claude/ui-lab.md](.claude/ui-lab.md)): mocks in lab
code only, in the Android UI Lab, recorded in `docs/ux-history/`; the app changes after the owner approves a plan.
See [docs/previews.md](docs/previews.md).

## Commands

No system JDK: use Homebrew's `openjdk@21`.

Android emulator: use the `Transcriptor` AVD, made for this project (2 cores, 2 GB RAM, Android 37.2 with Google Play).
Run it only while needed: `./scripts/emulator.sh up` before installing or testing on it, `./scripts/emulator.sh down`
when done. `up` starts it headless on the Mac's GPU (less power than software rendering), shuts down any other
emulator, since scripts and `adb` pick the first running one, and waits for the boot (seconds, from the quick-boot
snapshot). `WINDOW=1` shows its window.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :sharedLogic:testAndroidHostTest          # shared tests on the JVM
./gradlew :androidApp:assembleDebug
./scripts/ui-lab.sh [screen] [option]               # UI lab on the phone, else the emulator
```

## Gotchas

- Right after `am start -S` the screen can still be the splash: wait about 10 seconds before a screenshot.
