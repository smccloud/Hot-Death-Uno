package com.smccloud.hotdeath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Covers the two Hand paths HandTest had to leave alone because they need a real
 * Game: hasValidCards, which is a loop over Game.checkCard, and the isfinal
 * branch that adds a green 3 to the owner's virus penalty.
 *
 * Robolectric for the reason GameOptionsTest gives -- GameOptions reads Prefs,
 * and Prefs needs a Context -- and GameActivity is built with buildActivity().get()
 * rather than create(), so onCreate never stands up a GameTable.
 *
 * checkCard reads three Game fields -- m_currCard, m_currColor and m_penalty --
 * and none of them has a setter. The only code that sets them is startRound(),
 * which draws from the draw pile and calls redrawTable(), so it wants a real
 * GameTable and every card bitmap loaded. They go in by reflection instead. A
 * rename makes these tests fail on the getDeclaredField rather than quietly
 * stop covering anything, which is the trade wanted here: the fields and the
 * rule are coupled on purpose.
 *
 * The cards below are built the way CardDeck builds them (see the wild block in
 * CardDeck -- every wild, Mystery and Hot Death included, carries
 * VAL_WILD_DRAWFOUR as its value), so nothing here can pass on a value the deck
 * never produces.
 */
@RunWith(RobolectricTestRunner::class)
class HandPlayabilityTest
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
		standardRules(false)
	}

	// ---------------------------------------------------------------- fixture

	/** Puts a card face up with its own colour current and no penalty running. */
	private fun table (top: Card)
	{
		setField("m_currCard", top)
		setField("m_currColor", top.getColor())
		setField("m_penalty", Penalty())
	}

	/** A card is on the table and a penalty is running against the current player. */
	private fun underAttack (top: Card, penaltyFrom: Card)
	{
		setField("m_currCard", top)
		setField("m_currColor", top.getColor())

		val penalty = Penalty()
		penalty.addCards(penaltyFrom, 2, m_player, m_player)
		setField("m_penalty", penalty)
	}

	private fun standardRules (on: Boolean)
	{
		Prefs.defaultSharedPreferences(m_activity)
			.edit()
			.putString("cheat_code", if (on) "standardrules" else "")
			.commit()
	}

	// F.set on a Kotlin private var of non-null type bypasses the intrinsic null
	// check, so nothing here stops a wrong-typed value reaching the field the way
	// it would in Java. That was also true of the Java version -- the platform
	// types had no check either -- so the guard that matters is still the one
	// below: a field that is gone or renamed must fail here, loudly, rather than
	// leaving hasValidCards silently uncovered.
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

	private fun hand (vararg cards: Card): Hand
	{
		val h = Hand(m_player)
		for (c in cards)
		{
			h.addCard(c)
		}
		return h
	}

	// ---------------------------------------------------------------- cards

	/** A numbered card. Its ID sits in the green 3 block, well below the specials. */
	private fun numbered (color: Int, value: Int): Card
	{
		return Card(0, color, value, Card.ID_GREEN_0 + value, value)
	}

	private fun drawFour ()     = Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_DRAWFOUR, 50)
	private fun mysteryWild () = Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_MYSTERY, 0)
	private fun hotDeathWild () = Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_HD, 100)
	private fun shitter ()     = Card(0, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0_SHITTER, 0)
	private fun fuckYou ()     = Card(0, Card.COLOR_BLUE, 0, Card.ID_BLUE_0_FUCKYOU, 0)
	private fun holyDefender () = Card(0, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0)
	private fun aids ()        = Card(0, Card.COLOR_GREEN, 3, Card.ID_GREEN_3_AIDS, 3)
	private fun magicFive ()   = Card(0, Card.COLOR_RED, 5, Card.ID_RED_5_MAGIC, -5)
	private fun sixtyNine ()   = Card(0, Card.COLOR_YELLOW, 6, Card.ID_YELLOW_69, 6)

	// ------------------------------------------------------- hasValidCards

	@Test
	fun aHandWithNoLegalCardIsNotPlayable ()
	{
		table(numbered(Card.COLOR_RED, 7))

		assertFalse("neither the colour nor the value matches",
			hand(numbered(Card.COLOR_GREEN, 1), numbered(Card.COLOR_BLUE, 3)).hasValidCards(m_game))
	}

	@Test
	fun oneCardOfTheCurrentColourIsEnough ()
	{
		table(numbered(Card.COLOR_RED, 7))

		assertTrue("the red 9 is playable on a red 7",
			hand(numbered(Card.COLOR_GREEN, 1), numbered(Card.COLOR_RED, 9)).hasValidCards(m_game))
	}

	@Test
	fun oneCardOfTheCurrentValueIsEnough ()
	{
		table(numbered(Card.COLOR_RED, 7))

		assertTrue("the blue 7 is playable on a red 7",
			hand(numbered(Card.COLOR_GREEN, 1), numbered(Card.COLOR_BLUE, 7)).hasValidCards(m_game))
	}

	@Test
	fun aWildIsPlayableOnAnything ()
	{
		table(numbered(Card.COLOR_RED, 7))

		assertTrue(hand(numbered(Card.COLOR_GREEN, 1), drawFour()).hasValidCards(m_game))
	}

	/**
	 * One hand, two answers, and the only difference is the cheat code. The wild
	 * is on the table with yellow current, and the Shitter in the hand is the
	 * yellow that matches it -- without being playable itself, because a Shitter
	 * in a full hand only goes on the Holy Defender or the Magic 5. That is what
	 * leaves the draw four as the only candidate.
	 */
	@Test
	fun standardRulesRefuseADrawFourTheHandCanMatch ()
	{
		standardRules(true)
		table(mysteryWild())
		setField("m_currColor", Card.COLOR_YELLOW)

		assertFalse(hand(shitter(), numbered(Card.COLOR_GREEN, 1), drawFour()).hasValidCards(m_game))
	}

	@Test
	fun theSameHandIsPlayableWithoutStandardRules ()
	{
		table(mysteryWild())
		setField("m_currColor", Card.COLOR_YELLOW)

		assertTrue("the draw four is legal once the standard rules are off",
			hand(shitter(), numbered(Card.COLOR_GREEN, 1), drawFour()).hasValidCards(m_game))
	}

	@Test
	fun aLoneShitterIsPlayableOnAnything ()
	{
		table(numbered(Card.COLOR_RED, 7))

		assertTrue("a hand down to the Shitter can always play it",
			hand(shitter()).hasValidCards(m_game))
	}

	@Test
	fun aShitterInAFullHandOnlyPlaysOnHolyDefenderAndMagicFive ()
	{
		table(numbered(Card.COLOR_RED, 7))
		assertFalse(hand(shitter(), numbered(Card.COLOR_GREEN, 1)).hasValidCards(m_game))

		table(holyDefender())
		assertTrue(hand(shitter(), numbered(Card.COLOR_GREEN, 1)).hasValidCards(m_game))

		table(magicFive())
		assertTrue(hand(shitter(), numbered(Card.COLOR_GREEN, 1)).hasValidCards(m_game))
	}

	@Test
	fun magicFivePlaysOnAnything ()
	{
		table(numbered(Card.COLOR_BLUE, 9))

		assertTrue(hand(numbered(Card.COLOR_GREEN, 1), magicFive()).hasValidCards(m_game))
	}

	@Test
	fun the69LetsSixesAndNinesCrossPlay ()
	{
		table(numbered(Card.COLOR_RED, 9))
		assertTrue("a blue 6 on a red 9, holding the 69",
			hand(numbered(Card.COLOR_BLUE, 6), sixtyNine()).hasValidCards(m_game))

		table(numbered(Card.COLOR_RED, 6))
		assertTrue("and the 69 itself on a 6",
			hand(sixtyNine()).hasValidCards(m_game))
	}

	/** hasValidCards only ever asks about cards the hand holds, so the membership
	 *  guard at the top of checkCard is unreachable through it. checkCard is
	 *  internal, so this asks directly -- the test source set is a friend of the
	 *  main one, which is the same visibility a same-package Java test had. */
	@Test
	fun checkCardRefusesACardTheHandDoesNotHold ()
	{
		table(numbered(Card.COLOR_RED, 7))
		val h = hand(numbered(Card.COLOR_GREEN, 1))

		assertFalse("a red 9 the hand is not holding, however legal it looks",
			m_game.checkCard(h, numbered(Card.COLOR_RED, 9), false))
	}

	// ---------------------------------------------------------- under attack

	@Test
	fun aPenaltySuspendsTheColourAndValueRules ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), drawFour())

		assertFalse("a red 9 is legal on a red 7 right up until a draw four lands",
			hand(numbered(Card.COLOR_RED, 9)).hasValidCards(m_game))
	}

	@Test
	fun theThreeDefendersAreTheOnlyCardsPlayableOnAPenalty ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), drawFour())

		assertTrue(hand(fuckYou()).hasValidCards(m_game))
		assertTrue(hand(holyDefender()).hasValidCards(m_game))
		assertTrue(hand(aids()).hasValidCards(m_game))
	}

	@Test
	fun magicFiveDefendsOnlyAgainstTheHotDeathWild ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), hotDeathWild())
		assertTrue("the Magic 5 is the answer to the Hot Death wild",
			hand(numbered(Card.COLOR_GREEN, 1), magicFive()).hasValidCards(m_game))

		underAttack(numbered(Card.COLOR_RED, 7), drawFour())
		assertFalse("and to nothing else",
			hand(numbered(Card.COLOR_GREEN, 1), magicFive()).hasValidCards(m_game))
	}

	/**
	 * Stacking draw fours needs the penalty's card to still be the card on the
	 * table -- once a defender is down, no more -- and it is the one rule the
	 * standard-rules cheat takes away. The test has to pass the *same* Card to
	 * both, because checkCard compares the two by identity, not by ID.
	 */
	@Test
	fun aDrawFourStacksOnADrawFourUnlessStandardRules ()
	{
		val onTable = drawFour()
		underAttack(onTable, onTable)
		setField("m_currColor", Card.COLOR_RED)

		assertTrue(hand(drawFour()).hasValidCards(m_game))

		standardRules(true)
		assertFalse("standard rules cap the stack at one",
			hand(drawFour()).hasValidCards(m_game))
	}

	/**
	 * A penalty turns checkCard around before it ever reaches the Shitter rules,
	 * so a hand down to one card still cannot throw it. Pinned because it is the
	 * one case where "always playable" is not the answer.
	 */
	@Test
	fun aLoneShitterCannotPlayOntoAPenalty ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), drawFour())

		assertFalse(hand(shitter()).hasValidCards(m_game))
	}

	// --------------------------------------------------- the green 3 infection

	@Test
	fun theAidsCardInfectsOnlyOnTheFinalScore ()
	{
		val h = hand(aids())

		h.calculateValue(false)
		assertEquals("a mid-game estimate must not hand out points", 0, m_player.getVirusPenalty())

		h.calculateValue(true)
		assertEquals("the real score costs 10", 10, m_player.getVirusPenalty())
	}

	@Test
	fun eachAidsCardCostsAnotherTen ()
	{
		hand(aids(), aids()).calculateValue(true)

		assertEquals("the penalty is per card, not per hand", 20, m_player.getVirusPenalty())
	}

	@Test
	fun theVirusPenaltyAccumulates ()
	{
		val h = hand(aids())

		h.calculateValue(true)
		h.calculateValue(true)

		assertEquals("10 + 10, it never resets itself", 20, m_player.getVirusPenalty())
	}
}