# Settings › Models — the models per language (2026-11-04, Android)

Tag: `ux/models/2026-11-04`

## Options
- **a — One page, a row per job**: Settings on one page; per language a Live and a Refine row naming its model, a sheet
  to change it; the speaker models as one row; the storage at the bottom. *Turned down*.
- **b — Settings › Models, a catalog**: Settings with a Models row; the models as cards per language (chips: the
  notebooks' languages, Speakers) with Live/Refine/NPU badges, the download at the end and a menu (use for live, use
  to refine, delete, model page); a storage bar. *Liked, with the owner's changes*: b2.
- **c — Settings › Models, set up for you**: a tab per language with the recommended setup and one Download all;
  Choose yourself for the rest. *Turned down*.
- **b2 — b + models from Hugging Face** (the owner's changes to b): a search of Hugging Face for models the app can
  run (whisper.cpp ggml files, sherpa-onnx streaming models), licenses marked for commercial use, not, or unknown
  (adding a non-commercial or unlicensed one asks first), sizes to pick, pinned to the repo's commit; added models
  join the catalog ("From Hugging Face"). No storage bar: the owner doesn't want the phone's free space there.
- **b3 — b2 for any language** (the owner's changes): the catalog shows any language (Add a language, any Whisper
  knows), and the search filters by language and by kind of model; Parakeet, SenseVoice, Moonshine and Paraformer
  are listed, marked as not in the app yet.
- **b4 — b3 + NPU builds for this phone** (the owner's question, which models run on this phone's NPU): an "NPU on
  this phone (SM8750)" filter; NPU builds marked for this chip, another chip, or this chip with another QNN version;
  each NPU model tried on the NPU once downloaded ("Runs on this phone's NPU" or "Didn't load", with the reason).
  `b4-sm8750` shows it as on a Snapdragon 8 Elite. *Picked*. The filter is matched by chip, not phone model: every
  phone with the chip runs its builds (the owner asked; a plain "NPU" label was tried and turned back). Promoted in 05d12fa (fixes in 7d6cf37).

## What they faked
- All: downloads (a timer, then checking), the languages (the lab notebooks'), choosing a refine model (the app uses
  the first ready one), the free space (a, b, c).
- b2–b4: the Hugging Face search (no network: a fixed list matched by words); the repos are real but their downloads,
  likes, commits and sha256 are made up; `lab-example/` repos are made up whole (a non-commercial one, an unlicensed
  one, and in b4 every NPU build). b3–b4: the licenses of the Parakeet, SenseVoice, Moonshine and Paraformer repos
  are as last seen, not checked.
- b4: the NPU check (three seconds, then the made-up outcome).

## Reviving an option
    git checkout ux/models/2026-11-04 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/models/c androidApp/src/debug/res/values/strings_models_c.xml androidApp/src/debug/res/values-iw/strings_models_c.xml
Then add `modelsOptionC,` to `LabScreens`. b2 and b3 also need their `…State.kt` back (b4 has them as
`b4/ModelsHubState.kt` and `b4/ModelsHubKind.kt`). Built against `Models.kt` (`ModelsState`, `ModelsActions`,
`rememberModelsLab`, `ModelsDownloadControl`), `ModelsParts.kt`, `strings_models*.xml` and `LabData` as of the tag.
Compile errors after reviving are in the option's files and sample data; fix them there.
