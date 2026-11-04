# Settings › Models

In the app (round 1's b4). The gear on Notebooks opens it.

## What it shows

- **Settings**: the lectures folder (Choose a folder / Change), the audio format, "Improve after the lecture", and a
  **Models** row saying whether every language in use has a live model.
- **Models**: chips for the languages in use (the notebooks', a lecture in none, the phone's, those added here) and
  Speakers; **Add a language** finds any language Whisper knows by name. Each model is a card: its facts, badges (Live,
  Refine, NPU, Many languages, From Hugging Face, the NPU check), its download at the end, and a menu (use for live,
  use to refine, delete or remove from the list, model page). A language with no models of its own says the
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
- Shared: `models/Hub.kt` (`HubClient`, `HubModel`, `HubVariant`, `HubKind`, `NpuBuild`, `LicenseUse`, the file
  classification), `models/InstalledModels.kt` (`models.json`, `ModelLibrary`), `SettingsStore.refineModel` and
  `modelLanguages`. `ModelFiles` checks a small Hub file by its git blob id.
- Strings: `models_*`, `folder_*`. Lab: `models current`, `models current-sm8750` (`lab/models/ModelsCurrentLab.kt`).

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/models/2026-11-04-first-round.md): b4 — catalog + Hugging Face + NPU on this
  phone (picked); a — one page, a row per job, b — a catalog, c — set up for you, b2, b3.
