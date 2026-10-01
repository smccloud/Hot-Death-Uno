# TODO

Java-to-Kotlin migration.

**A note on the line references below.** They are written as `Foo.java:123` and are line numbers in the **pre-migration Java**, which is what the surrounding text is describing. Eight of the ten app classes referenced here — `Card`, `CardDeck`, `ComputerPlayer`, `Game`, `GameActivity`, `GameTable`, `Hand`, `Player`, `TapDismissableDialog` — are now `.kt`, and every line number in them has moved, so grepping for one will find the wrong line or nothing at all. The file names are kept as they were rather than rewritten to `.kt`, because a `.kt` line number that is wrong is worse than a `.java` one that announces itself as historical. `CardImageAdapter` is still Java, so its references are still live.

| Stage | Work | Verifiable by |
| --- | --- | --- |
| ~~0~~ | ~~Add Kotlin plugin to `app/build.gradle` only, no source changes~~ | ~~one CI compile~~ |
| ~~1~~ | ~~Run a headless Android emulator in Jenkins so `connectedAndroidTest` can take over the "manual on-device" checks below. Needs KVM / nested virtualisation on the controller, `-no-window -gpu off`, and the AVD plus system image cached rather than re-downloaded every run. — done: the matrix runs API 34-36, all 9 tests each, all enforced~~ | ~~build #40: 27/27 passed, `tested:[api34 api35 api36] known-failing:[]`~~ |
| ~~2~~ | ~~JUnit tests for the pure-logic classes (Card, Penalty, GameOptions, CardPile, Hand) — done, with two corrections to the original scope: `Card` does import `android.content.Context` and `R`, and `GameOptions` has no android imports at all yet is the least testable of the five, because every method forwards to a `Prefs.get*(Context)` call. `src/test` is 7 classes / 114 tests; the four that need a Context (`GameOptions`, `Card.toString`, the save-and-resume JSON round-trips) run under Robolectric, the rest are plain JVM.~~ | ~~build #43: 114/114 passed, full pipeline green~~ |
| ~~3~~ | ~~Convert leaf classes mechanically — `Card`, `CardDeck`, `CardPile`, `Penalty`, `GameOptions` are Kotlin. `Hand` was recorded here as unable to be converted, because it calls the package-private `Game.checkCard` and "Kotlin cannot see a Java package-private member from another file". **That was wrong, and stage 4 converted it** — see the section below.~~ | ~~build #60: 164/164 passed, full pipeline green~~ |
| ~~4~~ | ~~`Player` hierarchy → `Game` — `Player`, `HumanPlayer`, `ComputerPlayer`, `Hand` and `Game` are all Kotlin. Every class that is not an Android view is Kotlin now; the six files still in Java are exactly stage 5's list. Two visibility widenings were forced (`Player.drawCard`, `Game.checkCard`) because Kotlin has no package-private and `internal` mangles the JVM name. Nullability is the part that would have shipped as a runtime failure. Detail below.~~ | ~~build #63: 164/164 passed, full pipeline green; re-verified on #69, 167/167, once the round loop had a test~~ |
| 5 | Android UI — three files left as of 1.4.2: `CardImageAdapter`, `Prefs`, `Main`. Done so far: `GameTable` (#71), `GameActivity` (#82, released as 1.4.1), `TapDismissableDialog` (#85, released as 1.4.2) | manual on-device |
| 6 | Messaging: victim-centric penalty wording, the card counts in one place, and a toast for a legal play ("North threw another green 5") — detail below | manual on-device |
| 7 | Novice mode: tap to advance after each card played, as a timed-vs-tapped choice beside `game_speed` — detail below | manual on-device |
| 8 | Computer players: keep improving the rule-based AI, and settle the 4th seat reusing player 2's settings — detail below | manual on-device |
| 9 | ~~Make the app's activities launch on API 37, then promote it from known-failing to enforced in the Jenkins matrix~~ — cannot be done on this controller. The platform was ruled out: no published 37.x x86_64 image can either install the app or finish booting. Dropped from the matrix rather than left failing. Detail below. | re-add it when Google ships a working image |

## The leaf classes, in Kotlin

`Card`, `CardDeck`, `CardPile`, `Penalty` and `GameOptions` became Kotlin in stage 3. The point of the stage was to move files without changing behaviour, so the translation is deliberately dull: same method names, same signatures, `m_` field names left alone, no idiomatic rewrites. Four things did not survive a literal translation, and all four still govern the later stages.

**Constants had to move into a companion object.** Java reaches them as `Card.ID_RED_0_HD` and `Card.COLOR_RED` from `Game`, `Hand`, `ComputerPlayer`, `GameTable` and the tests — roughly 100 call sites. `const val` inside a `companion object` compiles to a static field on the outer class, so those call sites needed no edit; a plain `companion object { val ... }` would have turned every one of them into `Card.Companion.ID_RED_0_HD`. Same for `Penalty.PENTYPE_*`, and `Game`'s `SEAT_*` and `DIR_*` in stage 4.

**Every getter stayed a function.** Kotlin's idiomatic `val colour: Int` would generate `getColor()` and so keep the JVM signature — but the property names do not line up one-for-one (`getID()`, `getFaceUp()`, `getPointValue()`), and each mismatch needs an `@JvmName`. Keeping `fun getColor()` sidesteps the whole class of problems and leaves the Java callers and the test suite byte-identical, which is what makes "mechanical" checkable.

**Some parameters had to become nullable.** This is the part that would have shipped as a runtime failure if it were left to the compiler. `PenaltyTest` calls `p.setFaceup(null, null, null)` and `p.addCards(greenFive(), 4, null, null)`, and `JsonRoundTripTest` calls `new Penalty(original.toJSON(), null, m_deck)`. A non-null Kotlin parameter compiles to an `Intrinsics.checkNotNullParameter` on entry, so the "obvious" translation would have failed 13 existing tests at runtime rather than at compile time — green in the build, red on the device's first penalty. Nullable everywhere the Java was willing to accept null is the faithful reading, and stage 4 found far more of these.

Two smaller notes. `CardDeck` and `CardPile` hold `Array<Card?>` rather than `Card[]`, because `drawCard()` nulls the slot it hands out; `CardImageAdapter` then walks `d.getCards()` by `cary.length` (`CardImageAdapter.java:31`) rather than by `getNumCards()`, so a null-slot Kotlin array would have thrown `ArrayIndexOutOfBoundsException` where the Java threw `NullPointerException` — same crash, different exception, so the choice is worth stating. And `Card.toString` used `if (strValue != "")` on a `String`, which only worked because the `""` literal is interned; Kotlin compares by value there, which gives the same answer for every input the method can see.

The card table itself was generated from the old `CardDeck.java` rather than retyped: 648 `new Card(...)` lines, and a card's `deckIndex` *is* its position, which saved games store. Getting that table wrong would not fail a build or a test, it would invalidate saved games and silently deal a different deck. So the four variants were dumped to text under both implementations and diffed: same cards, same order, same indices, same multipliers, same `getCard` lookups.

## The Player hierarchy and Game, in Kotlin

Stage 4 moved `Player`, `HumanPlayer`, `ComputerPlayer`, `Hand` and `Game` — 3,964 lines of Java. Every class in the app that is not an Android view is Kotlin now; the six files still in Java are exactly stage 5's list, so the migration is past the point where a translation mistake can hide in a class nothing else touches.

**The recorded reason for `Hand` not being converted was wrong, and it cost a stage.** TODO.md said `Hand` "cannot be, because it calls the package-private `Game.checkCard` and Kotlin cannot see a Java package-private member from another file". Kotlin does resolve Java's package-private within the same package, and `Hand` is in `com.smccloud.hotdeath` like `Game`. Checked directly before writing a line of it: a scratch Kotlin object in the package calling `Game.checkCard` compiled, and the class file was produced. There was never a blocker to clear. The claim was plausible enough to be believed without a test, which is the argument for testing it.

Two visibility widenings were forced, and both for the same reason — Kotlin has no package-private, and `internal` compiles to `foo$app_debug`, which would have broken the Java callers outright rather than failing to compile:

  - **`Player.drawCard` was `protected`**, reached from `Game.java:1015` and `:1053`. That worked only because `Game` is in the same package. Now public.
  - **`Game.checkCard` was package-private**, and `HandPlayabilityTest.java` calls it. Now public — the one deliberate visibility change in `Game.kt`.

The rest of `Game`'s old package-private members are `internal`, which keeps the same audience with no Java callers: `promptUser`, `promptForDrawCard`, `getLastCardCheckedIsDefender`. `forceDraw` is private, which is what it already was in practice.

**Nullability is the part that would have shipped as a runtime failure.** Same failure mode stage 3 found, and bigger:

  - `Hand`'s player is nullable because `HandTest` and `JsonRoundTripTest` build hands with `new Hand(null)` in 40-odd places and `CardTest` does the same. The one place a `Hand` dereferences it — the green-3 branch in `calculateValue` — keeps `!!` rather than `?.`, so the Java's NPE survives rather than being turned into a silent no-op.
  - `Player.m_game`, `m_go` and `m_hand` are nullable because `shutdown()` nulls all three, so `getHand()` honestly returns `Hand?`. The Java callers dereference without a check and NPE exactly where they used to.
  - **`Game.m_penalty` is nullable in 90 places, across 68 lines.** It is null in the plain constructor until `startRound()` builds one, and the resuming constructor's empty `catch (JSONException)` leaves it null on a malformed save. Every dereference is `m_penalty!!`, deliberately, so both NPEs survive. The count is `grep -c` over `Game.kt`, not an estimate — an earlier version of this line said "about thirty", which was guessed and was less than half right. Defaulting the field to `Penalty()` would have been tidier and would have silently changed the half-built-game behaviour documented below.

**One thing invisible in review, and worth keeping.** `ComputerPlayer.computeColorBalance` averages the four suit counts with *integer* division and only then widens: a five-card hand averages 1, not 1.25. Kotlin refuses to widen an `Int` into a `Double` implicitly, so the intermediate is now spelled out as its own `val` and then `.toDouble()`d. Writing `/ 4.0` there is what a tidy-up would look like, and it would change every colour-balance score the Expert AI weighs at `m_skill >= 2`.

### How Game.kt was checked before it had a test

`Game.kt` was written before any of this could run it, so it was checked structurally against the Java it replaced — 73 of 73 methods carried over, the sequence of `R.string` references **identical** at 47 of 47, the sequence of `Card.ID_*` references **identical** at 254 of 254, and every `m_*` assignment accounted for, the only differences being five fields that moved to Kotlin declaration-site initialisers and three the Java wrote in a constructor. Kotlin's `==` calls `equals()` where Java's `==` is identity, and no class overrides `equals` — checked across every source file — so the reference comparisons in `sortHand`, `advanceRound` and `getNextPlayer` still mean what they meant.

That argued nothing was dropped. It is not the same as playing a game, which is what the next section is about.

## GameTable, in Kotlin — stage 5, one file in

`GameTable.java`, 1,935 lines, converted in build #71/#72. It is the largest of stage 5's six files and by a wide margin the one holding the most of the app's behaviour: the whole board is drawn by hand on a `Canvas`, there is no layout XML for any of it, and `onSizeChanged` computes the geometry for all of it. Five Java files remain — `GameActivity`, `Main`, `Prefs`, `CardImageAdapter`, `TapDismissableDialog`.

**Every method name kept its Java spelling, capitalisation included.** `RedrawTable`, `ShowCardHelp`, `Toast`, `PromptForVictim`, `PromptForNumCardsToDeal` and `PromptForColor` all read as lowerCamelCase now, and six of the seven are called only from `Game.kt`. Renaming them would have put a second file in the diff for a conversion whose whole claim is that it moved a file and changed nothing else. This is stage 3's "every getter stays a function" rule extended to a whole class, and it is why `Game.kt` and `GameActivity.java` are untouched in the diff.

The one name that genuinely could not stay is **`Toast`**, which collides with `android.widget.Toast`. The import is dropped and the class is written out in full at the two places it is needed — a qualified package name cannot be shadowed by a member, where `Toast.makeText` would be.

`m_game` and `m_go` are nullable, and every dereference is an explicit `!!`, because `shutdown()` nulls both. Same bargain as `Game.m_penalty`: the Java threw a `NullPointerException` on a table whose activity had gone away, and `!!` throws it in the same place rather than papering over it.

`getCardByID`, `getCardBitmap` return nullable because they are `HashMap.get` and the Java already returned null. `getCardImageID` and `getCardHelpText` are the exception — they unboxed, so a missing card threw, and `!!` keeps it throwing rather than inventing a `-1`. `getCardIDs` returns `Array<Int?>`, which is `Integer[]` on the JVM, so `CardImageAdapter.java` is unaffected.

### Three things that were not transliterations

**The two pile loops had to become `while`.** Both rewrite their own counter: on the last pass the body snaps `i` to the index of the top card, and the trailing `i += skip` then steps past it and ends the loop. Kotlin's `for` cannot reassign its counter, and reordering it to make it a `for` would silently drop the top card — which is the one card in the discard pile a player can actually see.

**Java widens `int` to `float` and to `double`; Kotlin widens `int` literals to neither — only to the integral types.** This produced two errors and one near-miss in the same commit. The seven `setTranslate` calls pass `Point.x`, an `Int` field, into a `float` parameter: those need `.toFloat()`. `Card(..., 0)` passes `0` for a `Double` multiplier: that needs `0.0`, and it is the only one of its kind in the file. But `postDelayed(task, 1000)` and `vibrate(100)` are correct **untouched**, because `Long` *is* in the integer-literal type list — which is why `Game.kt`'s existing `Thread.sleep(100)` was always fine. The rule is not "Kotlin does not widen literals", it is "literals widen to the integral types only".

**`Runnable { ... }` does not parse.** `kotlin.Runnable` is a SAM interface with no companion object, so there is no constructor to call and the name resolves as a reference to the interface. It has to be `object : Runnable { override fun run() { ... } }`, which is the closer translation anyway — the Java had an anonymous inner class. The syntax error cascaded into four spurious "actual type is `Unit` but `Runnable` was expected" errors at every `removeCallbacks` and `postDelayed`.

### What the checks caught, and what they did not

Build #70 failed on the two type errors above in 14 seconds. The structural checks had all passed first, which is the point worth recording: **the ordered-sequence diff does not catch a type error.** The reference counts were identical with `Runnable { }` and with `Card(..., 0)` still in place, because neither changes which resource or constant the file names. Checking that every `R.string`, `R.drawable` and `const val` the Kotlin refers to *exists* is a different and stronger check, and it is what caught the real mistake:

While transcribing I had "corrected" three help-text lookups to colour-specific strings — `cardhelp_green_s_double`, `cardhelp_green_r_skip`, `cardhelp_yellow_r_skip` — on the reasonable reading that a green card should show green's text. The Java uses the generic `cardhelp_s_double` and `cardhelp_r_skip` for all three colours, and none of the colour-specific strings exist in `strings.xml` at all. That one would have failed the build for a different reason entirely, and it is the same shape as the "improvement" that stages 3 and 4 had to resist.

What was verified before the build ran: 37 of 37 methods carried over, the only two absent being the anonymous inner classes that became a `Runnable` object and three `OnClickListener` lambdas; ordered `R.drawable` **identical** at 207 of 207, `R.string` at 95 of 95, `Card.ID_*` at 498 of 498, `Card.COLOR_*` at 111 of 111, `Game.SEAT_*` at 122 of 122, `Card.VAL_*` at 30 of 30; all 122 `decodeResource` calls; 81 card IDs, 81 `m_cardLookup` entries, 84 image-ID and image lookups, 87 help-string lookups.

| Item | Work | Verifiable by |
| --- | --- | --- |
| ~~Convert `GameTable`~~ | ~~1,935 lines, mechanical, `m_` names and method names untouched. `m_game`/`m_go` nullable with `!!`. `Toast` collides with `android.widget.Toast` and is qualified. The two pile loops became `while`; seven `setTranslate` calls needed `.toFloat()`; one `Card(..., 0)` needed `0.0`; the `Runnable` became an object expression. Three phantom colour-specific help strings reverted to the generic ones the Java used.~~ | build #71 build-only green, #72 green: 140 unit + 27 instrumented = 167, `tested:[api34 api35 api36] skipped:[] failed:[]` |

## GameActivity and TapDismissableDialog, in Kotlin

Two more of stage 5's six, released as 1.4.1 and 1.4.2. Three Java files left: `CardImageAdapter`, `Prefs`, `Main`. Nothing outside those two commits had to change — `Game.kt`, `GameTable.kt`, `CardImageAdapter.java`, `Main.java` and the whole test suite are untouched by both.

**`GameActivity` is constrained by reflection, and that shaped the whole file.** `GameRoundLoopTest` builds the activity with `buildActivity().get()` — so `onCreate` never runs and the game thread never starts — and then sets seven fields by name: `m_go`, `m_game`, `m_gt`, `m_btnFastForward`, `m_vMenuPanel`, `m_btnMenuDraw`, `m_btnMenuPass`. Every name has to survive, and every field has to be nullable: a nullable Kotlin property is still a plain field of the same name on the JVM (`GameOptions?` is `GameOptions`, not `Optional`), so `Field.set` is unaffected, and `onDestroy` nulls three of them anyway. The test exists precisely so that a rename fails loudly rather than as an NPE.

The three `findViewById` calls spell out their type argument. Inference would have compiled, but the property is what the test sets by name, so it has to stay a `Button` field and not widen to `View`.

`getBtnFastForward` returns a non-null `Button` on purpose, with the `!!` inside the getter rather than at the call site: `GameTable.kt` calls it unchecked and was not to be edited, and this throws where the Java would have thrown instead of passing null into `setVisibility`.

**The hex-literal trap, seen from the other side of 1.4.0's.** `setTextColor(0xff7f7f7f)` appears four times in `showMenuButtons`. 0xff7f7f7f is 4,286,543,487, past Int32 max, so Kotlin types the literal `Long` and `setTextColor(Long)` resolves to nothing — four compile errors that a reference count would never have caught. 1.4.0 learned that literals widen to the integral types but not to `float`; this is the same rule again for `Int`. Worth having as one sentence: **a literal that overflows `Int` is a `Long`, and every Android method taking an int colour or flag wants an `Int`.**

`TapDismissableDialog` is 43 lines and had exactly one interesting decision: its three fields were package-private, so they became `internal` rather than `private` — same audience, as `Game.kt` did with its own. Nothing reads them, but silently narrowing the visibility is not what a mechanical conversion should do.

| Item | Work | Verifiable by |
| --- | --- | --- |
| ~~Convert `GameActivity`~~ | ~~366 lines. Seven reflected field names and types preserved, all nullable. `getBtnFastForward` returns non-null with `!!` inside. Four `setTextColor` literals given `.toInt()`. `WindowInsets` imported explicitly since the Java wildcard `android.view.*` is not a thing in Kotlin. Four anonymous inner classes became lambdas. The stray `};` after `onDestroy` is gone.~~ | build #82: 140 unit + 27 instrumented = 167, matrix green. Released as **1.4.1** |
| ~~Convert `TapDismissableDialog`~~ | ~~43 lines. Three package-private fields → `internal`. 7px threshold, both ACTION branches, both `Math.abs` checks and the single `dismiss()` in the same order.~~ | build #85: 167 passed, matrix green, and the first run on the renamed AVDs. Released as **1.4.2** |

## The configuration cache: two ways of enabling it that both did nothing

The Gradle configuration cache is enabled and now genuinely hits, and getting there took three attempts. Worth writing down, because the failure mode is invisible — **the build is green either way.**

**Attempt 1 — turn on the flag.** Gradle documents where the state lives, and it is the load-bearing fact:

> The configuration cache state is stored in a `.gradle/configuration-cache` directory in the root of your Gradle build.

In the *project directory*. Not the Gradle user home, where this controller keeps the distribution, the dependency cache, the AVDs and the system images, all of which the Workspace cleanup stage deliberately leaves alone. So the stage was deleting the cache before every build: every run stored an entry and never hit one, paying the write cost for none of the benefit. Enabling it alone would have made CI strictly slower.

**Attempt 2 — preserve that one directory.** Stash it in `/tmp` before the wipe, copy it back after. The mechanism worked: build #78 logged 20 files, 400K, a complete entry with `entry.bin`, `work.bin` and a 30K `buildfingerprint.bin`. And Gradle still said `Calculating task graph as no cached configuration is available` on every run. `--info` did not say why — it repeated the same line with no reason attached — and cost the build 1m02s → 2m36s, so it came back out.

Copying a cache in and out is evidently not the same thing to Gradle as never having moved it.

**Attempt 3 — `--project-cache-dir`.** Relocate the whole project-local cache into the toolcache, so the thing that has to survive is never in the workspace. Build #80 reused the entry on both invocations; #82 reused it on all seven.

**And then the win turned out to be much smaller than predicted.** Build #74 was 418s of Gradle across seven invocations; #82 was 406s. That is **12 seconds**, not the 3–9% of a pipeline that was claimed. The pipeline total appeared to drop 132s, but most of that is emulator-run variance — api34 went 49s→24s and api35 24s→36s, neither of which is configuration. The saving is real but concentrated exactly where configuration dominates:

| Invocation | #74 | #82 | |
| --- | --- | --- | --- |
| `assembleDebug` | 18s | 15s | configuration-dominated: **−3s** |
| `assembleRelease` | 32s | 25s | configuration-dominated: **−7s** |
| `testDebugUnitTest` | 4m02s | 4m30s | execution-dominated: **+28s** |
| `lintDebug` | 19s | 16s | −3s |
| `connectedDebugAndroidTest` ×3 | 107s | 80s | device time, not configuration |

So: **~12s, about 1.5% of a ten-minute pipeline.** The original estimate was too generous because it attributed configuration time to invocations that are really test-execution and device time. Recording that here rather than leaving the estimate standing, since the estimate is the thing that would otherwise be quoted next time.

The larger levers remain where they were: `GameRoundLoopTest` is 4m02s because LEGACY looper mode makes every table redraw really execute, and the emulator boot polling is several minutes across three levels.

`org.gradle.configuration-cache.problems=warn` is still set, and should come back to the default once a few more runs have come back clean.

## Emulators are named after the job

`api34`, `api35` and `api36` are now `hot-death-uno-api34` and so on. The AVD directory is shared state and an emulator is not: two builds on this controller using the same AVD name fight over the same `config.ini` and the same console port, and the loser fails in a way that reads like a device problem rather than a name collision — the most expensive kind of CI failure to diagnose, because the obvious suspect is the platform or the app. Nothing stopped another job from picking the same name, and `api34` is about as likely a second job would reach for as any.

The archived per-level directories are named after the AVD too, so `instrumented-results/hot-death-uno-api34` is unambiguous about which job wrote it.

The qualified suffix for non-default images is unchanged and still load-bearing: it is what stops an image swap from booting the old image while reporting the new tag's results. A bare `hot-death-uno-api37` left over from the 37.0 days would do exactly that, which is how API 37 spent so long looking like an app bug.

Nothing else moved. The adb serial is `emulator-5554` regardless of the AVD name, so the boot gate, the teardown escalation and the stray-emulator sweep all still match on the `emulator-` prefix.

## A build failure that was neither a test failure nor the conversion

Build #81 failed on api36 with `DeviceException: No connected devices!` and **zero** test result files kept. Not a failing assertion — Gradle never got a device. The boot gate had passed: `device` state, `boot_completed`, both service checks, and a stable `system_server` PID held for 10s. The device was simply gone by the time Gradle asked.

Not a regression: the same app code passed api36 in #82, and api34 and api35 passed in #81 itself. Recorded because `kept 0 result file(s)` reads like "the tests ran and nothing passed" rather than "the tests never ran", which is the opposite and much more alarming.

## Driving the round loop, and the bug that was not one

`GameRoundLoopTest` runs `startGame` then `advanceRound` to a finish, twice — once on house rules, once on standard rules — plus a check that standard rules deal seven each. It is the first thing in the suite that reaches `dealHands`, `advanceRound`, `handleSpecialCards`, `assessPenalty`, `calculateScore`, `sortHand` and `finishRound`: everything `HandPlayabilityTest` could only reach by setting `m_currCard`, `m_currColor` and `m_penalty` by reflection, because the code that sets them properly is `startRound`, which deals and draws off the top.

Nothing asserts a score or a card, because the deal is random — the deck is shuffled, the dealer is chosen at random, and the dealer picks how many cards to deal. Every assertion is an invariant that holds whatever came out: no card created or destroyed (hands + draw pile + discard pile must add back up to the deck), every hand turned over at the end of a round, the player who went out is the next dealer, and a total is never below this round's score. Run often enough, the shuffles reach card rules that the fixed-deck tests cannot.

**The first version of it found a NullPointerException in `checkForAllBastardCards`, and it was the test's fault.** Worth keeping, because it reads exactly like a real card-rule bug. `Game` extends `Thread`, and `GameActivity.onCreate` ends by calling `m_gt.startGameWhenReady()`, which calls `m_game.start()`. So building the activity with `Robolectric.buildActivity(...).setup()` quietly starts a second thread that calls `startGame` — and the test was calling `startGame` too. Two threads dealt the same deck at once: every hand came out with twice the cards it should have had, the draw pile was filled twice over, and `sortHand` then wrote nulls into the back half of each hand because fewer cards claimed it than the hand's own count said. The NPE came from `postDealHands` reading one of those nulls.

Confirmed by instrumenting `dealHands`: `enter loop2` printed twice before either deal finished, with `m_numCardsToDeal=9` and the hands growing past 9 each. It reproduces identically on the pre-Kotlin `Game.java`, which is how it was ruled out as a translation regression in the first place.

The fix is `buildActivity(...).get()` instead of `setup()`, so `onCreate` never runs and the game thread never starts, plus a hand-built `GameTable` — its constructor is what calls `setGameTable` — the four button views `onCreate` would have inflated, and a measure/layout pass, because `RedrawTable` positions cards from `m_ptMessages` and `onSizeChanged` is what works those out. The round loop is then driven from the test thread alone, single-threaded, so no assertion reads state that is being mutated underneath it.

**It needs LEGACY looper mode and costs about five minutes.** In PAUSED mode `runOnUiThread` defers, and the run did not finish inside seven minutes; in LEGACY the redraws actually execute, which is the cost. That is the trade for driving the loop without a second thread, and two rounds is deliberately not four — two is already enough to reach a deal, a win, penalties and a score.

| Item | Work | Verifiable by |
| --- | --- | --- |
| ~~Cover the round loop~~ | ~~`GameRoundLoopTest`: two rounds driven to a finish on each ruleset, plus the standard-rules deal of seven. Asserts card conservation, hands revealed at round end, the winner becoming the dealer, and scores that only rise. Random deal, so invariant-only.~~ | build #65: 140 unit + 27 instrumented, 167 passed |

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

**A virus infection is charged again in every round for the rest of the game.** `Hand.calculateValue` adds 10 to the owner's penalty per AIDS card on a final score (`Hand.java:391`), and that accumulation is correct — two cards, two infections. The compounding is one level up, in `Game.calculateScore`: it adds the player's *whole* `getVirusPenalty()` to their total score every round (`Game.java:1387` and `:1390`), but nothing clears that field between rounds. `Player.resetRound` (`Player.java:197`) does not touch `m_virusPenalty`; only `resetGame` (`Player.java:216`) does, and that runs once per game, not per round. So a player who picks up the green 3 in round 1 pays 10 again in round 2 with no green 3 in sight, again in round 3, and the running total grows by the whole accumulated penalty each time. The round winner is exempt (`Game.java:1383`), which is the one hint this was not meant to compound. The `Hand`-level test pins the accumulation, which is correct and is *not* the bug; the bug is the round loop.

**This one is now reachable, and that changes what fixing it takes.** When it was written, the round loop had no test at all, so the excuse was that seeing the compounding needed a whole game. `GameRoundLoopTest` now plays two rounds of one game, which is exactly that shape. So this is no longer blocked on reachability — it is blocked on nobody having written the assertion, which is a much smaller thing. `checkScores` deliberately does *not* catch it: it asserts only that a total is never below this round's score and never negative, which the compounding satisfies. A test that caught it would want a hand of no cards, a known `m_virusPenalty`, and two `startRound` calls with the field left alone — or simply a player holding the green 3 in round 1 and nothing in round 2, whose total should not have moved.

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

