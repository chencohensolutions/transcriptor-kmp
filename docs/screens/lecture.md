# Moment, while recording

Android, in the app: recording, live lines and notes. iOS after Android is finished.

## What it's for

Recording a class: the live transcript grows as the lecturer speaks, and the user writes notes without stopping the
recording or the transcription.

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

Under it, the other models the recording uses (owner's request, Nov 6, 2026), in the same small grey
(`LiveLectureState.voicePrints`, `speakerPassOnStop`; `ModelsInUse` in `LectureScreen.kt`):
- **Voice prints:** "Speakers: WeSpeaker SimAM-ResNet100 · NPU". The end reads "with the first line" until the model
  loads (it loads with the first line long enough for it), then NPU, or CPU. If its NPU build failed, it reads
  "CPU (its NPU build failed)" in red.
- **Speaker pass:** "After Stop: speaker pass, pyannote + …", when it will run after Stop.
- **Live speaker turns:** with them, "Speakers: pyannote (NPU or CPU) + <model> · NPU, live". A line's speaker chip
  appears 2.5–5 s after its text, and a line may then split where the speaker changed.

- Speakers: where the speaker changes, a chip in the speaker's color above the line ("המרצה", "דובר 2"); tapping
  it names the speaker, and the app remembers the voice for the next moments.

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
- **Clocks and temperature** (in the chip's sheet): each `cpufreq/policy*`, the GPU's `devfreq/cur_freq` and
  `max_gpuclk`, and the hottest thermal zone named `nsp…`.

A meter the phone doesn't let the app read is left out. Readings are taken about once a second, only while this
screen is showing and the setting is on (`rememberProcessorMeters`); after the screen comes back the next interval
starts afresh. Amber from 70%, red from 90%.

## Behavior

- Pause stops recording and transcription; the FLAC continues from the same point on Resume. Stop ends the moment and
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
- Processor meters: `ui/ProcessorMetersView.kt` (`MetersBars`, `MetersChip`, `MetersSheet`, `MetersGraphs`),
  `ProcessorMetersSampling.kt` (`rememberProcessorMeters`, used in `MainActivity.kt`), `speech/NpuBusy.kt`; shared
  `recording/ProcessorMeters.kt` (`ProcessorMeters`, `ProcessorSample`, `processorMeters`, `ProcessorMeterSampler`,
  `NpuBusyClock`), `settings/Settings.kt` (`ProcessorMeterStyle`); Android's readers in sharedLogic's androidMain
  (`recording/AndroidProcessorProbe.kt`). Strings `meters_*`, `settings_meters*`, `settings_advanced`. Lab:
  `lecture current-bars`, `current-chip`, `current-graphs` (`LectureMetersLab.kt`, fake readings); the UI lab app's
  `MeterProbeReceiver` reads the real files as an ordinary app (`adb shell am broadcast -n
  com.chencohensolution.transcriptor.uilab/com.chencohensolution.transcriptor.ui.MeterProbeReceiver`, then
  `adb logcat -s MeterProbe`).
- Lab: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/LectureCurrentLab.kt`, the
  screen with a faked recording and live transcription that keeps making lines (`LectureGrowingLab.kt`;
  `./scripts/ui-lab.sh lecture current`).

## Design history

- [Round 1 (Nov 2, 2026)](../ux-history/lecture/2026-11-02-while-recording.md): a — one timeline (picked), b — two panes, c — document + recorder.
- [Round 2 (Nov 6, 2026)](../ux-history/lecture/2026-11-06-fix-while-recording.md): fixing a line while recording, and motion: a2 — in place, above the keyboard, lines glide up, words fade in (picked); a, b — in a sheet, c — words fade in, d — letter by letter.
- [Round 3 (Nov 7, 2026)](../ux-history/lecture/2026-11-07-processor-meters.md): processor meters: a — three bars,
  b — a chip and a sheet, c — the last minute as graphs; all three promoted, one picked in Settings › Advanced.
