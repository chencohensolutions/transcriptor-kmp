# Lecture, while recording

Android only, in the UI lab (not in the app yet); iOS after Android is finished.

## What it's for

Recording a class: the live transcript grows as the lecturer speaks, and the user writes notes without stopping the
recording or the transcription.

## What it shows

- The lecture's title and its notebook, the elapsed time, a red dot that breathes while recording, the microphone's
  level, Pause/Resume and Stop.
- The transcript: one line per piece of speech (split at pauses), each with its start time, in the notebook's
  language direction. The line being spoken now is grey and italic until it ends.
- The live model and how far behind the audio it is ("Live: ivrit.ai turbo · 1.8s behind").
- The notes, each with its time: the time the user **started** typing it.

How the notes sit beside the transcript is what the UI lab's round 1 compares (below).

## Behavior

- Pause stops recording and transcription; the FLAC continues from the same point on Resume. Stop ends the lecture and
  opens it after (refine, speakers).
- Tapping a line opens it (to come: play that part, correct the text).
- The transcript follows the live line; scrolling back stops following until "Back to live".

## Code

Lab: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/` (`LiveLecture.kt` is the state
every option uses, `LiveLectureState` and `LiveLectureActions`; `a/`, `b/`, `c/` the options).

## Design history

- Round 1 (Nov 2, 2026), open: a — one timeline, b — two panes, c — document + recorder.
