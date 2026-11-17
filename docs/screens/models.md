# Settings and Manage models

In the app: Settings is round 2's b (a page per language) with round 3's d2 (the speaker models in view), Manage
models round 1's b4. The gear on Notebooks opens
Settings.

## Two pages (Nov 15, 2026)

Settings opens on the plain choices: **Languages** (a language with nothing downloaded says "No model yet. Tap to get
one." in one calm line), **After recording** (Transcribe again automatically), and **Recordings** (the moments folder, the
audio format and FLAC effort), then an **Advanced** row. **Settings › Advanced** (`AdvancedPage`) holds what most
people never change: Speaker recognition with its slider, Speaker turns, Find the speakers again after Stop, Manage
models, and the processor meters. The speaker pages and the model library open from Advanced and return to it. What
each section does is as described below.

## Recommended models (Nov 15, 2026)

On the first page each language says where it stands against its recommended models (`DefaultModels.forLanguage`
for this phone's chip; `DefaultModels.setup`, `ModelsState.setup`), without model names:

- **Recommended models · ready**: they are downloaded and are the ones that run.
- **No model yet.** with **Get the recommended models · 1.7 GB**: nothing transcribes the language. One tap downloads
  what is missing and picks them for live and for improving (`ModelsActions.download`, `useLive`, `useRefine`); the row
  says "Getting the recommended models…" meanwhile.
- **Live: … / After: …** with **Use the recommended models** (or **Get … · size** when some are missing): other
  models run, the user's own choice or what was on the phone before.

There are no Fast / Balanced / Best tiers: each language has one combination worth recommending, and a second tier
would only offer models that are worse. The language's page still lists every model for another choice.

## The owner's settings as the defaults (Nov 15, 2026)

A new install starts as the owner's S25 was set that day (read from its `settings.json`):

- **Audio**: AAC as it records, LC, 48 kHz, stereo, 256 kbps (about 115 MB an hour); FLAC effort 8 for the FLAC ways.
- **Record bar**: Live. After stays one tap away and is remembered.
- **Recommended models** (`DefaultModels`): Hebrew live on the streaming Zipformer (NPU on the S25) and improved by
  ivrit.ai large-v3 with its encoder on the NPU (the q8 build on the CPU elsewhere); voice prints by WeSpeaker
  ResNet293-LM; live speaker turns by DiariZen WavLM Large s80 on the S25's NPU (now in the catalog, with the id it
  had when added from Hugging Face), then the Base build, then pyannote's.
- **Fewer ↔ more speakers** starts at the owner's value per voice model (`DEFAULT_SPEAKER_TUNING`: ResNet293 +0.5,
  CAM++ -0.2, ERes2NetV2 -0.3); a slider put back to the middle stays there.
- **Processor meters**: the last minute as graphs.

## Microphone (Nov 15, 2026)

hen's ask: pick the microphone, Bluetooth too. Settings › Recording format › **Microphone** (at the top since Nov 17, 2026: it decides which settings are offered). Since Nov 17, 2026 it also lists the phone's own microphones one by one, as the phone names them by place (`MicInput.Kind.BuiltIn`, key `builtin:<address>`: on the S25 "bottom" and "back"; the stereo pair is the default, The phone's microphones). Microphone is its own page (`FormatPage.Mic`, `MicrophonePage` in `ui/MicMap.kt`; hen's ask, Nov 17, 2026), with a **Test**: while the page is open it listens to the picked microphone (`MicProbe`; not while a moment records, nothing kept) and shows both channels' levels, and the picked dot on the drawing glows with what it hears, so scratching near a microphone shows which one it is. The page draws the phone from the front with its own microphones on it (`PhoneMicMap` in `ui/MicMap.kt`: filled dot on an edge or the front, a ring on the back, the picked one in the accent color), from the positions Android gives (`AudioManager.getMicrophones`, `MicDetail`), else from each one's place name; each built-in row says what that microphone is for and, when the phone says, how it hears (omni or directional, the level it clips at) (`SettingsStore.micInput`, a
`MicInput`; null, the default: the phone's own microphones, stereo 48 kHz) opens a list: the phone's own, then every
input connected now besides it (`AndroidAudioInputs`, refreshed as devices come and go): Bluetooth headsets (hands-free,
mono, phone-call quality), Bluetooth LE Audio earbuds, wired headsets, USB microphones. The one picked stays picked
while it is away ("not connected: records from the phone"). Recording from Bluetooth sets it as the communication device
and records as a call's voice (`VOICE_COMMUNICATION`), the only way a hands-free headset's microphone opens; wired and
USB are just the preferred device. The file is 48 kHz stereo either way (the phone converts), so a mono headset's meter
shows one set. The recording screen says "Microphone: <name>", or in red "<name> isn't connected: recording from the
phone" (also when it disconnects mid-moment: `AudioRecorder.inputInUse` follows the phone's routing).

## Transcribe moments recorded with After (Nov 16, 2026)

hen's ask: Settings › After a moment › **Transcribe moments recorded with After** (`SettingsStore.transcribeWhen`, an
`EncodeAfter`): **Right after the moment ends**, or **When charging, with 30% battery or more** (the default). A moment
recorded with After on the Record bar is transcribed at Stop when that allows it (`RecordingService.transcribeAllowed`),
else it waits in `improvePending` and starts when the phone charges with enough battery and the app is opened or
plugged in while open, or after the next pass ends. "No transcript" moments are never transcribed on their own.

## Hide the recording notification (Nov 15, 2026)

Settings › Recordings has **Hide the recording notification** (`SettingsStore.quietRecording`, off by default). On,
a recording's notification is the app's name only, on its own channel of the lowest importance: silent, not on the
lock screen, no clock and no Pause, Mark or Stop (`RecordingService.recordingNotification`). Android keeps one entry
for an app that records in the background, so the row says so and links to the phone's notification settings for
the app, where its notifications can be turned off altogether. The phone's own microphone indicator is not ours to
hide.

Under it, **Two taps to stop** (`SettingsStore.stopTwice`, off by default; owner's choice, Nov 15, 2026): on, Stop in
the recording screens arms first (`rememberArmedStop`) and the Record tile asks "Tap again to stop"; the second tap
stops.

## What it shows

- **Settings**: **Languages**, one row per language in use (the moments', the phone's, those
  added) with the model that writes it live and the one that improves its moments ("Live: … / After: …", red when
  none is downloaded), and Add a language. **Speaker recognition**: its note, a **Voice model** row with the model in
  use and its facts ("3D-Speaker CAM++ · 7M", red "No model downloaded" without one), and under it the slider "How
  readily it starts a new speaker", Fewer ↔ More speakers, with Reset (one value per model,
  `SettingsStore.speakerTuning`). **Speaker turns**: its note and a **Turn model** row. **After a moment**: Find the
  speakers again after Stop
  (`SettingsStore.findSpeakersOnStop`, off by default since speakers are found live), Transcribe again automatically (`autoImprove`, off by default). **Models and storage**:
  Manage models, the moments folder (Choose a folder / Change), **Server backup** (off, or on to which server; its own
  page: [server.md](server.md)), and a **Recording format** row whose line sums up the
  choice: the presets ("Normal quality · Right away") and under them the codec ("AAC · LC · 64 kbps · Mono · 48 kHz ·
  hardware · while recording"). It opens the Recording format page. At its top, **Quality**: High (FLAC, 48 kHz stereo,
  16 bit: the S25 gives its own microphones only as 16 bit), Normal (AAC-LC, 48 kHz stereo, 256 kbps), Low (AAC-LC, 24 kHz mono, 48 kbps), with a fourth, Custom,
  shown selected only while the settings match none; picking a preset keeps "WAV first" and the AAC encoder. Then
  **Battery**: Save battery (WAV first; encoding and After transcription on the charger at 30% or more) or Right away
  (encoding and After transcription right after Stop), Custom likewise (`QualityPreset`, `PowerPreset`,
  `RecordingChoice`, `SettingsStore.recordingChoice`). Under Quality, about how many hours fit in the free space (`megabytesPerHourWhileRecording`: the WAV when it records as WAV first). Under Battery, **Transcribe moments recorded with After**: right after or when charging (`transcribeWhen`; moved here from Settings › After a moment, Nov 17, 2026). Then **WAV**, **AAC** and **FLAC** as radio rows (AAC and FLAC with a › that opens their page; choosing one opens it), and
  under them **Record as WAV, encode afterwards** (greyed out for WAV) with **When the moment ends** or **When charging,
  with 30% battery or more** (`SettingsStore.audioFormat`: `wav`, `aac-live`, `wav-then-aac`, `flac-live`,
  `wav-then-flac`; `SettingsStore.encodeAfter`). The **AAC** page: AAC profile (LC, HE, HE v2), sample rate (16, 24,
  48 kHz; HE needs 24 or 48), channels (mono, stereo; HE v2 is stereo only), bitrate as chips from 16 to 320 kbps with
  the size an hour (a note above 64 kbps for HE), and the encoder: Automatic (hardware first) or any of the phone's AAC
  encoders, with the one that runs and whether it is hardware (`SettingsStore.aac`, `AacSettings`, `AacEncoders`).
  Only what the phone supports is shown: AAC profiles and bitrates some encoder takes, sample rates and channels the
  profile allows; FLAC's **Bit depth** row only when the microphone in use lists a deeper encoding than 16 bit
  (`AndroidAudioInputs.deepCapture`; the S25's own microphones don't, a USB one may), else FLAC records 16 bit.
  The **FLAC** page: sample rate (16, 24, 48 kHz) with the size an hour, channels (mono, stereo), bit depth (16, 24;
  24 reads the microphone in floating point and keeps 24 bits in the WAV and FLAC) (`SettingsStore.flac`,
  `FlacFormat`), and the compression effort, 0 (faster) to 8 (smaller file) (`SettingsStore.flacLevel`).
  Both pages say when they encode, when it is afterwards. **Advanced**: first **Advanced mode** (off by default): on, the main screen's Record bar also shows Quality and Battery
  ([notebooks.md](notebooks.md)). Processor meters while recording, one of Off (the default),
  Three bars under the top bar, A small chip, tap for details, or The last minute as graphs
  (`SettingsStore.processorMeters`, `ProcessorMeterStyle`); its note says what each meter counts. See
  [lecture.md](lecture.md#processor-meters).
- **A language's page** is the one place a model is chosen for a job: **Live transcription**, the models fast enough
  to keep up; **After recording** (was Improve transcription; the model for moments recorded with After, for transcribing
  again automatically, and picked first in a moment's Transcribe again), the Whisper models (CPU or NPU), the large ones first, then the Zipformer (CPU or
  NPU; hen's ask, Nov 16, 2026). Each list has the
  downloaded models to pick (a radio), then the others with their download; a model that does both is offered to get
  once, under Live. A large model that runs live in ~30 s windows (large-v3 on the CPU, `liveWindowSeconds`) is got
  under Improve and, once downloaded, can also be picked under Live, after the line models. One that fails the NPU
  check says so and can't be picked. "Find more models for …" opens the
  catalog on the language. The lists come from `ModelChoices` (shared).
- **A speaker page** (the Voice model or Turn model row): the same page for both jobs, titled Speaker recognition or
  Speaker turns, with the job's note, its models (the downloaded ones to pick with a radio, the others with their
  download), then **Find more on Hugging Face**. Picking marks the model and stays on the page; it applies from the
  next moment or speaker pass (`ModelsActions.useVoicePrints` / `useSegmentation`, `SettingsStore.voiceModel` /
  `segmentationModel`). Each model shows its parameters, "NPU" when its NPU build is on this phone, "Copied by hand",
  "From Hugging Face", and a non-commercial license in amber. Its size shows when it is known (never "0 MB").
- **Freeing a model** (a language's page and a speaker page, each row's ⋮; Manage models' card menu): **Remove** for
  a model added from Hugging Face (its files and its entry go), **Delete download** for a built-in model on the phone
  (its files go, it stays listed to download again); nothing for one copied by hand (`ModelChoices.removal`). Both ask
  first, saying that a job it is chosen for goes back to its default (pyannote for turns, the first ready model live;
  `SettingsStore.forgetModel`). While recording or improving the dialog only says to stop first (`ModelsState.busy`).
  - **Which models:** voice prints: the catalog's (CAM++, ERes2NetV2), those copied by hand (WeSpeaker ResNet293-LM,
    SimAM-ResNet100) once their folder is on the phone, and those added from Hugging Face. Turns: pyannote's,
    DiariZen's once copied by hand, and those added from Hugging Face (`ModelsState.voiceModels`, `turnModels`).
  - **Find more on Hugging Face** opens the search for that job only (Voice prints or Speaker turns; no language or
    NPU filter): sherpa-onnx exports, found by their names, each ONNX file a model to pick in the sheet, installed as
    `model.onnx`. Other ONNX exports lack the metadata sherpa-onnx reads and are counted, not listed.
- **Speakers** (the chip in Manage models): a library of the speaker models (download, delete, remove, model page),
  with "Speakers ✓" and "Turns ✓" on the ones in use. Picking and the slider are on Settings.
- **Manage models**: chips for the languages in use and Speakers; **Add a language** finds any language Whisper knows
  by name. Each model is a card: its facts, badges (Live, Improve, NPU, Many languages, From Hugging Face, the NPU
  check), its download at the end, and a menu (Delete download or Remove, asked first as above; model page). A language with no models of its own says the
  multilingual ones transcribe it. No storage bar (the owner's choice).
- **Find on Hugging Face**: a search of Hugging Face's public API, filtered by language (the one the catalog shows,
  to start), by kind (Whisper, Streaming; Parakeet, SenseVoice, Moonshine, Paraformer are listed as not in the app
  yet), and by **NPU on this phone (SM8750)** (greyed out on a phone without a Qualcomm NPU). Only repos with files the
  app's engines read are listed; the others are counted. Each result: its license (green for commercial use, amber
  for not, grey for none), kind, NPU fit (this chip; another chip; this chip with another QNN version, "may not
  load"), languages, likes and sizes. A repo's sheet says what its license allows, pins the sizes to the repo's
  commit, and adds one (asking first for a non-commercial or unlicensed model).
- **The NPU check**: once an NPU model is downloaded it is loaded on the NPU with a second of silence ("Trying the
  NPU…", then "Runs on this phone's NPU ✓" or "Didn't load on this NPU" with the runtime's reason); one that fails
  can't be chosen.

## Code

- Screen: `androidApp/.../ui/ModelsScreen.kt` (`ModelsScreen`, `ModelsState`, `ModelsActions`, `NpuCheck`); fed by
  `Settings.kt` (package root). `Recording.hub` (the API over HTTPS), `Recording.checkNpu`, `Recording.modelLibrary`.
- Settings' speaker models: `Recording.voiceModel` and `segmentationModel` read the catalog's and those added from
  Hugging Face.
- Shared: `models/ModelChoices.kt` (`ModelJob`, the lists per job, the model that runs, the one to suggest, the
  estimate), `models/Hub.kt` (`HubClient`, `HubModel`, `HubVariant`, `HubKind` (with `VoicePrints`, `SpeakerTurns`), `NpuBuild`,
  `LicenseUse`, the file classification, `speakerKind`), `models/InstalledModels.kt` (`models.json`, `ModelLibrary`), `SettingsStore.refineModel`, `findSpeakersOnStop`, `autoImprove` and
  `modelLanguages`. `ModelFiles` checks a small Hub file by its git blob id.
- Strings: `models_*`, `folder_*`. Strings also `settings_*`. Lab: `models current`, `models current-sm8750` (`lab/models/ModelsCurrentLab.kt`).

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/models/2026-11-04-first-round.md): b4 — catalog + Hugging Face + NPU on this
  phone (picked); a — one page, a row per job, b — a catalog, c — set up for you, b2, b3.
- [Round 2 (Nov 6, 2026)](../ux-history/models/2026-11-06-models-in-one-place.md): b — a page per language (picked); a — a
  Transcription section with a row per language and job.
- [Round 3 (Nov 6, 2026)](../ux-history/models/2026-11-06-speakers-in-view.md): d2 — a section per speaker job, picked on
  its own page with a Hugging Face search (picked); a, b, c, d, d3 — the speaker models in other places on Settings.
- [Recording format (Nov 15, 2026)](../ux-history/models/2026-11-15-recording-format.md): b3 — WAV, AAC, FLAC on a
  page of their own, "Record as WAV, encode afterwards" under them, AAC and FLAC pages (picked); a, b, b2, c.
- [Processor meters (Nov 7, 2026)](../ux-history/lecture/2026-11-07-processor-meters.md): the moment screen's round;
  all three styles were promoted, picked under Advanced.
