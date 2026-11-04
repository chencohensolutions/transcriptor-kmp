# Notebooks — the first screen (2026-11-04, Android)

Tag: `ux/notebooks/2026-11-04`

## Options
- **a — Notebooks with their lectures**: one page, each notebook with its latest three lectures (all a tap away) and
  its own record button; New lecture asks which notebook, the last used first. *Turned down*.
- **b — Shelf, then the notebook**: colored notebook tiles; a notebook's page has a big Record button over its
  lectures. *Turned down*.
- **c — Lectures first**: every lecture newest first, grouped today / this week / earlier, with its notebook's color and
  name; chips narrow it to a notebook; one Record bar at the bottom with the notebook it records in. *Liked, with the
  owner's changes*: c2.
- **c2 — Lectures first, record now, file later** (the owner's changes to c): Record can start in no notebook, and
  those lectures wait on top ("Not in a notebook yet") with the notebook their weekday and time suggest, one tap to
  move them; the notebook can still be picked before recording. The Record bar is on no notebook unless the lecture
  history guesses one for this hour (same weekday, within an hour, at least two lectures: "Usually on Monday at
  10:00"); a notebook the list is filtered to also counts. *Picked*, promoted in 37624bd.
- Each of a, b, c had an `-empty` entry (the first launch); c2 has `c2-no-guess` (Tuesday evening, no class usual).

## What they faked
- All: the notebooks and the time (`LabData`), recording (a clock; the banner stopped it and listed the lecture), a
  pass running on a lecture, new notebooks in memory; opening a lecture and Settings only said so. Notebook colors were
  the speakers' palette by position.
- c2: the time (the Monday after the sample's latest lecture at 9:50, or Tuesday at 18:00), two lectures recorded
  without a notebook, assigning in memory. The guess was the real `guessNotebook` (in `NotebooksOptionC2State.kt`) on
  that data.

## Reviving an option
    git checkout ux/notebooks/2026-11-04 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/notebooks/b androidApp/src/debug/res/values/strings_notebooks_b.xml androidApp/src/debug/res/values-iw/strings_notebooks_b.xml
Then add `notebooksOptionB,` (and `notebooksOptionBEmpty,`) to `LabScreens`. Built against `Notebooks.kt`
(`NotebooksState`, `NotebooksActions`, `rememberNotebooksLab`, the date and length helpers, `NotebooksNewDialog`),
`NotebooksParts.kt` (the banner, the lecture row, the empty state, `notebookColor`), `strings_notebooks.xml` and
`LabData` as of the tag; `rememberNotebooksLab` was deleted when the round closed, so take `Notebooks.kt` from the tag
too. Compile errors after reviving are in the option's files and sample data; fix them there.
