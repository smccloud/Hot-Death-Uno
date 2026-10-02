package com.smccloud.hotdeath

import android.preference.PreferenceManager
import android.view.View
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
 * The four rules questions of issue #10, as decided.
 *
 * Each of the four was a question rather than a bug, and three of them changed code.
 * `HandPlayabilityTest` covers whether a card is legal and `GameRoundLoopTest` asserts
 * invariants about a round running, so before this file nothing in the suite could
 * tell whether a draw 2 stacks, whether the cheat code skips its victim, or what the
 * dealer does when the opening card is a penalty.
 *
 * The two decisions that changed nothing are pinned here too. A rule that is *not* a
 * bug is the easiest kind to change by accident, because every test in the suite
 * stays green while it moves.
 *
 * What each test is protecting:
 *
 *  - **The 1,000-point hand is a pair.** Quitter and Fuck You, and no third card.
 *  - **`standardrules` skips the draw 2 victim**, as plain UNO does. It used to be
 *    the only setting in which a drawn victim got to play on top of their draw.
 *  - **A draw 2 stacks on a draw 2**, and only on a draw 2.
 *  - **Retaliation, AIDS and the Holy Defender do not answer a draw 2.** Decided, and
 *    the opposite of what issue #10 originally said.
 *  - **The dealer absorbs a penalty that opens the round.**
 */
@RunWith(RobolectricTestRunner::class)
class PenaltyRulesTest
{
	private lateinit var m_activity: GameActivity
	private lateinit var m_options: GameOptions
	private lateinit var m_game: Game
	private lateinit var m_player: Player

	@Before
	fun setUp ()
	{
		m_activity = Robolectric.buildActivity(GameActivity::class.java).get()
		m_options = GameOptions(m_activity)
		m_game = Game(m_activity, m_options)
		m_player = ComputerPlayer(m_game, m_options)
		m_game.setFastForward(true)
		standardRules(false)

		// forceDraw and the eject path both call redrawTable(), which posts to the
		// UI thread and dereferences m_gt -- so a real GameTable has to exist. Laid
		// out as well, because GameTable.Toast positions itself off a field that
		// only onSizeChanged builds. Same reason NoviceModeTest does this.
		val table = GameTable(m_activity, m_game, m_options)
		table.measure(
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, 1080, 1920)
	}

	// ------------------------------------------------------------- fixture

	private fun standardRules (on: Boolean)
	{
		PreferenceManager.getDefaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.putString("cheat_code", if (on) "standardrules" else "")
			.commit()
	}

	private fun setField (name: String, value: Any)
	{
		Game::class.java.getDeclaredField(name).apply { isAccessible = true }.set(m_game, value)
	}

	private fun getField (name: String): Any?
	{
		return Game::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m_game)
	}

	/**
	 * `m_currPlayer` and `m_dealer` have no setters, and the opening-penalty method is
	 * private because nothing outside the round-start path should call it. Reflection
	 * rather than a test-only accessor on Game: a production hook that exists only for
	 * a test is a hook the next person calls from production.
	 */
	private fun currentPlayer () = getField("m_currPlayer") as Player?

	private fun makeDealer (seat: Int): Player
	{
		val dealer = m_game.getPlayer(seat - 1)!!
		dealer.getHand()!!.reset()
		setField("m_dealer", dealer)
		return dealer
	}

	/**
	 * A round with a real deck and a draw pile with something in it.
	 *
	 * `Game.resetRound` builds both containers empty and nothing refills them until
	 * `startRound`, which wants a dealer and a whole deal. `forceDraw` pulls from the
	 * draw pile, so without this a victim "draws" zero cards and every count assertion
	 * below fails on an empty pile rather than on the rule.
	 */
	private fun roundWithDrawPile (cards: Int = 20)
	{
		m_game.resetRound()
		m_game.getDeck()!!.reset(false, false)

		val deckCards = m_game.getDeck()!!.getCards()
		for (i in 0 until m_game.getDeck()!!.getNumCards())
		{
			if (m_game.getDrawPile()!!.getNumCards() >= cards) break
			m_game.getDrawPile()!!.addCard(deckCards[i]!!)
		}
	}

	private fun absorbOpeningPenalty ()
	{
		Game::class.java.getDeclaredMethod("absorbOpeningPenaltyAtDealer")
			.apply { isAccessible = true }
			.invoke(m_game)
	}

	private fun hand (vararg cards: Card): Hand
	{
		val h = Hand(m_player)
		for (c in cards) { h.addCard(c) }
		return h
	}

	/**
	 * Is [c] legal, asked the way the game asks it?
	 *
	 * `checkCard` opens with `if (!h.isInHand(c)) return false`, and `isInHand`
	 * compares identity, so the card must be the same object in the hand and in the
	 * call. `HandPlayabilityTest` sidesteps this by going through `hasValidCards`,
	 * which passes each card twice; handing it a freshly built twin instead fails the
	 * identity check before any rule is reached, which looks exactly like the rule
	 * being false.
	 */
	private fun legal (c: Card): Boolean
	{
		return m_game.checkCard(hand(c), c, false)
	}

	/** A card on the table, no penalty running. */
	private fun table (top: Card)
	{
		setField("m_currCard", top)
		setField("m_currColor", top.getColor())
		setField("m_penalty", Penalty())
	}

	/**
	 * A card is on the table and a penalty it caused is running against `m_player`.
	 *
	 * The same `Card` goes in twice, because `checkCard` compares the penalty's
	 * original card to the current one by identity to decide whether a defender has
	 * already been thrown -- `HandPlayabilityTest` says the same thing about it.
	 */
	private fun underAttack (top: Card, count: Int = 2)
	{
		setField("m_currCard", top)
		setField("m_currColor", top.getColor())
		val penalty = Penalty()
		penalty.addCards(top, count, m_player, m_player)
		setField("m_penalty", penalty)
	}

	private fun drawTwo ()    = Card(0, Card.COLOR_RED, Card.VAL_D, Card.ID_RED_D, 20)
	private fun drawTwoBlue () = Card(1, Card.COLOR_BLUE, Card.VAL_D, Card.ID_BLUE_D, 20)
	private fun drawFour ()   = Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_DRAWFOUR, 50)
	private fun fuckYou ()    = Card(0, Card.COLOR_BLUE, 0, Card.ID_BLUE_0_FUCKYOU, 0)
	private fun aids ()       = Card(0, Card.COLOR_GREEN, 3, Card.ID_GREEN_3_AIDS, 3)
	private fun holyDefender () = Card(0, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0)
	private fun glassnost()   = Card(0, Card.COLOR_RED, 2, Card.ID_RED_2_GLASNOST, 75)
	private fun quitter()     = Card(0, Card.COLOR_GREEN, 0, Card.ID_GREEN_0_QUITTER, 100)

	// ------------------------------- question 1: the 1,000-point hand is a pair

	/**
	 * Quitter and Fuck You together are worth 1,000, and the Shitter does not join them.
	 *
	 * Issue #10 asked whether the pair needs Big Brother as well. It does not, and the
	 * two rules that look like they might collide do not: `Game.calculateScore` gives a
	 * four-bastard hand zero *before* `calculateValue` ever runs, so the instant-loss
	 * rule already shadows the 1,000-point rule for that hand.
	 *
	 * Pinned because it is the rule most likely to be "improved" by somebody who sees
	 * three cards and assumes the fourth was forgotten.
	 */
	@Test
	fun theQuitterAndFuckYouAloneAreAThousandPoints ()
	{
		val h = hand(fuckYou(), quitter())

		assertEquals ("the pair is 1,000", 1000, h.calculateValue(true))
	}

	@Test
	fun theShitterIsNotPartOfThePair ()
	{
		// Big Brother present or not, the pair is still just the pair: without the
		// Shitter the total is the pair's own 1,000, and with it the hand scores the
		// 1,000 rule rather than something derived from three cards.
		assertEquals ("pair alone", 1000, hand(fuckYou(), quitter()).calculateValue(true))
		assertEquals ("pair plus the Shitter is still the pair",
				1000, hand(fuckYou(), quitter(), shitter()).calculateValue(true))
	}

	private fun shitter () = Card(0, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0_SHITTER, 0)

	/**
	 * And the four-bastard hand is the instant-loss rule, not the 1,000-point one.
	 *
	 * `checkForAllBastardCards` counts Holy Defender, Quitter, Fuck You and Shitter --
	 * Holy Defender, *not* Hot Death, which is what issue #10 first said and was wrong.
	 * `gotAllBastardCards` scores nothing itself; the zero comes from `calculateScore`.
	 */
	@Test
	fun allFourBastardCardsAreAHolyDefenderNotAHotDeath ()
	{
		val h = hand(holyDefender(), quitter(), fuckYou(), shitter())

		assertTrue ("the bastard set is the Holy Defender's", m_game.checkForAllBastardCards(h))

		assertFalse ("a Hot Death wild is not one of the four",
				m_game.checkForAllBastardCards(
						hand(Card(0, Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD_HD, 0),
								quitter(), fuckYou(), shitter())))
	}

	// -------------------------------- question 2a: standardrules skips the victim

	/**
	 * The cheat code called `standardrules` used to be the only setting in which the
	 * victim of a draw drew *and then played*.
	 *
	 * `assessPenalty` had `if (!(m_go!!.getStandardRules())) m_currPlayer = nextPlayer()`,
	 * so outside standard rules the victim was stepped past and inside them they were
	 * left in place. `standardrules` is the cheat that makes the deck plain UNO, so its
	 * whole purpose is to be more standard, and in this one place it was less standard
	 * than the rules it replaces.
	 *
	 * Asserted through `assessPenalty` rather than by reading the field, because what
	 * matters is who ends up holding the turn.
	 */
	@Test
	fun standardRulesSkipTheDrawTwoVictim ()
	{
		standardRules(true)

		roundWithDrawPile()
		val victim = m_game.getPlayer(Game.SEAT_SOUTH - 1)!!
		victim.getHand()!!.reset()
		setField("m_currPlayer", victim)
		val penalty = Penalty()
		penalty.addCards(drawTwo(), Penalty.COUNT_DRAWTWO, m_game.getPlayer(Game.SEAT_WEST - 1), victim)
		setField("m_penalty", penalty)
		setField("m_currCard", drawTwo())
		setField("m_currColor", Card.COLOR_RED)

		val before = victim.getHand()!!.getNumCards()
		m_game.assessPenalty()

		assertEquals ("the victim still draws", before + 2, victim.getHand()!!.getNumCards())
		assertTrue ("but does not keep the turn",
				currentPlayer() !== victim)
	}

	@Test
	fun hotDeathRulesAlsoSkipTheDrawTwoVictim ()
	{
		standardRules(false)

		roundWithDrawPile()
		val victim = m_game.getPlayer(Game.SEAT_SOUTH - 1)!!
		victim.getHand()!!.reset()
		setField("m_currPlayer", victim)
		val penalty = Penalty()
		penalty.addCards(drawTwo(), Penalty.COUNT_DRAWTWO, m_game.getPlayer(Game.SEAT_WEST - 1), victim)
		setField("m_penalty", penalty)
		setField("m_currCard", drawTwo())
		setField("m_currColor", Card.COLOR_RED)

		m_game.assessPenalty()

		assertTrue ("both rule sets skip, which is what the guard's removal means",
				currentPlayer() !== victim)
	}

	// ------------------------------- question 2b: a draw 2 stacks on a draw 2

	/** The headline of the stacking change: a draw 2 answers a draw 2. */
	@Test
	fun aDrawTwoStacksOnADrawTwo ()
	{
		underAttack(drawTwo())

		assertTrue ("a draw 2 is legal on a draw 2",
				legal(drawTwo()))
	}

	/** And the draw four still stacks exactly as it did, which is what was mirrored. */
	@Test
	fun aDrawFourStillStacksOnADrawFour ()
	{
		underAttack(drawFour())

		assertTrue (legal(drawFour()))
	}

	/** Standard rules cap the stack at one, for the draw two as well as the draw four. */
	@Test
	fun standardRulesCapTheDrawTwoStackAtOne ()
	{
		standardRules(true)
		underAttack(drawTwo())

		assertFalse ("standard rules are plain UNO, and plain UNO does not stack",
				legal(drawTwo()))
	}

	/**
	 * A defender that has already been thrown closes the stack, for the draw two as for
	 * the draw four.
	 *
	 * `defenderAlreadyThrown` is the identity check on the penalty's original card, so
	 * this needs a *different* current card from the one the penalty came from.
	 */
	@Test
	fun aDrawTwoCannotStackOnceADefenderIsDown ()
	{
		val two = drawTwo()
		underAttack(two)
		setField("m_currCard", drawTwoBlue())

		assertFalse ("the stack is closed once a defender is on it",
				legal(drawTwo()))
	}

	/**
	 * A draw 2 with no draw 2 in hand leaves the victim no answer at all.
	 *
	 * This is the decision in question 3 seen from the other side: Retaliation, AIDS and
	 * the Holy Defender do not answer a draw 2, so a draw 2 in hand is the only thing that
	 * does. Worth a test because it is the whole of the rule's meaning.
	 */
	@Test
	fun aDrawTwoIsAnsweredByNothingButADrawTwo ()
	{
		underAttack(drawTwo())

		assertFalse ("Retaliation does not answer a draw 2",
				legal(fuckYou()))
		assertFalse ("AIDS does not answer a draw 2",
				legal(aids()))
		assertFalse ("and neither does the Holy Defender",
				legal(holyDefender()))
		assertTrue ("but a draw 2 does",
				legal(drawTwo()))
	}

	/**
	 * A draw 2 now sets a penalty object, which is the whole of why it can be answered.
	 *
	 * It used to draw inline: `handleSpecialCards` called `forceDraw` and advanced the
	 * turn itself, and `m_penalty` was never touched. Everything that lets a victim
	 * *have* a turn lives in the round loop's "did they get a defender" check, and that
	 * check only runs when a penalty is pending -- so a draw 2 that drew inline left
	 * its victim no way to answer it however they wanted to. Routing it through
	 * `addCardPenalty` like every other draw card is the fix.
	 *
	 * Asserted on the penalty rather than on a turn, because that is the state the rest
	 * of the machinery reads and it does not need a whole round to observe.
	 */
	@Test
	fun aDrawTwoSetsAPenaltyLikeEveryOtherDrawCard ()
	{
		roundWithDrawPile()
		val two = drawTwo()

		// A seat from this Game, not the standalone m_player: getNextPlayer walks the
		// leftOpp chain, and only the Game's own four players are wired into it.
		setField("m_currPlayer", m_game.getPlayer(Game.SEAT_SOUTH - 1)!!)
		setField("m_currCard", two)
		setField("m_currColor", Card.COLOR_RED)
		setField("m_penalty", Penalty())

		m_game.handleSpecialCards()

		assertEquals ("the draw 2 is the card the penalty came from",
				two, m_game.getPenalty()!!.getOrigCard())
		assertEquals ("and it is worth two", 2, m_game.getPenalty()!!.getNumCards())
	}

	/**
	 * A draw 2 in hand is what keeps its victim in the turn.
	 *
	 * `checkForDefender` is the gate the round loop uses to decide whether the victim of
	 * a penalty plays or just draws, and it is asked "can this hand defend?" rather than
	 * "can it answer?". A draw 2 branch counting draw 2s is therefore what turns the
	 * stacking rule on at all -- without it `checkCard` would allow a legal play that
	 * the turn order never offered anybody the chance to make.
	 *
	 * And per question 3 nothing else counts: a victim holding Retaliation against a
	 * draw 2 is *not* given a turn, which is the decision that keeps the two halves of
	 * this rule consistent -- the Draw 2 is the only answer to a Draw 2.
	 */
	@Test
	fun aDrawTwoInHandKeepsItsVictimInTheTurn ()
	{
		underAttack(drawTwo())
		setField("m_currPlayer", m_player)

		assertEquals ("one draw 2 keeps the turn",
				1, m_game.checkForDefender(hand(drawTwo())))
		assertEquals ("an empty answer does not",
				0, m_game.checkForDefender(hand(Card(0, Card.COLOR_BLUE, 4, Card.ID_BLUE_4, 4))))
		assertEquals ("and neither does Retaliation, per question 3",
				0, m_game.checkForDefender(hand(fuckYou())))
		assertEquals ("nor the Holy Defender",
				0, m_game.checkForDefender(hand(holyDefender())))
	}

	/** The draw four's own gate is untouched, and still counts everything it did. */
	@Test
	fun aDrawFourStillKeepsItsVictimInTheTurn ()
	{
		underAttack(drawFour())
		setField("m_currPlayer", m_player)

		assertTrue ("a draw four stacks", m_game.checkForDefender(hand(drawFour())) > 0)
		assertTrue ("and Retaliation still defends", m_game.checkForDefender(hand(fuckYou())) > 0)
	}

	// ------------------------------------------ question 3: defenders per card

	/**
	 * The defender clause is Wild Draw / Quitter / Glasnost, and a draw 2 is not in it.
	 *
	 * This is the corrected form of issue #10's question 3, which asserted that
	 * Retaliation already applied to a draw 2. It does not, and never did -- and the
	 * README's bastard-cards table agrees, listing exactly the three.
	 */
	@Test
	fun theThreeDefensiblePenaltiesAreNotADrawTwo ()
	{
		underAttack(drawFour())
		assertTrue ("Retaliation answers a draw four",
				legal(fuckYou()))
		assertTrue ("and AIDS", legal(aids()))

		underAttack(glassnost())
		assertTrue ("Retaliation answers Glasnost",
				legal(fuckYou()))

		underAttack(drawTwo())
		assertFalse ("and not a draw two",
				legal(fuckYou()))
	}

	// ------------------------------------ question 4: the dealer's opening card

	/**
	 * A draw 2 opening the round makes the dealer draw two.
	 *
	 * The FIXME this replaces asked for it. The reading is that the dealer is the
	 * *victim*, the seat the card would have hit had a player thrown it -- so the
	 * dealer draws, and the round starts with one fewer card in their hand.
	 */
	@Test
	fun aDrawTwoOpeningTheRoundIsEatenByTheDealer ()
	{
		roundWithDrawPile()
		val dealer = makeDealer(Game.SEAT_SOUTH)
		setField("m_currCard", drawTwo())
		setField("m_currColor", Card.COLOR_RED)
		setField("m_penalty", Penalty())

		val before = dealer.getHand()!!.getNumCards()

		absorbOpeningPenalty()

		assertEquals ("the dealer drew the two", before + 2,
				dealer.getHand()!!.getNumCards())
	}

	/** A Glasnost opening lays the dealer's hand face up, rather than somebody else's. */
	@Test
	fun aGlasnostOpeningTheRoundTurnsTheDealersHandUp ()
	{
		roundWithDrawPile()
		val dealer = makeDealer(Game.SEAT_SOUTH)
		dealer.getHand()!!.addCard(Card(0, Card.COLOR_BLUE, 4, Card.ID_BLUE_4, 4))
		setField("m_currCard", glassnost())
		setField("m_currColor", Card.COLOR_RED)
		setField("m_penalty", Penalty())

		absorbOpeningPenalty()

		assertTrue ("the dealer's own cards are face up",
				dealer.getHand()!!.getCard(0)!!.getFaceUp())
	}

	/**
	 * A wild cannot open a round at all, so there is no wild draw four to absorb.
	 *
	 * `postDealHands` draws in a loop until a non-wild turns up. Worth pinning because
	 * the absorb method has to know it can skip the wilds entirely, and because a
	 * Wild Draw Four as the opening card would otherwise be the most dramatic way this
	 * feature could be wrong.
	 */
	@Test
	fun aWildIsNeverTheOpeningCardToAbsorb ()
	{
		roundWithDrawPile()
		val dealer = makeDealer(Game.SEAT_SOUTH)
		setField("m_currCard",
				Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_DRAWFOUR, 50))
		setField("m_currColor", Card.COLOR_WILD)
		setField("m_penalty", Penalty())

		val before = dealer.getHand()!!.getNumCards()

		absorbOpeningPenalty()

		assertEquals ("nothing to absorb", before,
				dealer.getHand()!!.getNumCards())
	}

	/**
	 * An ordinary opening card changes nothing, which is the common case.
	 *
	 * The whole feature is four cards out of a 216-card deck; without this, a method
	 * that absorbed everything would pass every test above.
	 */
	@Test
	fun anOrdinaryOpeningCardCostsTheDealerNothing ()
	{
		roundWithDrawPile()
		val dealer = makeDealer(Game.SEAT_SOUTH)
		setField("m_currCard", Card(0, Card.COLOR_BLUE, 7, Card.ID_BLUE_7, 7))
		setField("m_currColor", Card.COLOR_BLUE)
		setField("m_penalty", Penalty())

		val before = dealer.getHand()!!.getNumCards()

		absorbOpeningPenalty()

		assertEquals ("a 7 is not a penalty", before,
				dealer.getHand()!!.getNumCards())
		assertNull ("and left no penalty behind",
				m_game.getPenalty()!!.getOrigCard())
	}
}