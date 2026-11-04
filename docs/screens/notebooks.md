# Notebooks

The app's first screen, lectures first (round 1's c2). In the app.

## What it shows

- **Chips** on top: All, Not filed (n) when there are unfiled lectures, one per notebook with its color, and + for a
  new notebook. A notebook's chip narrows the list to it.
- **Not in a notebook yet**: lectures recorded without a notebook, newest first, each with its weekday and time
  ("Wednesday at 17:26"). When the lecture history has a notebook for that time, a **Move to <notebook>** button files
  it there in one tap; **Other…** (or **Put in a notebook** without a suggestion) lists the notebooks, the suggested
  one first, and New notebook. Filing moves the lecture's folder and gives the notebook the voices of the speakers
  named in it.
- **Today / This week / Earlier**: every filed lecture, newest first, with its notebook's color and name (not when the
  list is narrowed to one notebook), its title, day, length, notes, Refined, and the progress of a pass running on it.
  A title goes by its own first letter, else its notebook's direction.
- The **Record bar** at the bottom: "Records in" and a chip with the notebook. By default it is **no notebook**,
  unless the lecture history guesses one for this hour ("✦ Usually on Monday at 10:00": same weekday, within an hour,
  at least two lectures), or the list is narrowed to a notebook. The chip's menu: the notebooks (the guessed one
  first, marked ✦), No notebook, the language of a lecture in no notebook (by default the last lecture's), New
  notebook. Record asks for the microphone, then records titled with the date.
- While a lecture records, a red banner replaces the bar: its clock and notebook, and **Back to it**. That lecture is
  not in the lists until it stops.
- The gear opens Settings (for now the old start screen's settings: audio format, model downloads, the passes after
  the lecture).

- The **lectures folder** card on top: until a folder is picked, "Where should lectures be kept?" with Choose a
  folder (the system's picker, on Documents; `Transcriptor` is made there) and Not now; while lectures move there, its
  progress; when it can't be reached, Choose again. Settings has the same choice (Change).

Empty (no notebooks, no lectures): a line on what a notebook is; Record still works.

## Code

- Screen: `androidApp/.../ui/NotebooksScreen.kt` (`NotebooksScreen`, `NotebooksActions`); wired in `MainActivity`.
- State: `sharedLogic/.../notebook/NotebooksController.kt` (`NotebooksController`: `library`, `reload`, `create`,
  `assign`, `assignToNew`; `notebooksState(...)`, `NotebooksState`, `RecordingNow`), `NotebookGuess.kt`
  (`guessNotebook`), `NotebookStore.kt` (`notebooks.json`, `UNFILED`, `migrate`), `LectureStore.move`.
- Filing with voices: `Recording.file`. Passes per lecture: `Recording.working`. The lectures folder:
  `Recording.folder`, `chooseFolder`, `archive`, `openAudio`; `storage/SafFileSystem.kt`; shared `Place.kt`,
  `LibraryFolder.kt` (`adoptLibrary`, `archiveFinished`), `LectureStore.archive`.
- Strings: `notebooks_*`. Lab: `notebooks current`, `notebooks current-no-guess` (`lab/notebooks/NotebooksCurrentLab.kt`).

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/notebooks/2026-11-04-first-round.md): c2 — lectures first, record now, file
  later (picked); a — notebooks with their lectures, b — shelf, then the notebook, c — lectures first.
