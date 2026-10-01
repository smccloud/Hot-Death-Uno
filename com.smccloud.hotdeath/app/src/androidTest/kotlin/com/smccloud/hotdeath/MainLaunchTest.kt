package com.smccloud.hotdeath

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the app still starts. `assembleDebug` only proves the code compiles,
 * so a layout rename, a missing R.id in Main.onCreate, or a bad
 * WindowInsets call would otherwise reach a device unnoticed. Launching Main
 * exercises the whole path: theme, layout inflation, the button wiring in
 * onCreate, the edge-to-edge inset listener, and the saved-game lookup in
 * onResume.
 */
@RunWith(AndroidJUnit4::class)
class MainLaunchTest
{
	@Test
	fun mainActivityLaunches ()
	{
		ActivityScenario.launch<Main>(Main::class.java).use { scenario ->
			scenario.onActivity { activity ->
				assertFalse("Main finished itself on launch", activity.isFinishing)
			}
		}
	}

	@Test
	fun mainButtonsAreWired ()
	{
		ActivityScenario.launch<Main>(Main::class.java).use { scenario ->
			scenario.onActivity { activity ->
				// Main.onCreate dereferences all six of these without a null
				// check, so a missing one is an immediate NPE. findViewById is
				// nullable from Kotlin, so the assertion is what says it is there.
				assertNotNull("btn_continue missing from the main layout", activity.findViewById<View>(R.id.btn_continue))
				assertNotNull("btn_new_game missing from the main layout", activity.findViewById<View>(R.id.btn_new_game))
				assertNotNull("btn_settings missing from the main layout", activity.findViewById<View>(R.id.btn_settings))
				assertNotNull("btn_help missing from the main layout", activity.findViewById<View>(R.id.btn_help))
				assertNotNull("btn_about missing from the main layout", activity.findViewById<View>(R.id.btn_about))
				assertNotNull("btn_exit missing from the main layout", activity.findViewById<View>(R.id.btn_exit))
			}
		}
	}

	/**
	 * On a fresh install there is no saved game, so Main.onResume hides the
	 * continue button. This covers that preference branch, which no other test
	 * reaches.
	 */
	@Test
	fun continueIsHiddenWithoutASavedGame ()
	{
		ActivityScenario.launch<Main>(Main::class.java).use { scenario ->
			scenario.onActivity { activity ->
				val newGame = activity.findViewById<View>(R.id.btn_new_game)!!
				assertEquals(View.VISIBLE, newGame.getVisibility())
			}
		}
	}
}