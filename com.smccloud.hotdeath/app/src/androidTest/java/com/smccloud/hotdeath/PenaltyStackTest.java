package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Covers how a penalty accumulates when cards are stacked, because the
 * messaging rewrite in TODO item 6 depends on exactly this: the victim draws
 * the running total, and a stacked card must not overwrite the card that
 * started the penalty.
 *
 * Runs instrumented rather than as a JVM unit test only because the Player
 * references are stubbed out here; the JSON round-trip in other Penalty tests
 * needs a real org.json.
 */
@RunWith(AndroidJUnit4.class)
public class PenaltyStackTest
{
	/** A green 5, as used by Game.assessPenalty. */
	private Card greenFive ()
	{
		return new Card(118, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5);
	}

	@Test
	public void freshPenaltyIsEmpty ()
	{
		Penalty p = new Penalty();

		assertEquals (Penalty.PENTYPE_NONE, p.getType());
		assertEquals (0, p.getNumCards());
		assertNull (p.getOrigCard());
		assertNull (p.getGeneratingPlayer());
		assertNull (p.getVictim());
	}

	@Test
	public void addCardsSetsTypeAndCount ()
	{
		Penalty p = new Penalty();

		p.addCards (greenFive(), 4, null, null);

		assertEquals (Penalty.PENTYPE_CARD, p.getType());
		assertEquals (4, p.getNumCards());
		assertNotNull (p.getOrigCard());
		assertEquals (Card.COLOR_GREEN, p.getOrigCard().getColor());
	}

	@Test
	public void stackingAccumulatesTheCount ()
	{
		Penalty p = new Penalty();

		p.addCards (greenFive(), 4, null, null);
		p.addCards (null, 8, null, null);

		assertEquals (12, p.getNumCards());
	}

	/**
	 * Game calls addCards(null, n, ...) for the second and later cards of a
	 * stack, so the original card has to survive a null argument.
	 */
	@Test
	public void stackingKeepsTheOriginalCard ()
	{
		Card original = greenFive();
		Penalty p = new Penalty();

		p.addCards (original, 4, null, null);
		p.addCards (null, 8, null, null);

		assertSame ("a stacked card must not replace the card that started the penalty", original, p.getOrigCard());
	}

	@Test
	public void resetClearsEverything ()
	{
		Penalty p = new Penalty();
		p.addCards (greenFive(), 4, null, null);

		p.reset();

		assertEquals (Penalty.PENTYPE_NONE, p.getType());
		assertEquals (0, p.getNumCards());
		assertNull (p.getOrigCard());
	}

	@Test
	public void ejectIsNotACardPenalty ()
	{
		Penalty p = new Penalty();

		p.setEject (greenFive(), null, null);

		assertEquals (Penalty.PENTYPE_EJECT, p.getType());
	}
}
