# Lecture, after — Word export and delete (2026-11-06, Android)

Tag: `ux/review/2026-11-06-2`

Same round as [deleting a lecture](../notebooks/2026-11-06-delete.md) and
[fixing a line while recording](../lecture/2026-11-06-fix-while-recording.md).

## Options
- **a — Export as Word… in the menu**: ⋮ → Export as Word… opens a sheet: the file's name, what goes in (speakers'
  names, times, notes), a preview of a paragraph, Share… or Save in the lecture's folder. ⋮ → Delete lecture…
  asks first. *Turned down*.
- **b — Share in the top bar**: a share button beside the pencil; one tap makes the Word file with everything
  (speakers' names, times, notes) and opens Android's share sheet; holding it, or ⋮ → Save as Word, saves it in the
  lecture's folder. ⋮ → Delete lecture… asks first, then back to the list. *Picked*.

## What they faked
- Playback and passes (as `review current`).
- The Word file was real, written by lab code (`ReviewWordLab.kt`: title, notebook · date · length, a paragraph per
  speaker turn with the name and time above it, notes as shaded italic paragraphs, right-to-left for Hebrew); saving
  went to Downloads/Transcriptor, not the lecture's folder. Delete only said so.

## Reviving an option
    git checkout ux/review/2026-11-06-2 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/<b> androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/ReviewExportLab.kt androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/ReviewWordLab.kt androidApp/src/debug/res/values/strings_review_<b>.xml androidApp/src/debug/res/values-iw/strings_review_<b>.xml
Then add `reviewOption<B>,` to `LabScreens`.
Built against a copy of `ReviewScreen` and the review lab as of the tag. Compile errors after reviving are in the
option's copy and sample data; fix them there.
