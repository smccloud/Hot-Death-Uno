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
| 9 | Make the app's activities launch on API 37, then promote it from known-failing to enforced in the Jenkins matrix — detail below | a green api37 row in the emulator matrix |

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

The Jenkins matrix runs the same APK on API 34-37. API 37 is currently a known-failing level (reported and archived, not enforced).

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
| Try a different 37 image | The fix is an image without the defect, not a change here. `system-images;android-37.0;default;x86_64` is the obvious candidate: the AOSP build ships no Google Play/vold extras, and the missing service is exactly the kind of thing a Google-added partition block is for. Change the tag in the matrix `for entry` line and rebuild. | the install commits on 37 and `MainLaunchTest` runs |
| Check which 37 tags exist | 37.0/37.1/37.2 are all published. If 37.1 or 37.2 ships a working vold, that is a smaller change than switching to `default`. The matrix already spells the tag out per level for this reason. | a tag whose install commits |
| If no image works | Then api37 is not testable on this controller and the honest end state is to drop it from the matrix, or leave it known-failing with a note pointing here. | a decision either way |
| Promote back to enforced | Only after the install commits and `MainLaunchTest` passes. Remove `37` from `KNOWN_FAILING` in the `Jenkinsfile` so it starts gating again. | a green api37 row in the emulator matrix |

