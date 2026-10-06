package com.smccloud.hotdeath

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Covers GameTable's input model: the wheel, and what a second pointer does to a
 * gesture.
 *
 * `GameTableLayoutTest` covers where everything is on the table. This covers what
 * the table does when something arrives at it -- and there was nothing here at
 * all, so the whole of `onTouchEvent` and `onGenericMotionEvent` had no test.
 * Issue #14 is where the gaps were catalogued: a hand longer than the table can
 * show is scrolled by dragging it, which is fine with a finger and awkward with
 * a mouse, so a large screen invited a scroll it offered no way to do; and the
 * touch handler compared `event.action`, which carries the pointer index, so a
 * second finger's up was not the first finger's up and the tail of a
 * two-finger gesture was read as a tap.
 *
 * **The fixture has to draw, not just lay out.** `m_handBoundingRect` is worked
 * out in `RedrawHand`, which only runs from `onDraw`, and it is the rect both
 * the wheel and the touch handler hit-test against. A table that has been laid
 * out but never drawn has no hand under the pointer at all -- so without
 * `draw()` here the tests would pass against an empty table.
 *
 * **What is asserted is behaviour, not coordinates.** The wheel tests go through
 * `scrollHandBy` and check the offset moves the right way and stops at the right
 * end; the pointer tests send real MotionEvents and check whether a card was
 * played. Nothing here knows a pixel, so none of it needs re-tuning when the
 * layout does.
 *
 * **What cannot be tested here is the event plumbing.** AXIS_VSCROLL is read out
 * of native device state, and a `MotionEvent` is built out of pointer
 * coordinates, so there is no way to construct a scroll event without a real
 * `InputDevice` behind it -- Robolectric 4.15 has no setter for it. `scrollHandBy`
 * is therefore `internal` and is tested directly; the four lines of
 * `onGenericMotionEvent` that feed it are read, not exercised. Everything else
 * below goes through the real handler with real events.
 *
 * `Game` is built rather than started, for the reason `GameRoundLoopTest` gives:
 * `Game` extends `Thread` and `buildActivity().setup()` runs `onCreate`, which
 * starts it. `get()` runs no `onCreate`, and nothing below calls `start()`.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class GameTableInputTest
{
	/**
	 * A laid-out, drawn table with cards dealt, plus the numbers the assertions read.
	 *
	 * [cardsInHumanHand] is there so a test can say "and the hand was this long"
	 * without reaching back into the fixture, and [maxCardsDisplay] so the two can
	 * be compared rather than both asserted independently.
	 */
	private class Fixture(
			val table: GameTable,
			val game: Game,
			val human: HumanPlayer,
			val maxCardsDisplay: Int,
			val cardsInHumanHand: Int)

	private val W = 1080
	private val H = 1920

	@Before
	fun setUp ()
	{
		// The density, not the window, is what the card bitmaps are decoded at, and
		// they are decoded in the GameTable constructor -- so this has to happen
		// before any table is built or the table would be holding another device's
		// cards. xhdpi is the phone density GameTableLayoutTest uses for the same
		// window, so the two fixtures describe the same table.
		RuntimeEnvironment.setQualifiers ("xhdpi")

		// The human is the south seat, which is the default: the fourth-computer
		// preference is what moves them out of it, and it is left unset here on
		// purpose. Set it and the tests below would be touching computer hands,
		// which handCardTapped ignores.
		val app: Context = ApplicationProvider.getApplicationContext()
		Prefs.defaultSharedPreferences(app)
			.edit()
			.putBoolean("computer_4th", false)
			.putBoolean("face_up", false)
			.putString("cheat_code", "")
			.commit()
	}

	// ---------------------------------------------------------------- the wheel

	/**
	 * A wheel turned towards the player brings later cards into view.
	 *
	 * AXIS_VSCROLL is positive away from the user, which moves the table's contents
	 * up, which means earlier cards come into view and the offset goes down. So the
	 * sign is the interesting half of this: a wheel that scrolled the wrong way
	 * would read as correct here if the test only checked that "something moved".
	 *
	 * Asserted as an offset rather than a pixel because the offset *is* the scroll
	 * position -- it is the index the hand is drawn from and the index
	 * `findTouchedCardHand` reads a tap against.
	 */
	@Test
	fun theWheelScrollsTowardsTheEndOfTheHand ()
	{
		val f = overflowing()

		f.table.scrollHandBy (Game.SEAT_SOUTH, -1f)

		assertEquals("one detent should have scrolled the hand on by one card",
				1, offset (f, Game.SEAT_SOUTH))
	}

	@Test
	fun theWheelScrollsBackTowardsTheStartOfTheHand ()
	{
		val f = overflowing()

		f.table.scrollHandBy (Game.SEAT_SOUTH, -3f)
		assertEquals("three detents down", 3, offset (f, Game.SEAT_SOUTH))

		f.table.scrollHandBy (Game.SEAT_SOUTH, 1f)
		assertEquals("and one back up is two, not four",
				2, offset (f, Game.SEAT_SOUTH))
	}

	/**
	 * The scroll stops at the end of the hand rather than running off it.
	 *
	 * The bound is `numcards - m_maxCardsDisplay`: the last page of cards. Asking
	 * for more than that is what a fast wheel does on every flick, and it has to
	 * land on the same place rather than going further and taking the hand's cards
	 * with it. `RedrawHand` clamps too, so the visible result is the same either
	 * way -- which is exactly why this asserts the field, since a bound that only
	 * existed in the draw path would look correct and still be wrong.
	 */
	@Test
	fun theWheelStopsAtTheEndOfTheHand ()
	{
		val f = overflowing()
		val lastPage = f.cardsInHumanHand - f.maxCardsDisplay

		f.table.scrollHandBy (Game.SEAT_SOUTH, -50f)

		assertEquals("the last page of cards is offset " + lastPage,
				lastPage, offset (f, Game.SEAT_SOUTH))
	}

	@Test
	fun theWheelStopsAtTheStartOfTheHand ()
	{
		val f = overflowing()

		f.table.scrollHandBy (Game.SEAT_SOUTH, -5f)
		f.table.scrollHandBy (Game.SEAT_SOUTH, 50f)

		assertEquals("scrolling back past the first card should stop at it",
				0, offset (f, Game.SEAT_SOUTH))
	}

	/**
	 * A hand that fits does not scroll at all.
	 *
	 * The bound is `max(0, numcards - m_maxCardsDisplay)`, so a hand of seven cards
	 * against a table showing fourteen has a bound of zero at both ends and is
	 * pinned there. This is the common case, not the corner one -- most hands are
	 * shorter than the table -- so a wheel that moved them would be a bug on the
	 * first flick rather than an edge case.
	 */
	@Test
	fun aHandThatFitsDoesNotScroll ()
	{
		// How many fit is a property of the window, not of the hand, so it is read
		// off a real table and then used to size a hand that is deliberately under
		// it.
		val fits = intField (tableOnly(), "m_maxCardsDisplay") - 4
		val f = fixture (fits)

		f.table.scrollHandBy (Game.SEAT_SOUTH, -10f)
		assertEquals("a hand of $fits against a table showing "
						+ f.maxCardsDisplay + " cannot be scrolled forwards",
				0, offset (f, Game.SEAT_SOUTH))

		f.table.scrollHandBy (Game.SEAT_SOUTH, 10f)
		assertEquals("nor backwards", 0, offset (f, Game.SEAT_SOUTH))
	}

	/**
	 * A trackpad swipe scrolls, because the sub-detent amounts add up.
	 *
	 * This is the half of the wheel that a mouse never exercises. A wheel reports
	 * a detent at a time and AXIS_VSCROLL is 1.0 for it, so everything above would
	 * pass with a `toInt()` straight on each event. A trackpad reports a swipe as a
	 * stream of amounts well under one -- three per cent is typical -- and rounding
	 * each event on its own throws away all but the fastest swipe and leaves the
	 * tail unspent.
	 *
	 * Twenty-five events of 0.04 is one detent, so this asserts one card and then
	 * asserts that the fraction that did not add up to a card is still there: the
	 * next quarter swipe finishes the second card rather than starting from zero.
	 */
	@Test
	fun aTrackpadSwipeAddsUpIntoWholeCards ()
	{
		val f = overflowing()

		for (i in 0 until 25)
		{
			f.table.scrollHandBy (Game.SEAT_SOUTH, -0.04f)
		}
		assertEquals("25 events of 0.04 is one whole detent",
				1, offset (f, Game.SEAT_SOUTH))

		for (i in 0 until 25)
		{
			f.table.scrollHandBy (Game.SEAT_SOUTH, -0.04f)
		}
		assertEquals("and the next 25 finish the second rather than restarting",
				2, offset (f, Game.SEAT_SOUTH))
	}

	/** A fifth of a detent is a fifth of a detent, and moves nothing yet. */
	@Test
	fun aSubDetentScrollMovesNothingOnItsOwn ()
	{
		val f = overflowing()

		f.table.scrollHandBy (Game.SEAT_SOUTH, -0.2f)

		assertEquals("a fifth of a detent should not have moved the hand",
				0, offset (f, Game.SEAT_SOUTH))
	}

	/**
	 * Each hand has its own scroll position.
	 *
	 * Four seats, four hands, and the point of the hit test is that the pointer
	 * says which one. Sharing one position would scroll the human's hand when the
	 * wheel was over a computer's -- and a computer's hand is drawn from its own
	 * offset, so the two would jump about independently of each other.
	 */
	@Test
	fun eachSeatScrollsIndependently ()
	{
		val f = overflowing()

		f.table.scrollHandBy (Game.SEAT_WEST, -2f)
		assertEquals("west moved", 2, offset (f, Game.SEAT_WEST))
		assertEquals("south did not", 0, offset (f, Game.SEAT_SOUTH))

		f.table.scrollHandBy (Game.SEAT_SOUTH, -4f)
		assertEquals("south moved", 4, offset (f, Game.SEAT_SOUTH))
		assertEquals("west stayed where it was", 2, offset (f, Game.SEAT_WEST))
	}

	/**
	 * And the whole point of it: a wheel reveals a card that can then be played.
	 *
	 * The two halves tested on their own -- the wheel moves the offset, a tap plays
	 * the card under the pointer -- say nothing about whether they compose. They
	 * did not have to: a hand is a fixed set of cards drawn from an index, and
	 * `findTouchedCardHand` reads that index, so scrolling and tapping compose only
	 * if the offset the wheel wrote is the one the tap reads. This is the test that
	 * fails if it ever stops being true, which is the difference between scrolling
	 * the hand and scrolling a picture of it.
	 */
	@Test
	fun scrollingTheHandRevealsACardThatCanBeTapped ()
	{
		val f = overflowing()
		val x = centreOf (f, Game.SEAT_SOUTH).x
		val y = centreOf (f, Game.SEAT_SOUTH).y

		tap (f.table, x, y)
		val first = f.human.getPlayingCard()
		assertNotNull("the control tap should have played a card", first)

		// Back to the start of the hand, then a page down it.
		f.table.scrollHandBy (Game.SEAT_SOUTH, 50f)
		assertEquals("back at the first card", 0, offset (f, Game.SEAT_SOUTH))

		f.table.scrollHandBy (Game.SEAT_SOUTH, -1f)
		tap (f.table, x, y)

		assertNotNull("the tap after scrolling should have played a card",
				f.human.getPlayingCard())
		assertFalse("scrolling then tapping played the card the tap would have " +
				"played without it (" + first!!.getID() + "), so the wheel moved the " +
				"picture and not the hand",
				f.human.getPlayingCard()!!.getID() == first.getID())
	}

	// ------------------------------------------------------------- the pointers

	/**
	 * One finger taps a card and plays it. The control for the next one.
	 *
	 * Without this, "a two-finger tap does not play a card" would pass for the
	 * wrong reason -- a table where no tap plays anything satisfies it. So this
	 * goes first in the file's logic and asserts the thing the other one denies.
	 */
	@Test
	fun oneFingerStillPlaysACard ()
	{
		val f = overflowing()
		val pt = centreOf (f, Game.SEAT_SOUTH)

		tap (f.table, pt.x, pt.y)

		assertNotNull("a single tap on the human's hand should have played a card",
				f.human.getPlayingCard())
	}

	/**
	 * A second finger does not play a card, and that is the fix.
	 *
	 * The bug: the handler compared `event.action`, which packs the pointer index
	 * into its high bits, so `action == ACTION_DOWN` and `action == ACTION_UP` were
	 * true only for pointer 0's own press and release. A second finger's press and
	 * release matched no branch and went to `super`, which was harmless until the
	 * ordering turned it over. Here -- one finger down on a card, a second lands
	 * and lifts, and then the *first* lifts -- the first finger's release is
	 * `ACTION_POINTER_UP` and the second's is a plain `ACTION_UP`. The tap handler
	 * ran on the second one, with the seat and the touch-down point still set from
	 * the first, and played the card.
	 *
	 * Which is two thumbs resting on a tablet, or a stylus left on the glass, or
	 * a hand that shifts while it is held down. None of them is a tap on a card.
	 *
	 * This is the same four events as the control above in a different order, so
	 * the two together say the difference is the second finger and not the
	 * position, the timing or the card.
	 */
	@Test
	fun aSecondFingerDoesNotPlayACard ()
	{
		val f = overflowing()
		val pt = centreOf (f, Game.SEAT_SOUTH)

		// The second finger lands in the corner, so that "it played the wrong card"
		// and "it played a card at all" are both distinguishable from it doing
		// nothing at all.
		val elsewhere = bareTable(f)

		// Finger 0 down on the card, finger 1 down in the corner, finger 0 up --
		// which is a pointer-up -- then finger 1 up, which is the plain ACTION_UP.
		send (f.table, down (pt.x, pt.y))
		send (f.table, secondFingerDown (pt.x, pt.y, 1))
		send (f.table, pointerUp (pt.x, pt.y, 0))
		send (f.table, up (elsewhere.x, elsewhere.y))

		assertNull("a two-finger tap should not have played a card; it played "
				+ f.human.getPlayingCard(), f.human.getPlayingCard())
	}

	/**
	 * Nor does a second finger hold a card help open.
	 *
	 * The long press is a delayed runnable posted from ACTION_DOWN, and
	 * ACTION_POINTER_DOWN now takes it back down. A resting second finger used to
	 * leave it posted, so the card help for the first finger's card came up a
	 * second later while the first finger was still down and about to lift. On a
	 * tablet that is a card help dialog opening under someone's thumb.
	 *
	 * Read as `m_waitingForTouchAndHold` rather than by idling the looper: the flag
	 * is what the runnable itself checks before it does anything, so this asserts
	 * the thing that decides whether help appears without depending on the
	 * thousand milliseconds.
	 */
	@Test
	fun aSecondFingerCancelsTheLongPress ()
	{
		val f = overflowing()
		val pt = centreOf (f, Game.SEAT_SOUTH)

		send (f.table, down (pt.x, pt.y))
		assertTrue("the long press should be armed by a press on a card",
				boolField (f.table, "m_waitingForTouchAndHold"))

		// The second finger goes down over the bare table; nothing reads where it is.
		val elsewhere = bareTable (f)
		send (f.table, secondFingerDown (elsewhere.x, elsewhere.y, 1))
		assertFalse("the second finger should have taken the long press back down",
				boolField (f.table, "m_waitingForTouchAndHold"))
	}

	/**
	 * A drag the platform took away is not committed.
	 *
	 * ACTION_CANCEL is what arrives when something else takes the gesture: a parent
	 * intercepting it, a window changing underneath it, a palm landing. It used to
	 * take down the long press and nothing else, which left `m_currentDrag` holding
	 * the delta the drag had got to. `RedrawHand` adds that to the seat's offset on
	 * every frame after, so the hand sat shifted by it for as long as the table was
	 * up; and the next tap's ACTION_UP found the stale delta still set and added it
	 * to `m_cardoffset` as well. A cancel partway through a drag therefore jumped
	 * the hand twice, once on the cancel and once on the next tap.
	 *
	 * Cancelled halfway, then tapped -- which is the sequence, and the only reason
	 * the second jump needed a tap to show up.
	 */
	@Test
	fun aCancelledDragIsNotCommitted ()
	{
		val f = overflowing()
		val pt = centreOf (f, Game.SEAT_SOUTH)

		send (f.table, down (pt.x, pt.y))
		send (f.table, move (pt.x - dragDistance (f), pt.y))
		assertTrue("the drag should have been under way",
				intArray (f.table, "m_currentDrag")[Game.SEAT_SOUTH - 1] != 0)

		send (f.table, cancel (pt.x - dragDistance (f), pt.y))
		assertEquals("the cancel should have dropped the drag",
				0, intArray (f.table, "m_currentDrag")[Game.SEAT_SOUTH - 1])

		tap (f.table, pt.x, pt.y)

		assertEquals("and the next tap should not have committed it either",
				0, offset (f, Game.SEAT_SOUTH))
	}

	// ------------------------------------------------------------ the dispatch

	/**
	 * A scroll over a hand is this table's; a scroll over bare table is not.
	 *
	 * The one part of the wheel that *is* reachable from a test, because it does not
	 * involve the axis: `onGenericMotionEvent` has to decide whose hand is being
	 * scrolled from the pointer position, and it has to decide whether the event is
	 * its business at all. Both are decisions, and both have a wrong answer --
	 * always consuming means a scroll over the game's border never reaches the
	 * window above it, and eating everything means a trackpad's hover and any
	 * sideways scroll are swallowed for nothing.
	 *
	 * Consumed is the observable. `View.onGenericMotionEvent` returns false when it
	 * has nothing to do, so "the table took it" and "the table passed it up" are
	 * told apart by the return value alone.
	 *
	 * It cannot check *how far* it scrolled -- see the class comment -- so what is
	 * being asserted here is the routing, and the distance is asserted through
	 * `scrollHandBy` above.
	 */
	@Test
	fun aScrollIsTakenOverAHandAndPassedOverTheTable ()
	{
		val f = overflowing()

		val overHand = centreOf (f, Game.SEAT_SOUTH)
		val taken = f.table.onGenericMotionEvent(scroll(overHand.x, overHand.y))
		assertTrue("a scroll with the pointer on a hand is the table's to handle", taken)

		val bare = bareTable (f)
		val passed = f.table.onGenericMotionEvent(scroll(bare.x, bare.y))
		assertFalse("a scroll over bare table at $bare belongs to whatever is above " +
				"the game, and the table should have let it past", passed)
	}

	/**
	 * And a hover is not a scroll.
	 *
	 * A trackpad reports hover as well as scroll, and there is nothing to do with a
	 * hover here: no highlight, no scrollbar, nothing drawn. Swallowing it would be
	 * free behaviour that costs a later gesture its turn at dispatch.
	 */
	@Test
	fun aHoverIsNotSwallowed ()
	{
		val f = overflowing()
		val overHand = centreOf (f, Game.SEAT_SOUTH)

		val taken = f.table.onGenericMotionEvent(
				MotionEvent.obtain(stamp(), stamp(), MotionEvent.ACTION_HOVER_MOVE,
						overHand.x.toFloat(), overHand.y.toFloat(), 0))

		assertFalse("a hover over the table has nothing to do here", taken)
	}

	// ---------------------------------------------------------------- fixture

	/**
	 * A table with a deal on it, laid out and drawn.
	 *
	 * The cards are all red and the current card is red, so `checkCard` accepts any
	 * of them on the colour match at the end of its rule list -- which is what lets
	 * the tap tests assert *whether a card was played* rather than whether some
	 * field was set, and keeps them off the penalty and wild-card branches, which
	 * have tests of their own in PenaltyRulesTest and HandPlayabilityTest.
	 *
	 * The draw is the part that is easy to miss. `m_handBoundingRect` is built in
	 * `RedrawHand`, which runs from `onDraw`, and it is the rect both the wheel and
	 * the touch handler hit-test against -- so a table that was laid out but not
	 * drawn has no hand under the pointer at all, and every test below would pass
	 * against an empty one.
	 */
	private fun fixture (cardsInHumanHand: Int): Fixture
	{
		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)
		val game = Game(activity, options)
		val table = GameTable(activity, game, options)

		table.measure(
				View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, W, H)

		// resetRound is what gives each seat a Hand and the Game a deck, and
		// Player.addCardToHand needs both -- the Hand to add to, and the deck for
		// sortHand to find the cards in. It is not a round and does not start one:
		// no thread, no deal, no piles filled.
		game.resetRound()

		// resetRound builds an empty CardDeck; startRound is what fills it, and
		// startRound wants a dealer chosen and asks the human how many cards to
		// deal, which is a dialog and a spin-wait. Filling the deck directly is the
		// same call one level down, under the same rules, without the shuffle --
		// which is what a test wants anyway, since the deal below is then repeatable.
		game.getDeck()!!.reset (false, false)

		// onDraw returns before it draws anything unless the round has a colour, so
		// the field goes in by hand -- the same reflection HandPlayabilityTest uses
		// for the same three fields, and for the same reason. The current card is a
		// red one and the current colour is red, so `checkCard` accepts any red card
		// in the hand on the colour match at the end of its rule list. That keeps the
		// tap tests asserting *whether a card was played* rather than whether some
		// field was set, and keeps them off the penalty and wild-card branches, which
		// PenaltyRulesTest and HandPlayabilityTest already cover.
		setGameField (game, "m_currCard", game.getDeck()!!.getCard(0)!!)
		setGameField (game, "m_currColor", Card.COLOR_RED)
		setGameField (game, "m_penalty", Penalty())

		// One cursor across all four hands, so no card is ever in two of them -- the
		// same Card object in two hands is one card wearing two hats, and sortHand
		// walks the deck picking out the cards whose hand is a given one, so it
		// would put the card in both. Starts at 1 because index 0 is the current card.
		var next = 1

		val deal = { player: Player, count: Int ->
			for (i in 0 until count)
			{
				player.addCardToHand (game.getDeck()!!.getCard(next++)!!)
			}
			// Face up, so the hand is drawn the way the player's own is. The long
			// press only offers card help for a face-up card, so this is also what
			// makes the tap tests' hand a hand a real one could play from.
			player.getHand()!!.setFaceUp (true)
		}

		deal (game.getPlayer(0)!!, cardsInHumanHand)

		// Every computer is given an overflowing hand too, not one card. Their hands
		// are drawn but never played -- handCardTapped only acts on a HumanPlayer --
		// so they are here for the geometry, and a hand that fits has a scroll bound
		// of zero at both ends, which would make a computer's hand untestable.
		for (i in 1..3)
		{
			deal (game.getPlayer(i)!!, cardsInHumanHand)
		}

		table.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))

		return Fixture(table, game, game.getPlayer(0) as HumanPlayer,
				intField(table, "m_maxCardsDisplay"), cardsInHumanHand)
	}

	/**
	 * A table whose hands are [extra] cards longer than the table can show.
	 *
	 * Read off a real table rather than guessed at, because "longer than the table
	 * can show" is the whole precondition of the wheel tests and the number is
	 * 30 at mdpi and 14 at xhdpi -- a hand of 20 overflows one and not the other,
	 * and a test that hard-coded it would quietly stop testing anything on
	 * whichever density it was not written for.
	 */
	private fun overflowing (extra: Int = 6): Fixture
	{
		val showing = intField (tableOnly(), "m_maxCardsDisplay")
		return fixture (showing + extra)
	}

	/**
	 * A bare table, laid out but never dealt to, for reading a layout number.
	 *
	 * Cheap because nothing here needs a hand: this is `onSizeChanged` and nothing
	 * else, and it runs before the deal is a thought.
	 */
	private fun tableOnly(): GameTable
	{
		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)
		val game = Game(activity, options)
		val table = GameTable(activity, game, options)

		table.measure(
				View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, W, H)

		return table
	}

	// ------------------------------------------------------------ motion events

	/**
	 * A whole tap, through the view rather than around it.
	 *
	 * `onTouchEvent` rather than `dispatchTouchEvent`, because the wheel and the
	 * hand rects are this class's own state and there is no parent here to
	 * dispatch through -- `GameActivity.onCreate` is never run, so the table has no
	 * container. What is being tested is the handler, and the handler is what
	 * receives a tap on a real screen.
	 */
	private fun tap (table: GameTable, x: Int, y: Int)
	{
		send (table, down (x, y))
		send (table, up (x, y))
	}

	private fun send (table: GameTable, event: MotionEvent)
	{
		try
		{
			table.onTouchEvent(event)
		}
		finally
		{
			event.recycle()
		}
	}

	// The event times are a fixed pair rather than SystemClock, so two events in
	// one gesture have the same downTime and are ordered by eventTime -- which is
	// what the platform sends, and what makes ACTION_POINTER_UP distinguishable
	// from ACTION_UP by anything other than the pointer index.

	private var m_now = 1000L

	private fun stamp (): Long = m_now++

	private fun down (x: Int, y: Int): MotionEvent = MotionEvent.obtain(
			stamp(), stamp(), MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)

	private fun up (x: Int, y: Int): MotionEvent = MotionEvent.obtain(
			stamp(), stamp(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)

	private fun move (x: Int, y: Int): MotionEvent = MotionEvent.obtain(
			stamp(), stamp(), MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 0)

	private fun cancel (x: Int, y: Int): MotionEvent = MotionEvent.obtain(
			stamp(), stamp(), MotionEvent.ACTION_CANCEL, x.toFloat(), y.toFloat(), 0)

	/**
	 * A scroll event at the pointer.
	 *
	 * AXIS_VSCROLL on this is 0.0, and always will be: the axis is read out of the
	 * input device, and there is no device behind a test. So this is for the
	 * dispatch -- whose hand, and is it ours -- and the distance it scrolls is
	 * tested through `scrollHandBy`.
	 */
	private fun scroll (x: Int, y: Int): MotionEvent = MotionEvent.obtain(
			stamp(), stamp(), MotionEvent.ACTION_SCROLL, x.toFloat(), y.toFloat(), 0)

	/**
	 * A second finger going down, the first still down at [x], [y].
	 *
	 * **What this is, precisely.** The public `MotionEvent.obtain` takes one
	 * pointer's x and y; the overload that takes both is `@hide`, and Robolectric
	 * 4.15 has no way to put a second pointer on an event at all. So these are
	 * single-pointer events carrying ACTION_POINTER_DOWN and ACTION_POINTER_UP,
	 * which is the closest a test can get -- and for the pointer bugs it is
	 * enough, because the handler dispatches on `actionMasked` and neither of those
	 * branches reads `pointerCount` or any pointer but the first. What it cannot
	 * show is that the platform's real two-finger event lands on the same branch,
	 * and what shows that is the one line it changed: `event.actionMasked` instead
	 * of `event.action`, which is the whole difference between "a second finger"
	 * and "a second finger, as far as this handler is concerned".
	 *
	 * [index] is carried in the action's index bits as the platform sends it, and is
	 * a parameter rather than a constant because the sequence that reproduces the
	 * bug needs pointer 0 to be the one that lifts: the second finger goes down
	 * second and comes up first, so it is pointer 0's release that becomes a
	 * pointer-up and the leftover second finger's that becomes the plain
	 * ACTION_UP the tap handler runs on.
	 *
	 * The second finger's own position is not carried, because nothing here reads
	 * it -- see above -- so the callers say where it is in a comment instead.
	 */
	private fun secondFingerDown (x: Int, y: Int, index: Int): MotionEvent
	{
		return pointerAction (MotionEvent.ACTION_POINTER_DOWN, index, x, y)
	}

	private fun pointerUp (x: Int, y: Int, index: Int): MotionEvent
	{
		return pointerAction (MotionEvent.ACTION_POINTER_UP, index, x, y)
	}

	private fun pointerAction (action: Int, index: Int, x: Int, y: Int): MotionEvent
	{
		return MotionEvent.obtain(stamp(), stamp(),
				action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
				x.toFloat(), y.toFloat(), 0)
	}

	// ------------------------------------------------------------------ reading

	private fun offset (f: Fixture, seat: Int): Int =
			intArray (f.table, "m_cardoffset")[seat - 1]

	/** A point inside the middle of a seat's hand, from the rect the draw built. */
	private fun centreOf (f: Fixture, seat: Int): Point
	{
		val r = handRect (f, seat)
				?: throw AssertionError("seat $seat has no hand on the table; was the " +
				"table drawn?")
		return Point(r.centerX(), r.centerY())
	}

	private fun handRect (f: Fixture, seat: Int): Rect? =
			field<Array<Rect?>>(f.table, "m_handBoundingRect")[seat - 1]

	/**
	 * A point inside the window and outside every hand.
	 *
	 * Searched rather than guessed at a corner, because "the corner is not on a
	 * hand" is exactly the kind of thing that stops being true when the layout is
	 * re-tuned -- and the alternative, hard-coding a coordinate, would then fail
	 * with a message about a scroll over bare table rather than about the layout
	 * having moved. Coarse steps, because any bare point will do.
	 */
	private fun bareTable (f: Fixture): Point
	{
		for (y in 2 until H step 6)
		{
			for (x in 2 until W step 6)
			{
				val onAHand = (1..4).any { handRect (f, it)?.contains(x, y) == true }
				if (!onAHand)
				{
					return Point(x, y)
				}
			}
		}

		throw AssertionError("every part of a ${W}x$H table is covered by a hand")
	}

	/**
	 * Half a card of drag, which is one card of offset.
	 *
	 * From the field rather than a constant, because `m_cardSpacingHuman` is what
	 * the MOVE handler divides by and it comes out of the layout -- so this moves
	 * by exactly one card at whatever size the table ended up.
	 */
	private fun dragDistance (f: Fixture): Int = intField (f.table, "m_cardSpacingHuman")

	// -------------------------------------------------------------- reflection

	/**
	 * Reading the table's private state, for the reasons GameTableLayoutTest gives:
	 * a rename fails here loudly instead of quietly turning the assertion into a
	 * no-op. `intField` rather than a generic accessor because every use below is an
	 * Int and an unchecked cast would only be a way to fail later.
	 */
	private inline fun <reified T> field (target: Any, name: String): T
	{
		try
		{
			val f = target.javaClass.getDeclaredField(name)
			f.isAccessible = true
			@Suppress ("UNCHECKED_CAST")
			return f.get(target) as T
		}
		catch (e: ReflectiveOperationException)
		{
			throw AssertionError("${target.javaClass.simpleName}.$name is gone or " +
					"renamed: $e")
		}
	}

	private fun intField (target: Any, name: String): Int = field<Int>(target, name)

	private fun boolField (target: Any, name: String): Boolean = field<Boolean>(target, name)

	private fun intArray (target: Any, name: String): IntArray = field<IntArray>(target, name)

	/**
	 * The Game fields `onDraw` and `checkCard` read and nothing else sets until a
	 * round is dealt. Failing loudly on a rename for the same reason as above.
	 *
	 * Takes the Game rather than reaching for one, because the fixture calls this
	 * on the way to becoming a fixture and there is nothing to reach for yet.
	 */
	private fun setGameField (game: Game, name: String, value: Any)
	{
		try
		{
			val f = Game::class.java.getDeclaredField(name)
			f.isAccessible = true
			f.set(game, value)
		}
		catch (e: ReflectiveOperationException)
		{
			throw AssertionError("Game.$name is gone or renamed: $e")
		}
	}
}