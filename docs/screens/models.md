# Settings and Manage models

In the app: Settings is round 2's b (a page per language), Manage models round 1's b4. The gear on Notebooks opens
Settings.

## What it shows

- **Settings**: **Languages**, one row per language in use (the notebooks', a lecture in none, the phone's, those
  added) with the model that writes it live and the one that improves its lectures ("Live: … / Improve: …", red when
  none is downloaded), and Add a language. **After a lecture**: Find the speakers when it stops
  (`SettingsStore.findSpeakersOnStop`), Improve automatically (`autoImprove`, off by default). **Models and storage**:
  Manage models, the lectures folder (Choose a folder / Change), the audio format.
- **A language's page** is the one place a model is chosen for a job: **Live transcription**, the models fast enough
  to keep up; **Improve transcription**, the Whisper models (CPU or NPU), the large ones first. Each list has the
  downloaded models to pick (a radio), then the others with their download; a model that does both is offered to get
  once, under Live. One that fails the NPU check says so and can't be picked. "Find more models for …" opens the
  catalog on the language. The lists come from `ModelChoices` (shared).
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
- Shared: `models/ModelChoices.kt` (`ModelJob`, the lists per job, the model that runs, the one to suggest, the
  estimate), `models/Hub.kt` (`HubClient`, `HubModel`, `HubVariant`, `HubKind`, `NpuBuild`, `LicenseUse`, the file
  classification), `models/InstalledModels.kt` (`models.json`, `ModelLibrary`), `SettingsStore.refineModel`, `findSpeakersOnStop`, `autoImprove` and
  `modelLanguages`. `ModelFiles` checks a small Hub file by its git blob id.
- Strings: `models_*`, `folder_*`. Strings also `settings_*`. Lab: `models current`, `models current-sm8750` (`lab/models/ModelsCurrentLab.kt`).

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/models/2026-11-04-first-round.md): b4 — catalog + Hugging Face + NPU on this
  phone (picked); a — one page, a row per job, b — a catalog, c — set up for you, b2, b3.
- [Round 2 (Nov 6, 2026)](../ux-history/models/2026-11-06-models-in-one-place.md): b — a page per language (picked); a — a
  Transcription section with a row per language and job.
