# TODO

Java-to-Kotlin migration. Stage 0 is complete (struck out).

+-------+----------------------------------------------------------------------------------------------------------+---------------------+
| Stage | Work                                                                                                     | Verifiable by       |
| ~~0~~ | ~~Add Kotlin plugin to `app/build.gradle` only, no source changes~~                                      | ~~one CI compile~~  |
| 1     | JUnit tests for the pure-logic classes (Card, Penalty, GameOptions, CardPile, Hand - no Android imports) | `testDebugUnitTest` |
| 2     | Convert leaf classes mechanically                                                                        | tests stay green    |
| 3     | `Player` hierarchy -> `Game` / `ComputerPlayer`                                                          | tests + review      |
| 4     | Android UI last (GameActivity, GameTable, Main, Prefs)                                                   | manual on-device    |
+-------+----------------------------------------------------------------------------------------------------------+---------------------+

