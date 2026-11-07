# Lecture, after

Android, in the app (round 1's c2); iOS after Android is finished. Opened from the start screen's list of lectures,
and after Stop on the lecture just recorded.

## What it's for

Going back to a recorded lecture: listen from any line, correct the text, put lines under the right speaker, name
the speakers, add or fix notes, and run the passes over the whole recording: the improve pass (the large model,
punctuation, more accurate; lines the user edited stay) and the speaker pass (who spoke when, overlaps included).

## What it shows

A document: paragraphs, one per speaker turn, each with the speaker's chip (in their color) and its time; the
sentence being played highlighted; edited lines marked ✎. Notes are pills after the paragraph they were written in.
At the bottom: the overview of the whole lecture (a lane per speaker with their turns, the notes as dots, the
playhead), the position and length, play/pause, the speed (1×, 1.25×, 1.5×, 2×) and a note-here button.

## Behavior

- Tap a sentence: play from its start (seek to the millisecond). Tap the overview: jump there.
- Tap a speaker's chip: name them; the notebook remembers the voice (a print of up to 20 s of their lines) when the
  voice-print model is downloaded.
- The pencil: every line becomes a field, with a dot in its speaker's color to move it to another speaker; Done
  saves the changed lines, marked edited (the refine pass will keep them).
- A note pill: tap to open it, tap its time to jump there, hold to edit or delete. The note-here button adds a note at
  the playhead.
- ⋮ → Find the speakers runs the speaker pass in the recording service (a banner shows its progress); the lines are
  read again when it finishes. Shown only when its models are downloaded.
- ⋮ → Improve transcription… (round 2's a) is always there, whether or not lectures improve on their own. Its sheet:
  the lecture's length, language and the model that wrote it now; the Whisper models downloaded for its language, the
  large ones first, each with about how long it would take (from that model's last run on this phone; none before)
  and NPU or CPU; the one chosen in Settings picked (another is a one-off); "Find the speakers first" when the speaker
  pass can run and hasn't (improving drops the word times it reads); what is kept. Improve runs it in the recording
  service: every line transcribed again from its own audio, edited lines kept, times and speakers kept. The transcript
  it replaces is kept as a version first. With no model downloaded, the sheet offers the one to get.
- With Improve automatically on (Settings), a lecture is improved with its language's model after it stops (after the
  speaker pass), if the phone is charging; otherwise it waits (`SettingsStore.improvePending`) until the app is open
  while the phone charges.
- ⋮ → Versions (n): the transcript now and every earlier one (live or refined, its model, when it was kept, its lines
  and edits). Tapping one brings it back, with its lines, speakers and edits; the one it replaces is kept, so nothing
  is lost. Notes belong to the lecture, not to a version.
- Share, beside the pencil (round 3's b): one tap makes the lecture's Word file (`transcriptDocx`: the title, the
  notebook, date and length, a paragraph per speaker turn with the name and time above it, the notes as shaded italic
  paragraphs after their turn; right-to-left for Hebrew) and opens Android's share sheet (through the app's
  FileProvider, from its cache). Holding it, or ⋮ → Save as Word, saves `<title>.docx` in the lecture's folder,
  beside the recording (`LectureStore.saveWord`).
- ⋮ → Delete lecture…, in red at the end, asks first, then closes the lecture and deletes its folder for good.
- Every change is saved to `lecture.json` at once.

## Code

- Screen: `androidApp/src/main/kotlin/com/chencohensolution/transcriptor/ui/ReviewScreen.kt` (`ReviewScreen`,
  `ReviewActions`, `speakerColor`, `ReviewSpeakerChip`).
- State and controller (shared): `review/ReviewState.kt` (`ReviewState`, `PassState`), `review/LectureReview.kt`
  (`open`, `close`, `reload`, `playPause`, `seek`, `playLine`, `cycleSpeed`, `editLine`, `setLineSpeaker`,
  `renameSpeaker`, `addNote`, `editNote`, `deleteNote`, `speakerPass`), `review/AudioPlayer.kt`.
- Android: `review/AndroidAudioPlayer.kt` (MediaPlayer; sharedLogic's androidMain); `Recording.printOf` (a speaker's
  voice print, with `RecordingAudio.range`); `MainActivity.kt` (opening it; the speaker pass's state).
- Lab: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/ReviewCurrentLab.kt`, the screen
  with faked playback and passes (`./scripts/ui-lab.sh review current`).

## Design history

- [Round 1 (Nov 2, 2026)](../ux-history/review/2026-11-02-after-the-lecture.md): c2 — document with the player at the bottom (picked); a — player + timeline, b — tabs, c — document + overview.
- [Round 2 (Nov 6, 2026)](../ux-history/review/2026-11-06-improve-by-hand.md): a — Improve transcription… always in the menu, with a sheet (picked).
- [Round 3 (Nov 6, 2026)](../ux-history/review/2026-11-06-word-export.md): b — Share as Word in the top bar, Delete lecture… in the menu (picked); a — Export as Word… sheet.

- **Tune speakers on this lecture (N corrections)**, in the menu, for a lecture recorded with live speaker turns
  (owner's request, Nov 6, 2026). N is the number of lines the user gave another speaker than live did, and the item
  is disabled at 0. It replays the lecture's stored voice prints across the model's "fewer ↔ more speakers" range and
  shows the best setting: "Fewer speakers than now: 52 of 60 lines would match your speakers (now 45), with 2 speakers
  (now 4)". Apply sets it, for the next lecture (`LectureReview.tuneSpeakers`, `applySpeakerTuning`).

- **Power** (owner's request, Nov 6, 2026): under the lecture's bar, one small line from its `power.json`, e.g. "Power:
  2.0 W avg, 33 mWh/min, +4.0 °C", or "Power: not measured (on the charger)". Lectures recorded before it have no line.
