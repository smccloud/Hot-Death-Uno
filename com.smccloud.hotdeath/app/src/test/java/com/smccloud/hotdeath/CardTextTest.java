package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers the one part of Card that needs a real Android runtime: toString, the
 * card-name lookup the whole UI displays through. It reaches R.string and
 * Context, so it cannot be a plain JVM test.
 *
 * Expectations are written as ctx.getString(R.string.x) rather than as literal
 * English. That still verifies the switch picked the right resource -- a red
 * card coming back labelled Blue fails just as loudly -- without the test
 * breaking the first time a translator touches the file.
 */
@RunWith(RobolectricTestRunner.class)
public class CardTextTest
{
	private Context m_context;

	@Before
	public void setUp ()
	{
		m_context = ApplicationProvider.getApplicationContext();
	}

	/**
	 * An unremarkable numbered card reads colour then value. Note the two
	 * spaces: the colour strings in strings.xml all carry a trailing space and
	 * toString joins with another one.
	 */
	@Test
	public void aNumberedCardIsPrefixedWithItsColour ()
	{
		Card c = new Card(0, Card.COLOR_BLUE, 7, Card.ID_BLUE_7, 7);

		assertEquals (m_context.getString (R.string.cardcolor_blue) + " " + 7, c.toString (m_context));
	}

	/**
	 * The ID switch only covers the named cards. Everything else falls through
	 * to the value switch, which is what gives Reverse its name here.
	 */
	@Test
	public void aReverseCardIsNamedByItsValue ()
	{
		Card c = new Card(0, Card.COLOR_GREEN, Card.VAL_R, Card.ID_GREEN_R, 20);

		assertEquals (m_context.getString (R.string.cardval_r), c.toString (m_context));
	}

	@Test
	public void aDrawTwoIsNamedByItsValue ()
	{
		Card c = new Card(0, Card.COLOR_RED, Card.VAL_D, Card.ID_RED_D, 20);

		assertEquals (m_context.getString (R.string.cardval_d), c.toString (m_context));
	}

	@Test
	public void aWildHasItsOwnName ()
	{
		Card c = new Card(0, Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD, 50);

		assertEquals (m_context.getString (R.string.cardval_wild), c.toString (m_context));
	}

	/** A named card returns early, so its colour never appears in the label. */
	@Test
	public void aNamedCardDropsItsColour ()
	{
		Card c = new Card(0, Card.COLOR_RED, 5, Card.ID_RED_5_MAGIC, -5);

		assertEquals (m_context.getString (R.string.cardname_red_5_magic), c.toString (m_context));
	}

	@Test
	public void theMysteryWildIsNamed ()
	{
		Card c = new Card(0, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_MYSTERY, 0);

		assertEquals (m_context.getString (R.string.cardname_wild_mystery), c.toString (m_context));
	}

	/**
	 * That early return is decided by `strValue != ""`, a reference comparison.
	 * It works only because an unmatched ID leaves strValue pointing at the
	 * interned "" literal while every matched case assigns a fresh string from
	 * the resource. Both sides are pinned down here so a rewrite that swaps the
	 * comparison for .equals() -- or reassigns strValue up front -- fails here.
	 */
	@Test
	public void anUnmatchedIdFallsBackToColourAndNumber ()
	{
		Card c = new Card(0, Card.COLOR_YELLOW, 5, Card.ID_YELLOW_5, 5);

		assertEquals (m_context.getString (R.string.cardcolor_yellow) + " " + 5, c.toString (m_context));
	}

	@Test
	public void theFamilyFriendlyFlagSwapsTheRudeName ()
	{
		Card c = new Card(0, Card.COLOR_BLUE, 0, Card.ID_BLUE_0_FUCKYOU, 0, 2.0);

		assertEquals (m_context.getString (R.string.cardname_blue_0_fuck_you_ff), c.toString (m_context, true));
		assertEquals (m_context.getString (R.string.cardname_blue_0_fuck_you), c.toString (m_context, false));
		assertNotEquals (c.toString (m_context, true), c.toString (m_context, false));
	}

	@Test
	public void theVirusCardIsAlsoRenamed ()
	{
		Card c = new Card(0, Card.COLOR_GREEN, 3, Card.ID_GREEN_3_AIDS, 10);

		assertEquals (m_context.getString (R.string.cardname_green_3_aids_ff), c.toString (m_context, true));
		assertEquals (m_context.getString (R.string.cardname_green_3_aids), c.toString (m_context, false));
	}

	@Test
	public void theShitterIsAlsoRenamed ()
	{
		Card c = new Card(0, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0_SHITTER, 0);

		assertEquals (m_context.getString (R.string.cardname_yellow_0_shitter_ff), c.toString (m_context, true));
		assertEquals (m_context.getString (R.string.cardname_yellow_0_shitter), c.toString (m_context, false));
	}

	/** GameTable passes the family-friendly flag through; the short form does not. */
	@Test
	public void theShortOverloadIsFamilyFriendly ()
	{
		Card c = new Card(0, Card.COLOR_BLUE, 0, Card.ID_BLUE_0_FUCKYOU, 0, 2.0);

		assertEquals (c.toString (m_context, true), c.toString (m_context));
	}

	@Test
	public void theNumericValueFallsOutAsWritten ()
	{
		Card c = new Card(0, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0, 0);

		assertEquals (m_context.getString (R.string.cardcolor_yellow) + " " + 0, c.toString (m_context));
	}
}