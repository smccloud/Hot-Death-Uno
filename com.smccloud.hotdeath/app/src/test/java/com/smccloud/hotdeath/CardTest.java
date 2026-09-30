package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Covers Card as plain data: the constructor overloads, the accessor pairs, and
 * the mutable per-round state (currentValue, faceUp, hand).
 *
 * Runs as a plain JVM unit test because none of this touches a real Android
 * runtime. The two parts of Card that do -- toString(Context) and the JSON
 * round-trip -- live in CardTextTest and JsonRoundTripTest, which run under
 * Robolectric.
 */
public class CardTest
{
	/** The green 5 Game.assessPenalty deals with, so the numbers here match the app. */
	private Card greenFive ()
	{
		return new Card(118, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5);
	}

	@Test
	public void constructorStoresEveryField ()
	{
		Card c = greenFive();

		assertEquals (118, c.getDeckIndex());
		assertEquals (Card.COLOR_GREEN, c.getColor());
		assertEquals (5, c.getValue());
		assertEquals (Card.ID_GREEN_5, c.getID());
		assertEquals (5, c.getPointValue());
	}

	@Test
	public void aFreshCardIsFaceDownAndUnclaimed ()
	{
		Card c = greenFive();

		assertFalse (c.getFaceUp());
		assertNull (c.getHand());
	}

	/**
	 * currentValue starts at zero rather than tracking pointValue: it is
	 * scratch space that Hand.calculateValue overwrites each round, so
	 * asserting it equals pointValue here would be wrong.
	 */
	@Test
	public void currentValueStartsAtZero ()
	{
		Card c = greenFive();

		assertEquals (0, c.getCurrentValue());
	}

	@Test
	public void currentValueIsMutable ()
	{
		Card c = greenFive();

		c.setCurrentValue (500);

		assertEquals (500, c.getCurrentValue());
	}

	@Test
	public void faceUpIsMutable ()
	{
		Card c = greenFive();

		c.setFaceUp (true);
		assertTrue (c.getFaceUp());

		c.setFaceUp (false);
		assertFalse (c.getFaceUp());
	}

	/**
	 * Hand.addCard sets this, and Hand.removeCard clears it, so the rest of
	 * the app can ask a card which hand it is in.
	 */
	@Test
	public void handBackReferenceRoundTrips ()
	{
		Hand h = new Hand(null);
		Card c = greenFive();

		c.setHand (h);
		assertSame (h, c.getHand());

		c.setHand (null);
		assertNull (c.getHand());
	}

	@Test
	public void multiplierOverloadSetsTheMultiplier ()
	{
		Card c = new Card(0, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0, 0.5);

		assertEquals (0.5, c.getPointMultiplier(), 0.0);
		assertEquals ("the point value must survive the multiplier overload", 0, c.getPointValue());
	}

	@Test
	public void multiplierDefaultsToOne ()
	{
		assertEquals (1.0, greenFive().getPointMultiplier(), 0.0);
	}

	@Test
	public void fullConstructorKeepsThePenaltyAndMatchCounters ()
	{
		Card c = new Card(7, Card.COLOR_BLUE, 3, Card.ID_BLUE_0, 3, 2.0, 12, 45);

		assertEquals (2.0, c.getPointMultiplier(), 0.0);
		assertEquals (12, c.getCumulativePenalty());
		assertEquals (45, c.getHighestCardMatch());
	}

	@Test
	public void penaltyCountersDefaultToZero ()
	{
		Card c = greenFive();

		assertEquals (0, c.getCumulativePenalty());
		assertEquals (0, c.getHighestCardMatch());
	}

	/**
	 * Card carries around eighty hand-written ID_ constants and nothing checks
	 * them at compile time. A duplicated value would make two visually
	 * different cards interchangeable everywhere IDs are switched on, which is
	 * exactly the kind of bug that would otherwise only show up mid-game.
	 */
	@Test
	public void cardIdsAreAllDistinct ()
	{
		Set<Integer> seen = new HashSet<Integer>();
		int checked = 0;

		for (Field f : Card.class.getFields())
		{
			if (!f.getName().startsWith("ID_")) continue;
			if (!Modifier.isStatic (f.getModifiers())) continue;
			if (f.getType() != int.class) continue;

			assertTrue ("duplicate Card ID " + Integer.toHexString (f.getInt (null))
				+ ": " + f.getName() + " collides with an earlier constant",
				seen.add (f.getInt (null)));
			checked++;
		}

		assertTrue ("expected the whole ID_ table to be scanned", checked > 50);
	}

	/** The same argument as cardIdsAreAllDistinct, for the VAL_ constants. */
	@Test
	public void cardValuesAreAllDistinct ()
	{
		Set<Integer> seen = new HashSet<Integer>();

		for (Field f : Card.class.getFields())
		{
			if (!f.getName().startsWith("VAL_")) continue;
			if (!Modifier.isStatic (f.getModifiers())) continue;
			if (f.getType() != int.class) continue;

			assertTrue ("duplicate Card value: " + f.getName(), seen.add (f.getInt (null)));
		}

		assertTrue (seen.size() >= 8);
	}

	/**
	 * A plain numbered card, with its ID deliberately parked in the ID_GREEN_0
	 * block (113-122). Every card with special handling has an ID of 152 or
	 * above, so this can never be mistaken for one of them.
	 */
	private Card number (int color, int value)
	{
		return new Card(value, color, value, Card.ID_GREEN_0 + value, value);
	}

	@Test
	public void aNumberedCardKeepsItsColor ()
	{
		assertEquals (Card.COLOR_BLUE, number (Card.COLOR_BLUE, 7).getColor());
	}

	@Test
	public void wildCardsUseTheWildColor ()
	{
		Card c = new Card(0, Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD, 50);

		assertEquals (Card.COLOR_WILD, c.getColor());
		assertEquals (Card.VAL_WILD, c.getValue());
		assertEquals (Card.ID_WILD, c.getID());
	}
}