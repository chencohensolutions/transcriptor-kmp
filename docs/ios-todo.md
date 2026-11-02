# iOS to do

iOS (SwiftUI, in `iosApp/`) is built after the Android app is finished. Each entry says what Android does, the
shared state and controller methods the SwiftUI side should call, the Android files to mirror, new strings, and
anything left to decide. Remove an entry once it is built.

## Lecture, while recording

- Android: `LectureScreen.kt` and `RecordingIndicators.kt` (the screen); `RecordingService.kt`, `Recording.kt` and
  `MainActivity.kt` (keeping it recording, and the temporary start screen); `docs/screens/lecture.md`.
- Shared, to call from SwiftUI: `LectureSession` (`state`, `start(notebook, title)`, `pauseOrResume()`,
  `addNote(atMillis, text)`, `stop()`), `timelineOf(lecture)`, `SettingsStore.audioFormat`, `FlacCompression`
  (`pending()`, `compress(lecture, progress)`).
- To write in `sharedLogic/src/iosMain`: an `AudioRecorder` on `AVAudioEngine` at 48 kHz stereo 16 bit, writing with
  `AVAudioFile` (`kAudioFormatLinearPCM` for WAV, `kAudioFormatFLAC` for FLAC live), and a `FlacEncode` that converts
  the WAV with `AVAudioFile`. The app needs the background audio mode and the microphone usage description.
- Strings: `lecture_*`, `recording_*`, `compressing_flac`, `start_*`, `format_*` in `androidApp/src/main/res/values/strings.xml`
  (Hebrew in `values-iw/`).
- To decide: where compression runs on iOS (a background task may be cut short; maybe only while the app is open).
