# Get your models (first run)

The first screen of a new install: the models the app uses for the phone's language, got with one tap.

## Shown when

On launch, while first run hasn't been answered (`SettingsStore.firstRunDone`) and no model that transcribes is on
the phone (`DefaultModels.offerFirstRun`). A phone set up before first run existed never sees it.

## What it shows

- "Get your models", and that everything is transcribed on the phone.
- One row per model, by job: live transcript, improve after recording, telling voices apart, who speaks when, speakers after recording; each
  with its name and size, then its download: progress, Waiting for Wi-Fi, a check mark, or Retry.
- The total size, and **Download models over Wi-Fi only** (a checkbox, off by default: hen, Nov 16, 2026; the same
  switch as Settings › Advanced, `SettingsStore.modelsOnWifiOnly`, read as each download starts).
- Later and Download; once started, Continue (they go on downloading in the background) or, when all are here, Start.

## Behaviour

- The models are `DefaultModels.forLanguage(language, chip)` for the phone's language **and English** (hen, Nov 16,
  2026: the languages they record; one language when the phone is in English): per job the first pick that runs on
  this chip, so a Snapdragon 8 Elite (SM8750) gets the NPU build and other phones the CPU one. With two languages the
  live and improve rows name their language. All are catalog models: the most promising per job whatever its license
  (the owner's call); a non-commercial one shows its license on its row. Hebrew: Zipformer streaming (NPU, else CPU)
  live, ivrit.ai large-v3 with its encoder on the NPU (q8_0 on the CPU elsewhere) to improve. English: no live model
  (recorded, then transcribed after), Whisper large-v3 to improve. Every language: WeSpeaker ResNet293 voice prints;
  speaker turns DiariZen WavLM Large s80 on the SM8750 NPU (CC-BY-NC-4.0), pyannote elsewhere; and pyannote for the
  speaker pass after recording. English is added to Settings' languages.
- Download answers first run, makes them the language's live and improve choices where none was made
  (`SettingsStore.useDefaults`), and gets them smallest first through `ModelFiles` (the same downloads as Settings ›
  Models).
- An NPU build is tried on the NPU once it is downloaded (`Recording.checkNpu`); if it doesn't load, the next pick
  (the CPU build) becomes the choice and is got.
- Later answers first run without downloading; the models are in Settings › Models.

## Code

`androidApp/.../FirstRun.kt` (feeds it), `ui/FirstRunScreen.kt`, `models/DefaultModels.kt`; strings `first_run_*`.
