package com.smccloud.hotdeath

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.XmlResourceParser
import android.graphics.Insets
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import android.view.View
import android.view.WindowInsets
import org.xmlpull.v1.XmlPullParser

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
// GameOptions.kt is the only thing that calls the get* accessors. It also used
// to be true that nothing outside this file mentioned Prefs at all -- GameActivity
// and Main reached SharedPreferences through PreferenceManager instead, and
// nine test files did the same. They all go through defaultSharedPreferences
// below now, so this class has three callers outside itself and the companion is
// no longer only reachable for the get* accessors. Still no Java, so still no
// @JvmStatic.
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
// restores exactly that: a null still reaches defaultSharedPreferences and still
// throws inside it, which is where the Java threw.
//
// DEPRECATION, and why it is suppressed rather than fixed. Everything the
// settings screen does -- PreferenceActivity, ListPreference, the
// PreferenceScreen walk in initSummary -- comes from android.preference, which
// was deprecated in API 29 and has no framework replacement; the only supported
// one is androidx.preference, and the project keeps zero third-party
// dependencies on purpose (see the edge-to-edge note in README.md for the same
// decision, taken for WindowInsetsCompat). The screen works, it is the only
// settings UI in the app, and it is not going to be removed on API 36. So the
// warnings are suppressed here and the reason is written down, which is the
// honest alternative to pretending the API is current.
//
// What *was* fixed here is the part that had a framework replacement:
// PreferenceManager.getDefaultSharedPreferences and setDefaultValues are
// deprecated in API 29 and both are reimplemented below against plain
// Context.getSharedPreferences. That removed 33 of the 40 deprecation warnings
// this file produced and, more to the point, left the deprecated surface down to
// the preference widget classes alone.
//
// OVERRIDE_DEPRECATION is the second half of the same story: onCreate and
// onResume override PreferenceActivity's, so the compiler also reports the
// override itself as un-annotated. Both diagnostics are the one fact -- this
// screen is built on a deprecated API on purpose.
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class Prefs : PreferenceActivity(), OnSharedPreferenceChangeListener

{
	// LOTS of code here to show ListPreference and EditTextPreference values
	// in the summary space

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		addPreferencesFromResource(R.xml.preferences)
		applyDefaultValues(this, R.xml.preferences)

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
		/**
		 * The suffix PreferenceManager appended to the package name to get the
		 * default preferences file.
		 *
		 * Kept as a literal rather than derived, because getting it wrong is
		 * silent: a different name is a different, empty file, so every setting a
		 * user ever changed would read back as its default and the saved game
		 * would vanish. This is the name the framework has used since API 1 and
		 * still uses for the screen above, which reads and writes the same file.
		 */
		private const val PREFS_FILE_SUFFIX = "_preferences"

		/**
		 * The attribute namespace and the two attribute names read below.
		 *
		 * Spelled as names rather than as `android.R.attr.key`, which reads
		 * better but only works on a device. `XmlResourceParser` has two overloads
		 * for an attribute lookup -- by resource id and by (namespace, name) --
		 * and Robolectric implements the id form wrongly: it uses the argument as
		 * an *index into the current element's attribute list* and throws
		 * IndexOutOfBoundsException for anything else, which every `android.R.attr`
		 * constant is. `getAttributeBooleanValue(int, boolean)` swallows that
		 * exception and hands back the default instead, so the symptom would be a
		 * checkbox quietly seeded false no matter what the XML says. The
		 * (namespace, name) form is the one both implement correctly, and on a
		 * device the two are equivalent anyway -- the id form looks the id up in
		 * this namespace and ends up here.
		 */
		private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
		private const val ATTR_KEY = "key"
		private const val ATTR_DEFAULT_VALUE = "defaultValue"

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


		/**
		 * The app's default SharedPreferences, the replacement for the deprecated
		 * PreferenceManager.getDefaultSharedPreferences.
		 *
		 * Same file, so this is a drop-in for every reader in the app and in the
		 * tests: `getDefaultSharedPreferences` was itself a call to
		 * `getSharedPreferences(context.getPackageName() + "_preferences",
		 * MODE_PRIVATE)`, so an existing install keeps every setting it has and
		 * the settings screen -- which goes through the framework's own accessor
		 * -- keeps seeing what this writes.
		 *
		 * Context is nullable for the reason spelled out at the top of the file,
		 * and the `!!` is deliberate: a null throws here, inside this function,
		 * which is where PreferenceManager threw when the parameter was the
		 * platform type. The signature says nothing about it, so a caller passing
		 * a nulled GameActivity still gets an NPE rather than a pref file.
		 */
		fun defaultSharedPreferences (context: Context?): SharedPreferences
		{
			return context!!.getSharedPreferences(
					context.packageName + PREFS_FILE_SUFFIX, Context.MODE_PRIVATE)
		}

		/**
		 * Seeds R.xml.preferences into the default file, the replacement for the
		 * deprecated PreferenceManager.setDefaultValues.
		 *
		 * Walks the XML and writes `android:defaultValue` for every key that is
		 * not already in the file, which is the whole of what setDefaultValues
		 * did. Two details of that method are deliberately not reproduced:
		 *
		 *  * Its `KEY_HAS_SET_DEFAULT_VALUES` marker, the private framework key
		 *    that made `readAgain=false` mean "only ever do this once". The
		 *    contains() check below is that guard, per key rather than for the
		 *    file as a whole, so a value the user changed is left alone exactly
		 *    as the marker left it alone -- and a key added to the XML after
		 *    first launch still gets its default, which the marker would have
		 *    prevented.
		 *  * Its silent return for any preference type it does not recognise.
		 *    Here it is a no-op too, and the types in preferences.xml are
		 *    CheckBoxPreference, ListPreference and EditTextPreference.
		 *
		 * The editor is committed rather than applied, as the framework's was, so
		 * the values are on disk before the screen that is about to be built
		 * reads them.
		 *
		 * internal rather than private because PrefsDefaultValuesTest drives this
		 * directly. It cannot go through onCreate -- Robolectric would have to
		 * inflate the whole preference screen to reach one call -- and the code
		 * being tested is the part that was rewritten, not the widgets around it.
		 */
		internal fun applyDefaultValues (context: Context, resId: Int)
		{
			// defaultSharedPreferences rather than another getSharedPreferences
			// call, so the file name is spelled once in this file and this cannot
			// seed a different one from the readers.
			val prefs = defaultSharedPreferences (context)
			val editor = prefs.edit()
			val parser = context.resources.getXml(resId)
			var seeded = false

			// Every START_TAG at any depth, not just the children of the root:
			// the players' preferences sit inside nested PreferenceScreen and
			// PreferenceCategory elements, and only the leaves carry a key, so
			// recursion is not needed -- a container is skipped for having neither.
			var event = parser.next()
			while (event != XmlPullParser.END_DOCUMENT)
			{
				if (event == XmlPullParser.START_TAG)
				{
					seeded = seedDefaultValue (parser, editor, prefs) || seeded
				}
				event = parser.next()
			}
			parser.close()

			if (seeded)
			{
				editor.commit()
			}
		}

		/**
		 * Writes one preference's declared default into the editor, and reports
		 * whether it wrote anything.
		 *
		 * Three ways of doing nothing, in the order they are checked. A key that is
		 * already in the file is the important one: that is a value the user chose,
		 * and overwriting it every time this screen opened would silently undo
		 * every setting they had ever changed. A missing key or a missing
		 * defaultValue is the case for every PreferenceScreen and PreferenceCategory
		 * in the file.
		 *
		 * [prefs] is passed rather than read from the editor because the check is
		 * against the file as it is, not against what this pass has written.
		 */
		private fun seedDefaultValue (parser: XmlResourceParser,
				editor: SharedPreferences.Editor,
				prefs: SharedPreferences): Boolean
		{
			val key = parser.getAttributeValue(ANDROID_NS, ATTR_KEY) ?: return false
			if (prefs.contains (key))
			{
				return false
			}
			if (parser.getAttributeValue(ANDROID_NS, ATTR_DEFAULT_VALUE) == null)
			{
				return false
			}

			when (parser.name)
			{
				// SwitchPreference is not in preferences.xml. It is here because
				// it is the other boolean preference, and a new one added to the
				// XML later should not silently miss its default.
				"CheckBoxPreference", "SwitchPreference" ->
				{
					editor.putBoolean (key,
							parser.getAttributeBooleanValue (ANDROID_NS, ATTR_DEFAULT_VALUE, false))
					return true
				}
				"ListPreference", "EditTextPreference" ->
				{
					editor.putString (key, parser.getAttributeValue (ANDROID_NS, ATTR_DEFAULT_VALUE))
					return true
				}
			}

			// SeekBarPreference and friends would land here. The framework's own
			// setDefaultValues handled them; this does not, and preferences.xml
			// has none, so a new one has to be added to the when above rather than
			// left to read back as whatever is in the file.
			return false
		}

		fun getGameSpeed (context: Context?): Int
		{
			val s = defaultSharedPreferences (context)
				.getString (OPT_GAME_SPEED, OPT_GAME_SPEED_DEF)
			return Integer.parseInt (s!!)
		}

		fun getTwoDecks (context: Context?): Boolean
		{
			return defaultSharedPreferences (context)
						.getBoolean (OPT_TWO_DECKS, OPT_TWO_DECKS_DEF);
		}

		fun getComputer4th (context: Context?): Boolean
		{
			return defaultSharedPreferences (context)
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
			return defaultSharedPreferences (context)
						.getBoolean (OPT_NOVICE_MODE, OPT_NOVICE_MODE_DEF);
		}

		fun getFaceUp (context: Context?): Boolean
		{
			return defaultSharedPreferences (context)
						.getBoolean (OPT_FACE_UP, OPT_FACE_UP_DEF);
		}

		fun getCheatLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_CHEAT_LEVEL, OPT_CHEAT_LEVEL_DEF)!!)
		}

		fun getCheatCode (context: Context?): String
		{
			return defaultSharedPreferences (context)
				.getString (OPT_CHEAT_CODE, OPT_CHEAT_CODE_DEF)!!
		}

		fun getP1SkillLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_P1_SKILL_LEVEL, OPT_P1_SKILL_LEVEL_DEF)!!)
		}

		fun getP1AggressionLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_P1_AGGRESSION_LEVEL, OPT_P1_AGGRESSION_LEVEL_DEF)!!)
		}

		fun getP2SkillLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_P2_SKILL_LEVEL, OPT_P2_SKILL_LEVEL_DEF)!!)
		}

		fun getP2AggressionLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_P2_AGGRESSION_LEVEL, OPT_P2_AGGRESSION_LEVEL_DEF)!!)
		}

		fun getP3SkillLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_P3_SKILL_LEVEL, OPT_P3_SKILL_LEVEL_DEF)!!)
		}

		fun getP3AggressionLevel (context: Context?): Int
		{
			return Integer.parseInt (defaultSharedPreferences (context)
						.getString (OPT_P3_AGGRESSION_LEVEL, OPT_P3_AGGRESSION_LEVEL_DEF)!!)
		}
	}
}
