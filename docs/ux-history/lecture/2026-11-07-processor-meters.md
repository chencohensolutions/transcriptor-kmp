# Moment, while recording — processor meters (2026-11-07, Android)

Tag: `ux/lecture/2026-11-07`

The owner's ask: CPU, GPU and NPU meters on the recording screen, to see how hard the phone works while it records
and transcribes (with large-v3 running live in ~30 s windows, PR #11). First, what an app may read on the Galaxy S25
(SM8750), probed over adb and then from inside an ordinary app (`untrusted_app`): its own CPU time, the cores'
clocks, the GPU's busy % and clock, and the NPU's temperature; not the whole phone's CPU (`/proc/stat`) and nothing
of the NPU's load (no counter is exposed; `/dev/fastrpc-cdsp`, devfreq and remoteproc are closed). So the NPU meter
is this app's own time on it, labeled "our use".

## Options
- **a — Three bars**: a slim strip under the top bar: CPU (this app, % of all 8 cores), GPU (the phone), NPU (our
  use). *Promoted*.
- **b — A chip and a sheet**: a chip with three tiny bars at the transcript's top end; tapping it opens a sheet with
  each meter explained, the clocks (cores 0–5, cores 6–7, the GPU) and the NPU's temperature. *Promoted*.
- **c — The last minute as graphs**: three small graphs under the top bar, so the windows' rhythm shows (the NPU
  rises for a window, rests between); tapping folds them into one line. *Promoted*.

The owner took all three: Settings › Advanced picks one (Off by default; `SettingsStore.processorMeters`).
Promoted with real readers (`AndroidProcessorProbe`) and the NPU calls timed (`npuBusy`); see
[lecture.md](../../screens/lecture.md#processor-meters).

## What they faked
- The readings: CPU, GPU and NPU of a recording with large-v3 on the NPU in ~30 s windows (about 10 s of NPU and a
  CPU burst per window, then only the recording); clocks following the load; the NPU warming during windows.
- The mocks were drawn over the app's `LectureScreen` just under its top bar (no copy of the screen).

Screenshots: [2026-11-07-meters-shots/](2026-11-07-meters-shots/).

## Reviving an option
    git checkout ux/lecture/2026-11-07 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/<b> androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/LectureMetersLab.kt androidApp/src/debug/res/values/strings_lecture_<b>.xml androidApp/src/debug/res/values-iw/strings_lecture_<b>.xml
Then add `lectureOption<B>,` to `LabScreens`. Built against `LectureScreen` and `ProcessorMeters` as of the tag; the
app's `ProcessorMetersView.kt` now has the same names (`MeterBar`, `meterColor`, `MetersChip`…), so rename the
option's copies when reviving.
