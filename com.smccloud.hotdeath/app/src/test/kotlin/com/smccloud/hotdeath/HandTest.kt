package com.smccloud.hotdeath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers Hand: card bookkeeping (add, remove, reset, membership) and
 * calculateValue, the nine-step scoring routine carried over from the original
 * PocketPC build. The scoring rules are the point of this class -- every
 * bastard card changes the total in a way that is not visible from the card's
 * own point value -- so they are exercised one rule at a time.
 *
 * Plain JVM test. The two paths that need a real context live in
 * HandPlayabilityTest instead: hasValidCards hands off to Game.checkCard, and
 * the isfinal flag adds to Player.getVirusPenalty when a hand holds the
 * green 3.
 */
class HandTest
{
	/**
	 * A plain numbered card. Its ID is parked in the ID_GREEN_0 block
	 * (113-122), which is entirely below 152 where every card with special
	 * handling lives, so it can never be mistaken for one of those.
	 */
	private fun number (color: Int, value: Int): Card
	{
		return Card(value, color, value, Card.ID_GREEN_0 + value, value)
	}

	/** One of the cards calculateValue treats specially. Value 0 keeps it out of the numeral scan. */
	private fun special (deckIndex: Int, id: Int, pointValue: Int): Card
	{
		return Card(deckIndex, Card.COLOR_RED, 0, id, pointValue)
	}

	// ---------------------------------------------------------------- bookkeeping

	@Test
	fun newHandIsEmpty ()
	{
		val h = Hand(null)

		assertEquals(0, h.getNumCards())
		assertNull(h.getCard(0))
	}

	@Test
	fun addCardClaimsTheCard ()
	{
		val h = Hand(null)
		val c = number (Card.COLOR_RED, 3)

		h.addCard (c)

		assertEquals(1, h.getNumCards())
		assertSame(h, c.getHand())
	}

	@Test
	fun cardsKeepTheirInsertionOrder ()
	{
		val h = Hand(null)
		val a = number (Card.COLOR_RED, 3)
		val b = number (Card.COLOR_BLUE, 7)
		val c = number (Card.COLOR_YELLOW, 2)

		h.addCard (a)
		h.addCard (b)
		h.addCard (c)

		assertSame(a, h.getCard(0))
		assertSame(b, h.getCard(1))
		assertSame(c, h.getCard(2))
	}

	/** getCard is bounds-checked because GameTable indexes it straight from a tap. */
	@Test
	fun getCardIsNullOutsideTheHand ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 3))

		assertNull(h.getCard(-1))
		assertNull(h.getCard(1))
		assertNull(h.getCard(Game.MAX_NUM_CARDS))
	}

	/**
	 * removeCard shifts everything after the hole down rather than nulling one
	 * slot, so the remaining cards have to stay contiguous and in order.
	 */
	@Test
	fun removeCardShiftsTheRemainingCardsDown ()
	{
		val h = Hand(null)
		val a = number (Card.COLOR_RED, 3)
		val b = number (Card.COLOR_BLUE, 7)
		val c = number (Card.COLOR_YELLOW, 2)
		h.addCard (a)
		h.addCard (b)
		h.addCard (c)

		h.removeCard (b)

		assertEquals(2, h.getNumCards())
		assertSame(a, h.getCard(0))
		assertSame(c, h.getCard(1))
	}

	@Test
	fun removingTheOnlyCardEmptiesTheHand ()
	{
		val h = Hand(null)
		val only = number (Card.COLOR_RED, 3)
		h.addCard (only)

		h.removeCard (only)

		assertEquals(0, h.getNumCards())
		assertNull(h.getCard(0))
	}

	@Test
	fun removeCardReleasesTheBackReference ()
	{
		val h = Hand(null)
		val c = number (Card.COLOR_RED, 3)
		h.addCard (c)

		h.removeCard (c)

		assertNull("a card played to the discard pile must no longer claim a hand", c.getHand())
	}

	@Test
	fun removingAnAbsentCardChangesNothing ()
	{
		val h = Hand(null)
		val held = number (Card.COLOR_RED, 3)
		h.addCard (held)

		h.removeCard (number (Card.COLOR_RED, 3))

		assertEquals(1, h.getNumCards())
		assertSame(held, h.getCard(0))
	}

	@Test
	fun resetDropsEveryCardAndReleasesThem ()
	{
		val h = Hand(null)
		val a = number (Card.COLOR_RED, 3)
		val b = number (Card.COLOR_BLUE, 7)
		h.addCard (a)
		h.addCard (b)

		h.reset()

		assertEquals(0, h.getNumCards())
		assertNull(a.getHand())
		assertNull(b.getHand())
	}

	/**
	 * isInHand(Card) is an identity test, not a colour-and-value test. Two
	 * copies of the same card are both playable but only one is actually in
	 * this hand, and GameCardClicked relies on that distinction.
	 */
	@Test
	fun isInHandComparesIdentityNotValue ()
	{
		val h = Hand(null)
		val held = number (Card.COLOR_RED, 7)
		val alsoHeld = number (Card.COLOR_RED, 7)
		val elsewhere = number (Card.COLOR_RED, 7)
		h.addCard (held)
		h.addCard (alsoHeld)

		assertTrue(h.isInHand(held))
		assertTrue(h.isInHand(alsoHeld))
		assertFalse("two identical cards are not the same card", h.isInHand(elsewhere))
	}

	@Test
	fun isInHandMatchesColorAndValue ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 7))

		assertTrue(h.isInHand(Card.COLOR_RED, 7))
		assertFalse(h.isInHand(Card.COLOR_RED, 8))
		assertFalse(h.isInHand(Card.COLOR_BLUE, 7))
	}

	@Test
	fun hasColorMatchIgnoresValue ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 7))

		assertTrue(h.hasColorMatch(Card.COLOR_RED))
		assertFalse(h.hasColorMatch(Card.COLOR_BLUE))
	}

	@Test
	fun countSuitCountsOneColor ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 1))
		h.addCard (number (Card.COLOR_RED, 2))
		h.addCard (number (Card.COLOR_BLUE, 3))

		assertEquals(2, h.countSuit(Card.COLOR_RED))
		assertEquals(1, h.countSuit(Card.COLOR_BLUE))
		assertEquals(0, h.countSuit(Card.COLOR_YELLOW))
	}

	@Test
	fun setFaceUpAppliesToTheWholeHand ()
	{
		val h = Hand(null)
		val a = number (Card.COLOR_RED, 3)
		val b = number (Card.COLOR_BLUE, 7)
		h.addCard (a)
		h.addCard (b)

		h.setFaceUp (true)

		assertTrue(a.getFaceUp())
		assertTrue(b.getFaceUp())
	}

	@Test
	fun replaceCardSwapsTheSlotInPlace ()
	{
		val h = Hand(null)
		val a = number (Card.COLOR_RED, 3)
		val b = number (Card.COLOR_BLUE, 7)
		val replacement = number (Card.COLOR_YELLOW, 9)
		h.addCard (a)
		h.addCard (b)

		h.replaceCard (a, replacement)

		assertSame(replacement, h.getCard(0))
		assertSame(b, h.getCard(1))
	}

	// ---------------------------------------------------------------- lowest / highest

	@Test
	fun getLowestCardStaysInTheRequestedColor ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 9))
		h.addCard (number (Card.COLOR_BLUE, 2))
		h.addCard (number (Card.COLOR_BLUE, 6))

		assertEquals(Card.COLOR_BLUE, h.getLowestCard(Card.COLOR_BLUE)!!.getColor())
		assertEquals(2, h.getLowestCard(Card.COLOR_BLUE)!!.getValue())
	}

	/** A colour of zero is the "don't care" case that ComputerPlayer uses. */
	@Test
	fun getLowestCardWithColourZeroIgnoresColour ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 9))
		h.addCard (number (Card.COLOR_BLUE, 2))

		assertEquals(2, h.getLowestCard(0)!!.getValue())
	}

	@Test
	fun getLowestCardIsNullWhenNoCardMatches ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 9))

		assertNull(h.getLowestCard(Card.COLOR_GREEN))
	}

	@Test
	fun getLowestCardOnAnEmptyHandIsNull ()
	{
		assertNull(Hand(null).getLowestCard(0))
	}

	@Test
	fun getHighestCardStaysInTheRequestedColor ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 9))
		h.addCard (number (Card.COLOR_BLUE, 2))
		h.addCard (number (Card.COLOR_BLUE, 6))

		assertEquals(6, h.getHighestCard(Card.COLOR_BLUE)!!.getValue())
	}

	@Test
	fun getHighestCardWithColourZeroIgnoresColour ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 9))
		h.addCard (number (Card.COLOR_BLUE, 2))

		assertEquals(9, h.getHighestCard(0)!!.getValue())
	}

	@Test
	fun getHighestNonTrumpSkipsTheGivenColour ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 9))
		h.addCard (number (Card.COLOR_BLUE, 2))
		h.addCard (number (Card.COLOR_GREEN, 6))

		assertEquals(Card.COLOR_GREEN, h.getHighestNonTrump(Card.COLOR_RED)!!.getColor())
		assertEquals(6, h.getHighestNonTrump(Card.COLOR_RED)!!.getValue())
	}

	@Test
	fun getHighestNonTrumpOnAnEmptyHandIsNull ()
	{
		assertNull(Hand(null).getHighestNonTrump(Card.COLOR_RED))
	}

	// ---------------------------------------------------------------- calculateValue

	@Test
	fun anEmptyHandIsWorthZero ()
	{
		assertEquals(0, Hand(null).calculateValue())
	}

	@Test
	fun plainCardsJustAddUp ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (number (Card.COLOR_BLUE, 7))

		assertEquals(10, h.calculateValue())
	}

	/** currentValue is how the rest of the app reads a card's worth after scoring. */
	@Test
	fun ordinaryCardsTakeTheirPointValueIntoCurrentValue ()
	{
		val h = Hand(null)
		val three = number (Card.COLOR_RED, 3)
		val seven = number (Card.COLOR_BLUE, 7)
		h.addCard (three)
		h.addCard (seven)

		h.calculateValue()

		assertEquals(3, three.getCurrentValue())
		assertEquals(7, seven.getCurrentValue())
	}

	@Test
	fun theFinalFlagDoesNotChangeOrdinaryScoring ()
	{
		val h = Hand(null)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (number (Card.COLOR_BLUE, 7))

		assertEquals("only the green 3 is affected by the final flag", h.calculateValue (false), h.calculateValue (true))
	}

	/**
	 * Step 4: the Mystery Wild is worth ten times the highest numeral in the
	 * hand, and is then added on top of that numeral's own points.
	 */
	@Test
	fun mysteryWildIsTenTimesTheHighestNumeral ()
	{
		val h = Hand(null)
		val mystery = special (0, Card.ID_WILD_MYSTERY, 0)
		h.addCard (number (Card.COLOR_BLUE, 7))
		h.addCard (mystery)

		assertEquals(7 + 70, h.calculateValue())
		assertEquals(70, mystery.getCurrentValue())
	}

	@Test
	fun mysteryWildIsTenWithNoNumeralToScale ()
	{
		val h = Hand(null)
		val mystery = special (0, Card.ID_WILD_MYSTERY, 0)
		h.addCard (mystery)

		assertEquals(10, h.calculateValue())
		assertEquals(10, mystery.getCurrentValue())
	}

	/** Step 6: the 69 overrides whatever the rest of the hand came to. */
	@Test
	fun sixtyNineOverridesTheTotal ()
	{
		val h = Hand(null)
		val sixtyNine = special (0, Card.ID_YELLOW_69, 6)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (sixtyNine)

		assertEquals(69, h.calculateValue())
		assertEquals("the 69 absorbs the rest of the hand's points", 66, sixtyNine.getCurrentValue())
	}

	/** Step 7: Magic 5 subtracts. Its -5 point value comes from CardDeck. */
	@Test
	fun magicFiveSubtractsFive ()
	{
		val h = Hand(null)
		val magic = special (0, Card.ID_RED_5_MAGIC, -5)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (magic)

		assertEquals(-2, h.calculateValue())
		assertEquals(-5, magic.getCurrentValue())
	}

	/**
	 * Step 8: the F.U. doubles the total but scores nothing itself, so
	 * its currentValue is the pre-double figure.
	 */
	@Test
	fun fuckYouDoublesTheTotal ()
	{
		val h = Hand(null)
		val fuckYou = special (0, Card.ID_BLUE_0_FUCKYOU, 0)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (fuckYou)

		assertEquals(6, h.calculateValue())
		assertEquals(3, fuckYou.getCurrentValue())
	}

	/** Step 9: Holy Defender halves the total, rounding up. */
	@Test
	fun holyDefenderHalvesRoundingUp ()
	{
		val h = Hand(null)
		val defender = special (0, Card.ID_RED_0_HD, 0)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (defender)

		assertEquals("(3 + 1) / 2", 2, h.calculateValue())
		assertEquals(-1, defender.getCurrentValue())
	}

	@Test
	fun holyDefenderLeavesAnEvenTotalAlone ()
	{
		val h = Hand(null)
		val defender = special (0, Card.ID_RED_0_HD, 0)
		h.addCard (number (Card.COLOR_RED, 4))
		h.addCard (defender)

		assertEquals(2, h.calculateValue())
		assertEquals(-2, defender.getCurrentValue())
	}

	/** Step 2: F.U. plus Quitter short-circuits everything to 1000. */
	@Test
	fun theFullMontyIsAThousandPoints ()
	{
		val h = Hand(null)
		val fuckYou = special (0, Card.ID_BLUE_0_FUCKYOU, 0)
		val quitter = special (1, Card.ID_GREEN_0_QUITTER, 100)
		h.addCard (fuckYou)
		h.addCard (quitter)

		assertEquals(1000, h.calculateValue())
		assertEquals("both bastard cards are pushed to 500 so the AI unloads them", 500, fuckYou.getCurrentValue())
		assertEquals(500, quitter.getCurrentValue())
	}

	/**
	 * The three bastard cards are skipped, but ordinary cards in the same hand
	 * are still counted on top of the 1000.
	 */
	@Test
	fun theFullMontyStillCountsTheRestOfTheHand ()
	{
		val h = Hand(null)
		h.addCard (special (0, Card.ID_BLUE_0_FUCKYOU, 0))
		h.addCard (special (1, Card.ID_GREEN_0_QUITTER, 100))
		h.addCard (number (Card.COLOR_RED, 3))

		assertEquals(1003, h.calculateValue())
	}

	/**
	 * Step 10: mid-game only, the Shitter puts a floor under the total, and the
	 * floor is the 150 from step 2. Magic 5 is the only card that can push a
	 * hand below it, because step 3 now skips the Shitter and so leaves its
	 * pseudo-value standing instead of overwriting it with the point value 0.
	 */
	@Test
	fun shitterKeepsAMagicFiveHandOffTheFloor ()
	{
		val h = Hand(null)
		h.addCard (special (0, Card.ID_YELLOW_0_SHITTER, 0))
		h.addCard (special (1, Card.ID_RED_5_MAGIC, -5))

		assertEquals("Magic 5 on its own would score this hand -5", 150, h.calculateValue())
	}

	/**
	 * A lone Shitter scores nothing in step 3, so step 10's floor is the whole
	 * mid-game estimate: 150, and the card keeps the value the AI reads.
	 */
	@Test
	fun loneShitterLiftsTheTotalToItsPseudoValue ()
	{
		val h = Hand(null)
		val shitter = special (0, Card.ID_YELLOW_0_SHITTER, 0)
		h.addCard (shitter)

		assertEquals(150, h.calculateValue())
		assertEquals("the pseudo-value is what ComputerPlayer reads", 150, shitter.getCurrentValue())
	}

	/**
	 * A Shitter beside a F.U. but no Quitter is not a full monty, so it takes
	 * the pseudo-value rather than the 500s. Step 8 doubles the total to 8,
	 * and step 10 still floors it at 150.
	 */
	@Test
	fun shitterSurvivesTheFuckYouDouble ()
	{
		val h = Hand(null)
		val shitter = special (0, Card.ID_YELLOW_0_SHITTER, 0)
		val fuckYou = special (1, Card.ID_BLUE_0_FUCKYOU, 0)
		h.addCard (shitter)
		h.addCard (fuckYou)
		h.addCard (number (Card.COLOR_GREEN, 4))

		assertEquals(150, h.calculateValue())
		assertEquals(150, shitter.getCurrentValue())
		assertEquals("the F.U. took the hand total before it was doubled", 4, fuckYou.getCurrentValue())
	}

	/**
	 * The pseudo-value is a mid-game estimate and never a score: at the end of
	 * a hand Game applies the real penalty, and step 10 skips the floor.
	 */
	@Test
	fun shitterPseudoValueNeverReachesAFinalScore ()
	{
		val h = Hand(null)
		h.addCard (special (0, Card.ID_YELLOW_0_SHITTER, 0))
		h.addCard (number (Card.COLOR_GREEN, 4))

		assertEquals("mid-game the floor applies", 150, h.calculateValue (false))
		assertEquals("a final score is Game's to apply", 4, h.calculateValue (true))
	}

	@Test
	fun shitterDoesNotPullABiggerHandDown ()
	{
		val h = Hand(null)
		h.addCard (special (0, Card.ID_YELLOW_0_SHITTER, 0))
		h.addCard (special (1, Card.ID_GREEN_0, 200))

		assertEquals(200, h.calculateValue())
	}

	/** Game scores a hand with the card about to be played left out. */
	@Test
	fun withoutCardIsLeftOut ()
	{
		val h = Hand(null)
		val redThree = number (Card.COLOR_RED, 3)
		val blueSeven = number (Card.COLOR_BLUE, 7)
		h.addCard (redThree)
		h.addCard (blueSeven)

		assertEquals(3, h.calculateValue (false, blueSeven))
		assertEquals(7, h.calculateValue (false, redThree))
	}

	@Test
	fun withoutCardAlsoSkipsTheSpecialRules ()
	{
		val h = Hand(null)
		val fuckYou = special (0, Card.ID_BLUE_0_FUCKYOU, 0)
		h.addCard (fuckYou)
		h.addCard (number (Card.COLOR_RED, 3))

		assertEquals("excluding the F.U. must stop it doubling the total", 3, h.calculateValue (false, fuckYou))
	}

	/** Step 5: Blue Shield takes the value of the best card alongside it. */
	@Test
	fun blueShieldTakesTheHighestValueInTheHand ()
	{
		val h = Hand(null)
		val shield = special (0, Card.ID_BLUE_2_SHIELD, 0)
		h.addCard (number (Card.COLOR_RED, 3))
		h.addCard (shield)

		assertEquals(3 + 3, h.calculateValue())
		assertEquals(3, shield.getCurrentValue())
	}

	@Test
	fun blueShieldAloneIsWorthNothing ()
	{
		val h = Hand(null)
		val shield = special (0, Card.ID_BLUE_2_SHIELD, 0)
		h.addCard (shield)

		assertEquals(0, h.calculateValue())
		assertEquals(0, shield.getCurrentValue())
	}
}