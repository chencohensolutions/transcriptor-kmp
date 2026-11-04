# Lecture — while recording (2026-11-02, Android)

Tag: `ux/lecture/2026-11-02`

## Options
- **a — One timeline**: speech lines and notes in one list, notes as amber cards right after the line that was being
  spoken when typing started; the note bar is always open, its time chip set at the first keystroke; a "Back to live"
  pill when scrolled up. *Picked*, promoted in d9419fc.
- **b — Two panes**: the transcript on top, a notes page below with a draggable divider; Enter starts a new note and
  tapping a note's time scrolls the transcript there. *Turned down*.
- **c — Document + recorder**: the transcript as paragraphs with notes as margin tabs that expand, a recorder bar with
  a big Note button that opens a sheet with the time frozen. *Turned down*.

## What they faked
- All: the recording (clock, level meter, the partial line growing), live transcription; Stop and tapping a line did
  nothing. Notes and pause worked in memory.
- a: the live model's lag figure.
- b: the divider and jumping from a note's time to the transcript worked in memory.
- c: the note sheet and margin tabs worked in memory.

## Reviving an option
    git checkout ux/lecture/2026-11-02 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/b androidApp/src/debug/res/values/strings_lecture_b.xml androidApp/src/debug/res/values-iw/strings_lecture_b.xml
(`c` likewise.) Then add `lectureOptionB,` to `LabScreens`.
Built against `LiveLecture.kt` (LiveLectureState, LiveLectureActions, timelineOf, rememberLiveLectureLab, RecordingDot,
LevelBars), `strings_lecture.xml` and `LabData` (the "זיכרון עבודה" lecture) as of the tag. Compile errors after
reviving are in the option's files and sample data; fix them there.
