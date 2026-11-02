# Lecture, while recording

Android, in the app: recording and notes work, and the lines arrive with live transcription (next). iOS after
Android is finished.

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

One timeline (round 1's option a): lines and notes in the order they happened, each note as an amber card right
after the line that was being said when its typing started. The note bar is always open at the bottom; its time chip
is set at the first keystroke. Until live transcription exists, a line in the app's language says so where the lines
will be.

## Behavior

- Pause stops recording and transcription; the FLAC continues from the same point on Resume. Stop ends the lecture and
  opens it after (refine, speakers).
- Tapping a line opens it (to come: play that part, correct the text).
- The transcript follows the live line; scrolling back stops following until "Back to live".

## Code

- Screen: `androidApp/src/main/kotlin/com/chencohensolution/transcriptor/ui/LectureScreen.kt` (`LectureScreen`,
  `LiveLectureActions`); `RecordingIndicators.kt` (`RecordingDot`, `LevelBars`).
- State and controller (shared): `recording/LiveLectureState.kt`, `recording/LectureSession.kt` (`start`,
  `pauseOrResume`, `addNote`, `stop`), `notebook/Timeline.kt` (`timelineOf`), `notebook/LectureStore.kt`,
  `recording/WavWriter.kt`, `recording/FlacCompression.kt`, `settings/Settings.kt` (`AudioFormat`).
- Android: `recording/AndroidRecorder.kt` and `FlacEncoder.kt` (sharedLogic's androidMain); `RecordingService.kt`
  (foreground service: microphone while recording, media processing while compressing); `Recording.kt` (the app's
  one session); `MainActivity.kt` (the temporary start screen, then this screen).
- Lab: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/LectureCurrentLab.kt`, the
  screen with a faked recording and live transcription (`./scripts/ui-lab.sh lecture current`).

## Design history

- [Round 1 (Nov 2, 2026)](../ux-history/lecture/2026-11-02-while-recording.md): a — one timeline (picked), b — two panes, c — document + recorder.
