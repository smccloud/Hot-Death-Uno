# Hot Death Uno

**An Android implementation of the Hot Death variant of the card game UNO.**

Hot Death UNO is a UNO add-on/variant that layers House Rules and 27 (or more,
depending on the variant) extra cards on top of standard UNO. The extra cards let
you remove players from the game, draw up to 69 cards at once, force an opponent to
lay their whole hand face up, and more. Fan favorites include Mystery Draw, the
Harvester of Sorrows, and Mutual Assured Destruction.

The app is a single-player, human-vs-AI implementation: you play the **South** seat
against three computer opponents at the North, East, and West seats. There is no
networked play.

| | |
|---|---|
| Package | `com.smccloud.hotdeath` |
| Current version | 1.3.0 (`versionCode` 1003000) |
| Platform | Android, `minSdk` 34 (Android 14) / `targetSdk` 36 (Android 16) |
| Language | Java 17 and Kotlin, JDK 17 target (no third-party runtime dependencies) |
| Build | Gradle 8.13 + Android Gradle Plugin 8.11.1 |
| License | MIT — see [License](#license) |

---

## Table of contents

- [Playing the game](#playing-the-game)
- [The card set](#the-card-set)
- [Scoring](#scoring)
- [Settings](#settings)
- [Cheat codes](#cheat-codes)
- [Building](#building)
- [Project layout](#project-layout)
- [Architecture](#architecture)
- [Development notes](#development-notes)
- [License](#license)
- [Credits and history](#credits-and-history)

---

## Playing the game

The launch screen (**Main**) offers New Game, Continue, Settings, Help, About, and
Exit. Continue is only shown when a saved game exists.

1. A random player is picked to deal the first hand; after that, the winner of the
   previous hand deals.
2. The dealer chooses how many cards to deal, between **5 and 15**.
3. Tap a card in your hand to play it. The badge in the lower-right corner of a hand
   shows how many cards it holds; drag left/right to scroll a hand that overflows.
4. **DRAW** and **PASS** are also available from the options menu, as is **HELP**.
5. Tap and hold any card to see its rules text in-game, or use the menu's *Card Info*
   to browse the full card catalog for the deck currently in play.
6. A round ends when a player empties their hand. Hands are then revealed, scores are
   tallied, and the game ends once a player reaches the target score.

Play is to **1000** points, lowest score wins. If the human player is ejected, a
**FAST FORWARD** button appears so you can skip the remaining turns.

### Turn flow

Every turn runs through `Game.advanceRound()`:

```
prompt user (human seat only)
  -> Player.startTurn()          // sets wantsToDraw / wantsToPlayCard / wantsToPass
  -> play a card?
       -> discard, set color, choose color if wild
       -> handleSpecialCards()   // creates a Penalty if the card is hostile
       -> checkForDefender()     // offer the victim a chance to counter
       -> advance turn
  -> draw / pass?
  -> no valid cards?  -> assessPenalty() or force a draw
  -> winner check     -> empty hand, or all four "bastard" cards
```

Human input is a cross-thread handshake: `HumanPlayer.startTurn()` spin-waits on the
game thread until the UI thread calls `turnDecisionPass()`, `turnDecisionDrawCard()`,
or `turnDecisionPlayCard(Card)`.

---

## The card set

### Standard cards

| Card | Count (1 deck) | Points |
|---|---|---|
| `0` | replaced by a bastard card in every color | — |
| `1`–`9` | 2 per color | face value |
| Draw Two | 1 per color | 20 |
| Skip | 1 per color | 20 |
| Reverse | 1 per color | 20 |
| Draw Two Spreader | 1 per color | 60 |
| Double Skip | 1 per color | 40 |
| Reverse Skip | 1 per color | 40 |
| Wild Draw Four | 4 | 50 |

A one-deck game is **108 cards**; enabling *Two decks* builds **216**.

### Wild variants

| Card | Effect | Points |
|---|---|---|
| **Hot Death Draw Four** | Wild Draw **8**, stackable | 100 |
| **Delayed Blast Draw Four** | Skips a player, then Wild Draw 4; stackable | 100 |
| **Harvester of Sorrows** | Wild Draw 4; stackable *onto*, nothing stacks onto it, cannot be defended | 0 |
| **Mystery Wild** | On a number: draw that many. On the 69: draw 69. On anything else: plain Wild | 10 x highest number in hand |

### Bastard cards

Exactly one copy of each, so no duplicates to memorize:

| Card | Color | Effect | Points |
|---|---|---|---|
| **Holy Defender** | Red 0 | Defends against Wild Draw / Quitter / Glasnost. Skips its owner and passes the penalty down the table | halves score |
| **Glasnost** | Red 2 | Forces a chosen player to lay their whole hand face up | 75 |
| **Magic 5** | Red 5 | Playable on *any* card; nullifies a Hot Death outright | -5 |
| **Quitter** | Green 0 | Removes the next player from the hand entirely | 100 |
| **AIDS / Virus** | Green 3 | Shares the incoming penalty with whoever dealt it | 3, +10 cumulative |
| **Luck of the Irish** | Green 4 | Draws one card fewer than required — passively, without being played | 75 |
| **Fuck You / Retaliation** | Blue 0 | Sends a Wild Draw / Quitter / Glasnost back at the thrower and reverses direction | doubles score |
| **Blue Shield** | Blue 2 | Immune to Spreaders | value of highest card in hand |
| **Shitter / Big Brother** | Yellow 0 | Playable only on Holy Defender, Magic 5, or as your last card; forces the *highest* table score | adopts max |
| **Mutual Assured Destruction** | Yellow 1 | Ejects a chosen player — and you | 100 |
| **69** | Yellow | Playable on any 6 or 9 regardless of color; locks your hand total to 69 | locks at 69 |

The three profanity-named cards (**Fuck You**, **AIDS**, **Shitter**) are displayed
with family-friendly names (**Retaliation**, **Virus**, **Big Brother**) by default.
See [Cheat codes](#cheat-codes) to restore the originals.

**The bastard set**: holding all four of Holy Defender, Quitter, Fuck You, and Shitter
ends the hand immediately with a score of **0** for that player.

### Penalty resolution

`Penalty` is a flat state record, not a hierarchy — one active penalty at a time, with
an optional secondary victim:

| Type | Constant |
|---|---|
| none | `PENTYPE_NONE` |
| draw N cards | `PENTYPE_CARD` |
| remove player from the hand | `PENTYPE_EJECT` |
| reveal the victim's hand | `PENTYPE_FACEUP` |

Splitting a penalty (AIDS) gives each victim `(n + 1) / 2` cards. Luck of the Irish
decrements the count by one. Stacking is permitted for Wild Draw cards under Hot Death
rules; Draw 2s, Spreaders, and Mystery Draw can never be stacked.

---

## Scoring

Scoring lives in `Hand.calculateValue()`, an ordered eleven-step pass:

1. Bastard set check (delegates to `Game`).
2. **Full Monty** — Quitter + Fuck You forces the hand to 1000; each card is valued
   at 500 so the AI dumps them. Shitter alone takes a pseudo-value of 150.
3. Sum the fixed-value cards, tracking the highest card and highest number. Ending a
   hand with AIDS adds a cumulative +10 per hand you go on to lose.
4. Mystery Wild = `10 x highest number in hand` (or 10 if you hold no numbers).
5. Blue Shield = value of the highest card in your hand.
6. **69** locks the hand total to exactly 69.
7. Magic 5 subtracts 5.
8. Fuck You doubles.
9. Holy Defender halves, rounding up: `(total + 1) / 2`.
10. Shitter adopts the highest hand total at the table.
11. Virus penalties accumulate in `Game.calculateScore()`.

The per-card `setCurrentValue(...)` result of every step is also the AI's heuristic
input, which is why this one method serves both scoring and card selection.

> When adding a new card type, `Hand.CheckCard()`, `Hand.CheckForDefender()`, and
> `Game.HandleSpecialCards()` must be edited together — the scoring pass was overhauled
> in v1.0.1 and the comment in `Hand.calculateValue()` says so.

---

## Settings

Defined in `res/xml/preferences.xml` and read through `GameOptions`.

| Setting | Key | Default | Values |
|---|---|---|---|
| Game speed | `game_speed` | `1` (Fast) | 0 Very fast … 4 Very slow |
| Two decks | `two_decks` | `false` | 108-card or 216-card deck |
| Computer 4th | `computer_4th` | `false` | Let the AI take the South seat (for testing) |
| Face up | `face_up` | `false` | Reveal all cards — useful for learning |
| Cheat level | `cheat_level` | `0` (Honest) | 0 Honest, 1 Rascal, 2 Scoundrel, 3 Dirtbag |
| Cheat code(s) | `cheat_code` | `""` | Comma-separated; see below |
| Skill (per computer seat) | `p{1,2,3}_skill` | `1` | 0 Weak, 1 Strong, 2 Expert |
| Aggression (per computer seat) | `p{1,2,3}_aggression` | `0` | -6 Pushover, -3 Nice guy, 0 Normal, 3 Jerk, 6 Sadist |
| Saved game | `gamestate` | `""` | Written by `GameActivity.onPause()`, not user-editable |

### AI behavior

*Skill* controls how much of the heuristic the AI is allowed to use:

- **Weak (0)** — scores candidates by `getCurrentValue()` only.
- **Strong (1, 2)** — plays a defensive card immediately if one is legal; otherwise
  maximizes hand value. Holds onto wild cards late in the hand. Special-cases the
  M.A.D. card, the 69, and Mystery Wild (Mystery is thrown preferentially on high
  numbers, and at a 69 above everything else).
- **Expert (2)** — additionally evaluates the change in the standard deviation of the
  four suit counts, so it will hold a bad color balance to keep an even one.

*Aggression* biases victim selection toward the human's seat and gates the expert
color-balance heuristic — a Sadist (`+6`) will wait out a color imbalance until the
opponent is down to 2 cards.

*Cheat level* N deals N special cards straight into the human's hand at deal time.

### Game speed

`Game.getDelay()` returns the pause between computer moves in milliseconds:
`0` while fast-forwarding, `250` when the human seat is inactive, then `700` / `1200` /
`1700` / `2900` / `4000` for settings `0`–`4`.

---

## Cheat codes

Enter these comma-separated in **Settings → Cheat code(s)**. Some require a restart.
Matching is a plain `String.contains()` in `GameOptions`.

| Code | Effect |
|---|---|
| `originalhotdeath` | Turns off family-friendly mode, restoring the card names **Fuck You**, **AIDS**, and **Shitter** |
| `standardrules` | Switches to a plain UNO ruleset: fixed 7-card deal, no defensive plays, no Draw Four stacking, Draw Two no longer grants an extra turn, and the target score drops to 500 |

> `standardrules` is currently **broken** — the deck-construction branch for it is
> missing a `oneDeck` case and enumerates more cards than the array it writes into. See
> [Development notes](#development-notes).

---

## Building

### Requirements

- JDK 17 or newer (required by Android Gradle Plugin 8.11.1)
- Android SDK Platform 36
- Gradle 8.13+ (or Android Studio Meerkat and newer)

### With Android Studio

Open the `com.smccloud.hotdeath` directory as a project. The Gradle sync pulls the
Android Gradle Plugin and builds from there.

### From the command line

```bash
cd com.smccloud.hotdeath
gradle assembleDebug
gradle assembleRelease
```

Outputs land in `com.smccloud.hotdeath/app/build/outputs/apk/`.

### Wrapper caveat

`gradlew` and `gradlew.bat` are committed, and
`gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.13 — but
**`gradle-wrapper.jar` is still not in the repository**. `./gradlew` will fail until
you run `gradle wrapper` once to generate it, or let Android Studio regenerate it on
sync. There is also no `local.properties` — set `sdk.dir` to your Android SDK path, or
rely on `ANDROID_HOME`.

### Tests and CI

`app/src/test` holds the JVM and Robolectric suite (`gradle testDebugUnitTest`) and
`app/src/androidTest` the instrumented one (`gradle connectedAndroidTest`, which
needs a device or emulator). A tracked `Jenkinsfile` runs both: unit tests, lint,
`assembleDebug`, `assembleRelease`, then `connectedAndroidTest` on a headless
emulator at API 34, 35 and 36.

The release APK is the published artifact, and since 1.2.0 it is **signed with the
project's release certificate** rather than the debug key. The key itself is not in
the repository: `app/build.gradle` reads `keystore.properties` when that file is
present and falls back to the debug key when it is not, so a fresh clone still
builds something installable. CI supplies the real material from Jenkins credentials
and the pipeline prints the signing certificate into the console log, so the key
that signed any given artifact can be checked rather than assumed. The published
releases are on the [GitHub releases page](https://github.com/smccloud/Hot-Death-Uno/releases).

One thing the pipeline still does not cover: `connectedAndroidTest` runs against the
debug APK, so the R8-minified release build is assembled, signed and archived but
never exercised on a device.

---

## Project layout

```
.
├── LICENSE                       MIT
├── README.md                     this file
└── com.smccloud.hotdeath/       the Gradle root
    ├── build.gradle              root buildscript; AGP 8.11.1
    ├── settings.gradle           include ':app'
    ├── gradle.properties         2 GB daemon heap
    ├── gradle/wrapper/           distributionUrl pinned to Gradle 8.13 (no JAR committed)
    ├── proguard.cfg              legacy ProGuard rules, superseded by app/proguard-rules.pro
    ├── default.properties        vestigial Ant-era stub
    ├── gradlew, gradlew.bat      wrapper scripts
    ├── CHANGELOG.txt             release notes, v0.9 through v1.0.5
    ├── TODO.txt                  open issues and completed work
    ├── README.md                 original project blurb
    ├── artwork/                  GIMP source for the store feature image
    └── app/
        ├── build.gradle          compileSdk 36, minSdk 34, versionCode 1003000
        ├── proguard-rules.pro    R8 rules (release is minified)
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/smccloud/hotdeath/    16 classes, ~8,300 lines (6 Java, 10 Kotlin)
            └── res/
                ├── layout/       7 XML layouts + layout-land/
                ├── values/       strings.xml, arrays.xml, colors.xml
                ├── xml/          preferences.xml
                └── drawable-*/   692 PNGs, 8.3 MB across 5 density buckets
```

### Artwork

The card art is drawn per-density, following the standard `ldpi:mdpi:hdpi:xhdpi`
`3:4:6:8` ratio, with the `xlarge-*` buckets using tablet-sized art instead:

| Bucket | Card height | Files |
|---|---|---|
| `drawable-mdpi` | 80 px | 144 |
| `drawable-hdpi` | 120 px | 144 |
| `drawable-xhdpi` | 160 px | 144 |
| `drawable-xlarge-mdpi` | 120 px | 144 |
| `drawable-xlarge-hdpi` | 160 px | 144 |
| `drawable-ldpi` | icons only | 2 |

The whole art set was run through `pngquant 128`, which cut the APK from 13 MB to
6 MB. `TODO.txt` notes that going to 64 colors was tried and looked too bad.

Card bitmaps are named `card_<color>_<value>[_<variant>].png`, and are resolved by
`GameTable.initCards()` from an 81-entry table of card IDs — this mapping is
hand-maintained and must be extended whenever a card is added.

---

## Architecture

Sixteen classes, ~7,600 lines of Java, no third-party libraries. There is no MVP/MVVM
layering; the game engine and the view are deliberately coupled.

```
Main ──> GameActivity ──> GameTable (View, custom Canvas drawing)
              │                   ▲
              ├──> GameOptions <───┘   setGameTable() / getString()
              │        │
              │        └──> Prefs (PreferenceActivity, SharedPreferences)
              └──> Game (extends Thread)  ──> Player / ComputerPlayer
                          │                      └──> Hand ──> Card
                          ├──> CardDeck ──> Card
                          ├──> CardPile  (draw + discard)
                          └──> Penalty
```

### Class reference

| Class | Lines | Role |
|---|---|---|
| **`Game`** | 2,062 | The rules engine *and* the game thread. Owns the deck, both piles, the four players, the active penalty, and the turn loop. Reaches the UI only through `runOnUiThread` and `GameTable.getString()`. |
| **`GameTable`** | 1,817 | The entire board, drawn by hand on a `Canvas` — no layout XML. Handles portrait/landscape geometry, per-seat hand placement and drag scrolling, pile rendering, direction and color indicators, scores, aggressor/victim emoticons, card-count badges, hit testing, long-press card help, and the color/victim/deal dialogs. |
| **`CardDeck`** | 796 | Card factory and registry. `reset(standardRules, oneDeck)` builds every card by enumeration, then `shuffle()` permutes a parallel `m_oCards` array so the canonical `m_cards` array stays in ID order for the UI. |
| **`ComputerPlayer`** | 517 | The AI. See [AI behavior](#ai-behavior). |
| **`Hand`** | 472 | A player's cards plus the eleven-step scoring pass in `calculateValue()`. |
| **`Player`** | 293 | Base class for a seat: score, hand, skill, aggression, and the decision flags the turn loop reads back after `startTurn()`. |
| **`Penalty`** | 216 | The active penalty record — type, card count, originating card, generating player, victim, secondary victim. |
| **`HumanPlayer`** | 199 | Bridges the game thread to the UI thread via spin-wait handshakes. |
| **`Prefs`** | 173 | The settings screen, plus static typed accessors used by `GameOptions`. |
| **`Main`** | 141 | Title screen. |
| **`Card`** | 343 | One card: color, value, ID, face-up flag, deck index, and the mutable scoring state (`currentValue`, `pointValue`, `pointMultiplier`, `cumulativePenalty`, `highestCardMatch`). Owns the whole card-ID constant namespace. Also holds a back-pointer to its owning `Hand`. |
| **`CardPile`** | 92 | Fixed-capacity LIFO stack, used for *both* the draw and discard piles. |
| **`GameOptions`** | 87 | Thin adapter over `Prefs` that caches a `GameActivity` reference, so `Game` and `Player` never touch a `Context` directly. Also resolves the two cheat codes. |
| **`CardImageAdapter`** | 80 | `BaseAdapter` for the card-catalog `GridView`, filtered to the cards actually present in the current deck. |
| **`TapDismissableDialog`** | 41 | Dialog that dismisses on tap, provided the finger moved less than 7 px on both axes. |

### Threading model

Exactly two threads: the Android UI thread and `Game`, which `extends Thread`.

```
Game.run()
  startGame()  or  resume from snapshot
  while (!m_gameOver) {
      runRound()          // snapshots state every turn
      waitForNextRound()
      startRound()
  }
  shutdown()
```

- **Pause/resume** uses a `m_pauseLock` monitor, a `m_paused` flag, and
  `waitUntilUnpaused()`. `toJSON()` synchronizes on the same lock so a snapshot can
  never tear against a pause.
- **Human interaction is a 100 ms spin-wait**, not a lock — `HumanPlayer.startTurn()`,
  `getNumCardsToDeal()`, `chooseColor()`, `chooseVictim()`, and
  `Game.waitForNextRound()` all busy-loop until a flag is flipped by the UI thread.
- **Shutdown is two-phase.** The first `shutdown()` sets `m_stopping`, which is polled
  after every blocking call. The second, from `GameActivity.onDestroy()`, releases the
  references to `GameTable`, `GameOptions`, `GameActivity`, and the players.
- **The game model itself is unsynchronized.** Only the game thread mutates it; the UI
  thread reads it via redraw requests. The startup handshake is a bit unusual:
  `GameTable.onSizeChanged()` is what actually calls `Game.start()`.

### Save / restore

State is serialized to a `JSONObject` and stored as a string in the `gamestate`
preference.

- **Save** — `GameActivity.onPause()` pauses the game, takes a snapshot, and commits it.
  `getSnapshot()` returns `""` if the game is over, which is also how *Continue* knows
  to hide itself.
- **Cadence** — a snapshot is taken at the top of every turn, plus at the end of a
  round, so a restore always resumes at a turn boundary.
- **Shape** —
  ```json
  {
    "state":       { "dealer": 1, "currPlayer": 2, "currColor": 3,
                     "direction": 1, "cardsPlayed": 7,
                     "roundComplete": false, "currCard": 42 },
    "deck":        [ /* every Card object, fully serialized */ ],
    "drawPile":    [ /* deck indices */ ],
    "discardPile": [ /* deck indices */ ],
    "penalty":     { /* ... */ },
    "players":     [ /* 4x: active, scores, virus penalties, hand indices */ ]
  }
  ```
  Only the `deck` carries card *data*; hands and piles serialize as deck indices.
- **Restore** — `Game(JSONObject, GameActivity, GameOptions)` rebuilds the deck first
  so index lookups resolve, then the piles, then the state, then the players, then the
  penalty.
- **Class reconstruction is preference-driven, not serialized.** Slot 0 (South) becomes
  a `HumanPlayer` or a `ComputerPlayer` based on the *current* `computer_4th` setting,
  so toggling it between plays changes who the South seat is on resume.

---

## Development notes

### Android 16 (API 36) retarget

`minSdk` was raised from 29 to 34 and `targetSdk`/`compileSdk` from 33 to 36. Because
AGP 7.3.0 tops out at `compileSdk` 33, the toolchain moved to **AGP 8.11.1 / Gradle
8.13 / Java 17** at the same time. That upgrade is not cosmetic — it is what makes API
36 possible at all.

Three behavioral changes came with the retarget:

- **Edge-to-edge is now mandatory.** Since `targetSdk` 35 the framework no longer lets
  an app reserve room for the system bars, and there is no opt-out. Each activity now
  installs a `WindowInsets` listener on `android.R.id.content` and pads it by the
  system-bar and display-cutout insets (`applyEdgeToEdgeInsets()` in `Main`,
  `GameActivity`, and `Prefs`). Because `GameTable` recomputes its entire layout from
  its view size in `onSizeChanged`, padding the content frame is enough — the board,
  the hands, and the options menu all re-flow. This uses the platform `WindowInsets`
  API, available from API 30, so the project still has **zero third-party
  dependencies**; the obvious alternative, `androidx.core`'s `WindowInsetsCompat`,
  would have forced AndroidX on the project.
- **Predictive back needed no migration.** Nothing in the app overrides
  `onBackPressed()`, so the default behavior is already correct under the API 36
  back-dispatch model.
- **Rotation no longer recreates the activity.** `configChanges` for
  `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density` is now
  declared on all three activities. This makes the pre-existing
  `onConfigurationChanged()` overrides actually fire — they were dead before, because
  without the manifest attribute Android recreates the activity instead. It also avoids
  losing an in-progress game on rotation, which happened because the game is persisted
  to preferences and the `STARTUP_MODE` intent extra does not survive recreation.

> **Cosmetic side effect:** `Prefs` was given `android:theme="@android:style/Theme.NoTitleBar"`
> to match the other two activities, which means the settings screen no longer draws
> its own title bar. Content padding alone cannot inset a framework title bar, and the
> old title ("Hot Death settings") is the activity's own label. Revert that one
> attribute if you would rather keep the title bar and accept the overlap.

### Known bugs

- **`standardrules` cheat code is non-functional.** The `standardRules && !oneDeck`
  branch in `CardDeck.reset()` is missing, and the `standardRules` branch that does
  exist enumerates 324 cards into an array sized for 108. It will throw
  `ArrayIndexOutOfBoundsException` or build an empty deck.
- **`gradle-wrapper.jar` is still not committed** — `gradlew` will not run until you
  generate it. See [Building](#building).
- **Snapshot corruption is swallowed.** Both the `Game(JSONObject, ...)` constructor and
  `GameActivity` wrap deserialization in `catch (JSONException e)` with a `FIXME` and
  no recovery, so a corrupt `gamestate` silently starts a fresh game.
- **Toast durations can go negative.** `GameTable` computes the duration as
  `getDelay() - 500`, which is negative both during fast-forward (`0`) and while the
  human seat is inactive (`250`).
- **`Main.onResume()` compares preference strings with `==`** rather than `.equals()`.
  It happens to work because the default is a string literal.
- **`Game.java.new` is a stale editor backup** that is still tracked in git. It does
  not compile (it is not a `.java` file) and should be deleted.

### Dead and vestigial code

- `Player.m_othersVoids[4][4]` is allocated and reset but never read.
- `Card.m_pointMultiplier` is a leftover from before the v1.0.1 scoring overhaul;
  the multipliers passed to the `Card` constructor are effectively dead because steps 8
  and 9 of `calculateValue()` do the doubling and halving explicitly.
- `Hand.getHighestNonTrump(color)` has an inverted condition — it skips cards that
  *match* the color, so it returns the highest card of a *different* color. Unused.
- `default.properties` is an Ant-era stub with no content.
- `Game.dealHands()` contains a large `debugDeal` block holding seven hand-written test
  scenarios, gated behind `android.os.Debug.isDebuggerConnected() && debugDeal` with
  `debugDeal = false`. Useful reference material for card interactions, but dead.
- `Player.resetGame()` has a pointless `if/else` where both branches set the same value.

### Open items from `TODO.txt`

- Scan additional card backgrounds for more realism.
- Clearer in-game messaging for penalties ("South draws, takes penalty").
- An optional novice mode that taps to advance after each card played.
- A toast when a matching card is played, since it is otherwise hard to tell what
  happened on a small table.
- Continuing AI improvements.
- Unresolved rules questions: whether the 1,000-point penalty needs Quitter +
  Retaliation or also Big Brother; whether Draw 2 may be stacked; whether Retaliation
  applies against Draw 2; and whether the dealer eats penalties on the first face-up
  card.

---

## License

Released under the [MIT License](LICENSE) — `Copyright (c) 2011-2026 Shaun McCloud`.

The `LICENSE` file previously held AGPL-3.0, while the in-app About text and the
original project `README.md` both said GPL. All three now agree on MIT.

> **Note for anyone redistributing this:** the underlying *Hot Death UNO* game design
> dates to 1993 and is credited to Byron Hoffman, John Howard, Chris Kulander, and
> Matt Murray, expanded by Keith Ammann, Larry Geraghty, Chris Kulander, Matt Murray,
> and Jason Seifert. UNO is a trademark of Mattel. The MIT grant above covers this
> Android implementation and its artwork only.

Card graphics and rules text were redrawn specifically to avoid the trademark and
copyright issues described in `CHANGELOG.txt` (v0.9.2), and the bundled "standard
rules" text was removed for the same reason.

---

## Credits and history

Hot Death UNO was created by **Byron Hoffman, John Howard, Chris Kulander, and Matt
Murray**, and expanded by **Keith Ammann, Larry Geraghty, Chris Kulander, Matt Murray,
and Jason Seifert**. It was originally coded for Windows in Visual Basic by
*Mr. Señor Love Daddy* in 1993. The author first encountered it on Windows Mobile
devices, then on Android, and has been playing it ever since.

This Android implementation was written by **priebe** (runtsoft.com) — first for
Pocket PC in the early 2000s, then ported to Android. Version 1.0.0 was released in
May 2011; the current source tree is version 1.3.0, migrated to a modern Gradle /
AGP 8.11.1 toolchain.

From 1.1.0 the patch number is the count of passing tests in the build that produced
the artifact — it moves by 0.0.1 per test rather than being maintained by hand — so
`versionName` 1.2.164 meant 137 unit tests plus 27 instrumented all green, which is
still the count as of 1.3.0 — that release moved files, not tests.
`versionCode` encodes the same three numbers as `major * 1000000 + minor * 1000 +
patch`, which leaves three digits each for the minor and the patch.

1.2.0 reset the patch to 0 and moved the minor instead, and 1.3.0 did the same. A
release that adds no tests has no new patch number to move to, and 1.1.143 had gone
out signed with the debug key, which Android refuses to replace with a differently
signed build at the same `versionCode` — so the minor carries releases until the test
count moves again.

See [`CHANGELOG.txt`](com.smccloud.hotdeath/CHANGELOG.txt) for the full release history
and [`TODO.txt`](com.smccloud.hotdeath/TODO.txt) for the original author's roadmap.

Upstream project history lives at <http://www.smorgasbork.com/hotdeath/>, and the
issue tracker referenced in the changelog at
<http://code.google.com/p/hotdeath/issues>.
