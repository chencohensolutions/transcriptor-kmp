# Settings › Server backup

Android only; iOS not built yet ([ios-todo.md](../ios-todo.md), "Server backup").

Backing up moments to the owner's journal server (journal-server-go) through **shed**, the storage library shared
with the journal app ([github.com/chencohensolutions/shed-kmp](https://github.com/chencohensolutions/shed-kmp), the
submodule `shed/`). Owner's request, Nov 16, 2026: Audio Logs asks shed to upload its finished moments (the journal
app doesn't watch Audio Logs' folders), recordings already on the server show in Audio Logs, and Audio Logs works
without a server.

## What it shows

- Settings, under **Recordings**, a **Server backup** row: "Off: moments stay on this phone only", or "On, to
  home.example:56398", with how many moments wait.
- **Not paired**: what it does and that the app works without it; when another app on the phone uses shed (the
  journal app, once it moves to shed), **Use the server from Journal**: that app asks the user to confirm and shares
  its server, with no new enrollment code (one enrollment per phone, owner's request, Nov 16, 2026); how to get the link (run `journal-server
  enroll-code <user>` on the server's computer and copy the `journal://enroll…` link under its QR code); the link field
  (always left to right), the phone's name on the server (the phone's model by default), **Check** (asks the server
  who the code is for without using it: "For hen on home.example:56398") and **Pair**. Errors in plain words: not an
  enrollment link, a wrong, expired or used code, too many tries, the server didn't answer.
- **Paired**: "Paired with … as … This phone is "…" there."; how the backup stands ("3 moments waiting · 120 MB left ·
  12 on the server", "Every finished moment is on the server (15)", "No moment backed up yet"); the server didn't
  answer (trying again later) or no longer accepts this phone; moments the server refused, by title; **Back up now**;
  **Wi‑Fi only** (on by default); **On the server**: how many of the server's recordings aren't moments here and the
  newest 20 by date, length and name (tucked ones counted apart); **What Audio Logs shows from the server**: **Calls**
  and **Voice messages** switches, off by default, kept on the server for this app only, and that recordings marked
  private never reach Audio Logs (Nov 16, 2026; shown once the server answers `soundRecordingsShown`); **Unpair** (asks first: what was sent stays on the server, every moment stays on
  the phone).

## How it behaves

- Nothing is handed over or sent until the user pairs. Pairing makes a key in the Android Keystore (StrongBox when
  there is one) and enrolls Audio Logs as a device of its own with the app name `audio-logs`, so the server shows it
  beside the journal app and lets it see sound recordings, which it hides from journal by default.
- Each time the moments' list reloads (a moment stops, one closes, a pass ends, the app opens) the finished moments
  not handed over yet go to shed: not the one recording or open, not one with a pass running, not one waiting to be
  encoded. Shed hashes the audio once (`backup.json` in the app's storage keeps which), queues it and sends it with
  WorkManager when the network allows (Wi‑Fi only, or any), resuming where it stopped. A moment that moved to the
  moments folder since is found there by its id.
- The page refreshes every second while open, and fetches the server's list of recordings when it opens.

## Code

- Screen: `androidApp/.../ui/ServerScreen.kt` (`ServerScreen`, `PairingUi`, `ServerActions`), fed by
  `ServerSettings.kt` (package root); opened from `Settings.kt` (`ModelsActions.openServer`, `ModelsState.server`).
- `Recording.backup` (`ServerBackup`), `Recording.shed` (shed's `AndroidShed`), `Recording.backUp`,
  `Recording.scheduleUploads`; `TranscriptorApp` is shed's `ShedHost` for the upload work.
- Shared: `sharedLogic/.../backup/ServerBackup.kt` (`ServerBackup`, `ServerState`, `MomentBackup`), tested in
  `ServerBackupTest`.
- One enrollment per phone: shed's `ShedSiblings` (finding and asking the other apps) and `SharePairingActivity` (Audio
  Logs answers journal's ask the same way, once paired).
- Strings: `server_*`.

## Not yet

- The server's recordings as moments in the list (without notes or lines until transcribed), playing from the server
  with shed's range reads.
- Scanning the QR code in the app (the link is pasted for now).
- A moment's own backup state on its row and on its page (`ServerBackup.stateOf`).
