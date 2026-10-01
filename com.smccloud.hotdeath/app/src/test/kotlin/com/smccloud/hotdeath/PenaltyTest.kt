package com.smccloud.hotdeath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers the parts of Penalty that are not the card-stacking case: the three
 * penalty kinds, the card-count setter, the secondary victim, and the JSON
 * sentinels. PenaltyStackTest already covers stacking and reset under the
 * instrumented runner.
 *
 * Plain JVM test, and the players are passed as null exactly as
 * PenaltyStackTest does -- Penalty only stores them, so there is nothing to
 * stub. Serialization is covered in JsonRoundTripTest, because a plain JVM test
 * gets the throwing org.json stub out of android.jar.
 */
class PenaltyTest
{
	/** The green 5 that Game.assessPenalty deals out. */
	private fun greenFive (): Card
	{
		return Card(118, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5)
	}

	@Test
	fun setNumCardsReplacesRatherThanAccumulates ()
	{
		val p = Penalty()

		p.addCards (greenFive(), 4, null, null)
		p.setNumCards (greenFive(), 7, null, null)

		assertEquals("setNumCards must overwrite, not add", 7, p.getNumCards())
		assertEquals(Penalty.PENTYPE_CARD, p.getType())
	}

	@Test
	fun setFaceupMarksThePenaltyKind ()
	{
		val p = Penalty()

		p.setFaceup (greenFive(), null, null)

		assertEquals(Penalty.PENTYPE_FACEUP, p.getType())
		assertNull(p.getSecondaryVictim())
	}

	/**
	 * setEject and setFaceup only change the kind of penalty; neither touches
	 * the card count, so a stale count from an earlier penalty would survive.
	 */
	@Test
	fun ejectLeavesTheCardCountAlone ()
	{
		val p = Penalty()

		p.addCards (greenFive(), 4, null, null)
		p.setEject (greenFive(), null, null)

		assertEquals(Penalty.PENTYPE_EJECT, p.getType())
		assertEquals(4, p.getNumCards())
	}

	@Test
	fun ejectKeepsTheCardThatTriggeredIt ()
	{
		val original = greenFive()
		val p = Penalty()

		p.setEject (original, null, null)

		assertSame(original, p.getOrigCard())
	}

	@Test
	fun aNullCardLeavesTheOriginalInPlace ()
	{
		val original = greenFive()
		val p = Penalty()

		p.setEject (original, null, null)
		p.setFaceup (null, null, null)

		assertSame("a null card must not clear the penalty's card", original, p.getOrigCard())
	}

	@Test
	fun secondaryVictimCanBeClearedWithoutTouchingTheRest ()
	{
		val p = Penalty()
		p.addCards (greenFive(), 2, null, null)

		p.setSecondaryVictim (null)

		assertNull(p.getSecondaryVictim())
		assertEquals("clearing the secondary victim must not reset the penalty", Penalty.PENTYPE_CARD, p.getType())
		assertEquals(2, p.getNumCards())
	}

	@Test
	fun resetClearsTheSecondaryVictim ()
	{
		val p = Penalty()
		p.addCards (greenFive(), 2, null, null)

		p.reset()

		assertNull(p.getSecondaryVictim())
		assertEquals(Penalty.PENTYPE_NONE, p.getType())
		assertEquals(0, p.getNumCards())
		assertNull(p.getOrigCard())
	}
}