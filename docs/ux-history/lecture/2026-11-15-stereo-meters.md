# Lecture, while recording — stereo sound meters (2026-11-15, Android)

Tag: `ux/lecture/2026-11-15`

hen's ask: the glance's one level meter (five small bars under "Recording · transcribed after you stop") should be two
when recording stereo, and ideas for a better frequency view. The recorder always records 48 kHz stereo.

## Options
- **a — two bars, L and R**: a level bar per channel with a peak that holds a moment, red near the top where it would clip.
- **b — the last 8 seconds**: a strip scrolling left, L above the middle line, R below.
- **c — voice spectrum**: 24 bands, 80 Hz to 8 kHz, with thin L/R level lines under it.
- **d — spectrogram**: the last 8 seconds of those bands as a strip, L/R bars beside it.

*All picked* (hen: "make it all, selectable from settings, the default is the way it designed now (two if stereo)"):
promoted together as `LevelMeterStyle` (Bars, the default: today's five bars, a set per channel when stereo; Lines = a;
Strip = b; Spectrum = c; Spectrogram = d), picked in Settings › Advanced › While recording.

## What they faked
- The microphone: a made-up voice at 10 Hz (syllables, runs of words, room hiss, one side louder). The bands were a
  voice's shape, not an FFT; the app now runs a 2048-point FFT (`MeterAnalyzer`), only while c or d is on screen.

## Reviving an option
    git checkout ux/lecture/2026-11-15 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/lecture/meters androidApp/src/debug/res/values/strings_lecture_meters.xml androidApp/src/debug/res/values-iw/strings_lecture_meters.xml
Then add `lectureMeterA,` (… `D`) to `LabScreens`. Built against `RecordingGlance` (copied as `MeterGlanceLab`) and
`rememberGrowingLectureLab` as of the tag; the app's `StereoLevel`/`StereoMeterState` (sharedLogic) now share the
option's names, so drop the option's `StereoMeterState.kt` when reviving.
