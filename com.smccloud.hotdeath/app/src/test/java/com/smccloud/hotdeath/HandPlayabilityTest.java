package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import android.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers the two Hand paths HandTest had to leave alone because they need a real
 * Game: hasValidCards, which is a loop over Game.checkCard, and the isfinal
 * branch that adds a green 3 to the owner's virus penalty.
 *
 * Robolectric for the reason GameOptionsTest gives -- GameOptions reads Prefs,
 * and Prefs needs a Context -- and GameActivity is built with buildActivity().get()
 * rather than create(), so onCreate never stands up a GameTable.
 *
 * checkCard reads three Game fields -- m_currCard, m_currColor and m_penalty --
 * and none of them has a setter. The only code that sets them is startRound(),
 * which draws from the draw pile and calls redrawTable(), so it wants a real
 * GameTable and every card bitmap loaded. They go in by reflection instead. A
 * rename makes these tests fail on the getDeclaredField rather than quietly
 * stop covering anything, which is the trade wanted here: the fields and the
 * rule are coupled on purpose.
 *
 * The cards below are built the way CardDeck builds them (see the wild block at
 * CardDeck.java:170-178 -- every wild, Mystery and Hot Death included, carries
 * VAL_WILD_DRAWFOUR as its value), so nothing here can pass on a value the deck
 * never produces.
 */
@RunWith(RobolectricTestRunner.class)
public class HandPlayabilityTest
{
	private GameActivity m_activity;
	private GameOptions m_options;
	private Game m_game;
	private Player m_player;

	@Before
	public void setUp ()
	{
		m_activity = Robolectric.buildActivity(GameActivity.class).get();
		m_options = new GameOptions(m_activity);
		m_game = new Game(m_activity, m_options);
		m_player = new ComputerPlayer(m_game, m_options);
		standardRules(false);
	}

	// ---------------------------------------------------------------- fixture

	/** Puts a card face up with its own colour current and no penalty running. */
	private void table (Card top)
	{
		setField("m_currCard", top);
		setField("m_currColor", top.getColor());
		setField("m_penalty", new Penalty());
	}

	/** A card is on the table and a penalty is running against the current player. */
	private void underAttack (Card top, Card penaltyFrom)
	{
		setField("m_currCard", top);
		setField("m_currColor", top.getColor());

		Penalty penalty = new Penalty();
		penalty.addCards(penaltyFrom, 2, m_player, m_player);
		setField("m_penalty", penalty);
	}

	private void standardRules (boolean on)
	{
		PreferenceManager.getDefaultSharedPreferences(m_activity)
			.edit()
			.putString("cheat_code", on ? "standardrules" : "")
			.commit();
	}

	private void setField (String name, Object value)
	{
		try
		{
			Field f = Game.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(m_game, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("Game." + name + " is gone or renamed: " + e);
		}
	}

	private Hand hand (Card... cards)
	{
		Hand h = new Hand(m_player);
		for (Card c : cards)
		{
			h.addCard(c);
		}
		return h;
	}

	// ---------------------------------------------------------------- cards

	/** A numbered card. Its ID sits in the green 3 block, well below the specials. */
	private Card numbered (int color, int value)
	{
		return new Card(0, color, value, Card.ID_GREEN_0 + value, value);
	}

	private Card drawFour ()   { return new Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_DRAWFOUR, 50); }
	private Card mysteryWild () { return new Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_MYSTERY, 0); }
	private Card hotDeathWild () { return new Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_HD, 100); }
	private Card shitter ()     { return new Card(0, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0_SHITTER, 0); }
	private Card fuckYou ()    { return new Card(0, Card.COLOR_BLUE, 0, Card.ID_BLUE_0_FUCKYOU, 0); }
	private Card holyDefender () { return new Card(0, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0); }
	private Card aids ()        { return new Card(0, Card.COLOR_GREEN, 3, Card.ID_GREEN_3_AIDS, 3); }
	private Card magicFive ()   { return new Card(0, Card.COLOR_RED, 5, Card.ID_RED_5_MAGIC, -5); }
	private Card sixtyNine ()   { return new Card(0, Card.COLOR_YELLOW, 6, Card.ID_YELLOW_69, 6); }

	// ------------------------------------------------------- hasValidCards

	@Test
	public void aHandWithNoLegalCardIsNotPlayable ()
	{
		table(numbered(Card.COLOR_RED, 7));

		assertFalse ("neither the colour nor the value matches",
			hand(numbered(Card.COLOR_GREEN, 1), numbered(Card.COLOR_BLUE, 3)).hasValidCards(m_game));
	}

	@Test
	public void oneCardOfTheCurrentColourIsEnough ()
	{
		table(numbered(Card.COLOR_RED, 7));

		assertTrue ("the red 9 is playable on a red 7",
			hand(numbered(Card.COLOR_GREEN, 1), numbered(Card.COLOR_RED, 9)).hasValidCards(m_game));
	}

	@Test
	public void oneCardOfTheCurrentValueIsEnough ()
	{
		table(numbered(Card.COLOR_RED, 7));

		assertTrue ("the blue 7 is playable on a red 7",
			hand(numbered(Card.COLOR_GREEN, 1), numbered(Card.COLOR_BLUE, 7)).hasValidCards(m_game));
	}

	@Test
	public void aWildIsPlayableOnAnything ()
	{
		table(numbered(Card.COLOR_RED, 7));

		assertTrue (hand(numbered(Card.COLOR_GREEN, 1), drawFour()).hasValidCards(m_game));
	}

	/**
	 * One hand, two answers, and the only difference is the cheat code. The wild
	 * is on the table with yellow current, and the Shitter in the hand is the
	 * yellow that matches it -- without being playable itself, because a Shitter
	 * in a full hand only goes on the Holy Defender or the Magic 5. That is what
	 * leaves the draw four as the only candidate.
	 */
	@Test
	public void standardRulesRefuseADrawFourTheHandCanMatch ()
	{
		standardRules(true);
		table(mysteryWild());
		setField("m_currColor", Card.COLOR_YELLOW);

		assertFalse (hand(shitter(), numbered(Card.COLOR_GREEN, 1), drawFour()).hasValidCards(m_game));
	}

	@Test
	public void theSameHandIsPlayableWithoutStandardRules ()
	{
		table(mysteryWild());
		setField("m_currColor", Card.COLOR_YELLOW);

		assertTrue ("the draw four is legal once the standard rules are off",
			hand(shitter(), numbered(Card.COLOR_GREEN, 1), drawFour()).hasValidCards(m_game));
	}

	@Test
	public void aLoneShitterIsPlayableOnAnything ()
	{
		table(numbered(Card.COLOR_RED, 7));

		assertTrue ("a hand down to the Shitter can always play it",
			hand(shitter()).hasValidCards(m_game));
	}

	@Test
	public void aShitterInAFullHandOnlyPlaysOnHolyDefenderAndMagicFive ()
	{
		table(numbered(Card.COLOR_RED, 7));
		assertFalse (hand(shitter(), numbered(Card.COLOR_GREEN, 1)).hasValidCards(m_game));

		table(holyDefender());
		assertTrue (hand(shitter(), numbered(Card.COLOR_GREEN, 1)).hasValidCards(m_game));

		table(magicFive());
		assertTrue (hand(shitter(), numbered(Card.COLOR_GREEN, 1)).hasValidCards(m_game));
	}

	@Test
	public void magicFivePlaysOnAnything ()
	{
		table(numbered(Card.COLOR_BLUE, 9));

		assertTrue (hand(numbered(Card.COLOR_GREEN, 1), magicFive()).hasValidCards(m_game));
	}

	@Test
	public void the69LetsSixesAndNinesCrossPlay ()
	{
		table(numbered(Card.COLOR_RED, 9));
		assertTrue ("a blue 6 on a red 9, holding the 69",
			hand(numbered(Card.COLOR_BLUE, 6), sixtyNine()).hasValidCards(m_game));

		table(numbered(Card.COLOR_RED, 6));
		assertTrue ("and the 69 itself on a 6",
			hand(sixtyNine()).hasValidCards(m_game));
	}

	/** hasValidCards only ever asks about cards the hand holds, so the membership
	 *  guard at the top of checkCard is unreachable through it. checkCard is
	 *  package-private, so this asks directly. */
	@Test
	public void checkCardRefusesACardTheHandDoesNotHold ()
	{
		table(numbered(Card.COLOR_RED, 7));
		Hand h = hand(numbered(Card.COLOR_GREEN, 1));

		assertFalse ("a red 9 the hand is not holding, however legal it looks",
			m_game.checkCard(h, numbered(Card.COLOR_RED, 9), false));
	}

	// ---------------------------------------------------------- under attack

	@Test
	public void aPenaltySuspendsTheColourAndValueRules ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), drawFour());

		assertFalse ("a red 9 is legal on a red 7 right up until a draw four lands",
			hand(numbered(Card.COLOR_RED, 9)).hasValidCards(m_game));
	}

	@Test
	public void theThreeDefendersAreTheOnlyCardsPlayableOnAPenalty ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), drawFour());

		assertTrue (hand(fuckYou()).hasValidCards(m_game));
		assertTrue (hand(holyDefender()).hasValidCards(m_game));
		assertTrue (hand(aids()).hasValidCards(m_game));
	}

	@Test
	public void magicFiveDefendsOnlyAgainstTheHotDeathWild ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), hotDeathWild());
		assertTrue ("the Magic 5 is the answer to the Hot Death wild",
			hand(numbered(Card.COLOR_GREEN, 1), magicFive()).hasValidCards(m_game));

		underAttack(numbered(Card.COLOR_RED, 7), drawFour());
		assertFalse ("and to nothing else",
			hand(numbered(Card.COLOR_GREEN, 1), magicFive()).hasValidCards(m_game));
	}

	/**
	 * Stacking draw fours needs the penalty's card to still be the card on the
	 * table -- once a defender is down, no more -- and it is the one rule the
	 * standard-rules cheat takes away. The test has to pass the *same* Card to
	 * both, because checkCard compares the two by identity, not by ID.
	 */
	@Test
	public void aDrawFourStacksOnADrawFourUnlessStandardRules ()
	{
		Card onTable = drawFour();
		underAttack(onTable, onTable);
		setField("m_currColor", Card.COLOR_RED);

		assertTrue (hand(drawFour()).hasValidCards(m_game));

		standardRules(true);
		assertFalse ("standard rules cap the stack at one",
			hand(drawFour()).hasValidCards(m_game));
	}

	/**
	 * A penalty turns checkCard around before it ever reaches the Shitter rules,
	 * so a hand down to one card still cannot throw it. Pinned because it is the
	 * one case where "always playable" is not the answer.
	 */
	@Test
	public void aLoneShitterCannotPlayOntoAPenalty ()
	{
		underAttack(numbered(Card.COLOR_RED, 7), drawFour());

		assertFalse (hand(shitter()).hasValidCards(m_game));
	}

	// --------------------------------------------------- the green 3 infection

	@Test
	public void theAidsCardInfectsOnlyOnTheFinalScore ()
	{
		Hand h = hand(aids());

		h.calculateValue(false);
		assertEquals ("a mid-game estimate must not hand out points", 0, m_player.getVirusPenalty());

		h.calculateValue(true);
		assertEquals ("the real score costs 10", 10, m_player.getVirusPenalty());
	}

	@Test
	public void eachAidsCardCostsAnotherTen ()
	{
		hand(aids(), aids()).calculateValue(true);

		assertEquals ("the penalty is per card, not per hand", 20, m_player.getVirusPenalty());
	}

	@Test
	public void theVirusPenaltyAccumulates ()
	{
		Hand h = hand(aids());

		h.calculateValue(true);
		h.calculateValue(true);

		assertEquals ("10 + 10, it never resets itself", 20, m_player.getVirusPenalty());
	}
}