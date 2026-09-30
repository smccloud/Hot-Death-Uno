package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers Hand: card bookkeeping (add, remove, reset, membership) and
 * calculateValue, the nine-step scoring routine carried over from the original
 * PocketPC build. The scoring rules are the point of this class -- every
 * bastard card changes the total in a way that is not visible from the card's
 * own point value -- so they are exercised one rule at a time.
 *
 * Plain JVM test. calculateValue is only reachable here because the tests below
 * avoid two paths that need a real context: Hand.hasValidCards hands off to
 * Game.checkCard, and the isfinal flag adds to Player.getVirusPenalty when a
 * hand holds the green 3.
 */
public class HandTest
{
	/**
	 * A plain numbered card. Its ID is parked in the ID_GREEN_0 block
	 * (113-122), which is entirely below 152 where every card with special
	 * handling lives, so it can never be mistaken for one of those.
	 */
	private Card number (int color, int value)
	{
		return new Card(value, color, value, Card.ID_GREEN_0 + value, value);
	}

	/** One of the cards calculateValue treats specially. Value 0 keeps it out of the numeral scan. */
	private Card special (int deckIndex, int id, int pointValue)
	{
		return new Card(deckIndex, Card.COLOR_RED, 0, id, pointValue);
	}

	// ---------------------------------------------------------------- bookkeeping

	@Test
	public void newHandIsEmpty ()
	{
		Hand h = new Hand(null);

		assertEquals (0, h.getNumCards());
		assertNull (h.getCard(0));
	}

	@Test
	public void addCardClaimsTheCard ()
	{
		Hand h = new Hand(null);
		Card c = number (Card.COLOR_RED, 3);

		h.addCard (c);

		assertEquals (1, h.getNumCards());
		assertSame (h, c.getHand());
	}

	@Test
	public void cardsKeepTheirInsertionOrder ()
	{
		Hand h = new Hand(null);
		Card a = number (Card.COLOR_RED, 3);
		Card b = number (Card.COLOR_BLUE, 7);
		Card c = number (Card.COLOR_YELLOW, 2);

		h.addCard (a);
		h.addCard (b);
		h.addCard (c);

		assertSame (a, h.getCard(0));
		assertSame (b, h.getCard(1));
		assertSame (c, h.getCard(2));
	}

	/** getCard is bounds-checked because GameTable indexes it straight from a tap. */
	@Test
	public void getCardIsNullOutsideTheHand ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 3));

		assertNull (h.getCard(-1));
		assertNull (h.getCard(1));
		assertNull (h.getCard(Game.MAX_NUM_CARDS));
	}

	/**
	 * removeCard shifts everything after the hole down rather than nulling one
	 * slot, so the remaining cards have to stay contiguous and in order.
	 */
	@Test
	public void removeCardShiftsTheRemainingCardsDown ()
	{
		Hand h = new Hand(null);
		Card a = number (Card.COLOR_RED, 3);
		Card b = number (Card.COLOR_BLUE, 7);
		Card c = number (Card.COLOR_YELLOW, 2);
		h.addCard (a);
		h.addCard (b);
		h.addCard (c);

		h.removeCard (b);

		assertEquals (2, h.getNumCards());
		assertSame (a, h.getCard(0));
		assertSame (c, h.getCard(1));
	}

	@Test
	public void removingTheOnlyCardEmptiesTheHand ()
	{
		Hand h = new Hand(null);
		Card only = number (Card.COLOR_RED, 3);
		h.addCard (only);

		h.removeCard (only);

		assertEquals (0, h.getNumCards());
		assertNull (h.getCard(0));
	}

	@Test
	public void removeCardReleasesTheBackReference ()
	{
		Hand h = new Hand(null);
		Card c = number (Card.COLOR_RED, 3);
		h.addCard (c);

		h.removeCard (c);

		assertNull ("a card played to the discard pile must no longer claim a hand", c.getHand());
	}

	@Test
	public void removingAnAbsentCardChangesNothing ()
	{
		Hand h = new Hand(null);
		Card held = number (Card.COLOR_RED, 3);
		h.addCard (held);

		h.removeCard (number (Card.COLOR_RED, 3));

		assertEquals (1, h.getNumCards());
		assertSame (held, h.getCard(0));
	}

	@Test
	public void resetDropsEveryCardAndReleasesThem ()
	{
		Hand h = new Hand(null);
		Card a = number (Card.COLOR_RED, 3);
		Card b = number (Card.COLOR_BLUE, 7);
		h.addCard (a);
		h.addCard (b);

		h.reset();

		assertEquals (0, h.getNumCards());
		assertNull (a.getHand());
		assertNull (b.getHand());
	}

	/**
	 * isInHand(Card) is an identity test, not a colour-and-value test. Two
	 * copies of the same card are both playable but only one is actually in
	 * this hand, and GameCardClicked relies on that distinction.
	 */
	@Test
	public void isInHandComparesIdentityNotValue ()
	{
		Hand h = new Hand(null);
		Card held = number (Card.COLOR_RED, 7);
		Card alsoHeld = number (Card.COLOR_RED, 7);
		Card elsewhere = number (Card.COLOR_RED, 7);
		h.addCard (held);
		h.addCard (alsoHeld);

		assertTrue (h.isInHand(held));
		assertTrue (h.isInHand(alsoHeld));
		assertFalse ("two identical cards are not the same card", h.isInHand(elsewhere));
	}

	@Test
	public void isInHandMatchesColorAndValue ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 7));

		assertTrue (h.isInHand(Card.COLOR_RED, 7));
		assertFalse (h.isInHand(Card.COLOR_RED, 8));
		assertFalse (h.isInHand(Card.COLOR_BLUE, 7));
	}

	@Test
	public void hasColorMatchIgnoresValue ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 7));

		assertTrue (h.hasColorMatch(Card.COLOR_RED));
		assertFalse (h.hasColorMatch(Card.COLOR_BLUE));
	}

	@Test
	public void countSuitCountsOneColor ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 1));
		h.addCard (number (Card.COLOR_RED, 2));
		h.addCard (number (Card.COLOR_BLUE, 3));

		assertEquals (2, h.countSuit(Card.COLOR_RED));
		assertEquals (1, h.countSuit(Card.COLOR_BLUE));
		assertEquals (0, h.countSuit(Card.COLOR_YELLOW));
	}

	@Test
	public void setFaceUpAppliesToTheWholeHand ()
	{
		Hand h = new Hand(null);
		Card a = number (Card.COLOR_RED, 3);
		Card b = number (Card.COLOR_BLUE, 7);
		h.addCard (a);
		h.addCard (b);

		h.setFaceUp (true);

		assertTrue (a.getFaceUp());
		assertTrue (b.getFaceUp());
	}

	@Test
	public void replaceCardSwapsTheSlotInPlace ()
	{
		Hand h = new Hand(null);
		Card a = number (Card.COLOR_RED, 3);
		Card b = number (Card.COLOR_BLUE, 7);
		Card replacement = number (Card.COLOR_YELLOW, 9);
		h.addCard (a);
		h.addCard (b);

		h.replaceCard (a, replacement);

		assertSame (replacement, h.getCard(0));
		assertSame (b, h.getCard(1));
	}

	// ---------------------------------------------------------------- lowest / highest

	@Test
	public void getLowestCardStaysInTheRequestedColor ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 9));
		h.addCard (number (Card.COLOR_BLUE, 2));
		h.addCard (number (Card.COLOR_BLUE, 6));

		assertEquals (Card.COLOR_BLUE, h.getLowestCard(Card.COLOR_BLUE).getColor());
		assertEquals (2, h.getLowestCard(Card.COLOR_BLUE).getValue());
	}

	/** A colour of zero is the "don't care" case that ComputerPlayer uses. */
	@Test
	public void getLowestCardWithColourZeroIgnoresColour ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 9));
		h.addCard (number (Card.COLOR_BLUE, 2));

		assertEquals (2, h.getLowestCard(0).getValue());
	}

	@Test
	public void getLowestCardIsNullWhenNoCardMatches ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 9));

		assertNull (h.getLowestCard(Card.COLOR_GREEN));
	}

	@Test
	public void getLowestCardOnAnEmptyHandIsNull ()
	{
		assertNull (new Hand(null).getLowestCard(0));
	}

	@Test
	public void getHighestCardStaysInTheRequestedColor ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 9));
		h.addCard (number (Card.COLOR_BLUE, 2));
		h.addCard (number (Card.COLOR_BLUE, 6));

		assertEquals (6, h.getHighestCard(Card.COLOR_BLUE).getValue());
	}

	@Test
	public void getHighestCardWithColourZeroIgnoresColour ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 9));
		h.addCard (number (Card.COLOR_BLUE, 2));

		assertEquals (9, h.getHighestCard(0).getValue());
	}

	@Test
	public void getHighestNonTrumpSkipsTheGivenColour ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 9));
		h.addCard (number (Card.COLOR_BLUE, 2));
		h.addCard (number (Card.COLOR_GREEN, 6));

		assertEquals (Card.COLOR_GREEN, h.getHighestNonTrump(Card.COLOR_RED).getColor());
		assertEquals (6, h.getHighestNonTrump(Card.COLOR_RED).getValue());
	}

	@Test
	public void getHighestNonTrumpOnAnEmptyHandIsNull ()
	{
		assertNull (new Hand(null).getHighestNonTrump(Card.COLOR_RED));
	}

	// ---------------------------------------------------------------- calculateValue

	@Test
	public void anEmptyHandIsWorthZero ()
	{
		assertEquals (0, new Hand(null).calculateValue());
	}

	@Test
	public void plainCardsJustAddUp ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (number (Card.COLOR_BLUE, 7));

		assertEquals (10, h.calculateValue());
	}

	/** currentValue is how the rest of the app reads a card's worth after scoring. */
	@Test
	public void ordinaryCardsTakeTheirPointValueIntoCurrentValue ()
	{
		Hand h = new Hand(null);
		Card three = number (Card.COLOR_RED, 3);
		Card seven = number (Card.COLOR_BLUE, 7);
		h.addCard (three);
		h.addCard (seven);

		h.calculateValue();

		assertEquals (3, three.getCurrentValue());
		assertEquals (7, seven.getCurrentValue());
	}

	@Test
	public void theFinalFlagDoesNotChangeOrdinaryScoring ()
	{
		Hand h = new Hand(null);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (number (Card.COLOR_BLUE, 7));

		assertEquals ("only the green 3 is affected by the final flag", h.calculateValue (false), h.calculateValue (true));
	}

	/**
	 * Step 4: the Mystery Wild is worth ten times the highest numeral in the
	 * hand, and is then added on top of that numeral's own points.
	 */
	@Test
	public void mysteryWildIsTenTimesTheHighestNumeral ()
	{
		Hand h = new Hand(null);
		Card mystery = special (0, Card.ID_WILD_MYSTERY, 0);
		h.addCard (number (Card.COLOR_BLUE, 7));
		h.addCard (mystery);

		assertEquals (7 + 70, h.calculateValue());
		assertEquals (70, mystery.getCurrentValue());
	}

	@Test
	public void mysteryWildIsTenWithNoNumeralToScale ()
	{
		Hand h = new Hand(null);
		Card mystery = special (0, Card.ID_WILD_MYSTERY, 0);
		h.addCard (mystery);

		assertEquals (10, h.calculateValue());
		assertEquals (10, mystery.getCurrentValue());
	}

	/** Step 6: the 69 overrides whatever the rest of the hand came to. */
	@Test
	public void sixtyNineOverridesTheTotal ()
	{
		Hand h = new Hand(null);
		Card sixtyNine = special (0, Card.ID_YELLOW_69, 6);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (sixtyNine);

		assertEquals (69, h.calculateValue());
		assertEquals ("the 69 absorbs the rest of the hand's points", 66, sixtyNine.getCurrentValue());
	}

	/** Step 7: Magic 5 subtracts. Its -5 point value comes from CardDeck. */
	@Test
	public void magicFiveSubtractsFive ()
	{
		Hand h = new Hand(null);
		Card magic = special (0, Card.ID_RED_5_MAGIC, -5);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (magic);

		assertEquals (-2, h.calculateValue());
		assertEquals (-5, magic.getCurrentValue());
	}

	/**
	 * Step 8: the F.U. doubles the total but scores nothing itself, so
	 * its currentValue is the pre-double figure.
	 */
	@Test
	public void fuckYouDoublesTheTotal ()
	{
		Hand h = new Hand(null);
		Card fuckYou = special (0, Card.ID_BLUE_0_FUCKYOU, 0);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (fuckYou);

		assertEquals (6, h.calculateValue());
		assertEquals (3, fuckYou.getCurrentValue());
	}

	/** Step 9: Holy Defender halves the total, rounding up. */
	@Test
	public void holyDefenderHalvesRoundingUp ()
	{
		Hand h = new Hand(null);
		Card defender = special (0, Card.ID_RED_0_HD, 0);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (defender);

		assertEquals ("(3 + 1) / 2", 2, h.calculateValue());
		assertEquals (-1, defender.getCurrentValue());
	}

	@Test
	public void holyDefenderLeavesAnEvenTotalAlone ()
	{
		Hand h = new Hand(null);
		Card defender = special (0, Card.ID_RED_0_HD, 0);
		h.addCard (number (Card.COLOR_RED, 4));
		h.addCard (defender);

		assertEquals (2, h.calculateValue());
		assertEquals (-2, defender.getCurrentValue());
	}

	/** Step 2: F.U. plus Quitter short-circuits everything to 1000. */
	@Test
	public void theFullMontyIsAThousandPoints ()
	{
		Hand h = new Hand(null);
		Card fuckYou = special (0, Card.ID_BLUE_0_FUCKYOU, 0);
		Card quitter = special (1, Card.ID_GREEN_0_QUITTER, 100);
		h.addCard (fuckYou);
		h.addCard (quitter);

		assertEquals (1000, h.calculateValue());
		assertEquals ("both bastard cards are pushed to 500 so the AI unloads them", 500, fuckYou.getCurrentValue());
		assertEquals (500, quitter.getCurrentValue());
	}

	/**
	 * The three bastard cards are skipped, but ordinary cards in the same hand
	 * are still counted on top of the 1000.
	 */
	@Test
	public void theFullMontyStillCountsTheRestOfTheHand ()
	{
		Hand h = new Hand(null);
		h.addCard (special (0, Card.ID_BLUE_0_FUCKYOU, 0));
		h.addCard (special (1, Card.ID_GREEN_0_QUITTER, 100));
		h.addCard (number (Card.COLOR_RED, 3));

		assertEquals (1003, h.calculateValue());
	}

	/**
	 * Step 10: mid-game only, the Shitter puts a floor under the total. Its
	 * deck point value is 0, so Magic 5 is what can actually push a hand below
	 * that floor -- without it the shitter's pseudo-value of 150 never
	 * survives, because step 3 overwrites currentValue with the point value.
	 */
	@Test
	public void shitterKeepsAMagicFiveHandOffTheFloor ()
	{
		Hand h = new Hand(null);
		h.addCard (special (0, Card.ID_YELLOW_0_SHITTER, 0));
		h.addCard (special (1, Card.ID_RED_5_MAGIC, -5));

		assertEquals ("Magic 5 on its own would score this hand -5", 0, h.calculateValue());
	}

	/**
	 * Documents the gap above: the shitter alone does not lift the total to the
	 * 150 its pseudo-value suggests, because step 3 replaces that pseudo-value
	 * with the card's real point value of 0.
	 */
	@Test
	public void shitterAloneDoesNotRaiseTheTotal ()
	{
		Hand h = new Hand(null);
		Card shitter = special (0, Card.ID_YELLOW_0_SHITTER, 0);
		h.addCard (shitter);

		assertEquals (0, h.calculateValue());
		assertEquals (0, shitter.getCurrentValue());
	}

	@Test
	public void shitterDoesNotPullABiggerHandDown ()
	{
		Hand h = new Hand(null);
		h.addCard (special (0, Card.ID_YELLOW_0_SHITTER, 0));
		h.addCard (special (1, Card.ID_GREEN_0, 200));

		assertEquals (200, h.calculateValue());
	}

	/** Game scores a hand with the card about to be played left out. */
	@Test
	public void withoutCardIsLeftOut ()
	{
		Hand h = new Hand(null);
		Card redThree = number (Card.COLOR_RED, 3);
		Card blueSeven = number (Card.COLOR_BLUE, 7);
		h.addCard (redThree);
		h.addCard (blueSeven);

		assertEquals (3, h.calculateValue (false, blueSeven));
		assertEquals (7, h.calculateValue (false, redThree));
	}

	@Test
	public void withoutCardAlsoSkipsTheSpecialRules ()
	{
		Hand h = new Hand(null);
		Card fuckYou = special (0, Card.ID_BLUE_0_FUCKYOU, 0);
		h.addCard (fuckYou);
		h.addCard (number (Card.COLOR_RED, 3));

		assertEquals ("excluding the F.U. must stop it doubling the total", 3, h.calculateValue (false, fuckYou));
	}

	/** Step 5: Blue Shield takes the value of the best card alongside it. */
	@Test
	public void blueShieldTakesTheHighestValueInTheHand ()
	{
		Hand h = new Hand(null);
		Card shield = special (0, Card.ID_BLUE_2_SHIELD, 0);
		h.addCard (number (Card.COLOR_RED, 3));
		h.addCard (shield);

		assertEquals (3 + 3, h.calculateValue());
		assertEquals (3, shield.getCurrentValue());
	}

	@Test
	public void blueShieldAloneIsWorthNothing ()
	{
		Hand h = new Hand(null);
		Card shield = special (0, Card.ID_BLUE_2_SHIELD, 0);
		h.addCard (shield);

		assertEquals (0, h.calculateValue());
		assertEquals (0, shield.getCurrentValue());
	}
}