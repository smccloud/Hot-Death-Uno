package com.smccloud.hotdeath

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers how a penalty accumulates when cards are stacked, because the
 * messaging rewrite in issue #3 depends on exactly this: the victim draws
 * the running total, and a stacked card must not overwrite the card that
 * started the penalty.
 *
 * Kept instrumented as the on-device check for this behaviour. The same class
 * logic is also covered faster on the JVM in src/test: PenaltyTest for the
 * penalty kinds and the card-count setter, JsonRoundTripTest for the JSON.
 */
@RunWith(AndroidJUnit4::class)
class PenaltyStackTest
{
	/** A green 5, as used by Game.assessPenalty. */
	private fun greenFive (): Card
	{
		return Card(118, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5)
	}

	@Test
	fun freshPenaltyIsEmpty ()
	{
		val p = Penalty()

		assertEquals(Penalty.PENTYPE_NONE, p.getType())
		assertEquals(0, p.getNumCards())
		assertNull(p.getOrigCard())
		assertNull(p.getGeneratingPlayer())
		assertNull(p.getVictim())
	}

	@Test
	fun addCardsSetsTypeAndCount ()
	{
		val p = Penalty()

		p.addCards (greenFive(), 4, null, null)

		assertEquals(Penalty.PENTYPE_CARD, p.getType())
		assertEquals(4, p.getNumCards())
		assertNotNull(p.getOrigCard())
		assertEquals(Card.COLOR_GREEN, p.getOrigCard()!!.getColor())
	}

	@Test
	fun stackingAccumulatesTheCount ()
	{
		val p = Penalty()

		p.addCards (greenFive(), 4, null, null)
		p.addCards (null, 8, null, null)

		assertEquals(12, p.getNumCards())
	}

	/**
	 * Game calls addCards(null, n, ...) for the second and later cards of a
	 * stack, so the original card has to survive a null argument.
	 */
	@Test
	fun stackingKeepsTheOriginalCard ()
	{
		val original = greenFive()
		val p = Penalty()

		p.addCards (original, 4, null, null)
		p.addCards (null, 8, null, null)

		assertSame("a stacked card must not replace the card that started the penalty", original, p.getOrigCard())
	}

	@Test
	fun resetClearsEverything ()
	{
		val p = Penalty()
		p.addCards (greenFive(), 4, null, null)

		p.reset()

		assertEquals(Penalty.PENTYPE_NONE, p.getType())
		assertEquals(0, p.getNumCards())
		assertNull(p.getOrigCard())
	}

	@Test
	fun ejectIsNotACardPenalty ()
	{
		val p = Penalty()

		p.setEject (greenFive(), null, null)

		assertEquals(Penalty.PENTYPE_EJECT, p.getType())
	}
}