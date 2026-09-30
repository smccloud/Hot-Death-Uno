# TODO

Java-to-Kotlin migration.

| Stage | Work | Verifiable by |
| --- | --- | --- |
| ~~0~~ | ~~Add Kotlin plugin to `app/build.gradle` only, no source changes~~ | ~~one CI compile~~ |
| ~~1~~ | ~~Run a headless Android emulator in Jenkins so `connectedAndroidTest` can take over the "manual on-device" checks below. Needs KVM / nested virtualisation on the controller, `-no-window -gpu off`, and the AVD plus system image cached rather than re-downloaded every run. — done: the matrix runs API 34-36, all 9 tests each, all enforced~~ | ~~build #40: 27/27 passed, `tested:[api34 api35 api36] known-failing:[]`~~ |
| ~~2~~ | ~~JUnit tests for the pure-logic classes (Card, Penalty, GameOptions, CardPile, Hand) — done, with two corrections to the original scope: `Card` does import `android.content.Context` and `R`, and `GameOptions` has no android imports at all yet is the least testable of the five, because every method forwards to a `Prefs.get*(Context)` call. `src/test` is 7 classes / 114 tests; the four that need a Context (`GameOptions`, `Card.toString`, the save-and-resume JSON round-trips) run under Robolectric, the rest are plain JVM.~~ | ~~build #43: 114/114 passed, full pipeline green~~ |
| ~~3~~ | ~~Convert leaf classes mechanically~~ — `Card`, `CardDeck`, `CardPile`, `Penalty`, `GameOptions` are Kotlin; `Hand` cannot be, because it calls the package-private `Game.checkCard` and Kotlin cannot see a Java package-private member. Two Java habits had to be replaced rather than translated: `public static final int` constants became `const val` in a companion object so `Card.ID_RED_0_HD` still resolves from Java, and every getter stayed a function named `getX()` so the Java callers and tests needed no edits at all. Also nullable: `Penalty.addCards`/`setEject`/`setFaceup`/`setNumCards` and the `Penalty(JSONObject, Game, CardDeck)` constructor take nullable players and card, because `PenaltyTest` and `JsonRoundTripTest` pass `null` there and Kotlin would otherwise insert a null check that Java never had. Detail below. | ~~build #60: 164/164 passed, full pipeline green~~ |
| 4 | `Player` hierarchy → `Game` / `ComputerPlayer` | tests + review |
| 5 | Android UI last (GameActivity, GameTable, Main, Prefs) | manual on-device |
| 6 | Messaging: victim-centric penalty wording, the card counts in one place, and a toast for a legal play ("North threw another green 5") — detail below | manual on-device |
| 7 | Novice mode: tap to advance after each card played, as a timed-vs-tapped choice beside `game_speed` — detail below | manual on-device |
| 8 | Computer players: keep improving the rule-based AI, and settle the 4th seat reusing player 2's settings — detail below | manual on-device |
| 9 | ~~Make the app's activities launch on API 37, then promote it from known-failing to enforced in the Jenkins matrix~~ — cannot be done on this controller. The platform was ruled out: no published 37.x x86_64 image can either install the app or finish booting. Dropped from the matrix rather than left failing. Detail below. | re-add it when Google ships a working image |

## The leaf classes, in Kotlin

`Card`, `CardDeck`, `CardPile`, `Penalty` and `GameOptions` are Kotlin now. The point of the stage was to move files without changing behaviour, so the translation is deliberately dull: same method names, same signatures, `m_` field names left alone, no idiomatic rewrites. Four things did not survive a literal translation.

**`Hand` is still Java.** It calls `Game.checkCard` (`Hand.java:244`), which is package-private (`Game.java:1103`), and Kotlin cannot see a Java package-private member from another file. Converting `Hand` would mean widening `checkCard` to public or moving it, which is a design change, not a mechanical one. It is stage 4's job.

**Constants had to move into a companion object.** Java reaches them as `Card.ID_RED_0_HD` and `Card.COLOR_RED` from `Game`, `Hand`, `ComputerPlayer`, `GameTable` and the tests — roughly 100 call sites. `const val` inside a `companion object` compiles to a static field on the outer class, so those call sites needed no edit; a plain `companion object { val ... }` would have turned every one of them into `Card.Companion.ID_RED_0_HD`. Same for `Penalty.PENTYPE_*`.

**Every getter stayed a function.** Kotlin's idiomatic `val colour: Int` would generate `getColor()` and so keep the JVM signature — but the property names do not line up one-for-one (`getID()`, `getFaceUp()`, `getPointValue()`), and each mismatch needs an `@JvmName`. Keeping `fun getColor()` sidesteps the whole class of problems and leaves the Java callers and the test suite byte-identical, which is what makes "mechanical" checkable.

**Some parameters had to become nullable.** This is the part that would have shipped as a runtime failure if it were left to the compiler. `PenaltyTest` calls `p.setFaceup(null, null, null)` and `p.addCards(greenFive(), 4, null, null)`, and `JsonRoundTripTest` calls `new Penalty(original.toJSON(), null, m_deck)`. A non-null Kotlin parameter compiles to an `Intrinsics.checkNotNullParameter` on entry, so the "obvious" translation would have failed 13 existing tests at runtime rather than at compile time — green in the build, red on the device's first penalty. Nullable everywhere the Java was willing to accept null is the faithful reading.

Two smaller notes. `CardDeck` and `CardPile` hold `Array<Card?>` rather than `Card[]`, because `drawCard()` nulls the slot it hands out; `CardImageAdapter` then walks `d.getCards()` by `cary.length` (`CardImageAdapter.java:31`) rather than by `getNumCards()`, so a null-slot Kotlin array would have thrown `ArrayIndexOutOfBoundsException` where the Java threw `NullPointerException` — same crash, different exception, so the choice is worth stating. And `Card.toString` used `if (strValue != "")` on a `String`, which only worked because the `""` literal is interned; Kotlin compares by value there, which gives the same answer for every input the method can see.

The card table itself was generated from the old `CardDeck.java` rather than retyped: 648 `new Card(...)` lines, and a card's `deckIndex` *is* its position, which saved games store. Getting that table wrong would not fail a build or a test, it would invalidate saved games and silently deal a different deck. So the four variants were dumped to text under both implementations and diffed: same cards, same order, same indices, same multipliers, same `getCard` lookups.

## Bugs the unit tests found

Stage 2 turned up three defects in the app itself. **All three are fixed** — the double-spaced labels in build #44, the multiplier truncation in build #45, which is published as 1.1.143, and the Shitter's lost pseudo-value in build #58. The last one was held back the longest because it changes behaviour rather than a string, and finding it was what the stage was for.

The Shitter was pinned by `HandTest` asserting the **current** behaviour, so fixing it turned that class red on purpose, to stop the fix passing unnoticed.

Of the three that were visible to a player, one was cosmetic and one was latent — it changed no score, and its section below says why. The third changed how the AI plays.

### The Shitter's 150 was overwritten before anything read it — fixed in build #58

`Hand.calculateValue` step 2 (`Hand.java:338`) gives the Shitter a pseudo-value of 150 so "computer players will want to unload it ASAP". That was never dead code — `ComputerPlayer` reads `getCurrentValue()` twice when choosing a card to shed (`ComputerPlayer.java:208`, `ComputerPlayer.java:280`), and that path is live at skill 1 and above.

Step 3 then ran over every card and called `c.setCurrentValue(pv)` (`Hand.java:437`), which reset the Shitter to its real point value of 0. So by the time the AI looked, the 150 was gone.

The reason it looked deliberate at first is that the sibling cards were protected: step 3 skips the F.U., Shitter and Quitter together (`Hand.java:370-376`), so the F.U./Quitter 500s from step 2 *did* survive. But that skip is gated on `bFullMonty`, which only becomes true when the F.U. **and** Quitter are both in the hand. A Shitter on its own was not covered, and a Shitter alongside a F.U. but no Quitter was not covered either.

Step 10's floor (`Hand.java:520`) then compared against that clobbered 0 instead of 150, so mid-game a Shitter hand could never score below 0 — where the intent was a floor of 150. `shitterAloneDoesNotRaiseTheTotal` and `shitterKeepsAMagicFiveHandOffTheFloor` in `HandTest` asserted exactly this.

**The fix skips the Shitter in step 3 whenever step 2 gave it the pseudo-value**, the same way the three bastard cards are already skipped under `bFullMonty`, which is the second of the two options this table listed. The first — re-applying 150 after step 3 — works too, and was passed over because it is the fragile half of the two: the repair would be undone by any later `setCurrentValue` added to that loop, silently.

**It changes no score, and no final score can move.** `total` is identical either way, because the Shitter's point value is 0 and step 3 was never what added the 150 to anything; the 150 only ever reached `total` through step 10's floor, which is already gated on `!isfinal`. `Game` applies the real penalty from the final scores and never reads `getCurrentValue()` at all. What did change is the two things that were actually broken: `ComputerPlayer` now sees 150 and sheds the card, and a mid-game estimate of a lone Shitter is 150 rather than 0.

Two tests were added because the pair that had pinned the bug could not have caught half of it: `shitterSurvivesTheFuckYouDouble` covers the F.U.-without-Quitter hand, which fell through the skip and is the case most likely to be missed by a fix that only tests a lone card, and `shitterPseudoValueNeverReachesAFinalScore` asserts the same hand scores 150 mid-game and 4 finally, so the fix cannot be made to pass by letting the pseudo-value into a real score.

### Every card label was double-spaced — fixed in build #44

The `cardcolor_*` strings all end in a trailing space, and `Card.toString` joined colour and value with another one (the join is now `strColor + strValue` at `Card.java:307`), so a plain card rendered `"Blue  7"` and a Reverse `"Green  Reverse"`. **Dropped the join's own space; the four resources are untouched.**

Worth keeping from the original note, because it decided which half was safe to touch: only the two paths that fall through to `msg = strColor + strValue` were affected. Cards matched by the `m_id` switch return early (`Card.java:274-277`) and were always clean, so the F.U., Shitter, Quitter, Shield, Magic 5, Holy Defender and Mystery Wild labels never had it. Every *numbered* card and every Reverse/Draw/Skip did. Cosmetic only, and it still is.

The join was the right half to remove, not the resources. `R.array.colors` feeds a single-choice picker in `GameTable.java:1920` and `Game.colorToString` (`Game.java:2258`) logs a colour on its own, so both still want that trailing space; stripping the resources would have touched more call sites for the same one-space result.

**What the tests did and did not guard, corrected.** An earlier version of this file claimed that fixing either half would turn `CardTextTest` red. That was wrong. The expectations were written as `getString(R.string.cardcolor_x) + " " + n`, which pins the join and the resource independently and never looks at the rendered label — so strip the trailing space out of `strings.xml` instead and all five still pass, leaving `"Blue7"`. The five now spell the join without its space, and `noCardLabelCarriesADoubleSpace` is the test that has teeth: it asserts on the label itself, over the numbered, Reverse, Draw, Double-Draw, Skip, value-switch and early-return cards.

### Save/resume truncated card multipliers — fixed in build #45

`Card`'s `JSONObject` constructor read `m_pointMultiplier` with `getInt` even though the field is a `double` (`Card.java:322`), while `toJSON` writes it as a double (`Card.java:339`). Android's `JSONObject.getInt` truncates toward zero, so the 0.5 Holy Defender (`CardDeck.java:66` and `:183`) resumed as `0.0`. **Now `getDouble`.**

**It never changed a score, and still does not.** `getPointMultiplier()` has exactly one definition and no call sites at all — `Hand.calculateValue` reads `getPointValue()` (`Hand.java:410`) and never the multiplier, and the routine's own header comment says multipliers are "useless now". So the 0.5 became 0.0 on every resume and nothing observable happened. That is why it was filed as latent rather than as a live bug, and why fixing it is not a scoring change: it removes a field that was quietly lossy, for whoever reads the multiplier next.

The regression test it needed is `JsonRoundTripTest.aFractionalMultiplierSurvivesTheRoundTrip`, which is exactly what this section said the suite was missing. `aCardSurvivesTheRoundTrip` could not catch it — it round-trips a `2.0` multiplier, which truncates to itself. The new case runs 0.5, 0.25, 1.5 and 2.5, on the reasoning that the defect is *any* fraction being truncated rather than the one value the deck happens to use.

| Item | Work | Verifiable by |
| --- | --- | --- |
| ~~Restore the Shitter's pseudo-value~~ | ~~Skipped the Shitter in step 3 whenever step 2 gave it the 150, rather than re-applying it after the loop, so nothing can overwrite it a second time. Covers a lone Shitter and a Shitter beside a F.U. with no Quitter. `shitterAloneDoesNotRaiseTheTotal` became `loneShitterLiftsTheTotalToItsPseudoValue` and the Magic 5 case now expects 150; added `shitterSurvivesTheFuckYouDouble` and `shitterPseudoValueNeverReachesAFinalScore`.~~ | build #58: 118 unit + 27 instrumented, 145 passed; **watch a Strong/Expert AI shed the Shitter on-device — still unverified, and the one thing the tests cannot show** |
| ~~Trim the double space~~ | ~~Dropped the join's own `" "` at `Card.java:307` and left the four `cardcolor_*` strings alone — the trailing space *is* the separator, and the colour picker plus `Game.colorToString` both still want it. Updated the five `CardTextTest` expectations and added the one that asserts on the rendered label.~~ | build #44: 115 unit + 27 instrumented, 142 passed, full pipeline green |
| ~~Fix the multiplier round-trip~~ | ~~`getInt` → `getDouble` at `Card.java:322`, plus `aFractionalMultiplierSurvivesTheRoundTrip` covering 0.5, 0.25, 1.5 and 2.5.~~ | build #45: 116 unit + 27 instrumented, 143 passed; published as 1.1.143 |
| ~~Cover `hasValidCards` and the green 3~~ | ~~`HandPlayabilityTest`, under Robolectric: `hasValidCards` is a loop over `Game.checkCard`, so what it covers is checkCard's rules -- colour, value, wilds, the 6/9 pair, the Shitter's two friends, and the whole under-a-penalty branch, which suspends the colour and value rules and admits only the F.U., the Holy Defender and AIDS, plus the Magic 5 against the Hot Death wild. Two cases are contrastive on one hand and differ only by the standard-rules cheat. The `isfinal` branch is charged 10 per AIDS card, and only on a final score.~~ | build #59: 137 unit + 27 instrumented, 164 passed |

### Two things the playability tests turned up, both left alone

Neither is fixed. Both are recorded because writing the tests meant reading the code around them, and neither is reachable from a test that asserts current behaviour — one of them *is* current behaviour.

**A virus infection is charged again in every round for the rest of the game.** `Hand.calculateValue` adds 10 to the owner's penalty per AIDS card on a final score (`Hand.java:391`), and that accumulation is correct — two cards, two infections. The compounding is one level up, in `Game.calculateScore`: it adds the player's *whole* `getVirusPenalty()` to their total score every round (`Game.java:1387` and `:1390`), but nothing clears that field between rounds. `Player.resetRound` (`Player.java:197`) does not touch `m_virusPenalty`; only `resetGame` (`Player.java:216`) does, and that runs once per game, not per round. So a player who picks up the green 3 in round 1 pays 10 again in round 2 with no green 3 in sight, again in round 3, and the running total grows by the whole accumulated penalty each time. The round winner is exempt (`Game.java:1383`), which is the one hint this was not meant to compound. The new test pins the accumulation at the `Hand` level, which is correct and is *not* the bug; the bug is the round loop, which needs a whole game to exercise.

**A malformed gamestate leaves `Game` half-built, silently.** The `catch (JSONException)` in the resuming constructor (`Game.java:280`) is empty, so a saved game missing any one key returns without a word: `m_penalty` is still null, `m_currCard` still null, and the first `checkCard` dereferences `m_penalty` unguarded at `Game.java:1117`. Nothing in the app should reach that — `startRound` assigns a fresh `Penalty` (`Game.java:713`) and the constructor does too once the JSON parses — so this is a bad save file or a future refactor rather than a live bug. Worth a log line either way, because "the game resumed" and "the game half-resumed and will crash on the first card played" look identical from outside.

## Messaging

Penalty messages are built in `Game.assessPenalty` and shown with `promptUser`.

| Item | Work | Verifiable by |
| --- | --- | --- |
| Penalty wording | The eleven `msg_penalty_*` strings are thrower-centric — `"%1$s threw a Draw Four - %2$d to %3$s..."` reads "North threw a Draw Four - 4 to South...". Name the player who has to act first: "South draws 4 cards (Draw Four from North)". | play a Draw Four |
| Card counts | Keep the counts in one place so wording and numbers cannot drift. From `Game.assessPenalty`: Draw Four 4, Hot Death 8, Delayed Blast 4, Harvester of Sorrows 4, Mystery Draw equals the value of the card it covers (69 on a yellow 69). | play each penalty card |
| Stacked penalties | Keep the `first`/`stacked` variants distinct after the rewrite ("South draws 4" vs "South draws 8"). Note `Penalty.addCards` overwrites the generating player on every play, so a stacked message names whoever stacked it, not the original thrower — `m_origCard` is the only field that survives. | stack a penalty twice |
| Mystery Draw on a non-number | `msg_penalty_null_wild_mystery` says "same as Wild" but never says the victim draws nothing. Say it: "South draws nothing - Mystery Draw on a non-number". | play Mystery Draw on a Wild |
| Confirm a legal play | There is `msg_card_no_good` for a rejected card and nothing at all for an accepted one, so a player cannot see *why* a play was legal. Toast it — "North threw another green 5". Needs a card descriptor (colour + value) helper, and a decision on whether "another" is reserved for a second consecutive match. | play a matching card |

## Novice mode

| Item | Work | Verifiable by |
| --- | --- | --- |
| Tap to advance | Pause the table after every card played and wait for a tap, so a novice can follow play at their own pace. `Game.waitForNextRound` already blocks the game thread on `m_waitingToStartRound` until `drawPileTapped` clears it, and `GameTable.onTouchEvent` handles the tap; generalise that to every play. | manual on-device |
| Timed vs tapped | The `game_speed` preference already gives a timed pause (`Thread.sleep(delay)` in `Game`), so the setting becomes a choice between timed and tapped rather than an on/off. | change game speed |
| Do not add more blocking sleeps | The tap wait is a `while` loop of `Thread.sleep(100)` on the game thread and `HumanPlayer` has four more. A pause that can wait forever makes that pattern a shutdown hazard — clear the flag in `onPause`/`onDestroy`. | background the app mid-pause |
| Default off | Expose the setting in `preferences.xml` beside `game_speed`, defaulted off. | fresh install |

## Computer players

`ComputerPlayer` is rule-based: `m_skill` gates behaviour at levels 1 and 2, `m_aggression` shifts the colour-balance thresholds, and `chooseColor` / `chooseVictim` / `getMinCardsRemaining` do the rest. `Random` is used only to pick how many cards to deal.

| Item | Work | Verifiable by |
| --- | --- | --- |
| Keep improving the AI | Hoard a wild instead of spending it on the first legal play, keep count of what each opponent is likely to be holding, and lead the colour the hand is weakest in. | play at Expert against Pushover |
| Fourth seat shares player 2's settings | `ComputerPlayer` reads `getP2Agg` / `getP2Skill` for the 4th computer seat, because `GameOptions` only exposes P1-P3. Settle whether that is deliberate; if not, the South seat is not independently tunable. | inspect the 4-computer-seat option |

## API 37

The Jenkins matrix runs the same APK on API 34-36, 9 tests per level: 3 in `MainLaunchTest` and 6 in `PenaltyStackTest`. **API 37 has been dropped from the matrix**: every published 37.x x86_64 image fails on this controller, for reasons that are not about the app. It cost roughly 30 minutes of boot polling per build to confirm that, so the level is out rather than left parked as known-failing. The matrix entry to restore is `37:37.2:google_apis_ps16k`.

**Three earlier diagnoses for this were wrong and are retracted.** All three were artifacts of when or how the device was asked, not of the app:

1. `am start -n com.smccloud.hotdeath/.Main` returning `Activity class does not exist` was read as proof the platform could not launch the app. But `connectedAndroidTest` uninstalls both APKs when a run finishes, so that command was being run against a device that no longer had the app installed. It produces the same error on every API level, passing or not.
2. `install-commit ... Broken pipe (32)` was read as an occasional flaky install. It is the install commit throwing, deterministically.
3. The `Broken pipe (32)` and the restart loop were read as the device being starved of memory, so api37 was given 4 GB instead of 2 GB. It fails identically at both, and the device log shows no lowmemorykiller, no OOM and no FATAL. The memory bump has been reverted.

**The actual cause**, from the device log of a run where the app is installed and system_server has settled:

```
E/SystemServiceRegistry: No service published for: persistent_data_block
android.os.ServiceManager$ServiceNotFoundException: No service published for: persistent_data_block
	at com.android.server.pm.PackageManagerSession.markAsSealed(...:2651)
	at com.android.server.pm.PackageInstallerSession.commit(...:2401)
	at com.android.server.pm.PackageManagerShellCommand.doCommitSession(...:4459)
I/Watchdog: Pausing of HandlerChecker: monitor thread for reason:
            vold#commitChanges might be slow
```

`persistent_data_block` is the Block Disk Assurance service, published by `vold`. On this image the package installer requires it to commit a session, and the service is not there, so the commit throws and the installer dies mid-commit — which surfaces as `Broken pipe (32)`. The same missing service is behind the restart loop: the watchdog pauses its handler checks on `vold#commitChanges`, and system_server comes back up to retry. This is a defect in the `google_apis` 37.0 image, not in the app. Nothing in this repo can fix it, and no resource setting reaches it.

| Item | Work | Verifiable by |
| --- | --- | --- |
| ~~37.2 tried, also unusable~~ | ~~`system-images;android-37.2;google_apis_ps16k;x86_64` gave 176 restarts and never settled inside the boot window. Its device log shows clean service initialisation and **no** crash, OOM or `persistent_data_block` error at all — it simply never gets past boot.~~ Fails differently from 37.0, and neither image works. | build #39: `known-failing:[api37-37.2-google_apis_ps16k]`, 34-36 all 9/9 |
| ~~Dropped from the matrix~~ | ~~api37 is out of the `for entry` list and `KNOWN_FAILING` is empty, so 34-36 all gate. Every level that runs is enforced again.~~ | build #40: `tested:[api34 api35 api36] known-failing:[]`, 27/27 passed |
| Images not yet tried | 37.1 `_ps16k`, and 37.0's other flavours (`google_apis_playstore`, `google_apis_playstore_ps16k`). Both were left alone deliberately: 37.2 showed the problem is not confined to one image, so each further attempt is ~30 min for a result that will probably be the same. Worth trying if a report says Google shipped an image fix. | a tag whose install commits |
| No AOSP fallback | `system-images;android-37.*;default;x86_64` is **not published for any 37.x**. An earlier note here named it as the obvious candidate; that was wrong and it was never available. | a published tag in `sdkmanager --list` |
| Restore api37 when one works | Add `37:37.2:google_apis_ps16k` back to the matrix. It will be enforced automatically, since `KNOWN_FAILING` is empty. `MainLaunchTest` must pass, not merely the install. | a green api37 row in the emulator matrix |

