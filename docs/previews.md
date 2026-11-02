# The UI lab (Android)

A library of the app's screens and of suggested designs for them, with made-up data, running on the phone or
emulator so they scroll, tap and animate. The first page lists the screens; each screen lists its options: the screen
as the app has it now (`current`), then the suggestions. A screen the app doesn't have yet has suggestions only.

```bash
./scripts/ui-lab.sh                    # build, install on the connected phone (else the emulator), open the list of screens
./scripts/ui-lab.sh lecture            # a screen's options
./scripts/ui-lab.sh lecture a          # one option (ids in LabScreens.kt)
DEVICE=emulator ./scripts/ui-lab.sh    # the emulator even with a phone connected (or DEVICE=<serial>)
```

The script builds a separate app, **Transcriptor UI Lab** (the `uiLab` build type: the debug build with the id
`com.chencohensolution.transcriptor.uilab`, compiling `src/debug`, with `src/uiLab/AndroidManifest.xml` making the
lab its only launcher entry). It installs beside Transcriptor and never touches its notebooks.

- The list is [LabScreens.kt](../androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/LabScreens.kt);
  the lab is [UiLabActivity.kt](../androidApp/src/debug/kotlin/com/chencohensolution/transcriptor/ui/UiLabActivity.kt).
- Suggestions are options, each in its own folder under `lab/<screen>/<option>/`; how they are built, recorded in
  [ux-history/](ux-history/README.md) and promoted into the app is the [ui-lab skill](../.claude/skills/ui-lab/SKILL.md).
- The made-up data is [LabData.kt](../sharedLogic/src/commonMain/kotlin/com/chencohensolution/transcriptor/lab/LabData.kt)
  in the shared logic, so the iOS lab can use it later.
- Release builds never compile `src/debug`.

Gradle needs a JDK (17 or newer): `JAVA_HOME`, else Homebrew's `openjdk@21`, else Android Studio's.
