# TODO

Java-to-Kotlin migration.

| Stage | Work | Verifiable by |
| --- | --- | --- |
| ~~0~~ | ~~Add Kotlin plugin to `app/build.gradle` only, no source changes~~ | ~~one CI compile~~ |
| 1 | Run a headless Android emulator in Jenkins so `connectedAndroidTest` can take over the "manual on-device" checks below. Needs KVM / nested virtualisation on the controller, `-no-window -gpu off`, and the AVD plus system image cached rather than re-downloaded every run. | a green `connectedAndroidTest` stage |
| 2 | JUnit tests for the pure-logic classes (Card, Penalty, GameOptions, CardPile, Hand — no Android imports) | `testDebugUnitTest` |
| 3 | Convert leaf classes mechanically | tests stay green |
| 4 | `Player` hierarchy → `Game` / `ComputerPlayer` | tests + review |
| 5 | Android UI last (GameActivity, GameTable, Main, Prefs) | manual on-device |
| 6 | Messaging: victim-centric penalty wording, the card counts in one place, and a toast for a legal play ("North threw another green 5") — detail below | manual on-device |
| 7 | Novice mode: tap to advance after each card played, as a timed-vs-tapped choice beside `game_speed` — detail below | manual on-device |
| 8 | Computer players: keep improving the rule-based AI, and settle the 4th seat reusing player 2's settings — detail below | manual on-device |

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

