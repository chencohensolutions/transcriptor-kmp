# UI lab notes (for the ui-lab skill)

How the lab itself works: [docs/previews.md](../docs/previews.md).

## Platform

Android first: iOS is built after the Android app is finished (owner's decision, Nov 2, 2026), so mocks are made in
the Android UI Lab on every machine, on the owner's phone when `adb devices` lists it, else the emulator.

| Lab app | Lab code | Open |
|---|---|---|
| Android UI Lab: the app **Transcriptor ui-lab** (`com.chencohensolution.transcriptor.uilab`), beside Transcriptor | `androidApp/src/debug/` | `./scripts/ui-lab.sh <screen> [option]` (`DEVICE=emulator` or `DEVICE=<serial>` to choose) |

Once the iOS app exists its lab goes in `iosApp/iosApp/Lab/`, with the same screen and option ids, in SwiftUI.

## Option folders

- Android: `androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/lab/<screen>/<b>/`, in the screens'
  package (`com.chencohensolution.transcriptor.ui`). Its strings files are in `src/debug/res/values/` and
  `values-iw/`, since Android resources can't sit in subfolders.
- iOS (later): `iosApp/iosApp/Lab/<Screen>/Option<B>/`, strings included.

## Files

- Files are `.kt` (`.swift` once iOS exists). The screen uses strings from resources, `MaterialTheme` and
  `LocalTranscriptorColors`.
- State is shaped as the controller would produce it, and moves to `sharedLogic`.
- Strings: Android `strings_<screen>_<b>.xml` (names prefixed `<screen>_<b>_`); iOS `<Screen>Option<B>.xcstrings`
  (`table: "<Screen>Option<B>"`).

## Sample data

`LabData` in the shared logic as it is.

## Fakes and edge cases

- Usual fakes: data the shared logic lacks; timing, the microphone, live transcription, the models.
- Edge cases: Hebrew (RTL) and an English moment, long lines and notes, empty, paused, the keyboard open.

## Screenshots

`adb -s <serial> exec-out screencap -p > <file>.png`, with the serial the script printed.

## Close the round

Link the history file from the screen's doc in `docs/screens/` under "Design history". History title platform:
Android.

## Promote

- Other platform: an entry in `docs/ios-todo.md` (iOS is built after Android is finished). Update `docs/screens/`.
- Screen target: Android `src/main/kotlin/.../ui/`; iOS `iosApp/iosApp/`, without its `#if DEBUG`.
- Strings: Android `src/main/res/`; iOS `Localizable.xcstrings`.
- State: into `sharedLogic`, produced by the controller with tests.
- Release build: `./gradlew :androidApp:assembleRelease`.
