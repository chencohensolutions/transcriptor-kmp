# Lecture, after

In the UI lab (round 1, not in the app yet); iOS after Android is finished.

## What it's for

Going back to a recorded lecture: listen from any line, correct the text, put lines under the right speaker, name
the speakers, add or fix notes, and run the passes over the whole recording: the refine pass (the large model,
punctuation, more accurate; lines the user edited stay) and the speaker pass (who spoke when, overlaps included).

## What it shows

- The lecture's title, its notebook and length.
- The lines with their times and speakers (the speaker's chip in their color), the line being played highlighted
  and followed; edited lines marked.
- The notes with their times; tapping a time jumps the audio there.
- A player: play/pause, position and length, speed (1×, 1.25×, 1.5×, 2×), seeking.
- Each pass: not run, running (progress), done.

How these are arranged is what round 1 compares (below).

## Behavior

- Tapping a line plays from its start. Editing a line marks it edited; the refine pass keeps it.
- Moving a line to another speaker, and renaming a speaker (the notebook remembers the voice, as during recording).
- A note added here is pinned to the playback position.
- The passes run in the background (the recording service), and the screen follows their progress.

## Code

Lab: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/` (`Review.kt`: `ReviewState`,
`ReviewActions`, `PassState` and the fakes every option uses; `a/`, `b/`, `c/` the options). Sample data:
`LabData.liveLectureWithSpeakers`.

## Design history

- Round 1 (Nov 2, 2026), open: a — player + timeline, b — tabs, c — document + overview.
