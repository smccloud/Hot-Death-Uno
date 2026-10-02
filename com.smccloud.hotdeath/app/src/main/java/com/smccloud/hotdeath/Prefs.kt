package com.smccloud.hotdeath

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Insets
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import android.view.View
import android.view.WindowInsets

// The twelve get* accessors and their OPT_* keys live in a companion object.
// GameOptions.kt calls them as Prefs.getGameSpeed(...) and that still works,
// because companion members are reachable through the class name.
//
// They are *not* static methods on the JVM class, though, and that is the one
// difference from the Java worth stating: a plain `fun` in a companion compiles
// to a method on Prefs$Companion, not to a static on Prefs. So any future Java
// caller would fail to compile and would need @JvmStatic.
//
// There is no such caller today, which was checked rather than assumed:
// GameOptions.kt is the only thing that calls them. GameOptionsTest.java and
// GameActivity.kt mention Prefs only in comments and for
// PreferenceManager.getDefaultSharedPreferences, and Main.java uses Prefs.class
// for its Intent and nothing else. So a companion is sufficient, and it keeps
// the file consistent with Card.kt, Game.kt and GameTable.kt.
//
// getCheatCode is declared String rather than String?, deliberately. It is fed
	// straight into GameOptions, which calls .contains() on the result with no check,
	// and GameOptions is not edited by this commit. The `!!` is inside the getter so
	// the NullPointerException lands where the Java's auto-unboxing would have put
	// it rather than at a call site that has no reason to expect null.
	//
	// Every Context below is Context? for the same reason and the same reason it
	// has to be. GameOptions holds `m_ga: GameActivity?` and shutdown() nulls it,
	// and it passes that straight in. While these were Java statics, `Context` was a
	// platform type and silently accepted the null -- build #89 found that out by
	// rejecting 14 call sites the moment it was written as non-null. Nullable here
	// restores exactly that: a null still reaches
	// PreferenceManager.getDefaultSharedPreferences and still throws inside it,
	// which is where the Java threw.
class Prefs : PreferenceActivity(), OnSharedPreferenceChangeListener

{
	// LOTS of code here to show ListPreference and EditTextPreference values
	// in the summary space

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		addPreferencesFromResource(R.xml.preferences)
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

		for (i in 0 until getPreferenceScreen().getPreferenceCount())
		{
			initSummary (getPreferenceScreen().getPreference(i))
		}

		applyEdgeToEdgeInsets();
	}

	/** See GameActivity.applyEdgeToEdgeInsets(): edge-to-edge is mandatory at targetSdk 35+. */
	private fun applyEdgeToEdgeInsets()
	{
		val content = findViewById<View>(android.R.id.content)
		content.setOnApplyWindowInsetsListener (View.OnApplyWindowInsetsListener { v, insets ->
			val bars = insets.getInsets (WindowInsets.Type.systemBars() or
					WindowInsets.Type.displayCutout())
			v.setPadding (bars.left, bars.top, bars.right, bars.bottom)
			WindowInsets.CONSUMED
		})
	}

	override fun onResume()
	{
		super.onResume();
		// Set up a listener whenever a key changes
		getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
	}

	override fun onPause()
	{
		super.onPause();
		// Unregister the listener whenever a key changes
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
	}

	// key is String? because that is how the interface declares it -- the SDK
	// annotates it @Nullable, and Kotlin will not let an override narrow a
	// platform parameter. The Java had no such problem because there was no
	// override, just a public method that happened to match.
	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
	{
		updatePrefSummary(findPreference(key));
	}

	private fun initSummary(p: Preference)
	{
		if (p is PreferenceCategory)
		{
			for (i in 0 until p.preferenceCount)
			{
				initSummary(p.getPreference(i))
			}
		}
		else if (p is PreferenceScreen)
		{
			for (i in 0 until p.preferenceCount)
			{
				initSummary(p.getPreference(i))
			}
		}
		else
		{
			updatePrefSummary(p);
		}

	}

	private fun updatePrefSummary(p: Preference)
	{
		if (p is ListPreference)
		{
			p.setSummary(p.entry)
		}
		if (p is EditTextPreference) {
			p.setSummary(p.text)
		}
	}

	companion object
	{
		private const val OPT_GAME_SPEED = "game_speed"
		private const val OPT_GAME_SPEED_DEF = "1"
		private const val OPT_NOVICE_MODE = "novice_mode"
		private const val OPT_NOVICE_MODE_DEF = false
		private const val OPT_TWO_DECKS = "two_decks"
		private const val OPT_TWO_DECKS_DEF = false
		private const val OPT_COMPUTER_4TH = "computer_4th"
		private const val OPT_COMPUTER_4TH_DEF = false
		private const val OPT_FACE_UP = "face_up"
		private const val OPT_FACE_UP_DEF = false
		private const val OPT_CHEAT_LEVEL = "cheat_level"
		private const val OPT_CHEAT_LEVEL_DEF = "0"
		private const val OPT_CHEAT_CODE = "cheat_code"
		private const val OPT_CHEAT_CODE_DEF = ""

		private const val OPT_P1_SKILL_LEVEL = "p1_skill"
		private const val OPT_P1_SKILL_LEVEL_DEF = "1"
		private const val OPT_P2_SKILL_LEVEL = "p2_skill"
		private const val OPT_P2_SKILL_LEVEL_DEF = "1"
		private const val OPT_P3_SKILL_LEVEL = "p3_skill"
		private const val OPT_P3_SKILL_LEVEL_DEF = "1"

		private const val OPT_P1_AGGRESSION_LEVEL = "p1_aggression"
		private const val OPT_P1_AGGRESSION_LEVEL_DEF = "0"
		private const val OPT_P2_AGGRESSION_LEVEL = "p2_aggression"
		private const val OPT_P2_AGGRESSION_LEVEL_DEF = "0"
		private const val OPT_P3_AGGRESSION_LEVEL = "p3_aggression"
		private const val OPT_P3_AGGRESSION_LEVEL_DEF = "0"


		fun getGameSpeed (context: Context?): Int
		{
			val s = PreferenceManager.getDefaultSharedPreferences(context)
				.getString (OPT_GAME_SPEED, OPT_GAME_SPEED_DEF)
			return Integer.parseInt (s!!)
		}

		fun getTwoDecks (context: Context?): Boolean
		{
			return PreferenceManager.getDefaultSharedPreferences(context)
						.getBoolean (OPT_TWO_DECKS, OPT_TWO_DECKS_DEF);
		}

		fun getComputer4th (context: Context?): Boolean
		{
			return PreferenceManager.getDefaultSharedPreferences(context)
						.getBoolean (OPT_COMPUTER_4TH, OPT_COMPUTER_4TH_DEF);
		}

		/**
		 * Novice mode: pause for a tap instead of a timed delay.
		 *
		 * A separate key rather than another value on game_speed, because
		 * game_speed is a number of milliseconds and two of its readers -- waitABit
		 * and GameTable's toast duration -- want exactly that. A "tap to advance"
		 * value has no duration to give, so it would have to be special-cased in
		 * both, and the toast would get a made-up duration out of it.
		 */
		fun getNoviceMode (context: Context?): Boolean
		{
			return PreferenceManager.getDefaultSharedPreferences(context)
						.getBoolean (OPT_NOVICE_MODE, OPT_NOVICE_MODE_DEF);
		}

		fun getFaceUp (context: Context?): Boolean
		{
			return PreferenceManager.getDefaultSharedPreferences(context)
						.getBoolean (OPT_FACE_UP, OPT_FACE_UP_DEF);
		}

		fun getCheatLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_CHEAT_LEVEL, OPT_CHEAT_LEVEL_DEF)!!)
		}

		fun getCheatCode (context: Context?): String
		{
			return PreferenceManager.getDefaultSharedPreferences(context)
				.getString (OPT_CHEAT_CODE, OPT_CHEAT_CODE_DEF)!!
		}

		fun getP1SkillLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_P1_SKILL_LEVEL, OPT_P1_SKILL_LEVEL_DEF)!!)
		}

		fun getP1AggressionLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_P1_AGGRESSION_LEVEL, OPT_P1_AGGRESSION_LEVEL_DEF)!!)
		}

		fun getP2SkillLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_P2_SKILL_LEVEL, OPT_P2_SKILL_LEVEL_DEF)!!)
		}

		fun getP2AggressionLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_P2_AGGRESSION_LEVEL, OPT_P2_AGGRESSION_LEVEL_DEF)!!)
		}

		fun getP3SkillLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_P3_SKILL_LEVEL, OPT_P3_SKILL_LEVEL_DEF)!!)
		}

		fun getP3AggressionLevel (context: Context?): Int
		{
			return Integer.parseInt (PreferenceManager.getDefaultSharedPreferences(context)
						.getString (OPT_P3_AGGRESSION_LEVEL, OPT_P3_AGGRESSION_LEVEL_DEF)!!)
		}
	}
}
