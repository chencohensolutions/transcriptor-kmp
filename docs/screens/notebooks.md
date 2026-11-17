# Notebooks (moments and labels)

The app's first screen, moments first (round 1's c2), with labels instead of notebooks (round 3's a). In the app.
The code still calls it `NotebooksScreen`.

## What it shows

- **Chips** on top: All, No label (n) when some moments have none, one per label with its color, and + for a new
  label. A label's chip narrows the list to the moments with it.
- **No labels yet**: moments without labels, newest first, each with its weekday and time ("Wednesday at 17:26").
  When the moment history has a label for that time, **Add <label>** adds it in one tap; **Labels…** (or **Add
  labels** without a suggestion) opens the labels sheet.
- **Today / This week / Earlier**: every moment with labels, newest first, with all its labels in their colors (but
  the one the list is narrowed to), its title, day, length, notes, the model that transcribed it again ("Zipformer", "Whisper"; `shortModelName`) or
  "n versions" when it keeps earlier transcripts, and the progress of a pass running on it.
  A title goes by its own first letter, else its language's direction.
- **One list by date** (Nov 15, 2026): moments without labels are rows like the others, in Today / This week /
  Earlier, with a quiet **Add <label>** (the one their time suggests) and **Labels…**, or **+ label**. The "No labels
  yet" block is gone; the No label chip still narrows the list to them.
- **Each row says who and what** (`LectureSummary.people`, `unnamedPeople`, `preview`): the named speakers, the one who
  talked most first ("Dan, Hen, Maya and 1 more"), and the moment's first sentence of five words or more in quotes.
- **Search** (the magnifier in the top bar; `NotebooksController.search`, `searchMoments`): titles, speakers' names,
  labels, notes and what was said, whatever the case, from two characters. A row per moment, newest first: its title,
  who said the line (or Note:, With, Label:), the text around the match with the match in bold, the line's time and
  how many more places match. Tapping opens the moment at that line (`LectureReview.open(atMillis)`). Back closes it.
- **Tuck away and private** (Nov 16, 2026; docs/design.md "Calls, tucked and private moments"): the ⋮ of the
  selection bar has **Tuck away** (**Bring back** when all the selected are tucked) and **Mark private…**. Tucked
  moments leave the list; a pill under their group ("2 tucked away · Show") asks for the phone's lock, then shows them
  with a "Tucked away" mark until the app is opened again; search never finds them. Mark private asks first (they are
  kept on the server with their transcripts and notes, then deleted from the phone, and Audio Logs won't show them
  again); without a server it says to pair first and offers Settings. A snackbar says how many left the phone, or why
  the rest stayed.
- **Moments from the server** (Nov 16, 2026; docs/design.md "Server backup"): a row may carry small marks: **Call** or
  **Voice message** for what came from the server as one, and **On the server** when its audio is there only (from
  the server, or freed here). The server's recordings are all listed, from a copy of the server's list kept on the
  phone (shed's `remote.json`) and brought up to date in the background from the server's change feed of sound
  recordings only (a spinner at the end only the first time, while nothing is kept yet); opening one plays it at once as it streams. **Locked** marks a moment behind the phone's lock (a
  locked label's, or a locked call); with the tucked ones they wait behind the group's pill ("3 more behind your lock ·
  Show"). The label's edit dialog has **Needs fingerprint**. A call shows its call icon; the filter row has a **Calls**
  chip (by type, when there are calls), and Settings' Lock calls hides them (the Calls label of earlier builds is
  deleted once). Calls or voice messages turned off in Server backup leave the list and its search, those opened
  earlier too, and so does the Calls chip (the Journal app still backs calls up). With a server paired, a moment not backed up yet shows a crossed-out cloud; nothing else does. Opening a
  moment only on the server dims the list under a spinner until its screen opens (tried again for a few seconds), and
  its Play button spins while the first blocks come. Back from a moment or Settings finds the list where it was.
  The list reads the moments' index (`moments-index.json` in the app's storage, `LectureStore.index`), not every
  moment's folder; the folders are read again once each start (`rescan`). The server's list kept on the phone is
  shown at once on start, before the server answers. Settings › Server backup › **Recordings from the server** off leaves them all out (and moments from a
  recorder or a call kept here), in the list and in search.
- The **labels sheet** (holding moments selects them; the tag icon in the top bar, or Labels… on one): a checkbox per
  label, a dash when only some of the selected moments have it; a tap sets it on all of them. New label makes one and
  puts it on them; the pencil renames a label or deletes it (its moments stay, without it).
- The **Record bar** at the bottom: the labels the next moment gets, as chips, and its language. By default they are
  the labels the moment history guesses for this hour, marked ✦ ("✦ Usually Psychology on Monday at 10:00": same
  weekday, within an hour, at least two moments), or the label the list is narrowed to, else none ("You can add labels
  later"). Tapping them (or +) opens a menu of checkboxes, the guessed ones first, and New label. The language chip:
  the guessed label's usual language, else the last moment's. Record asks for the microphone, then records titled
  with the date.
- Under the chips, **Live**, **After** or **No transcript** (kept in the settings, `SettingsStore.recordMode`): Live
  transcribes as the moment records; After only records it, and it is transcribed after it stops ([design.md](../design.md#record-now-transcribe-after-owners-request-nov-15-2026));
  No transcript (owner's request, Nov 15, 2026) records it and transcribes nothing until "Transcribe now" on the moment.
- **Music** (owner's request, Nov 17, 2026; `RecordMode.Music`, `recordMusic`): a concert. FLAC as it records at the High
  preset (48 kHz stereo 16 bit) whatever Recording format says, the microphone opened as `UNPROCESSED` where the phone
  supports it (else `CAMCORDER`), no transcript (like No transcript). The chips sit on their own line under the mode's
  note; the Quick Settings tile follows the mode. The source used is in the moment's power details.
- With **Advanced mode** on (Settings › Advanced, `SettingsStore.advancedMode`, off by default; owner's request, Nov 17,
  2026), a row under them: Quality **High**, **Normal**, **Low** and Battery **Save**, **Now**, the presets of Settings ›
  Recording format (`NotebooksState.recordingChoice`, `RecordingChoice.with`); each row has its name and a line saying what the
  choice does (the quality's codec, sound and bitrate; what the battery does). With **Music** picked the rows give
  way to one line: Music records with its own profile and timing (Settings › Recording settings › Music).
  One line beside them says which. (The "Usually … on Monday" line is gone: the guessed labels keep their ✦.)
  A moment waiting for its transcript says **Not transcribed yet** in the list.
- While a moment records, a red banner replaces the bar: its clock and labels, and **Back to it**. That moment is not
  in the list until it stops.
- The gear opens Settings.

- The **moments folder** card on top: until a folder is picked, "Where should moments be kept?" with Choose a
  folder (the system's picker, on Documents; `Audio Logs` is made there) and Not now; while moments move there, its
  progress; when it can't be reached, Choose again. Settings has the same choice (Change).

Empty (no moments): a line on recording a moment and giving it labels; Record still works.

**Deleting** (round 2's b): holding a moment selects it; the top bar becomes "2 selected" with ✕, the tag icon and a
trash can, a
tap adds or removes moments, Back clears the selection. Delete takes them off the list at once with "2 moments
deleted · Undo"; when the snackbar closes, their folders (recording, transcript, notes, versions) are deleted for good,
here or in the moments folder. A moment a pass is working on can't be selected, and the one recording or open is
skipped (`Recording.delete`, `NotebooksController.delete`, `LectureStore.delete`).

**Quick Settings tile** (Nov 15, 2026): "Record" in the notification shade's quick controls (added from the shade's
edit list). A tap records a moment as the Record bar would now: the guessed labels and language, Live, After or No transcript as last
picked, and the recording format and hide-notification settings. It records in the background: nothing opens and
the shade stays (while System UI is bound to the tile the app shares its microphone access; if Android refuses anyway,
an invisible activity starts it from the foreground). On a locked phone it asks to unlock
first. While recording the tile is on, labeled Stop with Recording or Paused under it; Stop takes one tap, or two within 4 seconds ("Tap again to stop") with Settings › Two taps to stop; while the last lines finish it is greyed out. Without the microphone
permission the tap opens the app, whose Record asks for it. A long press opens the app on the recording screen (on the moments when nothing records;
`TileLongPressActivity`, the tile's preferences activity).

## Code

- Screen: `androidApp/.../ui/NotebooksScreen.kt` (`NotebooksScreen`, `NotebooksActions`); wired in `MainActivity`.
- State: `sharedLogic/.../notebook/NotebooksController.kt` (`NotebooksController`: `library`, `reload`, `newLabel`,
  `setLabels`, `renameLabel`, `deleteLabel`, `delete`; `notebooksState(...)`, `NotebooksState`, `RecordingNow`,
  `labelNames`), `LabelGuess.kt` (`guessLabels`), `LabelStore.kt` (`labels.json`), `Migration.kt`
  (`migrateToLabels`), `LectureStore.setLabels`. Skipping moments being recorded or worked on: `Recording.setLabels`,
  `Recording.deleteLabel`.
- The moments folder: `Recording.folder`, `chooseFolder`, `archive`, `migrateLibrary`, `openAudio`;
  `storage/SafFileSystem.kt`; shared `Place.kt`, `LibraryFolder.kt` (`adoptLibrary`, `archiveFinished`),
  `LectureStore.archive`.
- The tile: `androidApp/.../RecordTileService.kt` (`RecordTileService`, `nextFromRecordBar`; `RecordFromTileActivity` is
  the fallback that starts it from the foreground; `RecordingService`'s `fromTile` start falls back to it). Strings `tile_*`.
- Strings: `notebooks_*`. Lab: `notebooks current`, `notebooks current-no-guess` (`lab/notebooks/NotebooksCurrentLab.kt`).

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/notebooks/2026-11-04-first-round.md): c2 — moments first, record now, file
  later (picked); a — notebooks with their moments, b — shelf, then the notebook, c — moments first.
- [Round 2 (Nov 6, 2026)](../ux-history/notebooks/2026-11-06-delete.md): deleting a moment: b — hold to select, Undo (picked); a — a menu on each moment, c — swipe to delete.
- [Round 3 (Nov 7, 2026)](../ux-history/notebooks/2026-11-07-labels.md): labels instead of notebooks: a — chips on top, labels on the Record bar (picked); b — labels after, one list; c — a side drawer.

With Music picked (Nov 17, 2026), the advanced rows show Music's encoder in a line instead of the quality chips, and Processing sets Music's own mode (`NotebooksState.musicChoice`, `musicPowerSaving`).
