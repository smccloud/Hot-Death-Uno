package com.smccloud.hotdeath

import android.content.Context
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * Drives the round loop -- the part of Game that nothing else in the suite reaches.
 *
 * [HandPlayabilityTest] covers `checkCard` and `hasValidCards`, and
 * [JsonRoundTripTest] covers save and resume, but both put `m_currCard`,
 * `m_currColor` and `m_penalty` in by reflection, because the code that sets them
 * properly is `startRound()`, which deals and draws off the top. That leaves
 * `dealHands`, `advanceRound`, `handleSpecialCards`, `assessPenalty`,
 * `calculateScore`, `sortHand` and `finishRound` untested -- most of Game, and all
 * of it reachable from here.
 *
 * Three things are needed to make it run, and each one is a trap:
 *
 *  * **The game thread must not be running.** `Game` extends `Thread`, and
 * `GameActivity.onCreate` finishes by calling `m_gt.startGameWhenReady()`, which
 * calls `m_game.start()`. So building the activity with `setup()` quietly starts
 * a second thread that calls `startGame()` -- and if the test also calls
 * `startGame()`, two threads deal at once. The hands come out with twice the cards
 * they should have, the draw pile is filled twice over, and `sortHand` then nulls
 * the back half of every hand because fewer cards claim it than the hand's own
 * count says. It surfaces as a NullPointerException in
 * `checkForAllBastardCards` on the first `postDealHands`, which reads exactly like
 * a real card-rule bug and is not one. Hence `get()` rather than `setup()`, and a
 * hand-built GameTable below.
 *  * **All four seats have to be computer players.** A `HumanPlayer`'s `startTurn`
 * is a spin-wait on a boolean only a tap on the real table sets, so the turn never
 * returns. The fourth-computer preference therefore has to be set before the Game
 * is built: its constructor reads it to decide what goes in each seat.
 *  * **`setFastForward(true)`**, or every `promptUser` sleeps for the pause length
 * -- up to four seconds, hundreds of times.
 *
 * `run()` is deliberately not called. It is the only caller of `waitForNextRound`,
 * an unconditional spin-wait that a tap on the draw pile clears, and it is the one
 * thing that needs a second thread. Driving `startGame` and `advanceRound` from the
 * test thread is the same loop without the waits, and it is single-threaded, so
 * every assertion below reads state that is not being mutated underneath it.
 *
 * The deal is random -- the deck is shuffled, the dealer is picked at random and
 * the dealer chooses how many cards to deal -- so nothing here asserts a score or
 * a card. Every assertion is an invariant that has to hold whatever came out: no
 * card created or destroyed, scores only ever rising, every hand turned over when
 * the round ends. Run often enough, the shuffles reach card rules that the
 * fixed-deck tests cannot.
 */
// LEGACY, which Robolectric has deprecated, and this is the one test in the
// suite that has to stay on it. Measured rather than assumed: commit 22486f4
// ("Cover the round loop with GameRoundLoopTest") recorded that in PAUSED "the
// redraws defer and the run did not finish inside seven" [minutes]. The reason
// is the fixture above -- the loop is driven from the test thread, so every
// RedrawTable is a deferred frame rather than one that has already happened by
// the time the next line reads the table, and the game never finishes.
//
// Dropping it means idling the looper as the loop goes, not just at the end:
// something like shadowOf(getMainLooper()).idleFor(Duration.ofSeconds(1))
// after each startGame/advanceRound call. That is a real change to the test's
// shape and its runtime, so it is a piece of work with its own commit rather
// than a drive-by deprecation fix -- and until somebody does it, the annotation
// below is the honest way to keep a known-good test honest about why it is
// different from its neighbours.
@Suppress("DEPRECATION")
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
class GameRoundLoopTest
{
	/** A round this long is a bug, not a slow shuffle. */
	private val MAX_TURNS = 4000

	@Test
	fun houseRulesPlaysRoundsToAFinish ()
	{
		val game = newGame(false)

		playRounds(game, 2)
	}

	@Test
	fun standardRulesPlaysRoundsToAFinish ()
	{
		val game = newGame(true)

		playRounds(game, 2)
	}

	/**
	 * Standard rules deal a fixed 7 and drop every house card, so this exercises a
	 * different set of branches in advanceRound, not merely a different deal. Kept
	 * apart from the round loop because the deal size is the one thing here that is
	 * not random.
	 *
	 * The dealer is allowed 9, and only because of what `startGame` does after the
	 * deal: `postDealHands` turns the top card over, and issue #10 made the dealer
	 * absorb a penalty that opens the round. A plain deck's only such card is the
	 * Draw 2, so a round opening on one leaves the dealer holding two extra. Every
	 * other seat is still exactly 7, which is what this test is really about, and
	 * `PenaltyRulesTest` covers the absorption itself.
	 *
	 * Asserted as a stated allowance rather than by pinning the shuffle, so the test
	 * keeps catching a wrong deal size without becoming a test of the deck order.
	 */
	@Test
	fun standardRulesDealSevenEach ()
	{
		val game = newGame(true)
		game.startGame()

		val dealer = game.getDealer()
		val openingCard = game.getLastPlayedCard()
		val dealerAbsorbedTwo = (openingCard != null)
				&& (openingCard.getValue() == Card.VAL_D)

		for (seat in 1..4)
		{
			val p = game.getPlayer(seat - 1)!!
			val expected = if ((p === dealer) && dealerAbsorbedTwo) 9 else 7

			assertEquals("standard rules deal 7"
					+ (if (expected == 9) " (plus the opening draw 2 the dealer ate)" else "")
					+ ", seat $seat got " + p.getHand()!!.getNumCards(),
					expected, p.getHand()!!.getNumCards())
		}
	}

	/**
	 * The green 3 is charged once, in the round it was picked up.
	 *
	 * Pinned directly rather than left to the random deal in the round-loop
	 * invariants above, because whether any green 3 is in anybody's hand at the
	 * end of a round is not something a test gets to choose -- and on a deal
	 * with no green 3 in it, the compounding never fires and the invariant
	 * passes with or without the bug. Here the field is set by hand to what a
	 * green 3 in round 1 would leave behind, and round 2 must not charge it
	 * again.
	 *
	 * Two rounds is what it takes: the field is charged in `finishRound` at the
	 * end of a round, so setting it before round 1 would just be scored in
	 * round 1 and never read again. Setting it between the two rounds puts it
	 * exactly where a round-1 infection would be when round 2 is scored.
	 */
	@Test
	fun anInfectionIsNotChargedAgainInTheNextRound ()
	{
		val game = newGame(false)
		game.startGame()

		driveRound(game)

		// What round 1 leaves behind if a green 3 was in a hand when it ended.
		for (i in 0 until 4)
		{
			game.getPlayer(i)!!.setVirusPenalty(10)
		}

		game.startRound()
		driveRound(game)

		checkTheVirusPenaltyIsThisRounds(game, 1)
	}

	/**
	 * resetRound clears the field, which is the whole of the fix.
	 *
	 * The scoring-level test above goes through `finishRound` and reads back a
	 * total, so it depends on several things lining up. This one asserts the
	 * reset directly: a round boundary has to take the infection with it.
	 */
	@Test
	fun startingARoundClearsTheVirusPenalty ()
	{
		val game = newGame(false)
		game.startGame()

		for (i in 0 until 4)
		{
			val p = game.getPlayer(i)!!
			p.setVirusPenalty(20)
			game.startRound()
			assertEquals("seat ${p.getSeat()} still owes an infection from "
					+ "the last round", 0, p.getVirusPenalty())
			p.setVirusPenalty(20)
		}
	}

	// ---------------------------------------------------------------- the round

	/** Plays `count` whole rounds, checking the invariants after each. */
	private fun playRounds (game: Game, count: Int)
	{
		game.startGame()

		for (round in 0 until count)
		{
			if (round > 0)
			{
				game.startRound()
			}

			val turns = driveRound(game)

			assertTrue("round $round did not complete", game.getRoundComplete())
			assertTrue("round $round took $turns turns", turns < MAX_TURNS)

			checkNoCardsWereLost(game, round)
			checkEveryHandIsFaceUp(game, round)
			checkTheDealerWonTheRound(game, round)
			checkTheVirusPenaltyIsThisRounds(game, round)
			checkScores(game, round)

			// At 1000 the game is over and there is no next round to deal.
			if (game.getWinner() != 0)
			{
				return
			}
		}
	}

	/**
	 * Runs advanceRound until the round ends, and returns how many turns it took.
	 *
	 * advanceRound returns false when a player runs out of cards, when the game is
	 * stopping, or when there is no current player, and true when the round carries
	 * on. The bound is here so that a failure to terminate reads as a failed
	 * assertion naming the round rather than a build that hangs.
	 */
	private fun driveRound (game: Game): Int
	{
		var turns = 0
		while (game.advanceRound())
		{
			if (++turns > MAX_TURNS)
			{
				fail("the round never ended after $turns turns")
			}
		}
		return turns
	}

	// ---------------------------------------------------------------- invariants

	/**
	 * Cards are only ever moved between a hand, the draw pile and the discard pile:
	 * the draw pile and the discard roll into each other, and a played card leaves a
	 * hand for the discard. Nothing creates one and nothing drops one, so the three
	 * have to add back up to the deck.
	 *
	 * This is the assertion most likely to catch a mistranslated line, because it is
	 * the one thing about the loop that holds no matter what the cards were.
	 */
	private fun checkNoCardsWereLost (game: Game, round: Int)
	{
		var inHands = 0
		for (i in 0 until 4)
		{
			inHands += game.getPlayer(i)!!.getHand()!!.getNumCards()
		}

		// Written on one line deliberately. Split after `=` with the operators
		// leading the following lines -- which is how the Java original read, and
		// how the app's own Kotlin still writes continuations -- Kotlin reads the
		// `+` as unary plus and ends the declaration there. Nothing warns: the
		// piles are then statements whose values are discarded, `accounted` is
		// `inHands` alone, and the assertion compares one pile's worth of cards
		// against the whole deck. It compiled and it failed loudly rather than
		// passing wrongly, but the arithmetic is now one expression that cannot be
		// split by accident.
		//
		// The message below still continues onto leading `+` lines, and that is
		// safe: it is an argument inside an already-open call, so there is no
		// statement end for the `+` to terminate.
		val draw = game.getDrawPile()!!.getNumCards()
		val discard = game.getDiscardPile()!!.getNumCards()
		val total = game.getDeck()!!.getNumCards()
		val accounted = inHands + draw + discard

		assertEquals("after round $round the cards do not add up: "
				+ "$inHands in hands, "
				+ "$draw in the draw pile, "
				+ "$discard in the discard, "
				+ "but the deck holds $total",
				total, accounted)
	}

	/** finishRound turns every hand face up so the scores can be read off the table. */
	private fun checkEveryHandIsFaceUp (game: Game, round: Int)
	{
		for (seat in 1..4)
		{
			val h = game.getPlayer(seat - 1)!!.getHand()!!
			for (i in 0 until h.getNumCards())
			{
				val c = h.getCard(i)
				assertTrue("seat $seat is still holding a card face down at "
						+ "the end of round $round: $c", c!!.getFaceUp())
			}
		}
	}

	/**
	 * finishRound makes the player who went out the next dealer, and going out
	 * means an empty hand or all four bastard cards -- advanceRound calls
	 * finishRound on exactly those players.
	 */
	private fun checkTheDealerWonTheRound (game: Game, round: Int)
	{
		var found = false
		for (i in 0 until 4)
		{
			val p = game.getPlayer(i)!!
			val wentOut = p.getHand()!!.getNumCards() == 0
					|| game.checkForAllBastardCards(p.getHand()!!)
			if (wentOut && p.getSeat() == game.getDealer()!!.getSeat())
			{
				found = true
			}
		}
		assertTrue("nobody who went out is the dealer after round $round"
				+ "; the dealer is seat ${game.getDealer()!!.getSeat()}", found)
	}

	/**
	 * A total is at least this round's score, and never negative.
	 *
	 * Only the sign and the accumulation. The virus penalty is added to a total
	 * rather than subtracted from one, so a total can only be inflated by the
	 * compounding bug -- and that is pinned by the check above, which reads the
	 * charge back against the hand it should have come from.
	 */
	private fun checkScores (game: Game, round: Int)
	{
		for (i in 0 until 4)
		{
			val p = game.getPlayer(i)!!
			assertTrue("seat ${p.getSeat()} has a negative total after round "
				+ "$round: ${p.getTotalScore()}", p.getTotalScore() >= 0)
			assertTrue("seat ${p.getSeat()} totals ${p.getTotalScore()}"
				+ " but only scored ${p.getLastScore()} this round",
				p.getTotalScore() >= p.getLastScore())
		}
	}

	/**
	 * The infection is charged once, for the round it was picked up in.
	 *
	 * calculateScore sets lastVirusPenalty to the player's whole virusPenalty
	 * and adds it to the total, and Hand.calculateValue is what accumulates it --
	 * 10 per green 3 held at the end of the round. So after a round the charge
	 * must be exactly 10 times the green 3s in that hand, and nothing carried
	 * over from before: the field used to be cleared only by resetGame(), so a
	 * green 3 picked up in round 1 was charged again in round 2, and the total
	 * grew by the whole accumulated penalty every round after. Issue #1.
	 *
	 * Two kinds of player are exempt, and both are deliberate. The winner,
	 * because calculateScore sets their lastVirusPenalty to 0 outright -- the
	 * winner is the dealer by the time this runs, since finishRound assigns
	 * m_dealer before calling calculateScore. And anyone holding all four
	 * bastard cards, because calculateScore scores that hand as 0 without ever
	 * calling calculateValue on it, so nothing is ever added to their penalty.
	 * That second one is reachable for a player who did not win: advanceRound
	 * stops at the first player it finds with an empty hand or the full set, so
	 * a seat earlier in the table can end the round before the bastard-card
	 * holder is looked at.
	 *
	 * Hands are face up by now (checked above), and the green 3 stays in the hand
	 * it was scored from, so the hand is the record of what was charged.
	 */
	private fun checkTheVirusPenaltyIsThisRounds (game: Game, round: Int)
	{
		val winner = game.getDealer()!!.getSeat()

		for (i in 0 until 4)
		{
			val p = game.getPlayer(i)!!
			val h = p.getHand()!!

			if (p.getSeat() == winner || game.checkForAllBastardCards(h))
			{
				assertEquals("seat ${p.getSeat()} was charged ${p.getLastVirusPenalty()} "
						+ "after round $round but is exempt from the infection",
					0, p.getLastVirusPenalty())
				continue
			}

			var green3s = 0
			for (j in 0 until h.getNumCards())
			{
				if (h.getCard(j)!!.getID() == Card.ID_GREEN_3_AIDS)
				{
					green3s++
				}
			}

			assertEquals("seat ${p.getSeat()} was charged ${p.getLastVirusPenalty()} "
					+ "after round $round, holding $green3s green 3s. A charge from an "
					+ "earlier round is being billed again: ${p.getTotalScore()} total, "
					+ "${p.getLastScore()} scored, ${p.getLastVirusPenalty()} penalty.",
				green3s * 10, p.getLastVirusPenalty())
		}
	}

	// ---------------------------------------------------------------- fixture

	/**
	 * Builds a Game with a GameTable and the activity's buttons attached, without
	 * ever starting the game thread.
	 *
	 * `get()` rather than `setup()` is the whole trick: setup() runs onCreate, and
	 * onCreate's last act is to start the game thread. So the views that onCreate
	 * would have built are built here instead, by hand and in the same way -- the
	 * round loop toggles the menu panel on every turn and the fast-forward button
	 * whenever a player is ejected, and both dereference views that only exist once
	 * the activity has been created.
	 */
	private fun newGame (standardRules: Boolean): Game
	{
		val app: Context = ApplicationProvider.getApplicationContext()

		Prefs.defaultSharedPreferences(app)
			.edit()
			.putBoolean("computer_4th", true)
			.putBoolean("face_up", false)
			.putString("cheat_code", if (standardRules) "standardrules" else "")
			.commit()

		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)
		val game = Game(activity, options)

		// The GameTable constructor is what calls setGameTable, which is what stops
		// Game's redrawTable from dereferencing a null table.
		val table = GameTable(activity, game, options)

		// RedrawTable positions cards from m_ptMessages, which onSizeChanged works
		// out from the view's real size. Nothing lays the table out here, because
		// it was never added to a parent the way onCreate adds it, so give it a
		// size by hand -- otherwise every redraw throws on the null points.
		table.measure(
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, 1080, 1920)

		val fastForward = activity.getLayoutInflater()
			.inflate(R.layout.action_button, null) as Button
		val menuPanel = activity.getLayoutInflater().inflate(R.layout.options_menu, null)

		// GameTable reaches the activity through its context, and showMenuButtons
		// reads m_game off it, so the activity has to be holding the same objects.
		set(activity, "m_go", options)
		set(activity, "m_game", game)
		set(activity, "m_gt", table)
		set(activity, "m_btnFastForward", fastForward)
		set(activity, "m_vMenuPanel", menuPanel)
		set(activity, "m_btnMenuDraw", menuPanel.findViewById(R.id.btn_menu_draw))
		set(activity, "m_btnMenuPass", menuPanel.findViewById(R.id.btn_menu_pass))

		game.setFastForward(true)
		return game
	}

	/**
	 * Reflection, so a rename of one of these fails here rather than as an NPE.
	 *
	 * The values are Game and GameTable objects, and every field written here is
	 * nullable in Kotlin, so nothing is at risk of a wrong type slipping past a
	 * check -- f.set would put a Game in a GameTable field without complaint if
	 * the two calls below were transposed. The field names are the coupling that
	 * matters, and this is what makes it a failure rather than a stale reference.
	 */
	private fun set (target: Any, name: String, value: Any)
	{
		try
		{
			val f = GameActivity::class.java.getDeclaredField(name)
			f.isAccessible = true
			f.set(target, value)
		}
		catch (e: ReflectiveOperationException)
		{
			throw AssertionError("GameActivity.$name is gone or renamed: $e")
		}
	}
}