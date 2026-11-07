# Lecture, while recording

Android, in the app: recording, live lines and notes. iOS after Android is finished.

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
is set at the first keystroke. Above the lines, a status line in the app's language: the live model and how far
behind it is, "Loading…", that no live model is downloaded for the language, or that live transcription stopped (the
recording goes on). After Stop, "Finishing the last lines…" until the waiting lines are done.

Under it, the other models the recording uses (owner's request, Nov 6, 2026), in the same small grey
(`LiveLectureState.voicePrints`, `speakerPassOnStop`; `ModelsInUse` in `LectureScreen.kt`):
- **Voice prints:** "Speakers: WeSpeaker SimAM-ResNet100 · NPU". The end reads "with the first line" until the model
  loads (it loads with the first line long enough for it), then NPU, or CPU. If its NPU build failed, it reads
  "CPU (its NPU build failed)" in red.
- **Speaker pass:** "After Stop: speaker pass, pyannote + …", when it will run after Stop.
- **Live speaker turns:** with them, "Speakers: pyannote (NPU or CPU) + <model> · NPU, live". A line's speaker chip
  appears 2.5–5 s after its text, and a line may then split where the speaker changed.

- Speakers: where the speaker changes, a chip in the speaker's color above the line ("המרצה", "דובר 2"); tapping
  it names the speaker, and the notebook remembers the voice for its next lectures.

## Behavior

- Pause stops recording and transcription; the FLAC continues from the same point on Resume. Stop ends the lecture and
  opens it after ([lecture-review.md](lecture-review.md)).
- Tapping a finished line fixes it in place (round 2's a2): it becomes a field where it is, with Cancel and Save, and
  glides up to stay above the keyboard as it opens and as the text grows; recording and live lines go on below and the
  note bar steps aside. A saved line is marked ✎ (edited: the improve pass keeps it). The session keeps the fixes apart
  and lays them over live transcription's lines every time they are made again (`LectureSession.editLine`, `shown`),
  before and after they are split by speaker. The line being spoken now can't be edited.
- The transcript is laid out from the bottom, so the live line stays in view by itself: the lines above glide up as it
  grows and as new lines come, with no scrolling (a scroll running all the time would swallow taps). Each new word
  fades in quickly (200 ms); a finished line settles from grey into the text's color. Scrolling back stops following
  until "Back to live".

## Code

- Screen: `androidApp/src/main/kotlin/com/chencohensolution/transcriptor/ui/LectureScreen.kt` (`LectureScreen`,
  `LiveLectureActions`); `RecordingIndicators.kt` (`RecordingDot`, `LevelBars`).
- State and controller (shared): `recording/LiveLectureState.kt`, `recording/LectureSession.kt` (`start`,
  `pauseOrResume`, `addNote`, `editLine`, `stop`), `notebook/Timeline.kt` (`timelineOf`), `notebook/LectureStore.kt`,
  `recording/WavWriter.kt`, `recording/FlacCompression.kt`, `settings/Settings.kt` (`AudioFormat`).
- Android: `recording/AndroidRecorder.kt` and `FlacEncoder.kt` (sharedLogic's androidMain); `RecordingService.kt`
  (foreground service: microphone while recording, media processing while compressing); `Recording.kt` (the app's
  one session); `MainActivity.kt` (the temporary start screen, then this screen).
- Lab: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/LectureCurrentLab.kt`, the
  screen with a faked recording and live transcription that keeps making lines (`LectureGrowingLab.kt`;
  `./scripts/ui-lab.sh lecture current`).

## Design history

- [Round 1 (Nov 2, 2026)](../ux-history/lecture/2026-11-02-while-recording.md): a — one timeline (picked), b — two panes, c — document + recorder.
- [Round 2 (Nov 6, 2026)](../ux-history/lecture/2026-11-06-fix-while-recording.md): fixing a line while recording, and motion: a2 — in place, above the keyboard, lines glide up, words fade in (picked); a, b — in a sheet, c — words fade in, d — letter by letter.
