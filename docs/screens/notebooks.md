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
  the one the list is narrowed to), its title, day, length, notes, Improved, and the progress of a pass running on it.
  A title goes by its own first letter, else its language's direction.
- The **labels sheet** (holding moments selects them; the tag icon in the top bar, or Labels… on one): a checkbox per
  label, a dash when only some of the selected moments have it; a tap sets it on all of them. New label makes one and
  puts it on them; the pencil renames a label or deletes it (its moments stay, without it).
- The **Record bar** at the bottom: the labels the next moment gets, as chips, and its language. By default they are
  the labels the moment history guesses for this hour, marked ✦ ("✦ Usually Psychology on Monday at 10:00": same
  weekday, within an hour, at least two moments), or the label the list is narrowed to, else none ("You can add labels
  later"). Tapping them (or +) opens a menu of checkboxes, the guessed ones first, and New label. The language chip:
  the guessed label's usual language, else the last moment's. Record asks for the microphone, then records titled
  with the date.
- While a moment records, a red banner replaces the bar: its clock and labels, and **Back to it**. That moment is not
  in the list until it stops.
- The gear opens Settings.

- The **moments folder** card on top: until a folder is picked, "Where should moments be kept?" with Choose a
  folder (the system's picker, on Documents; `Transcriptor` is made there) and Not now; while moments move there, its
  progress; when it can't be reached, Choose again. Settings has the same choice (Change).

Empty (no moments): a line on recording a moment and giving it labels; Record still works.

**Deleting** (round 2's b): holding a moment selects it; the top bar becomes "2 selected" with ✕, the tag icon and a
trash can, a
tap adds or removes moments, Back clears the selection. Delete takes them off the list at once with "2 moments
deleted · Undo"; when the snackbar closes, their folders (recording, transcript, notes, versions) are deleted for good,
here or in the moments folder. A moment a pass is working on can't be selected, and the one recording or open is
skipped (`Recording.delete`, `NotebooksController.delete`, `LectureStore.delete`).

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
- Strings: `notebooks_*`. Lab: `notebooks current`, `notebooks current-no-guess` (`lab/notebooks/NotebooksCurrentLab.kt`).

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/notebooks/2026-11-04-first-round.md): c2 — moments first, record now, file
  later (picked); a — notebooks with their moments, b — shelf, then the notebook, c — moments first.
- [Round 2 (Nov 6, 2026)](../ux-history/notebooks/2026-11-06-delete.md): deleting a moment: b — hold to select, Undo (picked); a — a menu on each moment, c — swipe to delete.
- [Round 3 (Nov 7, 2026)](../ux-history/notebooks/2026-11-07-labels.md): labels instead of notebooks: a — chips on top, labels on the Record bar (picked); b — labels after, one list; c — a side drawer.
