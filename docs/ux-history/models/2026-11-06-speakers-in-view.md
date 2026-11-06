# Settings — the speaker models in view (2026-11-06, Android)

Tag: `ux/models/2026-11-06-2`

The owner's asks: the model settings were still complicated, and the speaker models (who is speaking, where turns
change) were hidden in Settings › Manage models › Speakers, picked from a model card's ⋮ menu. Then: the speaker
models should be a picker, on a screen of their own like a language's, with a Hugging Face search there, and the same
screen for both speaker jobs.

## Options
- **a — Speakers beside the languages**: a Speaker labels row ("Voices: CAM++ / Turns: pyannote") opening a Speakers
  page with the voice models, the fewer ↔ more slider, the turn models and the pass after Stop. *Turned down*.
- **b — Speakers on Settings itself**: Voice model and Speaker turns rows on Settings, each opening a bottom sheet to
  pick; the slider and the pass after Stop on Settings. *Turned down*.
- **c — A short Settings**: Languages, a Speaker labels row and one Recording and storage row for the rest.
  *Turned down*.
- **d — A section per speaker job**: Speaker recognition and Speaker turns sections on Settings, each a list of models
  to pick. *Turned down*: the owner wanted a picker, not a list on Settings.
- **d2 — A section per speaker job, picked on its own page**: as d, but each section is one row with the chosen model
  (Voice model, Turn model) opening one shared model page (its note, the models to pick, Find more on Hugging Face);
  the slider stays on Settings under the Voice model row. *Picked*, promoted in e02070d (Find more on Hugging Face searches for real: sherpa-onnx speaker exports).
- **d3 — No After a lecture section**: as d2, with Improve automatically on a language's page and Find the speakers
  again under Speaker turns. *Turned down*: the owner didn't like it.

## What they faked
- All: the phone is an SM8750 (the NPU marks show); ResNet293 is "copied by hand"; Add a language adds French at once;
  Manage models does nothing.
- d2, d3: Find more on Hugging Face searched nothing: a fixed list of speaker repos per job, filtered by the field's
  words; Add only listed the repo as downloading.
- d3: Improve automatically stayed one setting for all languages, though each language's page showed it.

Screenshots (emulator): the mocks in [2026-11-06-speakers-shots/](2026-11-06-speakers-shots/), the promoted screen (in
the lab's `models current-sm8750`) in [2026-11-06-promoted/](2026-11-06-promoted/).

## Reviving an option
    git checkout ux/models/2026-11-06-2 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/models/<option> androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/models/ModelsLabParts.kt androidApp/src/debug/res/values/strings_models_lab.xml androidApp/src/debug/res/values-iw/strings_models_lab.xml
Then add `modelsOption<B>,` to `LabScreens`. Built against `ModelsScreen.kt` (`ModelsState`, `ModelsActions`,
`speakerPage`, `voiceModel`, `segmentationModel`, `speakerTuning`), `ModelCatalog` and the app's strings as of the
tag. Compile errors after reviving are in the option's files and `ModelsLabParts.kt`; fix them there.
