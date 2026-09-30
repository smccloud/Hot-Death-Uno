package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers the save-and-resume path: GameActivity stores a JSONObject in
 * SharedPreferences under "gamestate" and rebuilds the game from it on the next
 * launch, so a round trip that loses a field shows up as a hand quietly
 * changing between two sessions.
 *
 * Runs under Robolectric rather than as a plain JVM test because org.json is a
 * throwing stub in android.jar on the unit-test classpath. Robolectric supplies
 * the real implementation from its android-all jar, which is also the
 * implementation the app actually runs against.
 *
 * Players are passed as null throughout: Penalty's JSON form stores a seat
 * number rather than a Player, and the constructors only need a Game or Player
 * to turn those numbers back into objects, which the all-zero cases here avoid.
 */
@RunWith(RobolectricTestRunner.class)
public class JsonRoundTripTest
{
	private CardDeck m_deck;

	@Before
	public void setUp ()
	{
		m_deck = new CardDeck();
		m_deck.reset (true, true);
		assertTrue ("the test deck must not be empty", m_deck.getNumCards() > 0);
	}

	// ---------------------------------------------------------------- Card

	@Test
	public void aCardSurvivesTheRoundTrip () throws Exception
	{
		Card original = new Card(42, Card.COLOR_BLUE, 7, Card.ID_BLUE_7, 7, 2.0, 12, 45);
		original.setCurrentValue (123);
		original.setFaceUp (true);

		Card restored = new Card(original.toJSON());

		assertEquals (42, restored.getDeckIndex());
		assertEquals (Card.COLOR_BLUE, restored.getColor());
		assertEquals (7, restored.getValue());
		assertEquals (Card.ID_BLUE_7, restored.getID());
		assertEquals (7, restored.getPointValue());
		assertEquals (2.0, restored.getPointMultiplier(), 0.0);
		assertEquals (12, restored.getCumulativePenalty());
		assertEquals (45, restored.getHighestCardMatch());
		assertEquals (123, restored.getCurrentValue());
		assertTrue (restored.getFaceUp());
	}

	/**
	 * The only fractional multiplier in the app is the 0.5 Holy Defender
	 * (CardDeck.java:66 and :183), and it used to come back as 0.0. Card's
	 * JSONObject constructor read the field with getInt while toJSON writes it as
	 * a double, and getInt truncates toward zero.
	 *
	 * Nothing reads the multiplier yet, so this changed no score -- it is a guard
	 * for whenever something does. Any fraction truncates rather than just this
	 * one, so the case runs across a spread of them instead of pinning the single
	 * value the deck happens to use.
	 */
	@Test
	public void aFractionalMultiplierSurvivesTheRoundTrip () throws Exception
	{
		double[] multipliers = { 0.5, 0.25, 1.5, 2.5 };

		for (double multiplier : multipliers)
		{
			Card original = new Card(1, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0, multiplier);

			Card restored = new Card(original.toJSON());

			assertEquals (multiplier, restored.getPointMultiplier(), 0.0);
		}
	}

	@Test
	public void aFaceDownCardStaysFaceDown () throws Exception
	{
		Card restored = new Card(new Card(1, Card.COLOR_RED, 2, Card.ID_RED_2, 2).toJSON());

		assertFalse (restored.getFaceUp());
		assertEquals ("currentValue is only meaningful once scored", 0, restored.getCurrentValue());
	}

	/**
	 * The hand back-reference is deliberately absent from the JSON. Hand's own
	 * constructor re-establishes it by re-adding each card, so a card that came
	 * back through Card's constructor on its own has no owner yet.
	 */
	@Test
	public void theHandReferenceIsRebuiltByHandNotByCard () throws Exception
	{
		Hand hand = new Hand(null);
		Card original = new Card(3, Card.COLOR_RED, 4, Card.ID_RED_4, 4);
		hand.addCard (original);

		Card fromJson = new Card(original.toJSON());
		assertNull (fromJson.getHand());

		Hand restored = new Hand(hand.toJSON(), null, m_deck);
		assertSame (restored, restored.getCard(0).getHand());
	}

	// ---------------------------------------------------------------- CardPile

	@Test
	public void aPileKeepsItsOrder () throws Exception
	{
		CardPile pile = new CardPile();
		pile.addCard (m_deck.getCard(0));
		pile.addCard (m_deck.getCard(1));
		pile.addCard (m_deck.getCard(2));

		CardPile restored = new CardPile(pile.toJSON(), m_deck);

		assertEquals (3, restored.getNumCards());
		assertEquals (0, restored.getCard(0).getDeckIndex());
		assertEquals (1, restored.getCard(1).getDeckIndex());
		assertEquals (2, restored.getCard(2).getDeckIndex());
	}

	@Test
	public void anEmptyPileSurvives () throws Exception
	{
		CardPile restored = new CardPile(new CardPile().toJSON(), m_deck);

		assertEquals (0, restored.getNumCards());
		assertNull (restored.drawCard());
	}

	@Test
	public void aPileHandsBackTheSameCardObjects () throws Exception
	{
		CardPile pile = new CardPile();
		pile.addCard (m_deck.getCard(7));

		CardPile restored = new CardPile(pile.toJSON(), m_deck);

		assertSame ("a pile stores deck indices, not copies", m_deck.getCard(7), restored.getCard(0));
	}

	// ---------------------------------------------------------------- Hand

	@Test
	public void aHandKeepsItsCardsInOrder () throws Exception
	{
		Hand hand = new Hand(null);
		hand.addCard (m_deck.getCard(0));
		hand.addCard (m_deck.getCard(5));
		hand.addCard (m_deck.getCard(9));

		Hand restored = new Hand(hand.toJSON(), null, m_deck);

		assertEquals (3, restored.getNumCards());
		assertEquals (0, restored.getCard(0).getDeckIndex());
		assertEquals (5, restored.getCard(1).getDeckIndex());
		assertEquals (9, restored.getCard(2).getDeckIndex());
	}

	/**
	 * A hand stores deck indices rather than card state, so restoring one has
	 * to resolve those indices back to the deck's own card objects. If it did
	 * not, a resumed hand would score differently from the one the player left.
	 */
	@Test
	public void aRestoredHandScoresTheSameAsTheOriginal () throws Exception
	{
		Hand hand = new Hand(null);
		hand.addCard (m_deck.getCard(0));
		hand.addCard (m_deck.getCard(1));
		hand.addCard (m_deck.getCard(2));

		Hand restored = new Hand(hand.toJSON(), null, m_deck);

		assertSame (m_deck.getCard(0), restored.getCard(0));
		assertSame (m_deck.getCard(1), restored.getCard(1));
		assertSame (m_deck.getCard(2), restored.getCard(2));
		assertEquals (hand.calculateValue(), restored.calculateValue());
	}

	@Test
	public void anEmptyHandSurvives () throws Exception
	{
		Hand restored = new Hand(new Hand(null).toJSON(), null, m_deck);

		assertEquals (0, restored.getNumCards());
		assertEquals (0, restored.calculateValue());
	}

	// ---------------------------------------------------------------- Penalty

	/**
	 * A playerless penalty is the shape the JSON has when a card is played with
	 * no victim chosen yet. Seat 0 is the "nobody" sentinel and -1 is the "no
	 * card" sentinel for the deck index.
	 */
	@Test
	public void anEmptyPenaltySerialisesToSentinels () throws Exception
	{
		JSONObject o = new Penalty().toJSON();

		assertEquals (Penalty.PENTYPE_NONE, o.getInt("type"));
		assertEquals (0, o.getInt("numcards"));
		assertEquals (0, o.getInt("generatingPlayer"));
		assertEquals (0, o.getInt("victim"));
		assertEquals (0, o.getInt("secondaryVictim"));
		assertEquals (-1, o.getInt("origCard"));
	}

	@Test
	public void aPlayerlessPenaltySurvivesTheRoundTrip () throws Exception
	{
		Penalty original = new Penalty();
		original.addCards (m_deck.getCard(11), 4, null, null);

		Penalty restored = new Penalty(original.toJSON(), null, m_deck);

		assertEquals (Penalty.PENTYPE_CARD, restored.getType());
		assertEquals (4, restored.getNumCards());
		assertNull (restored.getGeneratingPlayer());
		assertNull (restored.getVictim());
		assertNull (restored.getSecondaryVictim());
		assertSame (m_deck.getCard(11), restored.getOrigCard());
	}

	@Test
	public void anEjectPenaltySurvivesTheRoundTrip () throws Exception
	{
		Penalty original = new Penalty();
		original.setEject (m_deck.getCard(3), null, null);

		Penalty restored = new Penalty(original.toJSON(), null, m_deck);

		assertEquals (Penalty.PENTYPE_EJECT, restored.getType());
		assertSame (m_deck.getCard(3), restored.getOrigCard());
	}
}