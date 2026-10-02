package com.smccloud.hotdeath

import android.preference.PreferenceManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Covers novice mode, issue #5: pause after each play and wait for a tap instead
 * of a timed delay, so a beginner can follow the game instead of racing it.
 *
 * The interesting part is not that it waits. It is the two ways waiting can go
 * wrong, and both are tested here rather than reasoned about:
 *
 *  * **A wait that cannot end.** `waitABit` blocks the game thread on a spin of
 *    `Thread.sleep(100)`, and an unbounded one is a shutdown hazard: background the
 *    app mid-pause and the thread sits there for ever. `pausePausesReleasesTheWait`
 *    and `stoppingReleasesTheWait` are the two exits that have to exist, and each
 *    is asserted by releasing a wait that is provably already blocked.
 *  * **A tap that means two things.** The existing tap handler,
 *    `Game.drawPileTapped`, clears the round-start wait *and* tells a human
 *    player they have decided to draw a card. Reusing it for a novice tap would
 *    hand the player a decision they never made. `tableTappedDoesNotDecideADraw`
 *    pins that it does not, and the paired assertion pins that the old handler
 *    still does -- which is what makes the first one mean anything.
 *
 * The waits are run on a background thread because blocking the test thread is
 * the thing under test. Each one waits for `waitingToAdvance()` to go true before
 * asserting it is still blocked, so a test cannot pass by the wait never having
 * started.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoviceModeTest
{
	private lateinit var m_activity: GameActivity
	private lateinit var m_options: GameOptions
	private lateinit var m_game: Game

	@Before
	fun setUp ()
	{
		noviceMode(false)

		m_activity = Robolectric.buildActivity(GameActivity::class.java).get()
		m_options = GameOptions(m_activity)
		m_game = Game(m_activity, m_options)

		// Laid out because promptUser posts to the UI thread and GameTable.Toast
		// positions itself off m_ptMessages, which only onSizeChanged builds.
		val table = GameTable(m_activity, m_game, m_options)
		table.measure(
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, 1080, 1920)
	}

	private fun noviceMode (on: Boolean)
	{
		PreferenceManager.getDefaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.putBoolean("novice_mode", on)
			.commit()
	}

	/** Spins until [cond] holds, so a test cannot pass by the wait never starting. */
	private fun waitUntil (what: String, cond: () -> Boolean)
	{
		for (i in 0 until 100)
		{
			if (cond()) return
			Thread.sleep(20)
		}
		throw AssertionError("timed out waiting for $what")
	}

	/** Runs waitABit on another thread, since blocking is the behaviour. */
	private fun startWait (): Thread
	{
		val t = Thread { m_game.waitABit() }
		t.isDaemon = true
		t.start()
		return t
	}

	// ------------------------------------------------------------ the preference

	@Test
	fun noviceModeIsOffByDefault ()
	{
		PreferenceManager.getDefaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.remove("novice_mode")
			.commit()

		assertFalse (m_options.getNoviceMode())
	}

	@Test
	fun thePreferenceIsReadThrough ()
	{
		noviceMode(true)
		assertTrue (m_options.getNoviceMode())

		noviceMode(false)
		assertFalse (m_options.getNoviceMode())
	}

	// ------------------------------------------------------------------ the wait

	/**
	 * The feature: a tap-until-ready pause instead of a delay.
	 *
	 * Blocked first, then released by the tap -- both halves asserted, because a
	 * test that only checked it returns would pass with `waitABit` doing nothing.
	 */
	@Test
	fun aTapIsWhatReleasesTheWait ()
	{
		noviceMode(true)

		val t = startWait()
		waitUntil("the wait to start") { m_game.waitingToAdvance() }

		assertTrue ("a novice wait should be waiting for a tap",
				Thread.State.WAITING == t.state || Thread.State.TIMED_WAITING == t.state)

		m_game.tableTapped()
		t.join(3000)

		assertFalse ("the tap should have released it", t.isAlive)
		assertFalse (m_game.waitingToAdvance())
	}

	/**
	 * Ordinary mode is untouched: no wait to be released, so a tap is irrelevant
	 * and the call returns on the delay.
	 */
	@Test
	fun anOrdinaryPauseDoesNotWaitForATap ()
	{
		noviceMode(false)

		val t = startWait()
		t.join(3000)

		assertFalse ("an ordinary pause should return on its own", t.isAlive)
		assertFalse (m_game.waitingToAdvance())
	}

	// ------------------------------------------------------- the shutdown hazard

	/**
	 * Backgrounding the app mid-pause must not hang the game thread. This is the
	 * hazard issue #5 raises, and it is the reason the flag is cleared in `pause`.
	 */
	@Test
	fun pauseReleasesTheWait ()
	{
		noviceMode(true)

		val t = startWait()
		waitUntil("the wait to start") { m_game.waitingToAdvance() }

		m_game.pause()
		t.join(3000)

		assertFalse ("pausing must release the game thread", t.isAlive)
	}

	/**
	 * And the other exit. `shutdown` sets m_stopping, the loop checks it every
	 * pass, and a game being torn down must not be held up by a pause.
	 */
	@Test
	fun stoppingReleasesTheWait ()
	{
		noviceMode(true)

		val t = startWait()
		waitUntil("the wait to start") { m_game.waitingToAdvance() }

		// First call only raises the flag, which is what the loop watches for.
		m_game.shutdown()
		t.join(3000)

		assertFalse ("shutdown must release the game thread", t.isAlive)
	}

	/**
	 * A very fast game asks for no pause at all, and that beats a tap: someone who
	 * has set Very fast is not asking to stop at every play.
	 *
	 * Without this ordering, novice mode would slow down the fastest setting --
	 * the one setting whose entire purpose is to not wait.
	 */
	@Test
	fun veryFastBeatsNoviceMode ()
	{
		noviceMode(true)
		m_game.setFastForward(true)

		val t = startWait()
		t.join(3000)

		assertFalse ("very fast should not wait for a tap", t.isAlive)
	}

	// ------------------------------------------------ the tap means one thing

	/**
	 * The trap. `drawPileTapped` clears the round-start wait and then tells a human
	 * player they have decided to draw a card. If the novice tap went through it, a
	 * beginner tapping to continue would be queueing a draw they never chose.
	 */
	@Test
	fun tableTappedDoesNotDecideADraw ()
	{
		noviceMode(true)

		val human = HumanPlayer(m_game, m_options)
		human.setSeat(Game.SEAT_SOUTH)
		setField(m_game, "m_currPlayer", human)

		m_game.tableTapped()

		assertFalse ("a novice tap is not a draw decision", human.getWantsToDraw())
	}

	/**
	 * The other half of that pair: the draw-pile tap still does decide a draw.
	 *
	 * Without this, `tableTappedDoesNotDecideADraw` would pass on a `tableTapped`
	 * that had been wired to nothing at all.
	 */
	@Test
	fun aDrawPileTapStillDecidesADraw ()
	{
		noviceMode(true)

		val human = HumanPlayer(m_game, m_options)
		human.setSeat(Game.SEAT_SOUTH)
		setField(m_game, "m_currPlayer", human)

		m_game.drawPileTapped()

		assertTrue ("tapping the draw pile is still how you draw",
				human.getWantsToDraw())
	}

	/**
	 * Why `tableTapped` exists at all: a draw-pile tap does not release a novice
	 * wait on its own.
	 *
	 * `GameTable.onTouchEvent` calls `tableTapped` first and then falls through to
	 * the pile handling, so in the app a draw-pile tap does release it. But the two
	 * are separate calls, and only `tableTapped` touches the novice flag. This pins
	 * that, so a later change which folds them together -- making the draw-pile tap
	 * silently mean "advance" as well as "draw" -- fails here rather than turning
	 * every draw into an advance.
	 */
	@Test
	fun aDrawPileTapAloneDoesNotReleaseTheNoviceWait ()
	{
		noviceMode(true)

		val t = startWait()
		waitUntil("the wait to start") { m_game.waitingToAdvance() }

		m_game.drawPileTapped()
		Thread.sleep(400)

		assertTrue ("a draw-pile tap should not advance on its own", t.isAlive)

		// Unblock it, so a failure here does not leave a thread spinning.
		m_game.tableTapped()
		t.join(3000)
	}

	/**
	 * The two in the order the app calls them: the tap releases the wait, and the
	 * pile tap still decides the draw. One flag for each, so neither cancels the
	 * other.
	 */
	@Test
	fun aTapAdvancesAndStillDecidesTheDraw ()
	{
		noviceMode(true)

		val human = HumanPlayer(m_game, m_options)
		human.setSeat(Game.SEAT_SOUTH)
		setField(m_game, "m_currPlayer", human)

		val t = startWait()
		waitUntil("the wait to start") { m_game.waitingToAdvance() }

		// What onTouchEvent does on an ACTION_UP over the draw pile.
		m_game.tableTapped()
		m_game.drawPileTapped()
		t.join(3000)

		assertFalse ("the tap should have released the wait", t.isAlive)
		assertTrue ("and still decided the draw", human.getWantsToDraw())
	}

	/** Reflection, so a rename fails here rather than as a null under the wait. */
	private fun setField (target: Any, name: String, value: Any)
	{
		var c: Class<*>? = target.javaClass
		while (c != null)
		{
			try
			{
				val f = c.getDeclaredField(name)
				f.isAccessible = true
				f.set(target, value)
				return
			}
			catch (e: NoSuchFieldException)
			{
				c = c.superclass
			}
		}
		throw AssertionError("${target.javaClass.simpleName}.$name is gone or renamed")
	}
}
