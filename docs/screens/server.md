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
- **Paired**: "Paired with … as … This phone is "…" there."; how the backup stands: "n moments on the phone · m uploaded" (the moments recorded in Audio Logs that are on
  the phone now, and how many of them went up; hen, Nov 17, 2026: the old "Every finished moment is on the server
  (917)" counted every moment ever handed over, deleted ones and server recordings opened here included), then
  "Waiting to upload: 2 moments · 64 calls · 279 MB left" while something waits; moments still WAV, waiting to be compressed on the charger, are said so ("3 moments wait to be compressed…"),
  and Upload now compresses them first, on battery too ("Compressing 3 moments, then sending…"; hen, Nov 17, 2026); what counts as uploaded is recorded in
  `backup.json` when shed reports a file done, and only then does shed forget it (`recordUploads`, `Shed.acknowledge`;
  hen, Nov 17, 2026: shed keeps each upload until the app that asked has learned it ended, as shed will one day be one
  app serving several); what was handed over before that is asked of the server once (`confirmWithServer`), and handed
  over again when the server doesn't hold it; "Sending now…" with a bar while a send
  runs; the server didn't answer (trying again later) or no longer accepts this phone; moments the server refused, by
  title; **Back up now**, or **Upload now** while something waits (one button for moments and calls, hen's call): from
  the tap it says "Sending now…", sends what already waits first, then hands over new moments and calls and sends
  those, in the app at once (`Recording.uploadNow`, `ServerBackup.runUploads`), not left to
  the background work, which may be waiting out a retry or for Wi‑Fi; with Wi‑Fi only on and the phone on mobile data
  it asks first, with how much would go;
  **Wi‑Fi only** (on by default); **Only while charging** (off by default; hen, Nov 17, 2026): the background
  sending waits for the charger and starts only once the battery is at 30% or more (`ENCODE_MIN_BATTERY`, the same rule
  as compressing and transcribing on the charger); Upload now sends anyway; **What Audio Logs shows from the server**: first **Recordings from the server** (on by default, a
  choice of this phone kept in `backup.json`; off, the moments list and search show only what was recorded in Audio
  Logs, nothing from the server or other phones, and backup goes on; Nov 16, 2026), then, while it is on, **Calls**
  and **Voice messages** switches, off by default, kept on the server for this app only, and that recordings marked
  private never reach Audio Logs (Nov 16, 2026; shown once the server answers `soundRecordingsShown`); **Back up calls** (Nov 16, 2026: Audio Logs, not the journal app, backs calls up, apart from whether it shows
  them), right under Wi‑Fi only: a switch that first explains and opens the system's folder picker (Samsung:
  Recordings › Call, which has a `.nomedia`), read only; then "From Call · 1,420 sent · 3 waiting", that a call goes a
  few minutes after it ends, also when hidden, and nothing is deleted from the phone, with **Change folder** (or
  **Allow access to call recordings** in red when the phone took the access away); turning it off asks first
  ("Stop sending calls?"). With calls hidden but backed up, the Calls switch below says "Hidden here, still backed
  up."; **Behind your fingerprint**: **Lock calls** and **Lock voice messages**
  (kept on this phone), and that a label can be locked too; **Free up
  space**: rule chips (Everything backed up, Older than 3 months, Older than 12 months, Keep the newest 10 GB), what
  it would free ("12 moments, 3.2 GB"), a **Free up …** button, its progress and result; **Cache on this phone**: how
  much shed keeps of the limit, with 2, 10, 20 and 50 GB chips; **Unpair** (asks first: what was sent stays on the server, every moment stays on
  the phone).

## How it behaves

- Calls: each backup pass (the app opening, a moment finishing, Back up now) and `CallsWorker` every 3 hours hand
  shed the sound files in the calls folder not handed over yet, or changed, and left unchanged for 2 minutes (a call
  still being recorded waits for a later pass) (`ServerBackup.backUpCalls`, kept by path,
  size and time in `backup.json`), with source `call` and the folder's name; shed finds them by the ref
  `call:<path>` when it sends. The server reads who and when from each name. Calls the server already has (from the
  journal app, which backed them up before) are answered "exists" and not sent again.

- Nothing is handed over or sent until the user pairs. Pairing makes a key in the Android Keystore (StrongBox when
  there is one) and enrolls Audio Logs as a device of its own with the app name `audio-logs`, so the server shows it
  beside the journal app and lets it see sound recordings, which it hides from journal by default.
- Each time the moments' list reloads (a moment stops, one closes, a pass ends, the app opens) the finished moments
  not handed over yet go to shed: not the one recording or open, not one with a pass running, not one waiting to be
  encoded. Shed hashes the audio once (`backup.json` in the app's storage keeps which), queues it and sends it with
  WorkManager when the network allows (Wi‑Fi only, or any) and, with Only while charging, on the charger with 30%
  battery or more (`ShedUploads.schedule(chargingMinBattery)`: the work asks for the charger and checks the battery
  when it starts, again every 15 minutes while low), resuming where it stopped. A moment that moved to the
  moments folder since is found there by its id.
- The page refreshes every second while open, and fetches the server's list of recordings when it opens (which also
  turns new ones into moments).
- Free up space deletes a moment's audio only after hashing it again and the server's `/verify` saying it holds it
  whole; a moment being recorded, worked on, or waiting to be encoded or sent is left out.

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

- Playing a server moment from the server with shed's range reads, without bringing all of it first.
- Scanning the QR code in the app (the link is pasted for now).
- A moment's own backup state on its row and on its page (`ServerBackup.stateOf`).
