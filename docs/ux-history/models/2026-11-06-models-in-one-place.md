# Settings — the models chosen in one place (2026-11-06, Android)

Tag: `ux/models/2026-11-06`

The owner's asks: choose the live model in one place, and improve a lecture by hand with a model even when the
automatic pass is off. Before this round the live model was chosen in Settings › Models › a language's chip › a
model's ⋮ › Use for live, and "Improve after the lecture" both ran the speaker pass on Stop and hid Refine with… when
off. The lecture screen's side of the round is [review/2026-11-06](../review/2026-11-06-improve-by-hand.md).

## Options
- **a — A Transcription section**: Settings opens with a Live row and an Improve-with row per language, each naming
  its model and opening a sheet to pick one (downloaded first, the others with Get); Manage models is a library only.
  *Turned down* (close to round 1's a).
- **b — A page per language**: Settings lists the languages in use, each with its live and improve models
  ("Live: … / Improve: …"); a language's page has both choices as lists, the models to get under them. *Picked*, promoted in 12d0d8a.
- Both: "Improve after the lecture" becomes two switches, Find the speakers when a lecture stops and Improve
  automatically; Improve lists Whisper models only (the streaming ones re-transcribe nothing better); the role items
  (Use for live, Use to refine) leave the Models cards.

## What they faked
- The phone is an SM8750 (the NPU models show and pass the check); downloads (a timer, then checking); the languages
  (Hebrew, English); what is downloaded (Hebrew: turbo CPU and NPU, large-v3 NPU; English: Whisper large-v3 only).
- The two switches are in memory: the app has one, and nothing improves on its own yet.
- Manage models opens today's Settings › Models, with its Use for live / Use to refine items.

Screenshots (emulator, before the fixes in 46b3021): [2026-11-06-shots/](2026-11-06-shots/).

## Reviving an option
    git checkout ux/models/2026-11-06 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/models/a androidApp/src/debug/res/values/strings_models_a.xml androidApp/src/debug/res/values-iw/strings_models_a.xml
Then add `modelsA,` to `LabScreens`. Built against `ModelsScreen.kt` (`ModelsState`, `ModelsActions`, `NpuCheck`,
`speakerModels`, `modelsSize`), `ModelCatalog` and the app's strings as of the tag. Compile errors after reviving are
in the option's files; fix them there.
