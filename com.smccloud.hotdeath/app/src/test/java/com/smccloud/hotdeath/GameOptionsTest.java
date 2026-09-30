package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers GameOptions, which has no android.* imports of its own but is not pure
 * logic: every one of its methods forwards to a Prefs.get*(Context) call, and
 * Prefs is an android.preference.PreferenceActivity subclass. That is why these
 * tests need Robolectric and why GameOptions takes a GameActivity instead of a
 * Context -- a plain JVM test cannot construct either.
 *
 * GameActivity is built with buildActivity().get() rather than create(): the
 * controller attaches the Activity (so getSharedPreferences resolves) without
 * running onCreate, which would otherwise stand up a whole Game and GameTable
 * and load every card bitmap.
 */
@RunWith(RobolectricTestRunner.class)
public class GameOptionsTest
{
	private GameActivity m_activity;
	private GameOptions m_options;

	@Before
	public void setUp ()
	{
		m_activity = Robolectric.buildActivity(GameActivity.class).get();
		m_options = new GameOptions(m_activity);
	}

	private void putString (String key, String value)
	{
		PreferenceManager.getDefaultSharedPreferences(m_activity)
			.edit()
			.putString(key, value)
			.commit();
	}

	private void putBoolean (String key, boolean value)
	{
		PreferenceManager.getDefaultSharedPreferences(m_activity)
			.edit()
			.putBoolean(key, value)
			.commit();
	}

	/**
	 * Every default is asserted in one place because they all live in Prefs as
	 * OPT_*_DEF constants, and the first hand a new player deals has to match
	 * the values the settings screen shows as unselected.
	 */
	@Test
	public void defaultsMatchThePrefsConstants ()
	{
		assertEquals (1, m_options.getP1Skill());
		assertEquals (1, m_options.getP2Skill());
		assertEquals (1, m_options.getP3Skill());
		assertEquals (0, m_options.getP1Agg());
		assertEquals (0, m_options.getP2Agg());
		assertEquals (0, m_options.getP3Agg());
		assertEquals (1, m_options.getPauseLength());
		assertEquals (0, m_options.getCheatLevel());
		assertFalse (m_options.getFaceUp());
		assertFalse (m_options.getComputer4th());
		assertTrue (m_options.getOneDeck());
		assertTrue (m_options.getFamilyFriendly());
		assertFalse (m_options.getStandardRules());
	}

	@Test
	public void skillLevelsComeFromPrefs ()
	{
		putString ("p1_skill", "3");
		putString ("p2_skill", "2");
		putString ("p3_skill", "0");

		assertEquals (3, m_options.getP1Skill());
		assertEquals (2, m_options.getP2Skill());
		assertEquals (0, m_options.getP3Skill());
	}

	@Test
	public void aggressionLevelsComeFromPrefs ()
	{
		putString ("p1_aggression", "2");
		putString ("p2_aggression", "1");
		putString ("p3_aggression", "3");

		assertEquals (2, m_options.getP1Agg());
		assertEquals (1, m_options.getP2Agg());
		assertEquals (3, m_options.getP3Agg());
	}

	/** getPauseLength is a legacy name for the game-speed preference. */
	@Test
	public void pauseLengthIsTheGameSpeed ()
	{
		putString ("game_speed", "4");

		assertEquals (4, m_options.getPauseLength());
	}

	@Test
	public void cheatLevelComesFromPrefs ()
	{
		putString ("cheat_level", "2");

		assertEquals (2, m_options.getCheatLevel());
	}

	/** getOneDeck inverts the pref, because the pref is named after two decks. */
	@Test
	public void oneDeckInvertsTheTwoDecksPref ()
	{
		putBoolean ("two_decks", true);
		assertFalse (m_options.getOneDeck());

		putBoolean ("two_decks", false);
		assertTrue (m_options.getOneDeck());
	}

	@Test
	public void faceUpComesFromPrefs ()
	{
		putBoolean ("face_up", true);

		assertTrue (m_options.getFaceUp());
	}

	@Test
	public void computer4thComesFromPrefs ()
	{
		putBoolean ("computer_4th", true);

		assertTrue (m_options.getComputer4th());
	}

	/**
	 * Family friendliness is a cheat code rather than a checkbox, and the check
	 * is a substring match: "originalhotdeath" anywhere in the field switches the
	 * rude card names off.
	 */
	@Test
	public void originalHotDeathTurnsOffFamilyFriendly ()
	{
		putString ("cheat_code", "originalhotdeath");

		assertFalse (m_options.getFamilyFriendly());
	}

	@Test
	public void anUnrelatedCheatCodeLeavesFamilyFriendlyOn ()
	{
		putString ("cheat_code", "somethingelse");

		assertTrue (m_options.getFamilyFriendly());
	}

	@Test
	public void standardRulesTurnsOnStandardRules ()
	{
		putString ("cheat_code", "standardrules");

		assertTrue (m_options.getStandardRules());
	}

	/** The two cheats are independent, so one must not imply the other. */
	@Test
	public void originalHotDeathIsNotStandardRules ()
	{
		putString ("cheat_code", "originalhotdeath");

		assertFalse (m_options.getStandardRules());
		assertFalse (m_options.getFamilyFriendly());
	}

	@Test
	public void standardRulesStillLeavesFamilyFriendlyOn ()
	{
		putString ("cheat_code", "standardrules");

		assertFalse (m_options.getStandardRules());
		assertTrue (m_options.getFamilyFriendly());
	}

	/**
	 * Game and GameTable call shutdown() when the game ends. The options object
	 * keeps no other state, so after shutdown every accessor has to fail loudly
	 * rather than quietly hand back a default that would look like a fresh game.
	 */
	@Test
	public void shutdownLeavesTheOptionsUnusable ()
	{
		m_options.shutdown();

		try
		{
			m_options.getP1Skill();
			fail ("reading a preference after shutdown must not succeed");
		}
		catch (NullPointerException expected)
		{
			// Prefs dereferences the GameActivity it was handed; there is none.
		}
	}
}