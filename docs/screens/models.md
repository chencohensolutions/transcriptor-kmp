# Settings and Manage models

In the app: Settings is round 2's b (a page per language) with round 3's d2 (the speaker models in view), Manage
models round 1's b4. The gear on Notebooks opens
Settings.

## What it shows

- **Settings**: **Languages**, one row per language in use (the moments', the phone's, those
  added) with the model that writes it live and the one that improves its moments ("Live: … / Improve: …", red when
  none is downloaded), and Add a language. **Speaker recognition**: its note, a **Voice model** row with the model in
  use and its facts ("3D-Speaker CAM++ · 7M", red "No model downloaded" without one), and under it the slider "How
  readily it starts a new speaker", Fewer ↔ More speakers, with Reset (one value per model,
  `SettingsStore.speakerTuning`). **Speaker turns**: its note and a **Turn model** row. **After a moment**: Find the
  speakers again after Stop
  (`SettingsStore.findSpeakersOnStop`, off by default since speakers are found live), Improve automatically (`autoImprove`, off by default). **Models and storage**:
  Manage models, the moments folder (Choose a folder / Change), the audio format, and under the two FLAC formats a
  slider for the FLAC compression effort, 0 (faster) to 8 (smaller file), 5 by default (`SettingsStore.flacLevel`;
  hidden for WAV). Its note says the sound is the same at every level, and what a higher level costs: time after the
  moment, or the phone while recording. **Advanced**: Processor meters while recording, one of Off (the default),
  Three bars under the top bar, A small chip, tap for details, or The last minute as graphs
  (`SettingsStore.processorMeters`, `ProcessorMeterStyle`); its note says what each meter counts. See
  [lecture.md](lecture.md#processor-meters).
- **A language's page** is the one place a model is chosen for a job: **Live transcription**, the models fast enough
  to keep up; **Improve transcription**, the Whisper models (CPU or NPU), the large ones first. Each list has the
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
  "From Hugging Face", and a non-commercial license in amber.
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
  check), its download at the end, and a menu (delete or remove from the list, model page). A language with no models of its own says the
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
- [Processor meters (Nov 7, 2026)](../ux-history/lecture/2026-11-07-processor-meters.md): the moment screen's round;
  all three styles were promoted, picked under Advanced.
