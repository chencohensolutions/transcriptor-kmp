# iOS to do

iOS (SwiftUI, in `iosApp/`) is built after the Android app is finished. Each entry says what Android does, the
shared state and controller methods the SwiftUI side should call, the Android files to mirror, new strings, and
anything left to decide. Remove an entry once it is built.

## App name and moments (Nov 7, 2026)

- The app is **Audio Logs** (two words); each recording is a **moment** (Hebrew רגע, plural רגעים). Use these in every
  SwiftUI string: the app's display name (`CFBundleDisplayName`), and the words Android has in `app_name`,
  `start_lecture`, `start_lectures`, `folder_*`, `settings_after`, `settings_*_note`, `review_tune_*`,
  `review_delete_*`, `notebooks_deleted_*`, `notebooks_empty_*`. The Hebrew app name is not decided yet. The code and
  files keep the name `Lecture` (`lecture.json`).

## Live in windows (Nov 7, 2026)

- A model with `SpeechModel.liveWindowSeconds` (large-v3 on the CPU) runs live in ~30 s windows: build its
  `UtteranceTranscriber` with `limits = UtteranceLimits.window(seconds)` (Android: `Recording.kt`, where the live
  transcriber is made). `ModelChoices` already lists it for live only once downloaded. No new strings.

## Processor meters while recording (Nov 7, 2026)

- Settings › Advanced: `SettingsStore.processorMeters` (`ProcessorMeterStyle`: Off, Bars, Chip, Graphs; off by
  default). The moment screen shows `LiveLectureState.meterStyle`, `meters`, `meterHistory`; Android fills them in
  `MainActivity.kt` from `rememberProcessorMeters` (`ProcessorMetersSampling.kt`), once a second while the screen
  shows. Views to mirror: `ui/ProcessorMetersView.kt`. Strings `meters_*`, `settings_meters*`, `settings_advanced`.
- iOS needs its own `ProcessorProbe` in `sharedLogic/src/iosMain` feeding `ProcessorMeterSampler`: the app's CPU time
  from `task_info`/`thread_info` (`cpuSeconds`), `ProcessInfo.activeProcessorCount` (`cpuCores`). iOS exposes no GPU
  busy %, clocks or temperatures to apps: leave them null (those meters hide). NPU: time the Core ML calls with an
  `NpuBusyClock` if the Neural Engine is used, else null.

## Notebooks

- Android: `NotebooksScreen.kt` (mirror it in SwiftUI), `MainActivity.kt` (the screen as the root, Record with the
  microphone permission, the banner back to the moment), `Recording.kt` (`library`, `file`, `working`, `utcOffset`);
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
- The speaker models on Settings (Nov 6, 2026, round 3's d2): Speaker recognition (a Voice model row, the fewer ↔
  more slider under it) and Speaker turns (a Turn model row), each row opening the same speaker page (`SpeakerPage`:
  the note, `ModelsState.voiceModels`/`turnModels` to pick with `useVoicePrints`/`useSegmentation`, Find more on
  Hugging Face with `HubKind.VoicePrints`/`SpeakerTurns`). `Recording.voiceModel`/`segmentationModel` include the
  speaker models added from Hugging Face. Strings `settings_recognition*`, `settings_turns*`, `settings_voice_model`,
  `settings_turn_model`, `settings_no_model`, `models_find_more`, `models_kind_voice_prints`,
  `models_kind_speaker_turns`, `models_speaker_search_note`.
- Shared: `HubClient(fetch)` (iOS: `fetch` on URLSession), `HubModel.toSpeechModel`, `InstalledModels`,
  `ModelLibrary`, `SettingsStore.refineModel`/`setRefineModel`/`modelLanguages`.
- iOS has no Qualcomm NPU: the NPU filter is off, NPU builds show "not this phone". Core ML builds would be iOS's own.
- Strings: `models_*`.

## The moments folder

- Android: finished moments move from the app to a folder the user picks (`Documents/Transcriptor`), through
  `storage/SafFileSystem.kt` (an okio `FileSystem` on the documents provider) and `Recording.chooseFolder`/`archive`/
  `openAudio`; a `.nomedia` in it.
- Shared: `LectureStore.library` (a `Place`), `adoptLibrary`, `archiveFinished`, `SettingsStore.lectureFolder`.
- iOS: a folder picked with `UIDocumentPickerViewController` kept as a security-scoped bookmark (in
  `SettingsStore.lectureFolder`), an okio `FileSystem` over it (`startAccessingSecurityScopedResource`), no `.nomedia`.
- Strings: `folder_*`.

## Moment, while recording

- Android: `LectureScreen.kt` and `RecordingIndicators.kt` (the screen); `RecordingService.kt`, `Recording.kt` and
  `MainActivity.kt` (keeping it recording, and the temporary start screen); `docs/screens/lecture.md`.
- Shared, to call from SwiftUI: `LectureSession` (`state`, `start(notebook, title)`, `pauseOrResume()`,
  `addNote(atMillis, text)`, `stop()`), `timelineOf(lecture)`, `SettingsStore.audioFormat`, `SettingsStore.flacLevel` (0–8,
  passed to `AudioRecorder.start` and to `FlacEncode`), `FlacCompression` (`pending()`, `compress(lecture, progress)`).
- To write in `sharedLogic/src/iosMain`: an `AudioRecorder` on `AVAudioEngine` at 48 kHz stereo 16 bit, writing with
  `AVAudioFile` (`kAudioFormatLinearPCM` for WAV, `kAudioFormatFLAC` for FLAC live), and a `FlacEncode` that converts
  the WAV with `AVAudioFile`. The app needs the background audio mode and the microphone usage description.
- Strings: `lecture_*`, `recording_*`, `compressing_flac`, `start_*`, `format_*`, `flac_effort*` in `androidApp/src/main/res/values/strings.xml`
  (Hebrew in `values-iw/`).
- Speakers: `LiveLectureActions.renameSpeaker` → `LectureSession.renameSpeaker(id, name)`; the chip and the rename
  dialog in `LectureScreen.kt` (`SpeakerChip`, `RenameSpeakerDialog`), colors in `TranscriptorColors.speakers`,
  strings `speaker_*`. Voice prints on iOS: sherpa-onnx's iOS build with the same CAM++ model, as a `VoiceEmbedder`.
- The voice-print model picked (Nov 6, 2026): `SettingsStore.voiceModel`, `ModelCatalog.voicePrintModels`, each
  model's `VoicePrintThresholds` (pass `sameSpeaker` and the model id to `SpeakerTracker`/`SpeakerPass`, `clustering`
  to the diarizer), and `VoiceStore`'s model provider (prints per model). Android: `Recording.voiceModel`,
  `voiceEmbedder`, `SherpaDiarizer`; the speaker page in `ModelsScreen.kt` (`useVoicePrints`, strings
  `models_badge_speakers`, `models_by_hand`). The NPU builds are Android-only. On iOS the
  WeSpeaker models run on the CPU, or later on Core ML.
- The recording screen's models in use: `LiveLectureState.voicePrints` (`ModelInUse`, its `runs` set when the
  voice-print model loads, from `LiveModel.voicePrints`) and `speakerPassOnStop`; strings `lecture_speakers_model`,
  `lecture_model_*`, `lecture_speaker_pass_on_stop`; Android's `ModelsInUse` in `LectureScreen.kt`.
- Moment, after: `ReviewScreen.kt` (mirror it in SwiftUI), `LectureReview` (`state`, `open`, `close`, `reload`,
  `playPause`, `seek`, `playLine`, `cycleSpeed`, `editLine`, `setLineSpeaker`, `renameSpeaker`, `addNote`, `editNote`,
  `deleteNote`, `switchTo` (versions), `passes`); an `AudioPlayer` in iosMain on `AVAudioPlayer` (`enableRate` for the speed); strings
  `review_*`.
- After a moment: `SettingsStore.findSpeakersOnStop` (the speaker pass when it stops, on by default; files from before
  read the old `afterLecture`) and `autoImprove` (off by default): after Stop and the speaker pass, improve with the
  language's model if charging, else `addImprovePending` until the app is open on the charger (Android:
  `RecordingService` ACTION_STOP and `improveNextPending`, `MainActivity.improvePendingIfCharging`).
- Improve transcription… on the moment, always offered: `ReviewState.refineModels` (`ModelChoice` with `minutes`
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
- Tuning: `SettingsStore.speakerTuning`/`setSpeakerTuning` (a slider on the speakers page) and the review's
  `LectureReview.tuneSpeakers()`/`applySpeakerTuning` with `ReviewState.speakerCorrections` (strings `review_tune_*`);
  `SettingsStore.segmentationModel` and `ModelCatalog.segmentationModels` (DiariZen).
- Power per moment: `PowerMeter` with a `PowerProbe` (iOS: `UIDevice` battery level and state, thermal state,
  `ProcessInfo`/`task_info` CPU time; iOS gives no battery current, so the power figures are null there or
  estimated), `PowerReport` in `power.json` (`LectureStore.savePower`), `power-log.csv`
  (`LectureStore.appendPowerLog`), `ReviewState.power` and strings `review_power*`.
- Deleting moments (Nov 7, 2026): `NotebooksController.delete(ids, busy)` and `LectureStore.delete`; the Notebooks
  screen's hold-to-select, the selection bar and the Undo snackbar (the files go when it closes); strings
  `notebooks_deleted_*`, `notebooks_undo`, `notebooks_delete`, `notebooks_clear_selection`, `notebooks_selected`.
- Fixing a line while recording: `LectureSession.editLine` (the fixes laid over live lines); the moment screen's field
  in place, kept above the keyboard, the list laid out from the bottom with lines gliding up and words fading in;
  strings `lecture_edit_*`.
- Word export: `transcriptDocx`/`docxName` (shared), `LectureStore.word`/`saveWord`, `LectureReview.word`/`saveWord`;
  iOS shares with `UIActivityViewController` from a temporary file. Delete moment… on the moment; strings
  `review_share_word`, `review_save_word`, `review_word_saved`, `review_delete_*`.
