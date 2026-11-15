# Settings — the recording format, with AAC (2026-11-15, Android)

Tag: `ux/models/2026-11-15`

The owner's ask: record AAC on the phone's hardware encoder (less power and heat than FLAC on the CPU), and choose every
setting of the codec in Settings.

## Options
- **a — One list, AAC first, its settings under it**: AAC as a fourth way beside WAV, WAV then FLAC and FLAC live; the
  profile, sample rate, channels, bitrate and encoder right under the list. *Turned down*.
- **b — A row in Storage, its own page, codec first**: one Recording format row opening a page with AAC | FLAC | WAV,
  then that codec's settings. *Picked as the direction*, changed in b2 and b3.
- **b2 — WAV, AAC, FLAC, each codec its own page**: the owner's change to b: the page lists WAV, AAC and FLAC; AAC and
  FLAC open pages of their own, each starting with "Record as WAV, encode afterwards" (when the moment ends, or when
  charging with 30% battery or more). *Turned down*: the owner wanted that setting once, on the Recording format page.
- **b3 — b2, with "Record as WAV" on the Recording format page**: one checkbox under the three codecs, for AAC and
  FLAC alike; the AAC and FLAC pages hold only their settings. *Picked*, promoted in b87d4dd.
- **c — Ready-made choices, then Custom AAC**: Balanced, Smallest, Stereo and the lossless ways as cards with their
  size an hour, and Custom AAC for every setting. *Turned down*.

Asked along the way: every bitrate (16–320 kbps) for every profile, shown as chips rather than a slider.

## What they faked
- All: only the audio part of Settings was drawn. The `-hw` variants listed a made-up hardware encoder
  (`c2.vendor.aac.hw.encoder`); the emulator has only Android's software one, and the S25's list wasn't known yet.

## Reviving an option
    git checkout ux/models/2026-11-15 -- androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/models/<b> androidApp/src/debug/res/values/strings_models_<b>.xml androidApp/src/debug/res/values-iw/strings_models_<b>.xml
Then add `modelsOption<B>,` (and `modelsOption<B>Hw,`) to `LabScreens`.
Built against `AacSettings`, `AacEncoders`, `EncodeAfter` and the app's strings as of the tag.
