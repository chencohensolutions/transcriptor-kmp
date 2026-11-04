# Lecture, after — opening a recorded lecture (2026-11-02, Android)

Tag: `ux/review/2026-11-02`

## Options
- **a — Player + timeline**: the recording screen's timeline (lines, speaker chips, note cards) with a player bar
  whose scrubber shows the speakers' turns and the notes; tap a line to play it, hold it to edit text or speaker;
  the passes as chips under the title. *Turned down*.
- **b — Tabs**: Transcript, Notes and Speakers tabs over a mini player; edit a line in place; the Speakers tab with
  talk time, a sample, rename, and the passes. *Turned down*.
- **c — Document + overview**: paragraphs per speaker turn that read like text, tap a sentence to play it, an edit
  mode, notes as pills; an overview strip at the top (a lane per speaker, notes, playhead); passes in the menu with a
  progress banner. *Liked, with the player moved to the bottom*: c2.
- **c2 — Document + player below** (the owner's change to c): c with the overview and the play controls in a bar at
  the bottom. *Picked*, promoted in cfafc1d.

## What they faked
- All: playback (a clock, no audio), the refine pass and the speaker pass (progress, then made-up results: large-v3's
  lines, a third speaker), the lecture's speakers and lines (`LabData.liveLectureWithSpeakers`, cut to its three
  minutes). Seeking, playing a line, editing, notes, renaming and the speed worked in memory.
- b: a speaker's sample played their longest line.

## Reviving an option
    git checkout ux/review/2026-11-02 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/b androidApp/src/debug/res/values/strings_review_b.xml androidApp/src/debug/res/values-iw/strings_review_b.xml
(`a` has no strings file of its own; `c` likewise with `strings_review_c.xml`.) Then add `reviewOptionB,` to
`LabScreens`. Built against `Review.kt` (ReviewState, ReviewActions, PassState, rememberReviewLab, speakerColor,
ReviewSpeakerChip), `strings_review.xml` and `LabData` as of the tag. Compile errors after reviving are in the
option's files and sample data; fix them there.
