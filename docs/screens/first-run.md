# Get your models (first run)

The first screen of a new install: the models the app uses for the phone's language, got with one tap.

## Shown when

On launch, while first run hasn't been answered (`SettingsStore.firstRunDone`) and no model that transcribes is on
the phone (`DefaultModels.offerFirstRun`). A phone set up before first run existed never sees it.

## What it shows

- "Get your models", and that everything is transcribed on the phone and downloads over Wi-Fi.
- One row per model, by job: live transcript, improve after recording, telling voices apart, who speaks when; each
  with its name and size, then its download: progress, Waiting for Wi-Fi, a check mark, or Retry.
- The total size.
- Later and Download; once started, Continue (they go on downloading in the background) or, when all are here, Start.

## Behaviour

- The models are `DefaultModels.forLanguage(phone language, chip)`: per job the first pick that runs on this chip, so a
  Snapdragon 8 Elite (SM8750) gets the NPU build and other phones the CPU one. All are catalog models (free for
  commercial use). Hebrew: Zipformer streaming (NPU, else CPU) live, ivrit.ai large-v3 q8_0 to improve. English:
  Whisper large-v3 turbo live, Whisper large-v3 to improve. Every language: CAM++ voice prints, pyannote segmentation.
- Download answers first run, makes them the language's live and improve choices where none was made
  (`SettingsStore.useDefaults`), and gets them smallest first through `ModelFiles` (the same downloads as Settings ›
  Models).
- An NPU build is tried on the NPU once it is downloaded (`Recording.checkNpu`); if it doesn't load, the next pick
  (the CPU build) becomes the choice and is got.
- Later answers first run without downloading; the models are in Settings › Models.

## Code

`androidApp/.../FirstRun.kt` (feeds it), `ui/FirstRunScreen.kt`, `models/DefaultModels.kt`; strings `first_run_*`.
