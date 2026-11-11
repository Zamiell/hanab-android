# Hanab Live for Android

An unofficial Android client for [hanab.live](https://hanab.live), the free web version of the card
game Hanabi. The website's interface does not scale well to a phone, so this app is a native
Kotlin / Jetpack Compose client built for touch: it talks to the same server, so you play in the same
lobby and the same games as everyone on the website.

> **Unofficial.** This is a fan project. It is not made by, or endorsed by, the hanab.live
> maintainers. Please keep the load on their server reasonable.

## What it does

**Lobby**
- Log in (a new username creates an account, as on the website). The password is stored encrypted
  with an Android Keystore key, and the app signs in again on launch and after a dropped connection.
- Tables, lobby chat, user list, private messages (`/pm`, `/w`, `/msg`, ... work as on the website).
- Create a table (variant search, optional password, remembered) or join, watch, or open a shared replay.

**Playing**
- Play, discard and clue, with a confirmation when you try something you already know is wrong
  (playing a card you know is unplayable, discarding a known critical or playable card).
- Clue panel on your turn only: pick a partner and a clue; invalid clues are disabled, the cards a
  clue would touch light up, and the choice carries over when you switch partner.
- Clue bubbles on the cards a clue touched, for the turn after it.
- Notes (long-press a card), discard pile, and a "cards left" grid.
- "What I know": every card of yours shows its remaining possibilities, with a red X over anything
  ruled out only by counting visible cards. A toggle shows what a partner knows about their hand.
- Critical (`!`) and discardable (bin) markers, dead cards dimmed, touched cards raised.
- Step back through earlier turns, also during a game. After a game: score, share link, play again,
  and a shared replay that follows the leader.
- Animations for plays, discards, draws, and the stacks; sounds and vibration taken from the website.
- Suit symbols on cards (for colour-blind players), tablet and landscape layout.
  Everything can be switched off or tuned in Settings.

**Variants**
All 2,428 of the website's variants: plain suits, Rainbow, Pink, Brown, White, Black, Prism, Orange (its cards are
played by discarding them) and the other simple suits, and the variants whose special rules are about what clues touch or allow (Rainbow-Ones, Pink-Ones, Brown-Ones,
Deceptive-Ones, Odds and Evens, Funnels, Chimneys, Synesthesia, Scarce Ones, Critical Fours, Clue Starved, Alternating
Clues, Color / Number / Totally Blind, Color Mute, Cow & Pig, Duck, Throw It in a Hole, Reversed suits, Up or Down, Sudoku). Offline play, which the bots
play, has fewer of them: the ones whose clue and play rules the bot knows.

**Bots**
Built for hosting [scala-bot](https://github.com/will-hanabi-bot/scala-bot): mark a user as a bot in the
Users tab, then use **Invite bots** in the pregame. The app sets the conventions and fast mode, then
joins the bot to your table by private message. You can also kick bots and set conventions for all of
them.

**Analysis**
**Analyze** (at the end of a game, and next to each game in a profile's history or in the offline
stats) has the bot go through a finished game on the phone itself and list every move it would have
played differently, with the clear mistakes marked. It works for games of the website and for games
played offline, with no bot online and, for offline games, no connection. It uses the same native
library as offline play (see "Offline play") and can take a minute or more.

## Building

Requirements: Android Studio (or the Android SDK), **JDK 21** (17 also works), and the Android SDK platform 35. Point `JAVA_HOME` at it first (on Windows the scripts default to a JDK 21 in `~\.jdks`, such as the one Android Studio downloads).

Debug build (installable straight away, signed with the debug key):

```powershell
# Windows
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\build.ps1                      # app\build\outputs\apk\debug\app-debug.apk
.\build.ps1 testDebugUnitTest    # runs the unit tests
```

```bash
# macOS / Linux
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Or open the folder in Android Studio, set Settings > Build Tools > Gradle > Gradle JDK to 21, and press Run.

Notes:
- JDK 25 (Android Studio's newest bundled JBR) makes Gradle 8.9 fail with "Unable to establish loopback connection"; use 21 or 17.
- `build.ps1` / `release.ps1` point `TEMP` at `C:\gtmp`, because the same error appears when the temp path is long.
- `local.properties` (the SDK path) is machine-specific and not committed.

## Offline play

"Play offline against bots" (on the login screen, and as "Offline" in the lobby) plays you against 1 to 4 bots on the
phone itself, with no account or connection. The bots are [hanab-bot-rs](https://github.com/michielbaird/hanab-bot-rs),
and a small one-table server in `native/offline` (Rust) deals the game and answers the app with the same messages
hanab.live sends, so the game screen is the same. It is built for 64-bit ARM phones (every build) and for x86_64 emulators (debug builds only); on other devices the buttons are hidden.

Gradle builds the library as part of every build (the `buildNative` task), and skips it when nothing under `native/offline` has changed, so the app never ships a library older than its code. To build it by hand:

```powershell
.\native\build.ps1      # Windows
```

```bash
./native/build.sh       # macOS / Linux
```

It needs [rustup](https://rustup.rs) (the pinned Rust toolchain is fetched for you) and the Android NDK (Android Studio >
SDK Manager > SDK Tools > NDK, r27 or newer). The results are copied to `app/src/main/jniLibs/` (phones) and `app/src/debug/jniLibs/` (emulators); neither is committed. Without `cargo` installed Gradle skips the task (offline play is then unavailable), and `-PskipNative` skips it on purpose.

## Release build

A release APK must be signed or Android will refuse to install it. Create a keystore once, and keep it (and its passwords) somewhere safe and out of git: if you lose it you cannot ship updates that install over the old version.

```bash
keytool -genkeypair -v -keystore hanab-release.jks -alias hanab -keyalg RSA -keysize 4096 -validity 10000
```

Then copy `keystore.properties.example` to `keystore.properties` and fill in the path and passwords (both files are gitignored). Build:

```powershell
.\release.ps1            # Windows  -> dist\hanab-<version>.apk (+ .sha256)
```

```bash
./release.sh             # macOS / Linux -> dist/hanab-<version>.apk (+ .sha256)
```

Without `keystore.properties` the scripts warn and produce `hanab-<version>-unsigned.apk`, which cannot be installed.

To publish on GitHub: bump `versionCode` (must increase every release) and `versionName` in `app/build.gradle.kts`, build, then attach `dist/hanab-<version>.apk` to a new GitHub release.

## How it works

The client speaks the website's own protocol: `POST /login`, which sets a session cookie, then a
WebSocket at `/ws` carrying `command {json}` frames. The game rules and clue behaviour are
reimplemented from the website's shared game code, driven by the server's stream of game actions.

| Path | What is there |
|---|---|
| `net/` | HTTP login, WebSocket client, message models |
| `game/GameState.kt` | Immutable game state, clue rules, deduction, replay of earlier turns |
| `game/Variants.kt` | Suits and variants, read from the bundled `suits.json` / `variants.json` |
| `LobbyViewModel.kt` | Connection, lobby, game and bot state |
| `ui/` | Compose screens (lobby, pregame, game board, dialogs, animations) |

The game logic has unit tests (`app/src/test`). The screens have no automated tests yet.

## Caveats

- The login and the WebSocket connection (`/ws?bot=true`) say the client is a bot, which the server accepts in place of the website's version number
  (without it, it rejects clients that are not the current website build). The server marks the account as a bot the first time.
- The server's WebSocket protocol was rewritten in November 2026 (`tableId`, a `settings` object, events with an `identity`). `net/WireAdapter.kt` turns it into the
  messages the rest of the app reads, and the app's commands into the new ones; the clues, strikes and score are counted from the events, as the website does.
- The double-discard sounds and the variant-specific sounds from the website are not implemented.

## Licence and credits

Released under the **GNU GPL v3** (see [LICENSE](LICENSE)).

The suit symbols, sound effects, and the suits / variants / colours data are taken from the
[hanabi-live](https://github.com/Hanabi-Live/hanabi-live) repository (GPL-3.0). Where its artwork
comes from is documented in `public/img/pips/notes.txt` in that repository; a copy of its licence is in
`app/src/main/assets/pips/`. Hanabi is a card game by Antoine Bauza; this project is not affiliated with
its publishers.
