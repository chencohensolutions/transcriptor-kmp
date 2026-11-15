# Moment, while recording

Android, in the app: recording, live lines and notes. iOS after Android is finished.

## What it's for

Recording a class: the live transcript grows as the lecturer speaks, and the user writes notes without stopping the
recording or the transcription.

## At a glance (Nov 15, 2026)

The screen opens at a glance (`RecordingGlance.kt`), for a phone lying on the table in a conversation: the title, a
large clock, "Recording · transcribing as you talk" or "· transcribed after you stop", the microphone's level, and when
transcribing live a card with who is talking, how many voices so far and their last words (tapping it opens the
transcript). The model lines aren't here; only a problem is said (live transcription stopped, a model that didn't fit).

- **Mark**, the large button between Pause and Stop, flags this second with one tap: a note without words
  (`LectureSession.mark`, `Note.isMark`). "Marked at 12:20" shows for a moment. On the moment after, a mark is a pill
  with a flag and its time; holding it gives it words. The recording's notification has Mark too.
- **Stop takes one tap, or two with Settings › Two taps to stop** (`rememberArmedStop`, `SettingsStore.stopTwice`, off by default since Nov 15, 2026): with two, the first turns the button into a check for 3 s and the screen says
  "Tap Stop again to end the moment"; the second stops. Here and in the transcript's top bar; the notification's Stop
  is one tap.
- **Show the transcript** (or **Write a note** when only recording) opens the timeline below; Back returns to the
  glance.

## What it shows

- The moment's title and its labels, the elapsed time, a red dot that breathes while recording, the microphone's
  level, Pause/Resume and Stop.
- The transcript: one line per piece of speech (split at pauses), each with its start time, in the moment's
  language direction. The line being spoken now is grey and italic until it ends. With a model that runs live in
  ~30 s windows (large-v3 on the CPU, when chosen for live) a line is a window, shown when it is transcribed, and there
  is no grey line; "behind" grows by up to a window.
- The live model and how far behind the audio it is ("Live: ivrit.ai turbo · 1.8s behind").
- The notes, each with its time: the time the user **started** typing it.

One timeline (round 1's option a): lines and notes in the order they happened, each note as an amber card right
after the line that was being said when its typing started. The note bar is always open at the bottom; its time chip
is set at the first keystroke. Above the lines, a status line in the app's language: the live model and how far
behind it is, "Loading…", that no live model is downloaded for the language, or that live transcription stopped (the
recording goes on). After Stop, "Finishing the last lines…" until the waiting lines are done.
When the picked live model's NPU build doesn't fit in the free memory at the start, the next ready model runs, and a
red line under the status says so: "Not enough free memory now for <model>; using <other>. Close other apps and try
again." (`LiveLectureState.skippedForMemory`; Nov 7, 2026). The model isn't disabled: the next recording tries it again.
When the language has a model to improve with, the line is calm instead (the time's color), and the moment is improved
as soon as it stops, once the live model is closed, charger or not: "<other> writes the live lines: <model> didn't fit
in the free memory with the recording. This moment is improved when you stop." (`LiveLectureState.improveOnStop`,
`ImproveAfterStop`; Nov 15, 2026). If its model still doesn't fit then (other apps), the moment waits for the charger.
An improve pass running when a recording starts stops before its next window, closing its model, and starts again
when the recording stops (`RefinePass.run(stop)`, `Recording.improveAfterRecording`).

Under it, the other models the recording uses (owner's request, Nov 6, 2026), in the same small grey
(`LiveLectureState.voicePrints`, `speakerPassOnStop`; `ModelsInUse` in `LectureScreen.kt`):
- **Voice prints:** "Speakers: WeSpeaker SimAM-ResNet100 · NPU". The end reads "with the first line" until the model
  loads (it loads with the first line long enough for it), then NPU, or CPU. If its NPU build failed, it reads
  "CPU (its NPU build failed)" in red.
- **Speaker pass:** "After Stop: speaker pass, pyannote + …", when it will run after Stop.
- **Live speaker turns:** with them, "Speakers: pyannote (NPU or CPU) + <model> · NPU, live". A line's speaker chip
  appears 2.5–5 s after its text, and a line may then split where the speaker changed.

- Speakers: where the speaker changes, a chip in the speaker's color above the line ("המרצה", "דובר 2"); tapping
  it names the speaker in this moment. Voices aren't guessed in later moments; the name is offered when naming there.

- **Recording only** (After on the Record bar, or no live model for the language; `LiveLectureState.recordOnly`): no
  lines and no model lines, only "Recording only. This moment is transcribed after you stop." above the notes.

## Sound level meter

hen's ask, Nov 15, 2026: two meters when recording stereo, and other ways to see the sound. Settings › Advanced › While
recording picks a style (`SettingsStore.levelMeter`, `LevelMeterStyle`, `LiveLectureState.levelMeter`); the glance
draws it under the clock (`LevelMeter`), the transcript's top bar always the small bars (`StereoLevelBars`):
- **Bars** (default): the five small bars as before, a set for L and one for R when the recording is stereo.
- **Lines**: a bar per channel, L and R, with a peak that holds; red near the top, where it would clip.
- **Strip**: the last 8 seconds scrolling left, L above the middle line, R below.
- **Spectrum**: the voice range now, 24 bands from 80 Hz to 8 kHz, both channels' levels as thin lines under it.
- **Spectrogram**: the last 8 seconds of those bands, L/R bars beside it.

The recorder works it out on its thread (`MeterAnalyzer`, shared): each channel's RMS 10 times a second, a peak that
falls 2.5% a reading, the last 80 readings. The bands are a 2048-point FFT of the last ~43 ms (Hann window), only
while Spectrum or Spectrogram is on screen (`LectureSession.meterBands`, set by `MainActivity`). A phone that copies
one microphone into both channels reads as mono (`StereoMeterState.stereo` false) and shows one channel. Meters are
laid out left to right in Hebrew too: the channels are the file's. Lab: `lecture current-meter-<style>`.

## Processor meters

Owner's request, Nov 7, 2026: how hard the phone works while it records and transcribes. Off by default; Settings ›
Advanced picks a style (`SettingsStore.processorMeters`, `LiveLectureState.meterStyle`):
- **Three bars** under the top bar: "CPU · app", "GPU · phone", "NPU · our use", each with its percent.
- **A chip** at the transcript's top end with three tiny bars; tapping it opens a sheet: each meter with what it
  counts, the clocks (cores 0–5, cores 6–7, the GPU: now of most) and the NPU's temperature.
- **The last minute as graphs** under the top bar; with Whisper in ~30 s windows on the NPU the NPU rises for a window
  and rests between them. Tapping folds them into one line.

What each meter is (what an app may read, checked on the Galaxy S25 as an ordinary app, Nov 7, 2026):
- **CPU**: this app's CPU time (`/proc/self/stat`) over the second, as a share of all the phone's cores (2 cores busy
  of 8 is 25%). The whole phone's CPU (`/proc/stat`) is closed to apps.
- **GPU**: the whole phone's busy % (`/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`; Qualcomm's Adreno only).
- **NPU**: only the time this app's models keep it busy: every call into a QNN model (Whisper, streaming, voice
  prints, speaker turns) is timed (`npuBusy`, `NpuBusyClock`; overlapping calls count once). Android doesn't let apps
  read the NPU's whole load, so it is labeled "our use". No NPU model running: no NPU meter.
  The hybrid model's NPU encoder (large-v3 · NPU encoder) is timed from whisper's JNI (`WhisperEngine.npuEncoderStarted`/
  `Finished`). After each of its windows the log (tag `whisper`) says how long each job held the NPU since the last
  one: "NPU since the last window: encoder 5400 ms; segmentation 12× 14200 ms (max 1300); voice prints 20× 310 ms"
  (`NpuBusyClock.drain`).
- **Clocks and temperature** (in the chip's sheet): each `cpufreq/policy*`, the GPU's `devfreq/cur_freq` and
  `max_gpuclk`, and the hottest thermal zone named `nsp…`.

A meter the phone doesn't let the app read is left out. Readings are taken about once a second, only while this
screen is showing and the setting is on (`rememberProcessorMeters`); after the screen comes back the next interval
starts afresh. Amber from 70%, red from 90%.

## Behavior

- **Live transcription off and on** (hen's ask, Nov 16, 2026): a chip under the glance's status, "Transcribing live" /
  "Live transcript off", when a live model is on the phone for the language (`LiveLectureState.canTranscribe`,
  `LectureSession.setTranscribing`). Off ends the line being said and stops feeding the model (it stays loaded, so on is
  quick); the status reads as recording only. On in a moment started with After or No transcript loads the live model
  then. The transcriber only hears the on stretches, so its times are shifted to the audio's (`toAudio`). Each off
  stretch is kept (`Lecture.liveOff`: the audio's from/to, the clock's off/on) and shown in the transcript, "Live
  transcript off 12 min · on again 14:32" (`TranscriptOffDivider`), and in Info. After Stop the moment is transcribed
  again as an After moment is (Settings › Transcribe After moments), which fills the off stretches; not for No transcript.
- Pause keeps its clock time with the moment (`Lecture.pauses`); the transcript shows "Paused at 14:20" while paused,
  then "Paused 12 min · resumed 14:32" (`PauseDivider`), in the timeline after the line it cut into (`timelineOf`).

- Pause stops recording and transcription; the FLAC continues from the same point on Resume. Stop ends the moment and
  opens it after ([lecture-review.md](lecture-review.md)).
- Tapping a finished line fixes it in place (round 2's a2): it becomes a field where it is, with Cancel and Save, and
  glides up to stay above the keyboard as it opens and as the text grows; recording and live lines go on below and the
  note bar steps aside. A saved line is marked ✎ (edited: the improve pass keeps it). The session keeps the fixes apart
  and lays them over live transcription's lines every time they are made again (`LectureSession.editLine`, `shown`),
  before and after they are split by speaker. The line being spoken now can't be edited.
- The transcript is laid out from the bottom, so the live line stays in view by itself: the lines above glide up as it
  grows and as new lines come, with no scrolling (a scroll running all the time would swallow taps). The text moves as
  one: everything in view, the grey words too, moves up together in a single ease-in-out tween (320 ms), no spring and
  no line on its own. New words fade in (200 ms) once the move ends; a finished line settles from grey into the text's
  color where the grey line was. The grey is the text's own color, faded (`onSurface` at 60%), so it reads in the
  light and the dark theme alike. Scrolling back stops following
  until "Back to live".

## Code

- Screen: `androidApp/src/main/kotlin/com/chencohensolution/transcriptor/ui/LectureScreen.kt` (`LectureScreen`,
  `LiveLectureActions`); `RecordingIndicators.kt` (`RecordingDot`, `LevelBars`); `LevelMeters.kt` (`LevelMeter`, `StereoLevelBars`); shared
  `recording/LevelMeter.kt` (`MeterAnalyzer`, `StereoMeterState`).
- State and controller (shared): `recording/LiveLectureState.kt`, `recording/LectureSession.kt` (`start`,
  `pauseOrResume`, `addNote`, `editLine`, `stop`), `notebook/Timeline.kt` (`timelineOf`), `notebook/LectureStore.kt`,
  `recording/WavWriter.kt`, `recording/AudioCompression.kt`, `settings/Settings.kt` (`AudioFormat`).
- Android: `recording/AndroidRecorder.kt` and `FlacEncoder.kt` (sharedLogic's androidMain); `RecordingService.kt`
  (foreground service: microphone while recording, media processing while compressing); `Recording.kt` (the app's
  one session); `MainActivity.kt` (the temporary start screen, then this screen).
- Processor meters: `ui/ProcessorMetersView.kt` (`MetersBars`, `MetersChip`, `MetersSheet`, `MetersGraphs`),
  `ProcessorMetersSampling.kt` (`rememberProcessorMeters`, used in `MainActivity.kt`), `speech/NpuBusy.kt`; shared
  `recording/ProcessorMeters.kt` (`ProcessorMeters`, `ProcessorSample`, `processorMeters`, `ProcessorMeterSampler`,
  `NpuBusyClock`), `settings/Settings.kt` (`ProcessorMeterStyle`); Android's readers in sharedLogic's androidMain
  (`recording/AndroidProcessorProbe.kt`). Strings `meters_*`, `settings_meters*`, `settings_advanced`. Lab:
  `lecture current-bars`, `current-chip`, `current-graphs` (`LectureMetersLab.kt`, fake readings); the UI lab app's
  `MeterProbeReceiver` reads the real files as an ordinary app (`adb shell am broadcast -n
  com.chencohensolution.transcriptor.uilab/com.chencohensolution.transcriptor.ui.MeterProbeReceiver`, then
  `adb logcat -s MeterProbe`).
- Lab: `lecture current-glance` (as it opens), `lecture current-record-only` (recording only), and `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/LectureCurrentLab.kt`, the
  screen with a faked recording and live transcription that keeps making lines (`LectureGrowingLab.kt`;
  `./scripts/ui-lab.sh lecture current`).

## Design history

- [2026-11-15: stereo sound meters](../ux-history/lecture/2026-11-15-stereo-meters.md)

- [Round 1 (Nov 2, 2026)](../ux-history/lecture/2026-11-02-while-recording.md): a — one timeline (picked), b — two panes, c — document + recorder.
- [Round 2 (Nov 6, 2026)](../ux-history/lecture/2026-11-06-fix-while-recording.md): fixing a line while recording, and motion: a2 — in place, above the keyboard, lines glide up, words fade in (picked); a, b — in a sheet, c — words fade in, d — letter by letter.
- [Round 3 (Nov 7, 2026)](../ux-history/lecture/2026-11-07-processor-meters.md): processor meters: a — three bars,
  b — a chip and a sheet, c — the last minute as graphs; all three promoted, one picked in Settings › Advanced.
