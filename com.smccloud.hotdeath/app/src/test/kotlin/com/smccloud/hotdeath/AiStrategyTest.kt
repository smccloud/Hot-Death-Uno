package com.smccloud.hotdeath

import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Covers the Expert AI's card choice and colour choice, issue #6.
 *
 * `HandPlayabilityTest` answers "is this card legal", which is the question the
 * rules ask. Everything here is the question the AI asks, which is the other
 * one: given several legal cards, which one, and given several colours, which
 * colour. `GameRoundLoopTest` asserts invariants about a round running; it never
 * inspects a decision. So before this there was no test in the project that could
 * tell whether the Expert seat chose well or chose arbitrarily, and every one of
 * these tests is a decision that a plausible-looking edit would change.
 *
 * The fixture is a real round rather than a stubbed table. `Game.resetRound()`
 * builds a deck, a draw pile, a discard pile and four fresh hands, and the cards
 * handed out are real cards out of the real 216-card Hot Death deck -- so no test
 * here can pass on a hand the deck never produces, and `calculateValue` is
 * computing the scores the game would compute.
 *
 * That matters for the hoarding tests in particular. The wild is scored against
 * the hand's own point total by `Hand.calculateValue`, and the thresholds the MAD
 * card is judged against are point values read out of real cards. A synthetic
 * `Card(...)` with a made-up point value would make those thresholds mean
 * something else.
 *
 * `setFastForward(true)` is what keeps the tests quick: `waitABit` returns
 * immediately when `getDelay()` is 0, and `getDelay()` is 0 while fast-forwarding.
 * Without it every `chooseColor` would sleep for the game's configured pause.
 *
 * ## The accounting, and why the fixture can control it exactly
 *
 * Sub-item 2 asks the AI to infer opponents' hands from a known deck.
 * `countUnaccountedInColor` works it out from three public things: the deck, this
 * seat's hand, and the discard pile. Everything else in the deck is either in the
 * draw pile or in somebody's hand.
 *
 * `fillPiles` therefore puts every dealt card in a hand, puts a named number of
 * cards in the draw pile, and discards the rest. With a draw pile of 0 the cards
 * nobody is holding are exactly the cards the opponents were dealt, so the
 * probabilities are exact and a test can say "this opponent holds one red card
 * and nothing else" and have it be true. The draw-pile tests then add cards back
 * and watch the estimate loosen, which is the other half of the model.
 */
@RunWith(RobolectricTestRunner::class)
class AiStrategyTest
{
	private lateinit var m_activity: GameActivity
	private lateinit var m_options: GameOptions
	private lateinit var m_game: Game

	// `getPlayer` takes an index into the four seats; `Game.SEAT_*` are 1 to 4 and
	// index the same four seats shifted by one. Everything below is written in
	// indices, and `difficulty` maps an index to the preference pair that seat
	// reads, so a test cannot accidentally set East's difficulty and hand the
	// cards to North.
	private val SOUTH = 0
	private val WEST = 1
	private val NORTH = 2
	private val EAST = 3

	// The acting seat is whichever one reads the preference pair the test set, so
	// Expert is East, Strong is North and Pushover is West. South is the human.
	private val EXPERT = EAST
	private val STRONG = NORTH
	private val PUSHOVER = WEST

	@Before
	fun setUp ()
	{
		m_activity = Robolectric.buildActivity(GameActivity::class.java).get()
		m_options = GameOptions(m_activity)
		m_game = Game(m_activity, m_options)
		m_game.setFastForward(true)
	}

	// ------------------------------------------------------------- fixture

	/**
	 * A round with the real deck in it, and no cards dealt yet.
	 *
	 * `reset(false, false)` is the Hot Death deck -- two of most things, all the
	 * specials -- which is what the game plays by default, and what every score
	 * below assumes. It is called directly rather than through `startRound`,
	 * which would deal seven random cards per seat and want a GameTable.
	 */
	private fun round ()
	{
		m_game.resetRound()
		m_game.getDeck()!!.reset(false, false)

		for (i in 0 until 4)
		{
			m_game.getPlayer(i)!!.getHand()!!.reset()
			m_game.getPlayer(i)!!.setActive(true)
		}
	}

	/**
	 * The seat at [seat], as the computer player it is.
	 *
	 * Every seat but South is a ComputerPlayer, and `playCard` is declared there,
	 * so the tests that ask for a decision need the concrete type rather than the
	 * `Player` that `getPlayer` hands back. South would need the
	 * `computer_4th` preference turned on, which is a different thing to test.
	 */
	private fun actor (seat: Int): ComputerPlayer
	{
		return m_game.getPlayer(seat) as ComputerPlayer
	}

	/** The next undealt deck card of [color] and [value]. */
	private fun take (color: Int, value: Int): Card
	{
		val cards = m_game.getDeck()!!.getCards()

		for (i in 0 until m_game.getDeck()!!.getNumCards())
		{
			val c = cards[i]!!

			if ((c.getColor() == color) && (c.getValue() == value) && (c.getHand() == null))
			{
				return c
			}
		}

		throw AssertionError("no undealt $color of value $value left in the deck")
	}

	private fun takeNumber (color: Int, value: Int) = take(color, value)

	/** An undealt Draw Four. The deck carries four, and they are worth 50 each. */
	private fun takeDrawFour (): Card
	{
		return takeId(Card.ID_WILD_DRAWFOUR)
	}

	private fun takeId (id: Int): Card
	{
		val cards = m_game.getDeck()!!.getCards()

		for (i in 0 until m_game.getDeck()!!.getNumCards())
		{
			val c = cards[i]!!

			if ((c.getID() == id) && (c.getHand() == null))
			{
				return c
			}
		}

		throw AssertionError("no undealt card with ID $id left in the deck")
	}

	/** The yellow 1 MAD card, which the existing AI already scores specially. */
	private fun takeMad (): Card = takeId(Card.ID_YELLOW_1_MAD)

	private fun deal (seat: Int, vararg cards: Card)
	{
		val hand = m_game.getPlayer(seat)!!.getHand()!!
		hand.reset()

		for (c in cards)
		{
			hand.addCard(c)
		}
	}

	/**
	 * Turns the rest of the deck into table, and [top] into the current card.
	 *
	 * [drawPileSize] cards go to the draw pile and every remaining undealt card
	 * goes face up to the discard pile. So with a draw pile of 0, the cards the
	 * table cannot account for are exactly the opponents' hands, which is what
	 * makes the colour arithmetic in these tests exact.
	 *
	 * `m_currCard`, `m_currColor` and `m_penalty` go in by reflection for the
	 * reason `HandPlayabilityTest` gives: `checkCard` reads all three, none has a
	 * setter, and the only code that writes them is `startRound`.
	 */
	private fun fillPiles (top: Card, drawPileSize: Int = 0)
	{
		val cards = m_game.getDeck()!!.getCards()
		var toDraw = drawPileSize

		for (i in 0 until m_game.getDeck()!!.getNumCards())
		{
			val c = cards[i]!!

			if (c === top)
			{
				continue
			}

			if (c.getHand() != null)
			{
				continue
			}

			if (toDraw > 0)
			{
				m_game.getDrawPile()!!.addCard(c)
				toDraw--
			}
			else
			{
				m_game.getDiscardPile()!!.addCard(c)
			}
		}

		setField("m_currCard", top)
		setField("m_currColor", top.getColor())
		setField("m_penalty", Penalty())
	}

	/** Sets the difficulty pair for [seat], which reads the p1/p2/p3 preferences. */
	private fun difficulty (seat: Int, skill: Int, aggression: Int = 0)
	{
		val pair = when (seat)
		{
			WEST -> "p1"
			NORTH -> "p2"
			else -> "p3"
		}

		PreferenceManager.getDefaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.putString("${pair}_skill", skill.toString())
			.putString("${pair}_aggression", aggression.toString())
			.commit()
	}

	private fun setField (name: String, value: Any)
	{
		try
		{
			val f = Game::class.java.getDeclaredField(name)
			f.isAccessible = true
			f.set(m_game, value)
		}
		catch (e: ReflectiveOperationException)
		{
			throw AssertionError("Game.$name is gone or renamed: $e")
		}
	}

	// ------------------------------------------------- sub-item 1: wilds

	/**
	 * Strong and Expert both hold a wild they have no reason to spend.
	 *
	 * A red 5 and a Draw Four, red on the table, two cards in every opponent's
	 * hand. Both cards are legal and the seat plays the 5.
	 *
	 * This is the plain statement of sub-item 1 and it is the *weakest* of the
	 * tests that cover it: a wild scoring 0 would lose to a 5 anyway, so this
	 * passes with the hoard switched off. Setting the hoard score to 0 fails
	 * `strongKeepsTheWildBackFromAMagicFive` and
	 * `expertKeepsTheWildBackRatherThanDumpTheMad` and leaves this one green.
	 *
	 * It is here for the two skill levels rather than the hoard, and because the
	 * ordinary board -- a middling numbered card and a wild, which is most turns
	 * -- is the case a player actually watches.
	 */
	@Test
	fun strongAndExpertBothHoldAWildTheyHaveNoReasonToSpend ()
	{
		round()

		val five = takeNumber(Card.COLOR_RED, 5)

		deal(EXPERT, five, takeDrawFour())
		deal(STRONG, takeNumber(Card.COLOR_RED, 8), takeDrawFour())
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1), takeNumber(Card.COLOR_BLUE, 2))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		fillPiles(takeNumber(Card.COLOR_RED, 7))
		difficulty(EXPERT, 2)
		difficulty(STRONG, 1)

		actor(EXPERT).playCard()
		actor(STRONG).playCard()

		assertEquals ("Expert kept the Draw Four", Card.ID_RED_5,
				actor(EXPERT).getPlayingCard()!!.getID())
		assertEquals ("Strong kept the Draw Four too", Card.ID_RED_8,
				actor(STRONG).getPlayingCard()!!.getID())
	}

	/**
	 * And the other direction, which is what stops the hoard from being a
	 * refusal to play wilds: when the wild is the only legal card it is played.
	 *
	 * The score has to stay above the -1000 that `maxpointval` starts at. Below
	 * it, `bestcard` would come back null for a hand with a wild in it and the
	 * seat would *draw* -- throwing away a card it could legally have played,
	 * which is worse than spending it.
	 */
	@Test
	fun expertPlaysTheWildWhenTheHandIsOtherwiseStuck ()
	{
		round()

		val wild = takeDrawFour()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 5), wild)
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1), takeNumber(Card.COLOR_BLUE, 2))
		deal(NORTH, takeNumber(Card.COLOR_BLUE, 3), takeNumber(Card.COLOR_BLUE, 4))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		// Green on the table, so the red 5 is dead and the wild is all that is left.
		fillPiles(takeNumber(Card.COLOR_GREEN, 7))
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertEquals ("Expert had nothing else and played the wild",
				Card.ID_WILD_DRAWFOUR, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getID())
	}

	/**
	 * Two wilds and nothing else is still a play, not a draw.
	 *
	 * The degenerate version of the test above: with every card in hand scoring
	 * -60, the tie between them has to resolve to one of them rather than to
	 * nothing.
	 */
	@Test
	fun aHandOfWildsStillPlaysOne ()
	{
		round()

		deal(EXPERT, takeDrawFour(), takeDrawFour())
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1))
		deal(NORTH, takeNumber(Card.COLOR_BLUE, 2))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 3))

		fillPiles(takeNumber(Card.COLOR_GREEN, 7))
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertTrue ("two wilds is a play", m_game.getPlayer(EXPERT)!!.getWantsToPlayCard())
		assertEquals ("and it was a wild",
				Card.COLOR_WILD, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getColor())
	}

	/**
	 * A hand with nothing legal in it still draws.
	 *
	 * The other half of the previous test: the hoard penalty is a score, and
	 * whatever it scores, a hand with no legal card must not be answered with a
	 * wild. Green on the table against a lone red 5.
	 */
	@Test
	fun aHandWithNothingLegalDraws ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 5))
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1))
		deal(NORTH, takeNumber(Card.COLOR_BLUE, 2))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 3))

		fillPiles(takeNumber(Card.COLOR_GREEN, 7))
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertTrue ("nothing legal means draw",
				m_game.getPlayer(EXPERT)!!.getWantsToDraw())
		assertNull ("and nothing was chosen to play",
				m_game.getPlayer(EXPERT)!!.getPlayingCard())
	}

	/**
	 * The hoard is not absolute: an opponent on their last card is worth a wild.
	 *
	 * This is the sharpest pair in the file, and it is why the hoard has an
	 * exception at all. The board is identical in both tests apart from one
	 * opponent's hand size.
	 *
	 * The hand is the MAD card and a Draw Four, both legal on a yellow 7. MAD is
	 * worth 100 points, which the existing code knows is a terrible thing to be
	 * holding, and scores it -20 when the hand is still worth more than 50
	 * without it -- which it is, since the Draw Four is worth 50 exactly. So MAD
	 * is the worst card in the hand and the wild is the second worst, and which
	 * of the two loses depends on nothing but whether somebody is about to go
	 * out.
	 */
	@Test
	fun expertKeepsTheWildBackRatherThanDumpTheMad ()
	{
		round()

		deal(EXPERT, takeMad(), takeDrawFour())
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1), takeNumber(Card.COLOR_BLUE, 2))
		deal(NORTH, takeNumber(Card.COLOR_BLUE, 3), takeNumber(Card.COLOR_BLUE, 4))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		fillPiles(takeNumber(Card.COLOR_YELLOW, 7))
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertEquals ("Expert played the MAD rather than the wild",
				Card.ID_YELLOW_1_MAD, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getID())
	}

	/** The same board, with West on one card, and the wild comes out instead. */
	@Test
	fun expertSpendsTheWildToStopSomebodyGoingOut ()
	{
		round()

		deal(EXPERT, takeMad(), takeDrawFour())
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1))
		deal(NORTH, takeNumber(Card.COLOR_BLUE, 3), takeNumber(Card.COLOR_BLUE, 4))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		fillPiles(takeNumber(Card.COLOR_YELLOW, 7))
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertEquals ("West was one card from winning, so the wild was worth it",
				Card.ID_WILD_DRAWFOUR, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getID())
	}

	/** Strong hoards too -- the old wild branch was in the skill >= 1 arm. */
	@Test
	fun strongHoardsTheWildAsWell ()
	{
		round()

		val five = takeNumber(Card.COLOR_RED, 5)

		deal(STRONG, five, takeDrawFour())
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1), takeNumber(Card.COLOR_BLUE, 2))
		deal(EAST, takeNumber(Card.COLOR_BLUE, 3), takeNumber(Card.COLOR_BLUE, 4))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		fillPiles(takeNumber(Card.COLOR_RED, 7))
		difficulty(STRONG, 1)

		actor(STRONG).playCard()

		assertEquals ("Strong kept the Draw Four",
				Card.ID_RED_5, m_game.getPlayer(STRONG)!!.getPlayingCard()!!.getID())
	}

	/**
	 * Pushover throws the wild away, and that is the skill-0 scoring path rather
	 * than a missing feature.
	 *
	 * Below skill 1 every card is scored with `getCurrentValue()`, and the Draw
	 * Four is a 50-point card. So the seat unloads it on merit -- the same reason
	 * it unloads a MAD card or a Holy Defender, all of which the existing AI
	 * gives pseudo-values precisely so it will.
	 *
	 * Worth pinning as a pair with `strongKeepsTheWildBackFromAMagicFive` below,
	 * because "Pushover is easy" and "the hoard is gated at skill 1" are different
	 * claims and only one of them is about the difficulty setting. An edit that
	 * moved the wild branch out from under the `m_skill >= 1` test would make the
	 * easiest opponent harder to beat without anybody noticing.
	 */
	@Test
	fun pushoverThrowsTheWildBecauseItIsWorthFiftyPoints ()
	{
		round()

		val five = takeNumber(Card.COLOR_RED, 5)

		deal(PUSHOVER, five, takeDrawFour())
		deal(NORTH, takeNumber(Card.COLOR_BLUE, 1), takeNumber(Card.COLOR_BLUE, 2))
		deal(EAST, takeNumber(Card.COLOR_BLUE, 3), takeNumber(Card.COLOR_BLUE, 4))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		fillPiles(takeNumber(Card.COLOR_RED, 7))
		difficulty(PUSHOVER, 0)

		actor(PUSHOVER).playCard()

		assertEquals ("a 50-point card goes out at skill 0",
				Card.ID_WILD_DRAWFOUR, actor(PUSHOVER).getPlayingCard()!!.getID())
	}

	/**
	 * The same hand at Strong keeps the wild and plays the Magic 5 instead.
	 *
	 * The Magic 5 scores -5, because `Hand.calculateValue` gives it that, and the
	 * hoard puts the wild at -60. So the wild loses to the worst card in the hand
	 * by 55 points.
	 *
	 * This is the sharpest statement of the hoard in the file: it is not that the
	 * wild is worth less, it is worth *less than the worst card in the hand*,
	 * which is the only way "held until the hand is otherwise stuck" can be
	 * implemented as a single score.
	 */
	@Test
	fun strongKeepsTheWildBackFromAMagicFive ()
	{
		round()

		val magicFive = takeId(Card.ID_RED_5_MAGIC)

		deal(STRONG, magicFive, takeDrawFour())
		deal(WEST, takeNumber(Card.COLOR_BLUE, 1), takeNumber(Card.COLOR_BLUE, 2))
		deal(EAST, takeNumber(Card.COLOR_BLUE, 3), takeNumber(Card.COLOR_BLUE, 4))
		deal(SOUTH, takeNumber(Card.COLOR_BLUE, 6), takeNumber(Card.COLOR_BLUE, 7))

		fillPiles(takeNumber(Card.COLOR_RED, 7))
		difficulty(STRONG, 1)

		actor(STRONG).playCard()

		assertEquals ("the wild stayed, the -5 went out",
				Card.ID_RED_5_MAGIC, actor(STRONG).getPlayingCard()!!.getID())
	}

	// ------------------------------------------- sub-item 3: ties and balance

	/**
	 * The balance figure chooses between two legal cards of equal score.
	 *
	 * Two 5s in two colours, a red on the table. The red 5 is legal by colour and
	 * the blue 5 by value, both score 5, and the red 1 in the hand is legal by
	 * colour but only scores 1. So the decision is between the two 5s.
	 *
	 * `computeChangeInColorBalance` says playing the red 5 is the better play --
	 * it takes the hand from 2-red-1-blue to 1-1, which is closer to even -- and
	 * the red 5 is *first* in the hand here. The old `>=` comparison handed a tie
	 * to whichever card was scanned last, so this board played the blue 5. Which
	 * of two identical cards gets played was decided by the order of an array.
	 */
	@Test
	fun expertBreaksAPointValueTieByBalance ()
	{
		round()

		val redFive = takeNumber(Card.COLOR_RED, 5)

		deal(EXPERT, redFive, takeNumber(Card.COLOR_BLUE, 5), takeNumber(Card.COLOR_RED, 1))
		deal(WEST, takeNumber(Card.COLOR_GREEN, 8))
		deal(NORTH, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(redFive)
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertEquals ("the balanced 5, not the last one in the hand",
				Card.ID_RED_5, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getID())
	}

	/**
	 * And the same board at Strong still goes to the last card in the hand.
	 *
	 * The tie-break is inside the `m_skill >= 2` arm, because issue #6 was about
	 * Expert play and not about rewriting the other two difficulties. This is the
	 * test that says so; without it the tie-break could quietly move down a skill
	 * level and nobody would know, since both answers are defensible.
	 */
	@Test
	fun strongStillBreaksTiesOnHandOrder ()
	{
		round()

		val redFive = takeNumber(Card.COLOR_RED, 5)

		deal(STRONG, redFive, takeNumber(Card.COLOR_BLUE, 5), takeNumber(Card.COLOR_RED, 1))
		deal(WEST, takeNumber(Card.COLOR_GREEN, 8))
		deal(EAST, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(redFive)
		difficulty(STRONG, 1)

		actor(STRONG).playCard()

		assertEquals ("Strong takes the last of two equal cards",
				Card.ID_BLUE_5, m_game.getPlayer(STRONG)!!.getPlayingCard()!!.getID())
	}

	/**
	 * A hand with one card in every colour has no balance opinion, and the
	 * tie-break has to notice that instead of inventing one.
	 *
	 * `computeChangeInColorBalance` divides by the balance before the play. With
	 * one card of each colour that balance is exactly 0, so the result is not a
	 * number -- it is an infinity, or a NaN, depending on the hand. Comparisons
	 * against NaN are all false and comparisons against infinity are all true in
	 * one direction, so an unguarded tie-break would either drop every candidate
	 * or prefer whichever card happened to be scanned first, for no reason. The
	 * `isFinite` check turns "no opinion" into falling back to hand order, which
	 * is at least the behaviour that existed before.
	 */
	@Test
	fun anEvenHandFallsBackToHandOrder ()
	{
		round()

		val redFive = takeNumber(Card.COLOR_RED, 5)

		deal(EXPERT, redFive, takeNumber(Card.COLOR_BLUE, 5),
				takeNumber(Card.COLOR_GREEN, 3), takeNumber(Card.COLOR_YELLOW, 4))
		deal(WEST, takeNumber(Card.COLOR_RED, 8))
		deal(NORTH, takeNumber(Card.COLOR_RED, 9))
		deal(SOUTH, takeNumber(Card.COLOR_RED, 2))

		fillPiles(redFive)
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertEquals ("no opinion, so the first of the tied pair",
				Card.ID_RED_5, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getID())
	}

	/**
	 * The long-game bonus still fires, and it beats the tie-break.
	 *
	 * With healthy opponents the aggression threshold opens `considerColorBalance`
	 * and the +/- 40/20 bonus applies to the score. That is the existing
	 * behaviour and it is not what issue #6 changed, but it interacts with the
	 * tie-break: here the blue 5 wins on score rather than on balance, so the
	 * board has to put the blue 5 *first* for the answer to mean anything.
	 */
	@Test
	fun theLongGameBonusStillDecides ()
	{
		round()

		val blueFive = takeNumber(Card.COLOR_BLUE, 5)

		deal(EXPERT, blueFive, takeNumber(Card.COLOR_RED, 5), takeNumber(Card.COLOR_BLUE, 1))
		deal(WEST, takeNumber(Card.COLOR_GREEN, 8), takeNumber(Card.COLOR_GREEN, 9))
		deal(NORTH, takeNumber(Card.COLOR_GREEN, 2), takeNumber(Card.COLOR_GREEN, 3))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 4), takeNumber(Card.COLOR_GREEN, 6))

		fillPiles(blueFive)
		difficulty(EXPERT, 2)

		actor(EXPERT).playCard()

		assertEquals ("the +20 balancing bonus is worth more than the tie-break",
				Card.ID_BLUE_5, m_game.getPlayer(EXPERT)!!.getPlayingCard()!!.getID())
	}

	// --------------------------------------------- sub-items 2 and 3: colours

	/**
	 * Expert will lead a colour it holds less of, because the opponent can follow
	 * the other one.
	 *
	 * Three red and two blue in hand, with West sitting on a single red card.
	 * The count says red by one card and the old code led red.
	 *
	 * Sub-item 2 is what overturns it. With an empty draw pile the cards the table
	 * cannot account for are exactly West's red, so a one-card hand is certainly
	 * red and certainly not blue. Red is charged 1.33 cards for that -- the odds
	 * times a weight of 2.0 for a player on their last card -- against blue's
	 * nothing, and 3 - 1.33 loses to 2 - 0.
	 *
	 * The two tests either side of this one are the same board with the draw pile
	 * changed and nothing else, which is the point: sub-item 2 is a claim about
	 * *when* a card is known, not only about what is known.
	 */
	@Test
	fun expertLeadsAColourTheOpponentsAreNotHolding ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_RED, 4), takeNumber(Card.COLOR_BLUE, 5),
				takeNumber(Card.COLOR_BLUE, 6))
		deal(WEST, takeNumber(Card.COLOR_RED, 8))
		deal(NORTH, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(takeNumber(Card.COLOR_GREEN, 3))
		difficulty(EXPERT, 2)

		assertEquals ("lead blue, not the red West is sitting on",
				Card.COLOR_BLUE, actor(EXPERT).chooseColor())
	}

	/**
	 * A small draw pile moves the estimate a little, not a lot -- and this is the
	 * test that pins by how much.
	 *
	 * Four red and two blue in hand, West on one red card, two cards in the draw
	 * pile. The count says red by two cards, which should be enough to survive a
	 * small threat, and it does: the estimate charges red 1.2 cards and the seat
	 * still leads red.
	 *
	 * The number is the whole point. `chanceHoldsColor` works out
	 * `inPool * (pool - drawPile) / pool`, and dropping that division leaves
	 * `inPool * (pool - drawPile)` -- a number larger than the pool by up to a
	 * factor of `pool`, because the draw pile's share of the colour is already
	 * counted inside `inPool`. That saturates for any draw pile small enough to
	 * matter, so the estimate reads "certain" from the first turn and does not
	 * tighten until the draw pile is nearly gone. With the division gone this
	 * board charges red 6 cards and the seat leads blue.
	 */
	@Test
	fun aSmallDrawPileMovesTheEstimateALittle ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_RED, 4), takeNumber(Card.COLOR_RED, 6),
				takeNumber(Card.COLOR_BLUE, 5), takeNumber(Card.COLOR_BLUE, 7))
		deal(WEST, takeNumber(Card.COLOR_RED, 8))
		deal(NORTH, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(takeNumber(Card.COLOR_GREEN, 3), 2)
		difficulty(EXPERT, 2)

		assertEquals ("red by two cards, and the estimate is not worth two",
				Card.COLOR_RED, actor(EXPERT).chooseColor())
	}

	/**
	 * And a full draw pile makes the estimate too thin to change a play.
	 *
	 * 200 cards in the draw pile against 5 the table cannot account for, so under
	 * four percent of any colour's unaccounted cards can be in a hand. Red's
	 * charge falls to under a hundredth of a card and the count wins again.
	 *
	 * This is the opening of a round, where the deck is 216 cards and the draw
	 * pile holds nearly all of them, and it is why the estimate is worth having:
	 * applied there it would be nonsense, and the model declines to have an
	 * opinion rather than guessing loudly.
	 */
	@Test
	fun aFullDrawPileMakesTheEstimateTooThinToActOn ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_RED, 4), takeNumber(Card.COLOR_RED, 6),
				takeNumber(Card.COLOR_BLUE, 5), takeNumber(Card.COLOR_BLUE, 7))
		deal(WEST, takeNumber(Card.COLOR_RED, 8))
		deal(NORTH, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(takeNumber(Card.COLOR_GREEN, 3), 200)
		difficulty(EXPERT, 2)

		assertEquals ("nothing can be known this early, so lead red",
				Card.COLOR_RED, actor(EXPERT).chooseColor())
	}

	/**
	 * The same board at Strong and at Pushover both still lead red.
	 *
	 * The colour strategy is gated at Expert, because the `FIXME` it replaces
	 * was not wrong for the other two -- a Pushover that leads around what the
	 * table is holding is a Pushover that has stopped being a Pushover. Two
	 * tests because two skill levels, and both answers are the old behaviour.
	 */
	@Test
	fun strongAndPushoverStillLeadTheirBiggestColour ()
	{
		round()

		deal(STRONG, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_BLUE, 4), takeNumber(Card.COLOR_BLUE, 5))
		deal(PUSHOVER, takeNumber(Card.COLOR_RED, 8))
		deal(EAST, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(takeNumber(Card.COLOR_GREEN, 3))
		difficulty(STRONG, 1)
		difficulty(PUSHOVER, 0)

		assertEquals ("Strong leads red", Card.COLOR_RED,
				m_game.getPlayer(STRONG)!!.chooseColor())
		assertEquals ("Pushover leads red", Card.COLOR_RED,
				m_game.getPlayer(PUSHOVER)!!.chooseColor())
	}

	/**
	 * The count still wins when nothing opposes it.
	 *
	 * Three blue to two red with the only unaccounted cards being three greens
	 * that nobody who matters is holding: no colour is under threat, so the seat
	 * leads the one it can most keep playing. Without this, "lead the colour
	 * opponents cannot follow" would be a strategy that leads green by default.
	 */
	@Test
	fun expertLeadsTheColourItHoldsMostWhenNothingOpposesIt ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_BLUE, 4), takeNumber(Card.COLOR_BLUE, 5),
				takeNumber(Card.COLOR_BLUE, 6))
		deal(WEST, takeNumber(Card.COLOR_GREEN, 8))
		deal(NORTH, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(takeNumber(Card.COLOR_YELLOW, 3))
		difficulty(EXPERT, 2)

		assertEquals ("three blue beats two red",
				Card.COLOR_BLUE, m_game.getPlayer(EXPERT)!!.chooseColor())
	}

	/**
	 * A colour the seat is not holding is never led, however cheap it looks.
	 *
	 * East holds one red card and West holds two red cards, and nothing else on
	 * the table is a colour anything can be holding. So red is certain to be
	 * followed -- charged 1.33 cards for a two-card hand at certainty, against
	 * yellow's nothing -- which is more than the one red card is worth. Lead red.
	 *
	 * The board is built so that dropping the `held == 0` check in
	 * `chooseColor` picks *yellow* instead: with an empty yellow in hand and no
	 * pressure on it, yellow scores 0 and beats red's -0.33. That is the failure
	 * this test is for. A seat that led a colour it held nothing of would be
	 * handing the table the one thing it cannot follow, having told them in
	 * advance which colour that was.
	 */
	@Test
	fun aColourTheSeatCannotPlayIsNeverLed ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2))
		deal(WEST, takeNumber(Card.COLOR_RED, 3), takeNumber(Card.COLOR_RED, 4))
		deal(NORTH)
		deal(SOUTH)

		fillPiles(takeNumber(Card.COLOR_GREEN, 3))
		difficulty(EXPERT, 2)

		assertEquals ("red is the only thing in hand, so red",
				Card.COLOR_RED, actor(EXPERT).chooseColor())
	}

	/**
	 * An opponent who is out of the round is not a threat.
	 *
	 * West holds the only unaccounted card on the table, so the seat leads blue
	 * around West's red -- and then leads red anyway once West is knocked out,
	 * because a player who cannot play is not going to follow a lead.
	 *
	 * North and South are dealt *nothing* here, and that is the fixture rather
	 * than an oversight. The estimate is about cards, not about players: a card in
	 * West's hand stays in the unaccounted count when West goes out, because the
	 * table does not know where it went, and while any card of that colour is
	 * unaccounted for every *other* opponent is still a candidate holder of it. An
	 * empty hand is the only way to say "nobody else can be holding this" without
	 * looking inside somebody's hand, which `countUnaccountedInColor` refuses to do.
	 */
	@Test
	fun anInactiveOpponentIsNotAPressure ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_BLUE, 4), takeNumber(Card.COLOR_BLUE, 5))
		deal(WEST, takeNumber(Card.COLOR_RED, 8))
		deal(NORTH)
		deal(SOUTH)

		fillPiles(takeNumber(Card.COLOR_GREEN, 3))
		difficulty(EXPERT, 2)

		assertEquals ("with West playing, lead blue", Card.COLOR_BLUE,
				actor(EXPERT).chooseColor())

		m_game.getPlayer(WEST)!!.setActive(false)

		assertEquals ("with West out, red is fine", Card.COLOR_RED,
				actor(EXPERT).chooseColor())
	}

	/**
	 * An opponent holding more cards than the table can account for is not
	 * described by the model, and the model says so.
	 *
	 * West is dealt 40 cards while only a handful of the deck's cards are
	 * unaccounted for, which cannot happen at a real table but is cheap to
	 * construct. The answer is "no chance of holding the colour", which is the
	 * safe direction: it can only make a lead look cheaper to follow than it is,
	 * and never makes a threat look safe to ignore.
	 */
	@Test
	fun impossibleOpponentHandsAreNotModelled ()
	{
		round()

		deal(EXPERT, takeNumber(Card.COLOR_RED, 2), takeNumber(Card.COLOR_RED, 3),
				takeNumber(Card.COLOR_BLUE, 4), takeNumber(Card.COLOR_BLUE, 5))

		val west = m_game.getPlayer(WEST)!!.getHand()!!
		west.reset()
		for (i in 0 until 40)
		{
			west.addCard(m_game.getDeck()!!.getCards()[i]!!)
		}

		deal(NORTH, takeNumber(Card.COLOR_GREEN, 9))
		deal(SOUTH, takeNumber(Card.COLOR_GREEN, 2))

		fillPiles(takeNumber(Card.COLOR_GREEN, 3), 200)
		difficulty(EXPERT, 2)

		assertEquals ("the model declines, so the count decides",
				Card.COLOR_RED, m_game.getPlayer(EXPERT)!!.chooseColor())
	}
}