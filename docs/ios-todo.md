# iOS to do

iOS (SwiftUI, in `iosApp/`) is built after the Android app is finished. Each entry says what Android does, the
shared state and controller methods the SwiftUI side should call, the Android files to mirror, new strings, and
anything left to decide. Remove an entry once it is built.

## Notebooks

- Android: `NotebooksScreen.kt` (mirror it in SwiftUI), `MainActivity.kt` (the screen as the root, Record with the
  microphone permission, the banner back to the lecture), `Recording.kt` (`library`, `file`, `working`, `utcOffset`);
  `docs/screens/notebooks.md`.
- Shared, to call from SwiftUI: `NotebooksController` (`library`, `reload()` off the main thread, `create`, `assign`,
  `assignToNew`), `notebooksState(library, now, utcOffset, recording, working)`, `RecordingNow`, `UNFILED`,
  `NotebookStore.migrate` at launch. `utcOffset` on iOS: `NSTimeZone.localTimeZone.secondsFromGMTForDate` × 1000.
- Recording in no notebook: `LectureSession.start(Notebook(UNFILED, <"Not in a notebook">, language, []), title)`.
- Filing gives the notebook the named speakers' voices (Android's `Recording.file`, with `printOf`).
- Strings: `notebooks_*`.

## Settings and Manage models

- Android: `ui/ModelsScreen.kt` (mirror it in SwiftUI: Settings with a page per language, then the catalog),
  `Settings.kt` (the state and actions), `Recording.hub`, `Recording.checkNpu`; `docs/screens/models.md`.
- A language's page: `ModelChoices.candidates(forLanguage, ModelJob.Live/Improve, ready)`, `running`, `improveToGet`;
  choosing calls `SettingsStore.setLiveModel`/`setRefineModel`. Strings `settings_*`.
- Shared: `HubClient(fetch)` (iOS: `fetch` on URLSession), `HubModel.toSpeechModel`, `InstalledModels`,
  `ModelLibrary`, `SettingsStore.refineModel`/`setRefineModel`/`modelLanguages`.
- iOS has no Qualcomm NPU: the NPU filter is off, NPU builds show "not this phone". Core ML builds would be iOS's own.
- Strings: `models_*`.

## The lectures folder

- Android: finished lectures move from the app to a folder the user picks (`Documents/Transcriptor`), through
  `storage/SafFileSystem.kt` (an okio `FileSystem` on the documents provider) and `Recording.chooseFolder`/`archive`/
  `openAudio`; a `.nomedia` in it.
- Shared: `LectureStore.library` (a `Place`), `adoptLibrary`, `archiveFinished`, `SettingsStore.lectureFolder`.
- iOS: a folder picked with `UIDocumentPickerViewController` kept as a security-scoped bookmark (in
  `SettingsStore.lectureFolder`), an okio `FileSystem` over it (`startAccessingSecurityScopedResource`), no `.nomedia`.
- Strings: `folder_*`.

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
- Speakers: `LiveLectureActions.renameSpeaker` → `LectureSession.renameSpeaker(id, name)`; the chip and the rename
  dialog in `LectureScreen.kt` (`SpeakerChip`, `RenameSpeakerDialog`), colors in `TranscriptorColors.speakers`,
  strings `speaker_*`. Voice prints on iOS: sherpa-onnx's iOS build with the same CAM++ model, as a `VoiceEmbedder`.
- The voice-print model picked (Nov 6, 2026): `SettingsStore.voiceModel`, `ModelCatalog.voicePrintModels`, each
  model's `VoicePrintThresholds` (pass `sameSpeaker` and the model id to `SpeakerTracker`/`SpeakerPass`, `clustering`
  to the diarizer), and `VoiceStore`'s model provider (prints per model). Android: `Recording.voiceModel`,
  `voiceEmbedder`, `SherpaDiarizer`; the Speakers page in `ModelsScreen.kt` (`useVoicePrints`, strings
  `models_badge_speakers`, `models_use_for_speakers`, `models_by_hand`). The NPU builds are Android-only. On iOS the
  WeSpeaker models run on the CPU, or later on Core ML.
- The recording screen's models in use: `LiveLectureState.voicePrints` (`ModelInUse`, its `runs` set when the
  voice-print model loads, from `LiveModel.voicePrints`) and `speakerPassOnStop`; strings `lecture_speakers_model`,
  `lecture_model_*`, `lecture_speaker_pass_on_stop`; Android's `ModelsInUse` in `LectureScreen.kt`.
- Lecture, after: `ReviewScreen.kt` (mirror it in SwiftUI), `LectureReview` (`state`, `open`, `close`, `reload`,
  `playPause`, `seek`, `playLine`, `cycleSpeed`, `editLine`, `setLineSpeaker`, `renameSpeaker`, `addNote`, `editNote`,
  `deleteNote`, `switchTo` (versions), `passes`); an `AudioPlayer` in iosMain on `AVAudioPlayer` (`enableRate` for the speed); strings
  `review_*`.
- After a lecture: `SettingsStore.findSpeakersOnStop` (the speaker pass when it stops, on by default; files from before
  read the old `afterLecture`) and `autoImprove` (off by default): after Stop and the speaker pass, improve with the
  language's model if charging, else `addImprovePending` until the app is open on the charger (Android:
  `RecordingService` ACTION_STOP and `improveNextPending`, `MainActivity.improvePendingIfCharging`).
- Improve transcription… on the lecture, always offered: `ReviewState.refineModels` (`ModelChoice` with `minutes`
  and `npu`), `improveDefault`, `improveToGet`; the sheet in `ReviewScreen.kt` (`ImproveSheet`); speakers first then
  improve (`RecordingService.refine(…, speakersFirst)`); `SettingsStore.recordImproveSpeed`/`improveSpeed` and
  `ModelChoices.estimateMinutes` for the estimates. Strings `review_improve*`, `review_speakers_first*`, `review_kept`,
  `review_get_model`, `review_no_model`, `review_default`, `review_minutes`, `review_npu`, `review_cpu`.
- NPU (Android only for now): `ModelKind.WhisperNpu` models run on a Qualcomm NPU; on iOS the equivalent is Core ML
  on the Neural Engine, not built.
- To decide: where compression runs on iOS (a background task may be cut short; maybe only while the app is open).
- Live speaker turns (Nov 6, 2026): `LiveSpeakers` (shared) needs a `Segmenter` (pyannote segmentation 3.0 on one 10 s
  window: sherpa-onnx's iOS build has the model; Android's `SherpaSegmenter` uses our patch's `SpeakerSegmentation`
  JNI class) and a `VoiceEmbedder`; `LiveModel.liveSpeakers`, `LiveLectureState.speakersLive`, strings
  `lecture_speakers_live`. `SettingsStore.findSpeakersOnStop` is off by default now.
