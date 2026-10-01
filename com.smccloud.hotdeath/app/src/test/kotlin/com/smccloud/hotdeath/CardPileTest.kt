package com.smccloud.hotdeath

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers CardPile, the stack the draw pile and the discard pile are both built
 * from. It is the only place in the app that decides what "the top card" means,
 * and draw order is load-bearing: the discard pile is dealt back out during
 * Game.rolloverDiscardPile, so drawing from the wrong end would reverse play.
 *
 * Plain JVM test -- CardPile reaches android.* through nothing but the
 * Game.MAX_NUM_CARDS constant, which the compiler inlines, so Game is never
 * even loaded.
 */
class CardPileTest
{
	/** A card distinguishable only by its deck index, which is all CardPile reads. */
	private fun card (deckIndex: Int): Card
	{
		return Card(deckIndex, Card.COLOR_BLUE, deckIndex % 10, Card.ID_BLUE_0, deckIndex % 10)
	}

	private fun pileOf (n: Int): CardPile
	{
		val pile = CardPile()
		for (i in 0 until n)
		{
			pile.addCard (card(i))
		}
		return pile
	}

	@Test
	fun newPileIsEmpty ()
	{
		val pile = CardPile()

		assertEquals(0, pile.getNumCards())
	}

	/**
	 * Game.drawCardTapped calls this once the pile runs dry and the pile has
	 * already been rolled over, so it has to return null rather than throw.
	 */
	@Test
	fun drawingFromAnEmptyPileReturnsNull ()
	{
		assertNull(CardPile().drawCard())
	}

	@Test
	fun getCardOnAnEmptyPileIsNull ()
	{
		assertNull(CardPile().getCard(0))
	}

	@Test
	fun addCardAppendsToTheEnd ()
	{
		val pile = CardPile()
		val first = card(1)
		val second = card(2)

		pile.addCard (first)
		pile.addCard (second)

		assertEquals(2, pile.getNumCards())
		assertSame(first, pile.getCard(0))
		assertSame(second, pile.getCard(1))
	}

	@Test
	fun drawCardTakesFromTheEnd ()
	{
		val pile = pileOf (3)

		assertEquals("third added", 2, pile.drawCard()!!.getDeckIndex())
		assertEquals("second added", 1, pile.drawCard()!!.getDeckIndex())
		assertEquals("first added", 0, pile.drawCard()!!.getDeckIndex())
	}

	@Test
	fun drawCardEmptiesThePileCleanly ()
	{
		val pile = pileOf (2)

		pile.drawCard()
		pile.drawCard()

		assertEquals(0, pile.getNumCards())
		assertNull(pile.drawCard())
	}

	/**
	 * drawCard nulls the slot it vacated. A pile is refilled in place by
	 * Game.rolloverDiscardPile rather than rebuilt, so a stale reference left
	 * behind would show up as a card that comes back twice.
	 */
	@Test
	fun drawCardClearsTheVacatedSlot ()
	{
		val pile = pileOf (3)

		pile.drawCard()

		assertNull(pile.getCard(2))
		assertEquals(2, pile.getNumCards())
	}

	@Test
	fun drawingDoesNotDisturbTheCardsBelow ()
	{
		val pile = pileOf (4)

		pile.drawCard()

		// The !!s are the pile's invariant rather than a guess: drawCard only ever
		// clears the slot it vacates, so every slot it left behind is still full.
		assertEquals(0, pile.getCard(0)!!.getDeckIndex())
		assertEquals(1, pile.getCard(1)!!.getDeckIndex())
		assertEquals(2, pile.getCard(2)!!.getDeckIndex())
	}

	@Test
	fun shuffleKeepsEveryCard ()
	{
		val pile = pileOf (50)

		pile.shuffle (5)

		val expected = ArrayList<Int>()
		val actual = ArrayList<Int>()
		for (i in 0 until 50)
		{
			expected.add (i)
		}
		for (i in 0 until pile.getNumCards())
		{
			actual.add (pile.getCard(i)!!.getDeckIndex())
		}
		Collections.sort (expected)
		Collections.sort (actual)

		assertEquals("shuffle must not drop or duplicate a card", expected, actual)
	}

	@Test
	fun shuffleDoesNotChangeTheCount ()
	{
		val pile = pileOf (30)

		pile.shuffle (3)

		assertEquals(30, pile.getNumCards())
	}

	/**
	 * Both shuffle overloads face the whole pile down before permuting it, so a
	 * card cannot stay revealed across a reshuffle.
	 */
	@Test
	fun shuffleTurnsEveryCardFaceDown ()
	{
		val pile = pileOf (10)
		for (i in 0 until pile.getNumCards())
		{
			pile.getCard(i)!!.setFaceUp (true)
		}

		pile.shuffle()

		for (i in 0 until pile.getNumCards())
		{
			assertFalse("card $i is still face up after a shuffle", pile.getCard(i)!!.getFaceUp())
		}
	}

	@Test
	fun shufflingAnEmptyPileIsHarmless ()
	{
		val pile = CardPile()

		pile.shuffle()
		pile.shuffle (5)

		assertEquals(0, pile.getNumCards())
		assertNull(pile.drawCard())
	}
}