package com.smccloud.hotdeath

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * Covers the malformed-save path: GameActivity stores a gamestate JSON in
 * SharedPreferences and rebuilds the game from it on the next launch, and
 * nothing in the app validates that string before handing it to Game's resuming
 * constructor.
 *
 * Issue #2. The constructor's `catch (JSONException)` was empty, so a
 * well-formed JSON object missing any one key returned a Game that had stopped
 * part-way through the parse -- `m_penalty` and `m_currCard` still null. The
 * resume looked like it had worked, and the crash came later, on the first card
 * played, off one of the 68 `m_penalty!!` sites in Game.kt, with nothing in the
 * stack trace pointing at the save file. Now it logs and rethrows, and
 * GameActivity's existing `if (m_game == null)` fallback starts a new game.
 *
 * Robolectric rather than plain JVM, for the same reason JsonRoundTripTest needs
 * it: org.json on the unit-test classpath is a throwing stub. This is also the
 * only test that can call the resuming constructor at all, because building a
 * Game needs a real GameActivity and GameOptions.
 *
 * The game thread is never started. `Game` extends `Thread`, and the constructor
 * does not start it -- `GameActivity.onCreate` finishes by calling
 * `m_gt.startGameWhenReady()`, so it is `get()` here and never `setup()`, the
 * same trap GameRoundLoopTest documents.
 *
 * The gamestate is built by hand rather than by saving a played game, because the
 * shape of a bad save is the thing under test and building it by hand says
 * exactly which key is missing. The well-formed case goes through
 * [Game.toJSON] so it cannot drift from what the app actually writes.
 */
// PAUSED, which is the Robolectric 4.x default. The unreadable-save toast is
// asserted here, and it is made and shown directly inside GameActivity.onCreate
// rather than posted, so it is on ShadowToast before onCreate returns. Nothing
// else in this test touches the looper: no game thread is ever started.
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class MalformedSaveTest
{
	private lateinit var m_activity: GameActivity
	private lateinit var m_options: GameOptions

	@Before
	fun setUp ()
	{
		// All four seats computer, so nothing in the constructors below waits on a
		// tap. Game reads this preference to decide what goes in seat 0, so it has
		// to be set before the Game is built.
		//
		// "gamestate" is cleared as well, because the tests below read it back to
		// assert what happened to a discarded save, and Robolectric shares one
		// default SharedPreferences across the whole class. Without this, a test
		// that ran after aGoodSaveIsResumedAndKept would start from whatever that
		// one left behind.
		Prefs.defaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.putBoolean("computer_4th", true)
			.putString("cheat_code", "")
			.remove("gamestate")
			.commit()

		m_activity = Robolectric.buildActivity(GameActivity::class.java).get()
		m_options = GameOptions(m_activity)
	}

	/**
	 * A complete gamestate, as [Game.toJSON] writes one. Every key the resuming
	 * constructor reads is here, with the real nested shapes: `deck` and the two
	 * piles are `{cards: [...]}`, `penalty` is the flat object Penalty writes, and
	 * each player carries a `hand` of its own.
	 */
	private fun wellFormedSave (): JSONObject
	{
		val deck = CardDeck()
		deck.reset (true, true)

		// The draw pile holds real cards, so a resumed game has something to
		// account for and the assertion below can fail. An empty pile and an empty
		// penalty are both legal fully-populated values rather than missing ones,
		// which is the distinction under test -- but two empty piles would make the
		// count assertion pass whatever the piles came back as.
		val drawPile = CardPile()
		drawPile.addCard (deck.getCard (0)!!)
		drawPile.addCard (deck.getCard (1)!!)

		val state = JSONObject ()
			.put ("currColor", 0)
			.put ("direction", Game.DIR_CLOCKWISE)
			.put ("cardsPlayed", 0)
			.put ("roundComplete", true)
			.put ("currCard", -1)
			.put ("currPlayer", 1)
			.put ("dealer", 1)

		val players = JSONArray ()
		for (i in 0 until 4)
		{
			// A hand with two cards, stored as deck indices -- which is how a hand
			// really is stored, and what makes Hand's constructor resolve them back
			// against the deck the Game just built. The other fields here are
			// defaults.
			val hand = Hand(null)
			hand.addCard (deck.getCard (2)!!)
			hand.addCard (deck.getCard (3)!!)

			players.put (JSONObject ()
				.put ("hand", hand.toJSON())
				.put ("totalScore", 0)
				.put ("lastScore", 0)
				.put ("lastDrawn", -1)
				.put ("virusPenalty", 0)
				.put ("lastVirusPenalty", 0)
				.put ("active", false))
		}

		return JSONObject ()
			.put ("state", state)
			.put ("deck", deck.toJSON())
			.put ("drawPile", drawPile.toJSON())
			.put ("discardPile", CardPile().toJSON())
			.put ("penalty", Penalty().toJSON())
			.put ("players", players)
	}

	/**
	 * Resume a save and insist it either succeeds or throws. Never returns a
	 * half-built Game: that is the entire claim of issue #2, and it is checked
	 * here rather than at each of the 68 dereference sites.
	 *
	 * A `null` return is the failure this test exists to catch, so it is an
	 * assertion failure and not a way to skip.
	 */
	private fun resumeOrThrow (save: JSONObject): Game
	{
		val game = Game (save, m_activity, m_options)

		// Not `!!`: if the constructor ever returns without building a Penalty again,
		// this says so here instead of the crash moving back to the first card
		// played.
		val penalty = game.getPenalty()
		if (penalty == null)
		{
			fail("the resuming constructor returned a Game with no Penalty; "
					+ "that is the half-built state issue #2 describes")
		}

		return game
	}

	/**
	 * Assert that a save missing `key` is rejected. The message has to name the
	 * key, because that is the log line a bug report would be made from.
	 */
	private fun assertRejected (save: JSONObject, key: String)
	{
		try
		{
			resumeOrThrow (save)
			fail ("a save with no '$key' was accepted")
		}
		catch (e: JSONException)
		{
			assertTrue(
				"the exception should name the missing key '$key', got: ${e.message}",
				e.message?.contains (key) == true)
		}
	}

	// ------------------------------------------------------------- the good path

	/**
	 * The control. If the constructor rejects a complete save the fix has broken
	 * resuming, which no malformed-save test above would catch.
	 */
	@Test
	fun aCompleteSaveStillResumes ()
	{
		val game = resumeOrThrow (wellFormedSave())

		assertEquals ("the draw pile came back with the cards it was saved with",
				2, game.getDrawPile()!!.getNumCards())
		assertEquals (0, game.getDiscardPile()!!.getNumCards())

		assertNotNull ("a resumed game has a dealer", game.getDealer())
		assertNotNull ("a resumed game has a current player", game.getCurrPlayer())

		assertTrue ("the deck came back", game.getDeck()!!.getNumCards() > 0)
		assertEquals ("every seat got a hand", 2, game.getPlayer(0)!!.getHand()!!.getNumCards())
		assertEquals ("and every seat was wired to its neighbours",
				game.getPlayer(0), game.getPlayer(1)!!.getRightOpp())
	}

	/**
	 * Every top-level key the constructor reads, removed one at a time. One case
	 * would only say that *one* key is checked; the empty catch swallowed all of
	 * them identically, so all of them are listed here.
	 */
	@Test
	fun everyMissingKeyIsRejected ()
	{
		for (key in listOf ("state", "deck", "drawPile", "discardPile",
				"penalty", "players"))
		{
			val save = wellFormedSave()
			save.remove (key)

			assertRejected (save, key)
		}
	}

	/**
	 * A key inside `state`, rather than at the top level. The constructor reads
	 * seven of them through the same `getInt`/`getBoolean` pair, so a save missing
	 * `dealer` fails just as late in the parse as one missing `deck` does -- but
	 * only after the deck and both piles have already been built.
	 */
	@Test
	fun aMissingKeyInsideStateIsRejected ()
	{
		for (key in listOf ("currColor", "direction", "cardsPlayed",
				"roundComplete", "currCard", "currPlayer", "dealer"))
		{
			val save = wellFormedSave()
			save.getJSONObject ("state").remove (key)

			assertRejected (save, key)
		}
	}

	/**
	 * A key inside a player. This is the one that got furthest before failing:
	 * all four seats are built before the constructor reads `penalty`, so a save
	 * missing a hand card index had already produced four Player objects with
	 * wires running back to this Game.
	 */
	@Test
	fun aMissingKeyInsideAPlayerIsRejected ()
	{
		for (key in listOf ("totalScore", "lastScore", "lastDrawn",
				"virusPenalty", "lastVirusPenalty", "active"))
		{
			val save = wellFormedSave()
			save.getJSONArray ("players").getJSONObject (0).remove (key)

			assertRejected (save, key)
		}
	}

	/** An empty object is the worst case: nothing at all is present. */
	@Test
	fun anEmptyObjectIsRejected ()
	{
		assertRejected (JSONObject(), "state")
	}

	/**
	 * A `players` array with three entries rather than four. `getJSONObject(3)`
	 * is out of range, so this fails on an index rather than a missing name --
	 * org.json's message here is about the index, not a key, so the assertion is
	 * that it throws at all rather than that the text says something specific.
	 */
	@Test
	fun tooFewPlayersIsRejected ()
	{
		val save = wellFormedSave()
		val players = save.getJSONArray ("players")
		players.remove (3)
		save.put ("players", players)

		try
		{
			resumeOrThrow (save)
			fail ("a save with three players was accepted")
		}
		catch (e: JSONException)
		{
			// Expected; the message names an index for this one, which is why the
			// other cases assert on the text and this one does not.
		}
	}

	// ------------------------------------------------- what the player sees

	/**
	 * The rest of this class tests the constructor directly, because that is where
	 * the half-built Game came from. These two go through `GameActivity.onCreate`
	 * instead, because the other half of the fix is there: the save is discarded,
	 * the player is told, and a new game starts. None of that is reachable from
	 * the constructor.
	 *
	 * The activity is built with `create()`, which is one step short of `setup()`
	 * and deliberately so. `onCreate` is the thing under test and `create()` runs
	 * it, but `setup()` additionally makes the activity visible, which lays the
	 * table out, and GameTable.onSizeChanged then starts the game thread for real.
	 * That thread deals hands, prompts the table, and toasts -- on a background
	 * thread that outlives the test. The next test's teardown then idles the
	 * looper, which runs those stale promptUser tasks and toasts a Toast whose
	 * creating sandbox is long gone, which fails with a NullPointerException out
	 * of Robolectric's own internals rather than anything to do with this code.
	 * Leaving the thread unstarted is both the stable choice and the truthful one:
	 * what is under test is the decision in onCreate, not the round that follows.
	 */

	/**
	 * Runs the activity's onCreate over whatever is in the "gamestate" preference,
	 * with STARTUP_MODE_CONTINUE, and returns the activity.
	 */
	private fun launchContinuing (savedState: String): GameActivity
	{
		// Toasts accumulate in ShadowToast across tests in a class, so the record
		// is cleared here rather than read-and-cleared afterwards: the game thread
		// toasts on its own schedule and a reset afterwards would race it.
		ShadowToast.reset()

		Prefs.defaultSharedPreferences (
				ApplicationProvider.getApplicationContext())
			.edit ()
			.putString ("gamestate", savedState)
			.commit ()

		val intent = android.content.Intent (
				m_activity, GameActivity::class.java)
		intent.putExtra (GameActivity.STARTUP_MODE, GameActivity.STARTUP_MODE_CONTINUE)

		return Robolectric.buildActivity (GameActivity::class.java, intent)
			.create()
			.get()
	}

	/**
	 * Whether a Toast carrying this exact text has been shown since the last
	 * [ShadowToast.reset].
	 *
	 * `getTextOfLatestToast()` would be the wrong tool here. A resumed game starts
	 * a round on the game thread and toasts "Tap the draw pile to start next
	 * hand." straight after onCreate returns, so "the latest toast" is a moving
	 * target -- asserting on it caught a game message once already. `showedToast`
	 * accumulates, so it answers the question actually being asked: did *this*
	 * message appear.
	 */
	private fun sawToast (text: String): Boolean
	{
		return ShadowToast.showedToast (text)
	}

	/** The message the player gets when their save cannot be read. */
	private fun unreadableMessage (): String
	{
		return m_activity.getString (R.string.msg_saved_game_unreadable)
	}

	/**
	 * The player is told. Without this the save disappears and a new game looks
	 * exactly like a resumed one, so there is no sign anything went wrong.
	 */
	@Test
	fun anUnreadableSaveTellsThePlayer ()
	{
		// Missing "penalty": the save parses as an object, so it gets past
		// JSONObject(s) and fails inside the resuming constructor. Both routes to
		// this catch are the same to the player.
		val save = wellFormedSave()
		save.remove ("penalty")

		launchContinuing (save.toString())

		assertTrue ("the player should be told their save was discarded",
				sawToast (unreadableMessage()))
	}

	/**
	 * And gets a fresh game rather than a crash on the first card played. This is
	 * the assertion that says the fallback in `onCreate` is live: without the
	 * rethrow, `m_game` is a half-built Game rather than null, the activity builds
	 * it and hands it to the table anyway, and nothing here fails.
	 *
	 * What distinguishes the two is `getDeck()`. The plain constructor leaves it
	 * null and only `resetRound()` builds one, while the resuming constructor sets
	 * it before the failure -- so null here means the activity took the new-game
	 * path, and non-null would mean it kept the half-built one. That is asserted
	 * directly rather than through the absence of a crash, which nothing in this
	 * test would have caused: the game thread is not running (see
	 * `launchContinuing`), so `checkCard` is never reached.
	 *
	 * `getPenalty()` is null too, which looks like the bug and is not: it is null
	 * in every plain game until `startRound()`, and the same as the Java. The
	 * difference from issue #2 is not whether it is null but whether a Game
	 * carrying a null penalty was allowed to escape a parse that had already
	 * failed.
	 */
	@Test
	fun anUnreadableSaveFallsBackToANewGame ()
	{
		val save = wellFormedSave()
		save.remove ("penalty")

		val game = launchContinuing (save.toString()).getGame()!!

		assertNull ("the plain constructor's deck, so this is a new game "
				+ "rather than the half-built one the save would have made",
				game.getDeck())
	}

	/**
	 * The bad save is dropped rather than left for the next launch to fail on
	 * again. `Main.onResume` only offers "Continue" when "gamestate" is non-empty,
	 * so a string left behind means the player backs out, comes back in, and
	 * gets the same unreadable save and the same toast with nothing new to show
	 * for it.
	 */
	@Test
	fun anUnreadableSaveIsDiscarded ()
	{
		val save = wellFormedSave()
		save.remove ("penalty")

		launchContinuing (save.toString())

		val stored = Prefs.defaultSharedPreferences (
				ApplicationProvider.getApplicationContext())
			.getString ("gamestate", "")
		assertEquals ("the unreadable save should not still be in preferences",
				"", stored)
	}

	/**
	 * A string that is not JSON at all takes the other route -- it fails in
	 * `JSONObject(s)`, before the resuming constructor is ever reached. It has to
	 * be told about and discarded the same way, and this is the only test that
	 * goes through it.
	 */
	@Test
	fun aSaveThatIsNotJsonIsAlsoDiscarded ()
	{
		launchContinuing ("this is not json at all")

		assertTrue ("a non-JSON save should be reported the same way",
				sawToast (unreadableMessage()))

		val stored = Prefs.defaultSharedPreferences (
				ApplicationProvider.getApplicationContext())
			.getString ("gamestate", "")
		assertEquals ("", stored)
	}

	/**
	 * A good save is left alone: no toast, and the preference still holds it, so
	 * the player can rotate the device and come back to the same game. Without
	 * this, a fix that cleared "gamestate" unconditionally would pass everything
	 * above and break resuming.
	 */
	@Test
	fun aGoodSaveIsResumedAndKept ()
	{
		val save = wellFormedSave()

		launchContinuing (save.toString())

		assertFalse ("a resumed game needs no explanation",
				sawToast (unreadableMessage()))

		assertNotNull ("the save should still be in preferences",
				Prefs.defaultSharedPreferences (
						ApplicationProvider.getApplicationContext())
					.getString ("gamestate", null))
	}
}