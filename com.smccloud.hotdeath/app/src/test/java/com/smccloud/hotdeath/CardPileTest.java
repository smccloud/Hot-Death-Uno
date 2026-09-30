package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

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
public class CardPileTest
{
	/** A card distinguishable only by its deck index, which is all CardPile reads. */
	private Card card (int deckIndex)
	{
		return new Card(deckIndex, Card.COLOR_BLUE, deckIndex % 10, Card.ID_BLUE_0, deckIndex % 10);
	}

	private CardPile pileOf (int n)
	{
		CardPile pile = new CardPile();
		for (int i = 0; i < n; i++)
		{
			pile.addCard (card(i));
		}
		return pile;
	}

	@Test
	public void newPileIsEmpty ()
	{
		CardPile pile = new CardPile();

		assertEquals (0, pile.getNumCards());
	}

	/**
	 * Game.drawCardTapped calls this once the pile runs dry and the pile has
	 * already been rolled over, so it has to return null rather than throw.
	 */
	@Test
	public void drawingFromAnEmptyPileReturnsNull ()
	{
		assertNull (new CardPile().drawCard());
	}

	@Test
	public void getCardOnAnEmptyPileIsNull ()
	{
		assertNull (new CardPile().getCard(0));
	}

	@Test
	public void addCardAppendsToTheEnd ()
	{
		CardPile pile = new CardPile();
		Card first = card(1);
		Card second = card(2);

		pile.addCard (first);
		pile.addCard (second);

		assertEquals (2, pile.getNumCards());
		assertSame (first, pile.getCard(0));
		assertSame (second, pile.getCard(1));
	}

	@Test
	public void drawCardTakesFromTheEnd ()
	{
		CardPile pile = pileOf (3);

		assertEquals ("third added", 2, pile.drawCard().getDeckIndex());
		assertEquals ("second added", 1, pile.drawCard().getDeckIndex());
		assertEquals ("first added", 0, pile.drawCard().getDeckIndex());
	}

	@Test
	public void drawCardEmptiesThePileCleanly ()
	{
		CardPile pile = pileOf (2);

		pile.drawCard();
		pile.drawCard();

		assertEquals (0, pile.getNumCards());
		assertNull (pile.drawCard());
	}

	/**
	 * drawCard nulls the slot it vacated. A pile is refilled in place by
	 * Game.rolloverDiscardPile rather than rebuilt, so a stale reference left
	 * behind would show up as a card that comes back twice.
	 */
	@Test
	public void drawCardClearsTheVacatedSlot ()
	{
		CardPile pile = pileOf (3);

		pile.drawCard();

		assertNull (pile.getCard(2));
		assertEquals (2, pile.getNumCards());
	}

	@Test
	public void drawingDoesNotDisturbTheCardsBelow ()
	{
		CardPile pile = pileOf (4);

		pile.drawCard();

		assertEquals (0, pile.getCard(0).getDeckIndex());
		assertEquals (1, pile.getCard(1).getDeckIndex());
		assertEquals (2, pile.getCard(2).getDeckIndex());
	}

	@Test
	public void shuffleKeepsEveryCard ()
	{
		CardPile pile = pileOf (50);

		pile.shuffle (5);

		List<Integer> expected = new ArrayList<Integer>();
		List<Integer> actual = new ArrayList<Integer>();
		for (int i = 0; i < 50; i++)
		{
			expected.add (Integer.valueOf(i));
		}
		for (int i = 0; i < pile.getNumCards(); i++)
		{
			actual.add (Integer.valueOf(pile.getCard(i).getDeckIndex()));
		}
		Collections.sort (expected);
		Collections.sort (actual);

		assertEquals ("shuffle must not drop or duplicate a card", expected, actual);
	}

	@Test
	public void shuffleDoesNotChangeTheCount ()
	{
		CardPile pile = pileOf (30);

		pile.shuffle (3);

		assertEquals (30, pile.getNumCards());
	}

	/**
	 * Both shuffle overloads face the whole pile down before permuting it, so a
	 * card cannot stay revealed across a reshuffle.
	 */
	@Test
	public void shuffleTurnsEveryCardFaceDown ()
	{
		CardPile pile = pileOf (10);
		for (int i = 0; i < pile.getNumCards(); i++)
		{
			pile.getCard(i).setFaceUp (true);
		}

		pile.shuffle();

		for (int i = 0; i < pile.getNumCards(); i++)
		{
			assertFalse ("card " + i + " is still face up after a shuffle", pile.getCard(i).getFaceUp());
		}
	}

	@Test
	public void shufflingAnEmptyPileIsHarmless ()
	{
		CardPile pile = new CardPile();

		pile.shuffle();
		pile.shuffle (5);

		assertEquals (0, pile.getNumCards());
		assertNull (pile.drawCard());
	}
}