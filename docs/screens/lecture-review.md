# Moment, after

Android, in the app (round 1's c2); iOS after Android is finished. Opened from the start screen's list of moments,
and after Stop on the moment just recorded.

## What it's for

Going back to a recorded moment: listen from any line, correct the text, put lines under the right speaker, name
the speakers, add or fix notes, and run the passes over the whole recording: the improve pass (the large model,
punctuation, more accurate; lines the user edited stay) and the speaker pass (who spoke when, overlaps included).

## What it shows

A document: paragraphs, one per speaker turn, each with the speaker's chip (in their color) and its time; the
sentence being played highlighted; edited lines marked ✎. Notes are pills after the paragraph they were written in.
At the bottom: the overview of the whole moment (a lane per speaker with their turns, the notes as dots, the
playhead), the position and length, play/pause, the speed (1×, 1.25×, 1.5×, 2×) and a note-here button.

A moment that was only recorded (`Lecture.pendingTranscript`, no lines) shows a card instead of the document: **Not
transcribed yet**, that it is transcribed when the phone charges, and **Transcribe now** with the language's improve
model and about how long it takes (`ReviewActions.improve`); with no model, the one to get. While the pass runs the
bar says "Transcribing · n%". Its notes are listed under the card, and the recording plays.

## Who was here?

The people button in the top bar opens a sheet with every voice of the moment, the one who talked most first
(`ReviewState.people`, `peopleOf`). It opens only from that button: it used to open by itself after Stop when a voice
was unnamed, which the owner turned off (Nov 16, 2026).

- **Play**: the voice's longest line, to hear who it is; again pauses it (`LectureReview.playPerson`).
- **Name**: a field per voice; while it is empty and focused, the voices the app knows are offered as chips
  (`ReviewState.knownNames`, those not in this moment first). Kept on Done on the keyboard, on leaving the field and
  when the sheet closes (`namePerson`), in the moment and in the app's voices with a print, as naming from a chip does.
- **Merge**: the merge button lists the other voices under "The same person as": the voice's lines become the other's
  and it is gone (`mergeSpeakers`, `Lecture.mergingSpeakers`); a named voice's print is made again from all its lines.
  Typing a name someone here already has merges the two as well. A voice with at most 3 lines and under 5% of the
  talking (`Person.minor`) says it is often a piece of someone else, with Merge beside it.
- Its share of the talking and its number of lines are under each name.

## As a conversation (Nov 15, 2026)

- **Short turns are compact**: a turn of up to 140 characters is one piece, the speaker's name in their color, its
  time, then the words (`COMPACT_TURN_CHARS`); tapping the name names the speaker. A longer turn keeps the chip and
  time above its paragraph, as a lecture reads.
- **Where the transcript stands**: under the top bar, "Written live. You can transcribe it again with another
  model." with **Transcribe again · about n min**, which opens the Transcribe with sheet. Shown while the moment has
  lines, a model to transcribe with, and hasn't been transcribed again.
- **One person's turns**: with two or more speakers, a chip per person above the player. Tapping one plays only their
  turns, skipping the others' and pausing after their last (`LectureReview.playOnly`, `Lecture.onlyOf`,
  `ReviewState.onlySpeaker`); the others' turns and lanes fade. Tapping it again plays everyone.
- **Share** opens a menu: **Share as text** (the moment as "Name: what they said", notes in brackets;
  `transcriptText`, `LectureReview.text`) or **Share as Word**. Holding it still saves the Word file.

- **The title renames the moment** (tap it; `LectureReview.rename`), and the line under it, its labels or "Add
  labels", opens the labels sheet of the first screen for this moment (`LectureReview.setLabels`): check, make, rename
  and delete labels without going back to the list.

## Behavior

- Tap a sentence: play from its start (seek to the millisecond). Tap the overview: jump there.
- Tap a speaker's chip: name them; the app remembers the voice (a print of up to 20 s of their lines) when the
  voice-print model is downloaded.
- The pencil: every line becomes a field, with a dot in its speaker's color to move it to another speaker; Done
  saves the changed lines, marked edited (the refine pass will keep them).
- A note pill: tap to open it, tap its time to jump there, hold to edit or delete. The note-here button adds a note at
  the playhead.
- ⋮ → Find the speakers runs the speaker pass in the recording service (a banner shows its progress); the lines are
  read again when it finishes. Shown only when its models are downloaded.
- ⋮ → Transcribe again… (was Improve transcription…, renamed Nov 16, 2026: running the Zipformer after is a choice,
  not always an improvement). Its sheet, **Transcribe with**: the moment's length and language, "Now: written live by
  …" or "Now: transcribed again by …"; the models downloaded for its language (large Whisper, then turbo, then the
  Zipformer), each with what it is good at (`ModelStrength`: most accurate with punctuation, slower / fast with
  punctuation / fastest, close to the live text, no punctuation), about how long it would take (from that model's last
  run on this phone; none before) and NPU or CPU; the one chosen in Settings picked (another is a one-off); "Find the
  speakers first" when the speaker pass can run and hasn't; what is kept. Transcribe runs it in the recording
  service: every line transcribed again from its own audio, edited lines kept, times and speakers kept. The transcript
  it replaces is kept as a version first. With no model downloaded, the sheet offers the one to get.
- With Transcribe again automatically on (Settings), a moment is improved with its language's model after it stops (after the
  speaker pass), if the phone is charging; otherwise it waits (`SettingsStore.improvePending`) until the app is open
  while the phone charges.
- ⋮ → Versions (n): the transcript now and every earlier one, named by what wrote it ("Live · Zipformer", or the
  model's name), when it was kept, its lines and edits. **Compare** on an earlier one shows it beside the text now, a
  line at a time (`compareTranscripts`: matched by time, words by longest common run, punctuation ignored), the words
  that differ highlighted in both; only the lines that differ at first, with how many; **Use …** brings it back. Tapping one brings it back, with its lines, speakers and edits; the one it replaces is kept, so nothing
  is lost. Notes belong to the moment, not to a version.
- Share, beside the pencil (round 3's b): one tap makes the moment's Word file (`transcriptDocx`: the title, the
  labels, date and length, a paragraph per speaker turn with the name and time above it, the notes as shaded italic
  paragraphs after their turn; right-to-left for Hebrew) and opens Android's share sheet (through the app's
  FileProvider, from its cache). Holding it, or ⋮ → Save as Word, saves `<title>.docx` in the moment's folder,
  beside the recording (`LectureStore.saveWord`).
- ⋮ → Delete moment…, in red at the end, asks first, then closes the moment and deletes its folder for good.
- Every change is saved to `lecture.json` at once.

## Code

- Screen: `androidApp/src/main/kotlin/com/chencohensolution/transcriptor/ui/ReviewScreen.kt` (`ReviewScreen`,
  `ReviewActions`, `speakerColor`, `ReviewSpeakerChip`).
- State and controller (shared): `review/ReviewState.kt` (`ReviewState`, `PassState`), `review/LectureReview.kt`
  (`open`, `close`, `reload`, `playPause`, `seek`, `playLine`, `cycleSpeed`, `editLine`, `setLineSpeaker`,
  `renameSpeaker`, `addNote`, `editNote`, `deleteNote`, `speakerPass`), `review/AudioPlayer.kt`.
- Android: `review/AndroidAudioPlayer.kt` (MediaPlayer; sharedLogic's androidMain); `Recording.printOf` (a speaker's
  voice print, with `RecordingAudio.range`); `MainActivity.kt` (opening it; the speaker pass's state).
- Lab: `review current-people` (an English conversation of four, the sheet open), and `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/review/ReviewCurrentLab.kt`, the screen
  with faked playback and passes (`./scripts/ui-lab.sh review current`).

## Design history

- [Round 1 (Nov 2, 2026)](../ux-history/review/2026-11-02-after-the-lecture.md): c2 — document with the player at the bottom (picked); a — player + timeline, b — tabs, c — document + overview.
- [Round 2 (Nov 6, 2026)](../ux-history/review/2026-11-06-improve-by-hand.md): a — Improve transcription… always in the menu, with a sheet (picked).
- [Round 3 (Nov 6, 2026)](../ux-history/review/2026-11-06-word-export.md): b — Share as Word in the top bar, Delete moment… in the menu (picked); a — Export as Word… sheet.

- **Tune speakers on this moment (N corrections)**, in the menu, for a moment recorded with live speaker turns
  (owner's request, Nov 6, 2026). N is the number of lines the user gave another speaker than live did, and the item
  is disabled at 0. It replays the moment's stored voice prints across the model's "fewer ↔ more speakers" range and
  shows the best setting: "Fewer speakers than now: 52 of 60 lines would match your speakers (now 45), with 2 speakers
  (now 4)". Apply sets it, for the next moment (`LectureReview.tuneSpeakers`, `applySpeakerTuning`).

- **Pauses** (hen's ask, Nov 16, 2026): where the recording was paused, a divider between the turns, "Paused 12 min ·
  resumed 14:32" (the date too when it isn't today), after the turn it cut into (`Lecture.pauses`, `Pause`: the audio's
  position and the clock times; saved by `LectureSession.pauseOrResume`). The audio has no gap there. Info lists them.
- **Info** (menu › Info; hen's ask, Nov 16, 2026): a sheet "About this moment" (`MomentInfoSheet`, from
  `LectureReview.info()` → `MomentInfo`): started, length, language, the audio file (and what it was recorded as
  when encoded afterwards), the encoder (hardware or software), the file's size, the microphone, the models; then
  "Power while recording" (average W, mWh and mWh/min, battery start → end and %/hour, temperature start → end, the
  app's CPU s/min and share of one core, screen-on share, readings) and, for "WAV, then encode", "Encoding afterwards"
  (`power-encode.json`). For comparing formats and microphones on the phone.
- **Power** (owner's request, Nov 6, 2026): under the moment's bar, one small line from its `power.json`, e.g. "Power:
  2.0 W avg, 33 mWh/min, +4.0 °C", or "Power: not measured (on the charger)". Moments recorded before it have no line.
