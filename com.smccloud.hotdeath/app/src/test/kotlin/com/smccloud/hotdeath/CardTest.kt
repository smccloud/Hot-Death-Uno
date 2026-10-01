package com.smccloud.hotdeath

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers Card as plain data: the constructor overloads, the accessor pairs, and
 * the mutable per-round state (currentValue, faceUp, hand).
 *
 * Runs as a plain JVM unit test because none of this touches a real Android
 * runtime. The two parts of Card that do -- toString(Context) and the JSON
 * round-trip -- live in CardTextTest and JsonRoundTripTest, which run under
 * Robolectric.
 */
class CardTest
{
	/** The green 5 Game.assessPenalty deals with, so the numbers here match the app. */
	private fun greenFive (): Card
	{
		return Card(118, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5)
	}

	@Test
	fun constructorStoresEveryField ()
	{
		val c = greenFive()

		assertEquals(118, c.getDeckIndex())
		assertEquals(Card.COLOR_GREEN, c.getColor())
		assertEquals(5, c.getValue())
		assertEquals(Card.ID_GREEN_5, c.getID())
		assertEquals(5, c.getPointValue())
	}

	@Test
	fun aFreshCardIsFaceDownAndUnclaimed ()
	{
		val c = greenFive()

		assertFalse(c.getFaceUp())
		assertNull(c.getHand())
	}

	/**
	 * currentValue starts at zero rather than tracking pointValue: it is
	 * scratch space that Hand.calculateValue overwrites each round, so
	 * asserting it equals pointValue here would be wrong.
	 */
	@Test
	fun currentValueStartsAtZero ()
	{
		val c = greenFive()

		assertEquals(0, c.getCurrentValue())
	}

	@Test
	fun currentValueIsMutable ()
	{
		val c = greenFive()

		c.setCurrentValue (500)

		assertEquals(500, c.getCurrentValue())
	}

	@Test
	fun faceUpIsMutable ()
	{
		val c = greenFive()

		c.setFaceUp (true)
		assertTrue(c.getFaceUp())

		c.setFaceUp (false)
		assertFalse(c.getFaceUp())
	}

	/**
	 * Hand.addCard sets this, and Hand.removeCard clears it, so the rest of
	 * the app can ask a card which hand it is in.
	 */
	@Test
	fun handBackReferenceRoundTrips ()
	{
		// A null Player is legal here: this hand is only ever asked what cards it
		// holds, and Hand.p is optional for exactly that reason. See
		// JsonRoundTripTest, which says the same about the seat-index round trip.
		val h = Hand(null)
		val c = greenFive()

		c.setHand (h)
		assertSame(h, c.getHand())

		c.setHand (null)
		assertNull(c.getHand())
	}

	@Test
	fun multiplierOverloadSetsTheMultiplier ()
	{
		val c = Card(0, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0, 0.5)

		assertEquals(0.5, c.getPointMultiplier(), 0.0)
		assertEquals("the point value must survive the multiplier overload", 0, c.getPointValue())
	}

	@Test
	fun multiplierDefaultsToOne ()
	{
		assertEquals(1.0, greenFive().getPointMultiplier(), 0.0)
	}

	@Test
	fun fullConstructorKeepsThePenaltyAndMatchCounters ()
	{
		val c = Card(7, Card.COLOR_BLUE, 3, Card.ID_BLUE_0, 3, 2.0, 12, 45)

		assertEquals(2.0, c.getPointMultiplier(), 0.0)
		assertEquals(12, c.getCumulativePenalty())
		assertEquals(45, c.getHighestCardMatch())
	}

	@Test
	fun penaltyCountersDefaultToZero ()
	{
		val c = greenFive()

		assertEquals(0, c.getCumulativePenalty())
		assertEquals(0, c.getHighestCardMatch())
	}

	/**
	 * Card carries around eighty hand-written ID_ constants and nothing checks
	 * them at compile time. A duplicated value would make two visually
	 * different cards interchangeable everywhere IDs are switched on, which is
	 * exactly the kind of bug that would otherwise only show up mid-game.
	 */
	@Test
	fun cardIdsAreAllDistinct ()
	{
		val seen = HashSet<Int>()
		var checked = 0

		for (f in Card::class.java.fields)
		{
			if (!f.name.startsWith("ID_")) continue
			if (!Modifier.isStatic (f.modifiers)) continue
			if (f.type != Int::class.javaPrimitiveType) continue

			// getInt(null) on a static field in Java; in Kotlin the same boxed read
			// is get(null), and the constants are all non-null Ints.
			val value = f.get(null) as Int
			assertTrue("duplicate Card ID " + Integer.toHexString (value)
				+ ": " + f.name + " collides with an earlier constant",
				seen.add (value))
			checked++
		}

		assertTrue("expected the whole ID_ table to be scanned", checked > 50)
	}

	/** The same argument as cardIdsAreAllDistinct, for the VAL_ constants. */
	@Test
	fun cardValuesAreAllDistinct ()
	{
		val seen = HashSet<Int>()

		for (f in Card::class.java.fields)
		{
			if (!f.name.startsWith("VAL_")) continue
			if (!Modifier.isStatic (f.modifiers)) continue
			if (f.type != Int::class.javaPrimitiveType) continue

			val value = f.get(null) as Int
			assertTrue("duplicate Card value: " + f.name, seen.add (value))
		}

		assertTrue(seen.size >= 8)
	}

	/**
	 * A plain numbered card, with its ID deliberately parked in the ID_GREEN_0
	 * block (113-122). Every card with special handling has an ID of 152 or
	 * above, so this can never be mistaken for one of them.
	 */
	private fun number (color: Int, value: Int): Card
	{
		return Card(value, color, value, Card.ID_GREEN_0 + value, value)
	}

	@Test
	fun aNumberedCardKeepsItsColor ()
	{
		assertEquals(Card.COLOR_BLUE, number(Card.COLOR_BLUE, 7).getColor())
	}

	@Test
	fun wildCardsUseTheWildColor ()
	{
		val c = Card(0, Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD, 50)

		assertEquals(Card.COLOR_WILD, c.getColor())
		assertEquals(Card.VAL_WILD, c.getValue())
		assertEquals(Card.ID_WILD, c.getID())
	}
}