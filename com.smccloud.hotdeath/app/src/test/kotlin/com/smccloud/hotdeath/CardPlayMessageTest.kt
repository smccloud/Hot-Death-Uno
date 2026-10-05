package com.smccloud.hotdeath

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
import org.robolectric.shadows.ShadowToast

/**
 * Covers the accepted-play toast, issue #4: a rejected card said "That card's no
 * good" and an accepted one said nothing at all, so on a small table a legal play
 * looked exactly like a tap that never registered.
 *
 * This is the first test that watches a message arrive. The ones for the penalty
 * wording (#3) checked what `Game` formats; this one drives `HumanPlayer` and
 * reads what reached the table, because the gap was that nothing was emitted at
 * all, and only an emitted message can show that.
 *
 * The setup is the fiddly part, and each piece is there because something NPEs
 * without it:
 *
 *  * **A laid-out `GameTable`.** The toast goes `promptUser` to `runOnUiThread`
 *    to `GameTable.Toast`, and `Toast` reads `m_ptMessages` to position itself.
 *    `m_ptMessages` is only built in `onSizeChanged`, so the table has to be
 *    measured and laid out by hand. `GameActivity.onCreate` never lays out
 *    anything here either -- see `GameRoundLoopTest`, which does the same thing
 *    and says why.
 *  * **A hand on the player.** `turnDecisionPlayCard` returns immediately if the
 *    card is not in it, so the hand is built before the play is offered.
 *  * **`m_currCard`, `m_currColor` and `m_penalty` set.** `checkCard` reads all
 *    three and dereferences the first two unguarded. Same three fields
 *    `HandPlayabilityTest` sets, for the same reason.
 *  * **`fastForward` left off.** `promptUser` returns immediately when it is on,
 *    which would make this test pass without a message ever being emitted --
 *    exactly the failure this issue is about, reproduced in the test that is
 *    supposed to catch it.
 *
 * `turnDecisionPlayCard` does not start the game thread and does not commit the
 * play; `Game.runRound` does both, and it is not called here. So nothing races
 * the assertions, and the card under test is still in the hand afterwards.
 */
// PAUSED, which is the Robolectric 4.x default. The mode matters here only
// because the toast has to arrive, and it arrives for a reason that has nothing
// to do with the looper: the test thread *is* the main thread under
// Robolectric, and Activity.runOnUiThread runs its Runnable inline when it is
// already on the UI thread rather than posting it. So GameTable.Toast is called
// synchronously from turnDecisionPlayCard, and ShadowToast has it by the time
// the assertion runs.
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardPlayMessageTest
{
	private lateinit var m_game: Game
	private lateinit var m_player: HumanPlayer

	@Before
	fun setUp ()
	{
		ShadowToast.reset()

		Prefs.defaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.putBoolean("computer_4th", true)
			.putString("cheat_code", "")
			.commit()

		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)

		m_game = Game(activity, options)

		// The GameTable constructor is what calls setGameTable, and a table is
		// what gives promptUser somewhere to send the toast. Laid out by hand
		// because nothing here adds it to a parent the way onCreate does.
		val table = GameTable(activity, m_game, options)
		table.measure(
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, 1080, 1920)

		m_player = HumanPlayer(m_game, options)

		// Game's constructors call setSeat on the four seats they build; a player
		// built on its own has seat 0, and seatToString has no case for 0, so the
		// message would come out as " plays Green 5".
		m_player.setSeat(Game.SEAT_SOUTH)

		// A green 5 on the table with no penalty running, which makes a green 5 in
		// the hand the one legal play available.
		val top = Card(1, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5)
		setField(m_game, "m_currCard", top)
		setField(m_game, "m_currColor", Card.COLOR_GREEN)
		setField(m_game, "m_penalty", Penalty())

		val hand = Hand(null)
		hand.addCard(Card(2, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5))
		setField(m_player, "m_hand", hand)

		// Deliberately not fast-forwarded: promptUser returns before doing anything
		// when it is on.
		m_game.setFastForward(false)
	}

	private fun lastToast (): String?
	{
		return ShadowToast.getTextOfLatestToast()
	}

	/**
	 * The whole point of the issue: an accepted play says what was played.
	 *
	 * Asserted on the exact sentence rather than on "it toasted something". A test
	 * that only checked that *a* toast appeared would pass just as happily against
	 * the "no good" rejection, which is the one thing this message must never be.
	 */
	@Test
	fun anAcceptedPlayTellsThePlayerWhatWasPlayed ()
	{
		val played = m_player.getHand()!!.getCard(0)!!

		m_player.turnDecisionPlayCard(played)

		assertEquals ("the player should be told the accepted card by name",
				"South plays Green 5", lastToast())
	}

	/** The play is really accepted, not merely announced: the flags the round loop reads. */
	@Test
	fun anAcceptedPlayIsAccepted ()
	{
		val played = m_player.getHand()!!.getCard(0)!!

		m_player.turnDecisionPlayCard(played)

		assertTrue ("the card should be the one offered to play",
				m_player.getPlayingCard() === played)
		assertTrue (m_player.getWantsToPlayCard())
	}

	/**
	 * And a rejected one is not, and says so instead. `checkCard` refuses a card
	 * whose value and colour both differ from the table, so the two paths are told
	 * apart by what reaches the player rather than by inspecting the internals.
	 */
	@Test
	fun aRejectedPlaySaysNoGoodAndNotWhatWasPlayed ()
	{
		// In the hand, and refused on its merits. A card outside the hand returns
		// at the isInHand guard before checkCard, which is the other test.
		val illegal = Card(3, Card.COLOR_RED, 1, Card.ID_RED_1, 1)
		m_player.getHand()!!.addCard(illegal)

		m_player.turnDecisionPlayCard(illegal)

		assertEquals ("a refused card should say so",
				m_game.getString(R.string.msg_card_no_good), lastToast())
		assertFalse ("a refused card must not be offered to play",
				m_player.getWantsToPlayCard())
		assertEquals ("nothing is announced when the play is refused",
				m_game.getString(R.string.msg_card_no_good), lastToast())
	}

	/**
	 * A card the hand does not hold is neither accepted nor refused, and says
	 * nothing. It cannot arrive from the UI -- you tap cards in your own hand --
	 * but it is the one path in the method with no message at all, and pinning it
	 * stops a future change from treating it as an acceptance.
	 */
	@Test
	fun aCardNotInHandIsIgnoredSilently ()
	{
		val stranger = Card(4, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5)

		m_player.turnDecisionPlayCard(stranger)

		assertFalse (m_player.getWantsToPlayCard())
		assertEquals ("a card outside the hand should say nothing at all", null, lastToast())
	}

	/**
	 * The descriptor the message is built from. `Game.cardToString` is what the
	 * draw message already renders in a sentence, so it is pinned here rather than
	 * a parallel descriptor being written for this issue: a card with no name of its
	 * own comes out as colour and value, with the separator carried by the trailing
	 * space on the cardcolor_* strings.
	 *
	 * A card that does have a name returns it alone, with no colour, which is why
	 * the message reads "Quitter" rather than "Green Quitter". That is the existing
	 * behaviour and #4 reuses it; this test records it so a change to either half
	 * is visible here.
	 */
	@Test
	fun theDescriptorReadsAsASentence ()
	{
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

		assertEquals ("Green 5",
				Card(1, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5).toString(ctx))
		assertEquals ("Red Draw Two",
				Card(1, Card.COLOR_RED, Card.VAL_D, Card.ID_RED_D, Card.VAL_D).toString(ctx))
		assertEquals ("a named card carries no colour",
				"Quitter",
				Card(1, Card.COLOR_GREEN, 0, Card.ID_GREEN_0_QUITTER, 0).toString(ctx))
	}

	/** Reflection, so a rename fails here rather than as an NPE under the toast. */
	private fun setField (target: Any, name: String, value: Any)
	{
		try
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
		catch (e: ReflectiveOperationException)
		{
			throw AssertionError("${target.javaClass.simpleName}.$name is gone or renamed: $e")
		}
	}
}
