# Contributing to All Radio Tools

Thanks for taking the time to help. This page is the short version of how the project is built,
tested and reviewed, so a pull request lands with as little back-and-forth as possible.

*(Version française : [CONTRIBUTING.fr.md](CONTRIBUTING.fr.md).)*

## Getting set up

You need a recent JDK (21 is what the CI uses) and the Android SDK (API 37).

```bash
git clone https://github.com/ToftMalone/All-Radio-Tools.git
cd All-Radio-Tools
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:testDebugUnitTest    # run the unit tests (and the screenshot tests)
./gradlew :app:lintDebug            # run Android lint
```

The `scripts/` folder fetches the fonts and generates the world map; you rarely need it.

The debug build installs next to the published app, with its own package
(`com.allnetworktools.debug`) and name, so you can keep both on one phone.

## How the app is laid out

The code lives under `app/src/main/java/com/allnetworktools/`:

| Package | What goes there |
| --- | --- |
| `data/` | Logic, parsing and the repositories. No Compose. This is where the testable work lives. |
| `model/` | The `Network` and `Tool` enums — the catalogue of every screen the app can open. |
| `ui/pages/` | One screen per tool, grouped by radio (`ui/pages/bt/`, `ui/pages/wifi/`, …). |
| `ui/components/`, `ui/tools/`, `ui/theme/` | Shared Compose pieces, the `Sym` icon names and the theme. |

**Adding a tool** touches three places, in this order:

1. an entry in the `Tool` enum (`model/Network.kt`);
2. the screen itself under the matching `ui/pages/<radio>/`;
3. two wires in `ui/pages/PageHost.kt` — the route that builds the screen, and the group in
   `ui/pages/ToolsPage.kt` that lists it on the radio's Tools page.

Keep the logic in `data/` and the Compose in `ui/`: it is what lets the logic be unit-tested
without an emulator.

## Icons

Icon names live in `ui/theme/Symbols.kt` as `Sym.*`. The font is **subset** to exactly that
list, so reuse an existing name where you can. If you genuinely need a new glyph, add it to
`Sym` and rebuild the font with `scripts/fetch_fonts.py --icons`, and say so in the PR.

## Strings and translations

Every user-facing string goes through the resources — no hard-coded text in the UI.

- English (`app/src/main/res/values/strings.xml`) is the source of truth.
- Each translation lives in its own folder: `values-fr/`, `values-de/`, … A string that has
  no translation falls back to English, so a new string with **English and, ideally, French**
  is enough to merge; other languages can follow.
- Use plurals (`<plurals>`) for anything that counts.
- Keep quotes around values as the existing file does, and keep positional `%1$d` / `%2$s`
  arguments so translations can reorder them.

`lintDebug` reports `MissingTranslation` for strings not yet in every language. The project
already carries a number of these, so a few more from a new string are expected and are **not**
a reason to hold a PR — the unit tests are the gate (see below).

## Tests

Unit tests live in `app/src/test/java/com/allnetworktools/` and run on the plain JVM
(with Robolectric where a screen is involved). **Logic changes should come with a test.** The
existing tests are a good template: they feed real byte payloads to a parser and assert on the
result, with no Android device in the loop.

Plain-JVM tests still read the real French strings through the `ant.strings` system property
(wired in `app/build.gradle.kts`), so a test can assert on a sentence the user actually sees.

Run the suite before opening a PR:

```bash
./gradlew :app:testDebugUnitTest
```

## Commit messages

Follow the style already in the history: a short, specific summary line, prefixed with the area
and a colon.

```
Bluetooth: spot the pop-up flooding attack on BLE advertising
Wardriving: export the whole run as a WiGLE CSV file
Settings: one debug log tile opening share / save / clear
```

Explain the *why* in the body when the change is not obvious. When your work builds on someone
else's reverse engineering or an open-source project, credit it in the body and keep the
licenses compatible (the app is GPL-3.0).

## Pull requests

- **One concern per PR.** A focused diff is reviewed faster than a broad one.
- Say what the change does and why. If you tested on real hardware, say which device and what
  you saw — for anything that reads radios, that is the evidence that matters.
- Make sure `:app:assembleDebug` and `:app:testDebugUnitTest` pass. The CI runs both on every
  PR (see [`.github/workflows/ci.yml`](.github/workflows/ci.yml)).
- The `.github/workflows/release.yml` workflow builds signed releases on a tag; you do not need
  to touch it for a normal change.

## Responsible use

All Radio Tools observes the radios around you; it is a measurement and defence tool. Please
keep contributions on that side of the line: features that read, identify and warn, not ones
built to transmit interference, spoof, flood or disrupt other people's devices. The README's
"Responsible use" section is the reference.

## License

By contributing, you agree that your work is released under the project's
[GNU General Public License v3.0](LICENSE).
