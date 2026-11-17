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

## Notebooks (moments and labels)

- Android: `NotebooksScreen.kt` (mirror it in SwiftUI: chips by label, the Record bar's labels and language, the
  labels sheet), `MainActivity.kt` (the screen as the root, Record with the microphone permission, the banner back to
  the moment), `Recording.kt` (`library`, `setLabels`, `deleteLabel`, `working`, `utcOffset`, `migrateLibrary`);
  `docs/screens/notebooks.md`.
- Shared, to call from SwiftUI: `NotebooksController` (`library`, `reload()` off the main thread, `newLabel`,
  `setLabels(ids, add, remove, busy)`, `renameLabel`, `deleteLabel(id, busy)`, `delete`),
  `notebooksState(library, now, utcOffset, recording, working)`, `RecordingNow`, `labelNames`, `LabelStore`.
  `utcOffset` on iOS: `NSTimeZone.localTimeZone.secondsFromGMTForDate` × 1000.
- At launch: `migrateToLabels(local, now, utcOffset, into = library ?: local)`, then for the moments folder before the
  first list (an iOS app has had no notebooks, so this only matters for a folder shared with Android).
- Recording: `LectureSession.start(labels, labelNames, language, title)`.
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
- Freeing a model (Nov 15, 2026): a row menu on the language and speaker pages and the card menu offer
  `ModelChoices.removal(model, downloaded)` (Remove: delete the files, `InstalledModels.remove`; Delete download:
  delete the files), confirmed in a dialog, refused while recording or improving; both call
  `SettingsStore.forgetModel(id)` so its jobs go back to their defaults. Sizes ≤ 0 aren't shown. Strings
  `models_delete_download`, `models_remove_*`, `models_delete_title`/`_text`, `models_removal_chosen`/`_busy`.
- Strings: `models_*`.

## The moments folder

- Android: finished moments move from the app to a folder the user picks (`Documents/Audio Logs`), through
  `storage/SafFileSystem.kt` (an okio `FileSystem` on the documents provider) and `Recording.chooseFolder`/`archive`/
  `openAudio`; a `.nomedia` in it.
- Shared: `LectureStore.library` (a `Place`), `adoptLibrary`, `archiveFinished`, `SettingsStore.lectureFolder`.
- iOS: a folder picked with `UIDocumentPickerViewController` kept as a security-scoped bookmark (in
  `SettingsStore.lectureFolder`), an okio `FileSystem` over it (`startAccessingSecurityScopedResource`), no `.nomedia`.
- Strings: `folder_*`.

## Moment, while recording

- Android: `LectureScreen.kt` and `RecordingIndicators.kt` (the screen); `RecordingService.kt`, `Recording.kt` and
  `MainActivity.kt` (keeping it recording, and the temporary start screen); `docs/screens/lecture.md`.
- Shared, to call from SwiftUI: `LectureSession` (`state`, `start(labels, labelNames, language, title)`, `pauseOrResume()`,
  `addNote(atMillis, text)`, `stop()`), `timelineOf(lecture)`, `SettingsStore.audioFormat`, `SettingsStore.flacLevel` (0–8,
  passed to `AudioRecorder.start` and to `FlacEncode`), `AudioCompression` (`pending()`, `compress(lecture, progress)`).
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
  `ImproveAfterStop.of(...)` decides (Nov 15, 2026): a moment whose live model was skipped for memory
  (`LiveLectureState.improveOnStop`) is improved right after Stop; string `lecture_model_skipped_improve`. An improve
  pass stops when a recording starts (`RefinePass.run(stop = …)`, `RefinePass.Stopped`) and runs again after it.
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
- Recording format (Nov 15, 2026): `AudioFormat.AacLive` and `WavThenAac`, `AacSettings` (`SettingsStore.aac`),
  `EncodeAfter` (`SettingsStore.encodeAfter`; `allows(charging, batteryPercent)`, `ENCODE_MIN_BATTERY`),
  `Lecture.pendingAac`, `AudioCompression` with an `AacEncode`, `PcmReducer` and `Adts` (shared). iOS: an
  `AudioRecorder` with an AAC sink (AVAudioConverter or AudioToolbox; Apple's AAC encoder is hardware-backed) writing
  ADTS, the `AacEncode` for encoding afterwards, a BGProcessingTask with `requiresExternalPower` for "when charging"
  (Android's `EncodeJob`), and Settings' Recording format pages (`ui/RecordingFormat.kt`; strings `rec_format_*`).
  `PowerReport.audio` / `phase` and `power-encode.json` go with the power log above.

## Record now, transcribe after (Nov 15, 2026)

- Shared: `SettingsStore.recordLive`, `LectureSession.start(transcribeLive)`, `LiveLectureState.recordOnly`,
  `Lecture.pendingTranscript`, `LectureSummary.pendingTranscript`, `NotebooksState.recordLive`, `lineTimes` and
  `transcribed` (`speech/LineTimes.kt`).
- Android to mirror: the Live/After chips on the Record bar (`NotebooksScreen.kt`), the status line
  (`LectureScreen.kt`), the Not transcribed yet card (`NotTranscribedYet` in `ReviewScreen.kt`), and the pass itself
  (`Recording.transcribe`, queued in `RecordingService`: at Stop on the charger, else when it charges).
- Strings: `notebooks_record_*`, `notebooks_not_transcribed`, `lecture_record_only`, `transcribing`,
  `review_transcribing`, `review_waiting_*`, `review_transcribe_now*`.

## Recording at a glance, and Mark (Nov 15, 2026)

- Shared: `LectureSession.mark`, `Note.isMark` (exports show a mark as ⚑ and its time).
- Android to mirror: `ui/RecordingGlance.kt`, `LectureScreen(startInTranscript)`, the flag on `NotePill`
  (`ReviewScreen.kt`), the notification's Mark action (`RecordingService`).
- Strings: `glance_*` (plurals `glance_people`, `glance_notes`), `note_mark`.

## Who was here? (Nov 15, 2026)

- Shared: `review/People.kt` (`Person`, `peopleOf`, `Lecture.mergingSpeakers`), `ReviewState.people` and `knownNames`,
  `LectureReview.namePerson`, `mergeSpeakers`, `playPerson`.
- Android to mirror: `ui/PeopleSheet.kt`, the people button and `askPeople` in `ReviewScreen.kt`.
- Strings: `people_*` (with the plurals `people_summary`, `people_lines`). Lab sample: `LabData.conversation`.

## The moment as a conversation (Nov 15, 2026)

- Shared: `Lecture.onlyOf`, `LectureReview.playOnly`, `ReviewState.onlySpeaker`; `transcriptText`, `LectureReview.text`.
- Android to mirror in `ReviewScreen.kt`: compact turns in `Turn`, the live-draft line, the people chips above the
  player and the faded lanes in `Overview`, the share menu.
- Strings: `review_share`, `review_share_text`, `review_draft*`.

## Finding moments (Nov 15, 2026)

- Shared: `LectureSummary.people`, `unnamedPeople`, `preview` (`Lecture.summary`, `previewOf`); `notebook/Search.kt`
  (`SearchHit`, `searchMoments`), `NotebooksController.search`; `LectureReview.rename`, `setLabels`, `open(atMillis)`.
- Android to mirror: the search bar and `SearchResults`, `peopleLine` and the preview in `MomentRow`, the one list by
  date (`NotebooksScreen.kt`); the title and labels taps, `RenameTitleDialog` (`ReviewScreen.kt`).
- Strings: `notebooks_search*`, `notebooks_people_more`, `notebooks_add_label_quiet`, `review_rename*`,
  `review_add_labels`, plurals `notebooks_voices`, `notebooks_search_more`. Lab sample: `LabData.conversations`.
- First run, "Get your models" (Nov 15, 2026): `DefaultModels.forLanguage`/`offerFirstRun`, `SettingsStore.firstRunDone`
  and `useDefaults`; the screen in `FirstRunScreen.kt` fed by `FirstRun.kt` (downloads, the NPU check and its CPU
  fallback); strings `first_run_*`. iOS has no NPU builds: it gets the CPU picks.

## Sound level meter (Nov 15, 2026)

- `AudioRecorder.meter` (`StereoMeterState`) and `wantBands`: iOS's recorder feeds a shared `MeterAnalyzer` (`feed`
  every buffer, `read` about 10 times a second, `silence` on pause, `reset` on start), as `AndroidRecorder` does.
- `SettingsStore.levelMeter` (`LevelMeterStyle`), shown as `LiveLectureState.levelMeter`; set `LectureSession.meterBands`
  while the screen shows a style with `needsBands`. Views to mirror: `ui/LevelMeters.kt`. Strings `lecture_meter_*`,
  `settings_level_meter*`.

## Microphone (Nov 15, 2026)

- `SettingsStore.micInput` (`MicInput`: key, name, kind); `AudioRecorder.start(..., input)` and `inputInUse`;
  `LiveLectureState.input` / `inputMissing`. iOS: list `AVAudioSession.availableInputs` (Bluetooth HFP, wired, USB),
  `setPreferredInput`, the `.allowBluetooth` category option, and follow `routeChangeNotification` for `inputInUse`.
  Strings `settings_mic*`, `glance_mic*`. Android: `AndroidAudioInputs.kt`, `MicrophoneRow` in `ModelsScreen.kt`.
## Record from the system's controls (Nov 15, 2026)

- Android: a Quick Settings tile (`RecordTileService.kt`) records as the Record bar would (`notebooksState`'s guess and
  language, `SettingsStore.recordLive`) and stops with a second tap. iOS: a Control Center control (`ControlWidget`,
  iOS 18) with an App Intent doing the same. Strings `tile_stop_again`, `tile_finishing`.

## Moment info (Nov 16, 2026)

- `LectureReview.info()` (`MomentInfo`: the lecture, `LectureStore.audioBytes`, `loadPower`, `loadEncodePower`);
  `PowerAudio.input` (the microphone). View to mirror: `ui/MomentInfoSheet.kt`, menu item `review_info`; strings `info_*`.
  language, `SettingsStore.recordLive`) and stops with one tap. iOS: a Control Center control (`ControlWidget`,
  iOS 18) with an App Intent doing the same. String `tile_finishing`.

## Server backup (Nov 16, 2026)

- Android: Settings › Server backup ([screens/server.md](screens/server.md)) pairs Audio Logs with the journal server
  through shed and backs up finished moments. Shared: `ServerBackup` (`state: StateFlow<ServerState>`, `preview`,
  `pair`, `unpair`, `setWifiOnly`, `backUpFinished(busy)`, `runUploads`, `syncServerList`, `refresh`, `stateOf`,
  `locator`). Make it with shed's `Shed` and `JournalBackend` and call `attach(shed, journal)`, as `Recording.init`
  does.
- iOS needs shed's iOS pieces, not built yet: `DeviceKeys` in the Secure Enclave (an identity mTLS can present), a
  `JournalClients` on Ktor's Darwin engine that pins the server's key and presents the identity, and uploads in the
  background (`BGProcessingTask`, or a background `URLSession`). See shed-kmp's README.
- Files to mirror: `ui/ServerScreen.kt`, `ServerSettings.kt`, the Server row in `ui/ModelsScreen.kt`. Strings
  `server_*`.


## Calls, tucked and private moments (Nov 16, 2026)

- Android: the moments list's selection ⋮ has Tuck away / Bring back and Mark private…; tucked moments hide behind a
  pill per group that asks for the phone's lock (`BiometricPrompt`, biometric or device credential; iOS:
  `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`); Settings › Server backup has Calls and Voice messages
  switches. Docs: screens/notebooks.md, screens/server.md, design.md "Calls, tucked and private moments".
- Shared: `NotebooksController.setTucked(ids, tucked, busy)`, `LectureSummary.tucked`, `ServerBackup.setTucked`,
  `ServerBackup.makePrivate(id, busy)` (`PrivateResult`), `ServerBackup.setShown`, `ServerState.shown` and
  `tuckedOnServer`. Android files to mirror: `ui/NotebooksScreen.kt` (`TuckedPill`, `PrivateDialog`, `PrivateOutcome`),
  `MainActivity.askPhoneLock`, `Recording.setTucked` / `makePrivate`, `ui/ServerScreen.kt` (`ShowSwitch`). Strings
  `notebooks_tuck*`, `notebooks_private*`, `notebooks_unlock_tucked`, `notebooks_more`, `notebooks_cancel`,
  `server_show*`, `server_tucked_on_server`.


## Server moments, Free up space and the cache limit (Nov 16, 2026)

- Android: the server's recordings (and calls and voice messages when shown) appear as moments; rows carry Call /
  Voice message / On the server marks (`MomentMark` in `ui/NotebooksScreen.kt`); opening one whose audio is on the
  server only shows a progress dialog while it comes (`MainActivity.openLecture`, `BringingDialog`). Settings › Server
  backup has Free up space (rule chips, plan, button, progress, result) and Cache on this phone (2/10/20/50 GB chips).
  Docs: design.md "Server backup", screens/server.md, screens/notebooks.md.
- Shared: `LectureSummary.audioOnServer` and `source`; `ServerBackup.adoptServerRecordings`, `syncNotes`,
  `bringAudio(id, progress)`, `freeUpPlan(rule, now, busy)` (`FreeUpRule`, `FreeUpPlan`), `freeUp(ids, busy,
  progress)` (`FreeUpResult`), `setCacheLimit`, `refreshCache`, `dismiss`; `ServerState.cacheLimitBytes` and
  `cacheBytes`. Android files to mirror: `Recording.backUp` / `refreshServerMoments` / `freeUp` / `freeUpPlan`,
  `ServerSettings.kt`, `ui/ServerScreen.kt` (`FreeUpSection`). Strings `server_free*`, `server_cache*`, `bring_*`,
  `notebooks_on_server`, `notebooks_source_*`.


## Pages, streaming, labels on the server, locks (Nov 16, 2026)

- Android: the moments list reads the server's recordings by pages as it scrolls (`NotebooksActions.olderFromServer` /
  `newerFromServer`, `NotebooksState.serverOlder` / `serverNewer`); opening one makes it a moment here and plays it as
  it streams (no download dialog); Settings › Server backup has Lock calls / Lock voice messages; a label's edit dialog
  has Needs fingerprint; locked moments wait behind the same pill as tucked ones, after the phone's lock.
- Shared: `ServerMoments` (window of pages), `ServerBackup.momentHere`, `summaryOf`, `streamOf` (`AudioStream`: iOS
  plays it with an `AVAssetResourceLoaderDelegate`), `syncLabels`, `tidyServerMoments`, `NotebooksController.setServer`,
  `setLabelLocked`, `locksChanged`, `Locks`, `LectureSummary.locked`, `Label.locked`, settings `lockCalls`,
  `lockVoiceMessages`. Android files: `Recording.kt` (`showServerMoments`, `momentsHere`, `audioHere`, `setLocks`),
  `AndroidAudioPlayer.kt` (`StreamedSource`), `ui/NotebooksScreen.kt`, `ui/ServerScreen.kt`. Strings `server_lock*`,
  `notebooks_locked`, `notebooks_label_locked*`.
- Recording presets (Nov 17, 2026): `QualityPreset`, `PowerPreset`, `RecordingChoice` (`SettingsStore.recordingChoice`),
  `FlacFormat` (`SettingsStore.flac`: FLAC's rate, channels, 16/24 bit; the bit depth also the WAV's). iOS's recorder
  should read 24 bit as floats when `flac.bitDepth` is 24 and hand 16 bits to live transcription (`PcmConvert`,
  `PcmReducer(bits = 24)`). UI: `ui/RecordingFormat.kt` (`Presets`, `FlacFormatPart`), strings `rec_preset_*`,
  `rec_format_depth*`, `rec_format_flac_*`.
