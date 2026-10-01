package com.smccloud.hotdeath

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
@RunWith(RobolectricTestRunner::class)
class JsonRoundTripTest
{
	private lateinit var m_deck: CardDeck

	@Before
	fun setUp ()
	{
		m_deck = CardDeck()
		m_deck.reset (true, true)
		assertTrue("the test deck must not be empty", m_deck.getNumCards() > 0)
	}

	/**
	 * CardDeck.getCard is nullable because it returns null for an index the deck
	 * does not hold. Every index below is well inside the deck -- the emptiness
	 * assertion in setUp is what says so -- so the read is a non-null one rather
	 * than a guess, and the !! lives here instead of at thirty call sites.
	 */
	private fun deckCard (i: Int): Card
	{
		return m_deck.getCard(i)!!
	}

	// ---------------------------------------------------------------- Card

	@Test
	fun aCardSurvivesTheRoundTrip ()
	{
		val original = Card(42, Card.COLOR_BLUE, 7, Card.ID_BLUE_7, 7, 2.0, 12, 45)
		original.setCurrentValue (123)
		original.setFaceUp (true)

		val restored = Card(original.toJSON())

		assertEquals(42, restored.getDeckIndex())
		assertEquals(Card.COLOR_BLUE, restored.getColor())
		assertEquals(7, restored.getValue())
		assertEquals(Card.ID_BLUE_7, restored.getID())
		assertEquals(7, restored.getPointValue())
		assertEquals(2.0, restored.getPointMultiplier(), 0.0)
		assertEquals(12, restored.getCumulativePenalty())
		assertEquals(45, restored.getHighestCardMatch())
		assertEquals(123, restored.getCurrentValue())
		assertTrue(restored.getFaceUp())
	}

	/**
	 * The only fractional multiplier in the app is the 0.5 Holy Defender
	 * (Card.kt's CardDeck counterpart -- see CardDeck's wild block), and it used
	 * to come back as 0.0. Card's JSONObject constructor read the field with
	 * getInt while toJSON writes it as a double, and getInt truncates toward
	 * zero.
	 *
	 * Nothing reads the multiplier yet, so this changed no score -- it is a guard
	 * for whenever something does. Any fraction truncates rather than just this
	 * one, so the case runs across a spread of them instead of pinning the single
	 * value the deck happens to use.
	 */
	@Test
	fun aFractionalMultiplierSurvivesTheRoundTrip ()
	{
		val multipliers = doubleArrayOf(0.5, 0.25, 1.5, 2.5)

		for (multiplier in multipliers)
		{
			val original = Card(1, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0, multiplier)

			val restored = Card(original.toJSON())

			assertEquals(multiplier, restored.getPointMultiplier(), 0.0)
		}
	}

	@Test
	fun aFaceDownCardStaysFaceDown ()
	{
		val restored = Card(Card(1, Card.COLOR_RED, 2, Card.ID_RED_2, 2).toJSON())

		assertFalse(restored.getFaceUp())
		assertEquals("currentValue is only meaningful once scored", 0, restored.getCurrentValue())
	}

	/**
	 * The hand back-reference is deliberately absent from the JSON. Hand's own
	 * constructor re-establishes it by re-adding each card, so a card that came
	 * back through Card's constructor on its own has no owner yet.
	 *
	 * The null Player here is legal because Penalty's JSON stores a seat index
	 * rather than a player reference, so a restored hand has no player to resolve
	 * one against. It is what the constructor takes, and passing it is the point:
	 * if Hand ever tightens to a non-null Player, this line stops compiling rather
	 * than the seat-index round trip quietly losing its case.
	 */
	@Test
	fun theHandReferenceIsRebuiltByHandNotByCard ()
	{
		val hand = Hand(null)
		val original = Card(3, Card.COLOR_RED, 4, Card.ID_RED_4, 4)
		hand.addCard (original)

		val fromJson = Card(original.toJSON())
		assertNull(fromJson.getHand())

		val restored = Hand(hand.toJSON(), null, m_deck)
		assertSame(restored, restored.getCard(0)!!.getHand())
	}

	// ---------------------------------------------------------------- CardPile

	@Test
	fun aPileKeepsItsOrder ()
	{
		val pile = CardPile()
		pile.addCard (deckCard(0))
		pile.addCard (deckCard(1))
		pile.addCard (deckCard(2))

		val restored = CardPile(pile.toJSON(), m_deck)

		assertEquals(3, restored.getNumCards())
		assertEquals(0, restored.getCard(0)!!.getDeckIndex())
		assertEquals(1, restored.getCard(1)!!.getDeckIndex())
		assertEquals(2, restored.getCard(2)!!.getDeckIndex())
	}

	@Test
	fun anEmptyPileSurvives ()
	{
		val restored = CardPile(CardPile().toJSON(), m_deck)

		assertEquals(0, restored.getNumCards())
		assertNull(restored.drawCard())
	}

	@Test
	fun aPileHandsBackTheSameCardObjects ()
	{
		val pile = CardPile()
		pile.addCard (deckCard(7))

		val restored = CardPile(pile.toJSON(), m_deck)

		assertSame("a pile stores deck indices, not copies", deckCard(7), restored.getCard(0))
	}

	// ---------------------------------------------------------------- Hand

	@Test
	fun aHandKeepsItsCardsInOrder ()
	{
		val hand = Hand(null)
		hand.addCard (deckCard(0))
		hand.addCard (deckCard(5))
		hand.addCard (deckCard(9))

		val restored = Hand(hand.toJSON(), null, m_deck)

		assertEquals(3, restored.getNumCards())
		assertEquals(0, restored.getCard(0)!!.getDeckIndex())
		assertEquals(5, restored.getCard(1)!!.getDeckIndex())
		assertEquals(9, restored.getCard(2)!!.getDeckIndex())
	}

	/**
	 * A hand stores deck indices rather than card state, so restoring one has
	 * to resolve those indices back to the deck's own card objects. If it did
	 * not, a resumed hand would score differently from the one the player left.
	 */
	@Test
	fun aRestoredHandScoresTheSameAsTheOriginal ()
	{
		val hand = Hand(null)
		hand.addCard (deckCard(0))
		hand.addCard (deckCard(1))
		hand.addCard (deckCard(2))

		val restored = Hand(hand.toJSON(), null, m_deck)

		assertSame(deckCard(0), restored.getCard(0))
		assertSame(deckCard(1), restored.getCard(1))
		assertSame(deckCard(2), restored.getCard(2))
		assertEquals(hand.calculateValue(), restored.calculateValue())
	}

	@Test
	fun anEmptyHandSurvives ()
	{
		val restored = Hand(Hand(null).toJSON(), null, m_deck)

		assertEquals(0, restored.getNumCards())
		assertEquals(0, restored.calculateValue())
	}

	// ---------------------------------------------------------------- Penalty

	/**
	 * A playerless penalty is the shape the JSON has when a card is played with
	 * no victim chosen yet. Seat 0 is the "nobody" sentinel and -1 is the "no
	 * card" sentinel for the deck index.
	 */
	@Test
	fun anEmptyPenaltySerialisesToSentinels ()
	{
		val o: JSONObject = Penalty().toJSON()

		assertEquals(Penalty.PENTYPE_NONE, o.getInt("type"))
		assertEquals(0, o.getInt("numcards"))
		assertEquals(0, o.getInt("generatingPlayer"))
		assertEquals(0, o.getInt("victim"))
		assertEquals(0, o.getInt("secondaryVictim"))
		assertEquals(-1, o.getInt("origCard"))
	}

	@Test
	fun aPlayerlessPenaltySurvivesTheRoundTrip ()
	{
		val original = Penalty()
		original.addCards (deckCard(11), 4, null, null)

		val restored = Penalty(original.toJSON(), null, m_deck)

		assertEquals(Penalty.PENTYPE_CARD, restored.getType())
		assertEquals(4, restored.getNumCards())
		assertNull(restored.getGeneratingPlayer())
		assertNull(restored.getVictim())
		assertNull(restored.getSecondaryVictim())
		assertSame(deckCard(11), restored.getOrigCard())
	}

	@Test
	fun anEjectPenaltySurvivesTheRoundTrip ()
	{
		val original = Penalty()
		original.setEject (deckCard(3), null, null)

		val restored = Penalty(original.toJSON(), null, m_deck)

		assertEquals(Penalty.PENTYPE_EJECT, restored.getType())
		assertSame(deckCard(3), restored.getOrigCard())
	}
}