package com.smccloud.hotdeath

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers `Prefs.applyDefaultValues`, which replaces the deprecated
 * `PreferenceManager.setDefaultValues`.
 *
 * That replacement is the one thing in the settings code that can destroy a
 * user's settings rather than merely fail to compile: it writes into the same
 * SharedPreferences file every setting is read from, so a guard that is missing
 * or subtly wrong does not throw anywhere -- it quietly puts game_speed back to
 * 1 and novice_mode back to false the next time somebody opens Settings. No
 * other test in the suite opens the settings screen, so this is the only place
 * that can catch it.
 *
 * Robolectric rather than a plain JVM test: `applyDefaultValues` calls
 * `resources.getXml`, which needs the compiled resource out of
 * `app/build/intermediates`. The context is the application rather than an
 * activity because that is all the method needs, and because it is what
 * `getPackageName` resolves to either way -- which is the part that has to agree
 * with the file the rest of the app reads.
 *
 * `applyDefaultValues` is called directly rather than through `Prefs.onCreate`,
 * because going through onCreate means inflating the whole preference screen to
 * reach one function call, and the code under test is the seeding rather than
 * the widgets around it.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsDefaultValuesTest
{
	private lateinit var m_context: Context

	@Before
	fun setUp ()
	{
		m_context = ApplicationProvider.getApplicationContext()
		// Robolectric shares one default SharedPreferences across the class, and
		// two of these tests assert that a key is *absent* before seeding it.
		// Without this they would inherit whatever the previous test left.
		Prefs.defaultSharedPreferences(m_context).edit().clear().commit()
	}

	/** Seeds exactly the way Prefs.onCreate does. */
	private fun seed ()
	{
		Prefs.applyDefaultValues(m_context, R.xml.preferences)
	}

	private fun prefs () = Prefs.defaultSharedPreferences(m_context)

	@Test
	fun aFreshInstallGetsEveryDefault ()
	{
		seed()

		// The nine string-valued preferences. Read back with a null default so a
		// missing key cannot pass for a seeded one.
		assertEquals("game_speed", "1", prefs().getString("game_speed", null))
		assertEquals("cheat_level", "0", prefs().getString("cheat_level", null))
		assertEquals("cheat_code", "", prefs().getString("cheat_code", null))
		assertEquals("p1_skill", "1", prefs().getString("p1_skill", null))
		assertEquals("p1_aggression", "0", prefs().getString("p1_aggression", null))
		assertEquals("p2_skill", "1", prefs().getString("p2_skill", null))
		assertEquals("p2_aggression", "0", prefs().getString("p2_aggression", null))
		assertEquals("p3_skill", "1", prefs().getString("p3_skill", null))
		assertEquals("p3_aggression", "0", prefs().getString("p3_aggression", null))

		// The four checkboxes, with the default flipped in the read so that a
		// *missing* key fails. All four are declared "false" in the XML, so this
		// cannot tell true-seeded from false-seeded -- which is the point of
		// the pair of assertions below.
		assertFalse("novice_mode", prefs().getBoolean("novice_mode", true))
		assertFalse("two_decks", prefs().getBoolean("two_decks", true))
		assertFalse("computer_4th", prefs().getBoolean("computer_4th", true))
		assertFalse("face_up", prefs().getBoolean("face_up", true))
	}

	/**
	 * A boolean default has to be written as a boolean.
	 *
	 * Read with `contains` rather than with a value, because the four checkboxes
	 * all default to false and `getBoolean(key, false)` would return false for a
	 * key that was never written at all -- which is also what a string "false"
	 * would read back as. The type is the thing being asserted.
	 */
	@Test
	fun aBooleanDefaultIsStoredAsABoolean ()
	{
		seed()

		for (key in listOf("novice_mode", "two_decks", "computer_4th", "face_up"))
		{
			assertTrue("$key should be in the file", prefs().contains(key))
			assertTrue("$key should be stored as a boolean, not a string",
					prefs().all[key] is Boolean)
		}
	}

	/** The nine string preferences, likewise: a default is stored as a String. */
	@Test
	fun aStringDefaultIsStoredAsAString ()
	{
		seed()

		for (key in listOf("game_speed", "cheat_level", "cheat_code",
				"p1_skill", "p1_aggression", "p2_skill", "p2_aggression",
				"p3_skill", "p3_aggression"))
		{
			assertTrue("$key should be stored as a string, not an integer",
					prefs().all[key] is String)
		}
	}

	/**
	 * The one that matters. Seeding is guarded per key rather than run once per
	 * file, so it is safe to run on every visit to the screen -- which is what
	 * makes this guard the difference between a settings screen and one that
	 * forgets what it was told.
	 */
	@Test
	fun aValueTheUserChangedSurvivesAnotherVisit ()
	{
		prefs().edit()
			.putString("game_speed", "4")
			.putBoolean("novice_mode", true)
			.commit()

		seed()

		assertEquals("game_speed should not be reset to its default",
				"4", prefs().getString("game_speed", null))
		assertTrue("novice_mode should not be reset to its default",
				prefs().getBoolean("novice_mode", false))
	}

	/**
	 * Half of the above: a key the user *unset* -- removed from the file, as when
	 * a preference is deleted from the XML -- comes back with its default rather
	 * than staying absent. That is the difference between a per-key guard and the
	 * framework's one-marker-per-file behaviour, which would have left it absent.
	 */
	@Test
	fun aRemovedKeyComesBackWithItsDefault ()
	{
		seed()
		prefs().edit().remove("game_speed").commit()
		assertFalse("the key should be gone for this test to mean anything",
				prefs().contains("game_speed"))

		seed()

		assertEquals("1", prefs().getString("game_speed", null))
	}

	/** Idempotent, and it adds nothing of its own to the file. */
	@Test
	fun seedingTwiceChangesNothing ()
	{
		seed()
		val first = prefs().all.toMap()

		seed()

		assertEquals("a second pass should write nothing at all",
				first, prefs().all.toMap())
	}

	/**
	 * The complete key set, so a preference added to `preferences.xml` and one
	 * added by accident are both visible. `gamestate` is not here: it belongs to
	 * GameActivity.onPause and to nothing in this screen.
	 */
	@Test
	fun onlyTheKeysTheXmlDeclaresAreWritten ()
	{
		seed()

		assertEquals("the file should hold exactly preferences.xml's keys",
				setOf("game_speed", "novice_mode", "two_decks", "computer_4th",
						"face_up", "cheat_level", "cheat_code",
						"p1_skill", "p1_aggression", "p2_skill", "p2_aggression",
						"p3_skill", "p3_aggression"),
				prefs().all.keys)
	}

	/** The screen's own accessors read what was seeded, without a default of their own. */
	@Test
	fun theAccessorsReadTheSeededValues ()
	{
		seed()

		assertEquals("getGameSpeed", 1, Prefs.getGameSpeed(m_context))
		assertEquals("getCheatLevel", 0, Prefs.getCheatLevel(m_context))
		assertEquals("getCheatCode", "", Prefs.getCheatCode(m_context))
		assertEquals("getP1SkillLevel", 1, Prefs.getP1SkillLevel(m_context))
		assertEquals("getP3AggressionLevel", 0, Prefs.getP3AggressionLevel(m_context))
		assertFalse("getNoviceMode", Prefs.getNoviceMode(m_context))
		assertFalse("getTwoDecks", Prefs.getTwoDecks(m_context))
		assertFalse("getComputer4th", Prefs.getComputer4th(m_context))
		assertFalse("getFaceUp", Prefs.getFaceUp(m_context))
	}
}