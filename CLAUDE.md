# Transcriptor (Kotlin Multiplatform)

A notebook for classes on Android and iOS: it records the lecture, transcribes it on the phone with a local speech
model (Hebrew first, other languages by model), and lets the user write notes while it keeps transcribing. Every line
of speech and every note carries its time in the recording, and the audio is kept as FLAC so lines can be refined,
re-transcribed and corrected later. **Read [docs/design.md](docs/design.md) first**: the decisions, the models, the
audio and data formats, and what is built. Each screen is described in [docs/screens/](docs/screens/README.md);
update those docs whenever a screen changes.

## Layout

- `sharedLogic/` — all logic, shared. `notebook/`: `Notebook`, `Lecture`, `Segment` (a line of speech with start
  and end), `Note`, `Speaker`. `models/`: `SpeechModel` and `ModelCatalog` (the models offered, by language).
  `lab/LabData.kt`: made-up notebooks and a Hebrew lecture for the UI labs. `notebook/LectureStore.kt`: lectures
  on disk (`lecture.json`). `recording/`: `LectureSession` (the controller while recording), `AudioRecorder` (per
  platform; Android's in androidMain with `FlacEncoder`), `WavWriter`, `FlacCompression`. `settings/`:
  `SettingsStore`, `AudioFormat`. `speech/`: `SpeechEngine`, `LiveTranscriber` (live lines), `Utterances`
  (splitting at pauses), `Downsampler`.
- `androidApp/` — Jetpack Compose UI, package `com.chencohensolution.transcriptor.ui`; `Theme.kt` has
  `TranscriptorTheme`, `LocalTranscriptorColors`, `layoutDirectionOf` and `formatClock`. Strings in
  `res/values/` (Hebrew in `values-iw/`). The UI lab is in `src/debug/`. whisper.cpp's JNI bridge is
  `src/main/cpp/` with `speech/WhisperEngine.kt`; `speech/ModelFiles.kt` downloads models.
- `third_party/whisper.cpp` — a git submodule pinned to a release: `git submodule update --init` after cloning.
- `iosApp/` — native SwiftUI, not created yet: iOS is built after the Android app is finished. The shared logic
  already builds for iOS.

## Conventions

- Logic goes in `sharedLogic` (with tests); the UIs only render state and call controller methods.
- Android first. When Android gets something iOS will need, add it to [docs/ios-todo.md](docs/ios-todo.md): the
  shared state and controller methods SwiftUI should call, the Android files to mirror, the strings.
- Hebrew is first-class: a notebook's content (lines, notes) is laid out in its language's direction
  (`layoutDirectionOf`), the app's chrome in the phone's. Check every screen in Hebrew and in an English notebook.
- Times are milliseconds from the start of the recording, everywhere.
- Models are public files only, pinned to a commit with their sha256; nothing is hosted by us. Only models free for
  commercial use go in `ModelCatalog`.
- Files other tools will read (the lecture's data file) are versioned (`"v": 1`) and written to a temp file, then
  renamed.

## UX suggestions: the UI lab

UX/UI suggestions follow the `ui-lab` skill ([.claude/skills/ui-lab/](.claude/skills/ui-lab/SKILL.md)): mocks in lab
code only, in the Android UI Lab, recorded in `docs/ux-history/`; the app changes after the owner approves a plan.
See [docs/previews.md](docs/previews.md).

## Commands

No system JDK: use Homebrew's `openjdk@21`.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :sharedLogic:testAndroidHostTest          # shared tests on the JVM
./gradlew :androidApp:assembleDebug
./scripts/ui-lab.sh [screen] [option]               # UI lab on the phone, else the emulator
```

## Gotchas

- Right after `am start -S` the screen can still be the splash: wait about 10 seconds before a screenshot.
