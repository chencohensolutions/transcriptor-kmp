# Lecture, while recording — fixing a line, and motion (2026-11-06, Android)

Tag: `ux/lecture/2026-11-06`

Same round as [deleting a lecture](../notebooks/2026-11-06-delete.md) and
[Word export](../review/2026-11-06-word-export.md).

## Options
- **a — Fix a line in place**: tapping a finished line turns it into a field where it is, with Cancel and Save;
  live lines keep coming below; the note bar steps aside; a saved line is marked ✎. *Changed into a2*: the owner
  wanted the scroll fixed while typing, lines moving up with motion, and new words written in with a quick fade.
- **a2 — a, with motion**: the line being fixed glides up to stay above the keyboard as it opens and as the text
  grows; the transcript is laid out from the bottom (reversed list), so lines glide up as the live line grows and as
  new lines come, with no scrolling (a scroll running all the time swallowed taps in a); each new word fades in over
  200 ms; a finished line settles from grey into the text's color. *Picked*.
- **b — Fix lines in a sheet**: a sheet with the line, ‹ › to the lines around it, and what is said now on top.
  *Turned down*.
- **c — Words fade in**: each word fades in (450 ms), lines glide up, no "…". *Turned down* (its fade went into a2).
- **d — Written out, letter by letter**: the live line typed out evenly with a soft caret; a new line glows, then
  fades. *Turned down*.

## What they faked
- The recording and live transcription: LabData's lines said again a word at a time at an uneven pace, so lines keep
  coming (`LectureGrowingLab.kt`). The real model sends words in bursts and may rewrite its last words.
- Saving a line only changed it in memory.

## Reviving an option
    git checkout ux/lecture/2026-11-06 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/<b> androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/LectureGrowingLab.kt androidApp/src/debug/res/values/strings_lecture_<b>.xml androidApp/src/debug/res/values-iw/strings_lecture_<b>.xml
(c and d have no strings files.) Then add `lectureOption<B>,` to `LabScreens`.
Built against a copy of `LectureScreen` and LabData's live lecture as of the tag. Compile errors after reviving are in
the option's copy and sample data; fix them there.
