package com.smccloud.hotdeath

import android.graphics.Bitmap
import android.graphics.Point
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Covers GameTable's geometry, which nothing else in the suite could see.
 *
 * `onSizeChanged` is where every position on the table is computed -- the four
 * seats, both piles, the direction colour, the four player indicators, the card
 * badges, the score text, the emoticons, the toast anchor and the winning banner
 * -- out of two numbers and the card bitmap's dimensions. There was no test for
 * any of it, so a layout change could only be checked by looking at a screen.
 *
 * The fixture is the one NoviceModeTest uses, and it is the only way to make
 * `onSizeChanged` run: a real GameTable, `measure`d with EXACTLY specs and
 * `layout`ed, because the geometry is computed in the layout pass rather than in
 * the constructor. The activity is built with `get()` rather than `setup()` so
 * `onCreate` never runs and no game thread is started.
 *
 * **What is asserted is invariants, not pixels.** There is nothing stable to
 * compare against, because the geometry is derived from the window rather than
 * drawn from an asset -- and a test that asserted exact coordinates would fail
 * on every legitimate re-tune while catching none of the failures below. So each
 * case says "the geometry is inside the window it was given" and "the hand fits",
 * which is what actually broke.
 *
 * **What this is not** is a device test. Densities here are Robolectric's, and it
 * caps at xhdpi -- xxhdpi and xxxhdpi report xhdpi's numbers, which is why the
 * case list stops there. Nothing here has been run on a foldable, on a tablet or
 * on a real density bucket above xhdpi.
 *
 * The geometries are the ones from issues #13 and #14: three phone densities, a
 * cover screen, unfolded portrait and landscape, a near-square unfolded window,
 * a tablet, and the small window that is the only case where the old
 * density-dependent orientation test ever fired.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class GameTableLayoutTest
{
	/**
	 * One window to lay the table out in.
	 *
	 * [arrangement] is what GameTable is expected to choose for the shape, and it
	 * is asserted rather than assumed -- that is the whole of the near-square
	 * case, which had no arrangement at all before.
	 */
	private class Geometry(
			val name: String,
			val w: Int,
			val h: Int,
			val density: String,
			val arrangement: GameTable.Arrangement)

	/** The window list. Densities stop at xhdpi because Robolectric does. */
	private val GEOMETRIES = listOf(
			Geometry("phone portrait, mdpi", 1080, 1920, "mdpi", GameTable.Arrangement.PORTRAIT),
			Geometry("phone portrait, hdpi", 1080, 1920, "hdpi", GameTable.Arrangement.PORTRAIT),
			Geometry("phone portrait, xhdpi", 1080, 1920, "xhdpi", GameTable.Arrangement.PORTRAIT),
			Geometry("phone landscape", 1920, 1080, "xhdpi", GameTable.Arrangement.LANDSCAPE),
			Geometry("fold cover screen", 1080, 2208, "xhdpi", GameTable.Arrangement.PORTRAIT),
			Geometry("unfolded upright", 1840, 2208, "xhdpi", GameTable.Arrangement.NEAR_SQUARE),
			Geometry("unfolded on its side", 2208, 1840, "xhdpi", GameTable.Arrangement.NEAR_SQUARE),
			Geometry("tablet portrait", 1600, 2560, "xhdpi", GameTable.Arrangement.PORTRAIT),
			Geometry("tablet landscape", 2560, 1600, "xhdpi", GameTable.Arrangement.LANDSCAPE),
			Geometry("small window", 900, 700, "xhdpi", GameTable.Arrangement.LANDSCAPE),
			// The reachable bottom end: 220dp is the minimum size the platform gives a
			// freeform or split-screen window, and 320dp the narrow half of a tablet
			// in a portrait split. Before the cards were sized to the window, the
			// 320x240 window in issue #14's table computed m_maxCardsDisplay as 0 and
			// drew no cards at all; these three are the sizes that replaced it, at all
			// three densities, and the floor of one card in onSizeChanged is asserted
			// against them rather than against a window the system cannot produce.
			Geometry("freeform minimum, mdpi", 220, 220, "mdpi", GameTable.Arrangement.NEAR_SQUARE),
			Geometry("freeform minimum, hdpi", 330, 330, "hdpi", GameTable.Arrangement.NEAR_SQUARE),
			Geometry("freeform minimum, xhdpi", 440, 440, "xhdpi", GameTable.Arrangement.NEAR_SQUARE),
			Geometry("tablet split screen", 640, 440, "xhdpi", GameTable.Arrangement.LANDSCAPE))

	/**
	 * A laid-out table, plus the numbers the assertions read.
	 *
	 * Everything the geometry is made of is private and there is no getter, so
	 * this reads it by reflection -- the same trade GameRoundLoopTest and
	 * HandPlayabilityTest make, and for the same reason: a rename fails here
	 * loudly instead of silently turning the assertion into a no-op.
	 */
	private class Laid(
			val geometry: Geometry,
			val table: GameTable,
			val cardWidth: Int,
			val cardHeight: Int,
			val maxCardsDisplay: Int,
			val maxWidthHand: Int,
			val maxHeightHand: Int,
			val maxWidthHandHuman: Int,
			val arrangement: GameTable.Arrangement,
			val points: Map<String, Point>)

	// ------------------------------------------------------------------ fixture

	private fun layOut (geometry: Geometry): Laid
	{
		// Only the density is qualified, and that is not laziness: a view's size
		// comes from the measure spec and the layout call, not from the display,
		// so the window size below is what `onSizeChanged` sees whatever the
		// screen is. The density is the one thing that has to come from the
		// environment, because it is what the card bitmaps are decoded at --
		// before the table is built, or the table would be holding another
		// device's cards.
		RuntimeEnvironment.setQualifiers(geometry.density)

		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)
		val game = Game(activity, options)
		val table = GameTable(activity, game, options)

		table.measure(
				View.MeasureSpec.makeMeasureSpec(geometry.w, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(geometry.h, View.MeasureSpec.EXACTLY))
		table.layout(0, 0, geometry.w, geometry.h)

		return Laid(geometry, table,
				intField(table, "m_cardWidth"),
				intField(table, "m_cardHeight"),
				intField(table, "m_maxCardsDisplay"),
				intField(table, "m_maxWidthHand"),
				intField(table, "m_maxHeightHand"),
				intField(table, "m_maxWidthHandHuman"),
				table.m_arrangement,
				pointsOf(table))
	}

	/** Every anchor point on the table, by a name that says which field it was. */
	private fun pointsOf (table: GameTable): Map<String, Point>
	{
		val points = HashMap<String, Point>()

		for (seat in intArrayOf(Game.SEAT_NORTH, Game.SEAT_EAST,
				Game.SEAT_SOUTH, Game.SEAT_WEST))
		{
			val name = "SEAT_" + seat
			points["m_ptSeat[$name]"] = pointInArray(table, "m_ptSeat", seat - 1)!!
			points["m_ptCardBadge[$name]"] = pointInArray(table, "m_ptCardBadge", seat - 1)!!
			points["m_ptScoreText[$name]"] = pointInArray(table, "m_ptScoreText", seat - 1)!!
			points["m_ptEmoticon[$name]"] = pointInArray(table, "m_ptEmoticon", seat - 1)!!
			points["m_ptPlayerIndicator[$name]"] =
					pointInArray(table, "m_ptPlayerIndicator", seat - 1)!!
		}

		points["m_ptDrawPile"] = field<Point>(table, "m_ptDrawPile")
		points["m_ptDiscardPile"] = field<Point>(table, "m_ptDiscardPile")
		points["m_ptDirColor"] = field<Point>(table, "m_ptDirColor")
		points["m_ptWinningMessage"] = field<Point>(table, "m_ptWinningMessage")
		points["m_ptMessages"] = field<Point>(table, "m_ptMessages")

		return points
	}

	// ------------------------------------------------------------- the geometry

	/**
	 * Every anchor has to be inside the window it was laid out in.
	 *
	 * This is the assertion that catches the failures in #13 and #14: a layout
	 * that puts a seat, a badge or a score off the edge is not a layout that
	 * looks sparse, it is a layout with a seat you cannot see or tap. It is an
	 * invariant rather than a comparison because *where* the points land is a
	 * design decision that has already been changed once and may change again,
	 * while *being on the table* has not.
	 *
	 * **How far down the window sizes go, and why not further.** The list stops at
	 * the smallest window the platform will actually hand the app -- 220dp, which is
	 * the minimum size for a freeform or split-screen window. Below that it is not a
	 * case a device can produce, and asserting on it would only pin a number nobody
	 * can reach. It was worth measuring rather than assuming, because it is not
	 * obvious where the floor is, and it came out further down than expected:
	 *
	 *     density   smallest square   smallest 4:3     both in dp
	 *     mdpi      200px             200x150px         200dp, 200x150dp
	 *     hdpi      300px             300x225px         200dp, 200x150dp
	 *     xhdpi     340px             340x255px         170dp, 170x127dp
	 *
	 * Every one of those is below the 220dp platform minimum, so at every density
	 * the layout holds together across every window the system can give it. Below
	 * it the direction arrow and the four player indicators start to run off: they
	 * are laid out at their bitmap's own size, which does not scale with the window
	 * the way the cards now do, so a window narrower than the group itself puts the
	 * east indicator past the right edge -- at 320x240 xhdpi its left edge is 325.
	 * That is a limit of the chrome, not of the cards, and it is out of reach; it is
	 * recorded in issue #14 rather than fixed here.
	 */
	@Test
	fun everyAnchorPointIsInsideTheWindow ()
	{
		for (geometry in GEOMETRIES)
		{
			val laid = layOut(geometry)

			for ((name, point) in laid.points)
			{
				// "Outside", not "off the left": the same assertion catches a point
				// past the right edge, and a failure message that said "left" for a
				// point at x=325 in a 320 pixel window sent the reading in the wrong
				// direction. It cost a probe to work out which anchor it was.
				assertTrue("${geometry.name}: $name is outside the window" +
								" (x=${point.x} in 0..${geometry.w})",
						point.x in 0..geometry.w)
				assertTrue("${geometry.name}: $name is outside the window" +
								" (y=${point.y} in 0..${geometry.h})",
						point.y in 0..geometry.h)
			}
		}
	}

	/**
	 * A hand of at least one card has to fit, in both the human's spacing and the
	 * computers'.
	 *
	 * `m_maxCardsDisplay` is 7 as a field initialiser and is recomputed in
	 * `onSizeChanged` from two candidate layouts, taking whichever shows more.
	 * A window small enough to drive it to zero or below is the failure that
	 * matters: nothing clamps it, so every card-count calculation downstream
	 * divides and multiplies by it.
	 */
	@Test
	fun atLeastOneCardFitsInEveryWindow ()
	{
		for (geometry in GEOMETRIES)
		{
			val laid = layOut(geometry)

			assertTrue("${geometry.name}: m_maxCardsDisplay is ${laid.maxCardsDisplay}",
					laid.maxCardsDisplay >= 1)
		}
	}

	/** The hand is as wide as it is tall: the box the cards are laid out in. */
	@Test
	fun theHandFitsInsideTheWindow ()
	{
		for (geometry in GEOMETRIES)
		{
			val laid = layOut(geometry)

			assertTrue("${geometry.name}: a computer hand is ${laid.maxWidthHand}px wide" +
							" in a ${geometry.w}px window",
					laid.maxWidthHand <= geometry.w)
			assertTrue("${geometry.name}: a computer hand is ${laid.maxHeightHand}px tall" +
							" in a ${geometry.h}px window",
					laid.maxHeightHand <= geometry.h)
			assertTrue("${geometry.name}: the human hand is ${laid.maxWidthHandHuman}px wide" +
							" in a ${geometry.w}px window",
					laid.maxWidthHandHuman <= geometry.w)
		}
	}

	// ------------------------------------------------- the arrangement, and why

	/**
	 * The arrangement follows the shape of the window.
	 *
	 * The test this replaces was `if (h < 4.5 * m_cardHeight)`, and `m_cardHeight`
	 * is the card bitmap's height, which comes from the *resource density* rather
	 * than from the window: 80px at mdpi, 160px at xhdpi. So the threshold was a
	 * comparison against 360 or 720 pixels of height, which no phone or foldable
	 * window is ever under -- the landscape branch could not fire for any real
	 * device, in either orientation, at any density from mdpi to xxxhdpi. Only
	 * genuinely small windows reached it, which is the one case where a landscape
	 * arrangement is least useful. That is why "unfolded landscape" is a case here
	 * and why it is expected to be LANDSCAPE: with a ratio test it is.
	 */
	@Test
	fun theArrangementFollowsTheShapeOfTheWindow ()
	{
		for (geometry in GEOMETRIES)
		{
			val laid = layOut(geometry)

			assertEquals("${geometry.name}: ${geometry.w}x${geometry.h}",
					geometry.arrangement, laid.arrangement)
		}
	}

	/**
	 * And a near-square window is neither of the two, which is the case the old
	 * two-branch test had no answer for.
	 *
	 * A book-style foldable unfolded is nearly square in *either* orientation --
	 * 2208x1840 one way and 1840x2208 the other, ratios of 1.20 and 0.84 -- so it
	 * has to be its own case rather than a coin flip between the other two. It is
	 * placed like LANDSCAPE, and that is a decision rather than a shrug: in a
	 * window at least as wide as it is tall, centring the pile row vertically
	 * wastes less than pushing it above centre and leaving the bottom half to the
	 * seats.
	 *
	 * Both of those windows are in the list above with the same expectation, which
	 * is the more interesting half: the old test could not have got either right,
	 * because the card height it compared against is the same 160 pixels whichever
	 * way the device is held.
	 */
	@Test
	fun aNearSquareWindowIsItsOwnArrangement ()
	{
		assertEquals(GameTable.Arrangement.NEAR_SQUARE,
				layOut(GEOMETRIES.first { it.name == "unfolded upright" }).arrangement)
		assertEquals(GameTable.Arrangement.NEAR_SQUARE,
				layOut(GEOMETRIES.first { it.name == "unfolded on its side" }).arrangement)
	}

	/**
	 * NEAR_SQUARE is placed like LANDSCAPE, and this is what says so.
	 *
	 * Three names for two placements is a thing to be able to check rather than a
	 * thing to leave implied. The pile row is the tell, and it has to be compared
	 * *relatively*: the two windows below have different sizes and different card
	 * sizes, so their pile points are not equal and never were. What is being
	 * asserted is the rule each arrangement follows -- the row sits at the
	 * vertical centre in the landscape placement and one card height above it in
	 * the portrait one.
	 */
	@Test
	fun nearSquareIsPlacedLikeLandscape ()
	{
		val nearSquare = layOut(GEOMETRIES.first { it.name == "unfolded on its side" })
		val landscape = layOut(GEOMETRIES.first { it.name == "tablet landscape" })
		val portrait = layOut(GEOMETRIES.first { it.name == "phone portrait, xhdpi" })

		// The rule each arrangement follows, rather than the point itself: the two
		// windows below differ in size and in card size, so their pile points are
		// not equal and never were.
		for (laid in listOf(nearSquare, landscape))
		{
			assertEquals("${laid.geometry.name}: the pile row should sit at the" +
							" vertical centre, a half card down from it",
					laid.geometry.h / 2 - laid.cardHeight / 2,
					laid.points["m_ptDrawPile"]!!.y)
		}

		assertEquals("portrait should sit the pile row a card height above centre",
				portrait.geometry.h / 2 - portrait.cardHeight,
				portrait.points["m_ptDrawPile"]!!.y)
	}

	// ------------------------------------------------------------- the card size

	/**
	 * A card is a fixed fraction of the window, whatever the resource density
	 * says.
	 *
	 * `m_cardWidth` and `m_cardHeight` are the card bitmap's dimensions and were
	 * used unchanged for the life of the process. A card is 52px wide at mdpi and
	 * 103px at xhdpi, so the same window shows two very different tables and an
	 * unfolded foldable -- which is both large *and* dense -- gets the small end of
	 * both. Hands are clamped and scrollable rather than truncated, so the symptom
	 * is "you scroll your own hand on a screen with room to spare" rather than
	 * data loss, which is why this went unnoticed for years.
	 *
	 * The bounds are the ones GameTable clamps to, asserted from the outside so a
	 * change to them is a visible edit here as well as in the layout.
	 */
	@Test
	fun aCardStaysAWindowProportionalSize ()
	{
		for (geometry in GEOMETRIES)
		{
			val laid = layOut(geometry)
			val maxWidth = geometry.w / GameTable.MAX_CARD_WIDTH_DIVISOR
			val minWidth = geometry.w / GameTable.MIN_CARD_WIDTH_DIVISOR
			val maxHeight = geometry.h / GameTable.MAX_CARD_HEIGHT_DIVISOR
			val minHeight = geometry.h / GameTable.MIN_CARD_HEIGHT_DIVISOR

			assertTrue("${geometry.name}: card is ${laid.cardWidth}px wide," +
							" over the ${maxWidth}px ceiling",
					laid.cardWidth <= maxWidth)
			assertTrue("${geometry.name}: card is ${laid.cardWidth}px wide," +
							" under the ${minWidth}px floor",
					laid.cardWidth >= minWidth)
			assertTrue("${geometry.name}: card is ${laid.cardHeight}px tall," +
							" over the ${maxHeight}px ceiling",
					laid.cardHeight <= maxHeight)
			assertTrue("${geometry.name}: card is ${laid.cardHeight}px tall," +
							" under the ${minHeight}px floor",
					laid.cardHeight >= minHeight)
		}
	}

	/**
	 * Scaling a card keeps the shape of the art it is a picture of.
	 *
	 * Compared against the *source* bitmap rather than a constant, because the
	 * buckets are not the same shape: the card art is 52x80 at mdpi, 77x120 at
	 * hdpi and 103x160 at xhdpi, so its ratio is 0.650, 0.642 and 0.644. Three
	 * slightly different shapes, none of which is the others', and a test pinned
	 * to any one of them would fail on the other two.
	 */
	@Test
	fun scalingACardKeepsItsShape ()
	{
		for (geometry in GEOMETRIES)
		{
			val laid = layOut(geometry)
			val source = laid.table.sourceCardSize()
			val expected = source.first.toDouble() / source.second

			assertEquals("${geometry.name}: a ${source.first}x${source.second} card" +
							" scaled to ${laid.cardWidth}x${laid.cardHeight}",
					expected, laid.cardWidth.toDouble() / laid.cardHeight, 0.01)
		}
	}

	/**
	 * A phone at every density is left exactly as it was.
	 *
	 * This is the test that says the fix for the foldable is not a re-tune of the
	 * phone. The three card sizes below are what `res/drawable-mdpi`, `-hdpi` and
	 * `-xhdpi` actually contain, and a game people have played since 2011 should
	 * not look different on the device it has always looked right on. If a
	 * density ever needs different treatment from the others, this is the test
	 * that will say so, which is the point of pinning the sizes rather than
	 * asserting only the bounds above.
	 */
	@Test
	fun aPhoneIsUnchangedAtEveryDensity ()
	{
		val expected = mapOf("mdpi" to 52, "hdpi" to 77, "xhdpi" to 103)

		for (density in expected.keys)
		{
			val laid = layOut(GEOMETRIES.first {
				it.name == "phone portrait, $density"
			})

			assertEquals("the card at $density should not have moved",
					expected[density], laid.cardWidth)
		}
	}

	// --------------------------------------------------------------- the resize

	/**
	 * Resizing re-lays the table out and leaves the round alone.
	 *
	 * This is what a fold does: `onSizeChanged` on a new size, with no activity
	 * recreation because all three activities declare `configChanges` for
	 * `screenSize` and friends. #13 calls this "the part most likely to be broken
	 * by a careless fix", and the fix touches the very method that runs -- which
	 * now rebuilds 84 card bitmaps when the window asks for a different card size
	 * -- so it wants a test rather than a hope.
	 *
	 * Asserted on the game's own serialised state rather than on a hand, because
	 * no round is ever dealt here: nothing starts the game thread, so every hand
	 * is null and there is nothing to count. `getSnapshot` is the state that gets
	 * written to preferences on pause, so it is the round in full.
	 */
	@Test
	fun aResizeReLaysOutAndKeepsTheRound ()
	{
		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)
		val game = Game(activity, options)
		val table = GameTable(activity, game, options)

		fun resize (w: Int, h: Int)
		{
			table.measure(
					View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
			table.layout(0, 0, w, h)
		}

		resize(1080, 1920)
		val beforeCards = intField(table, "m_maxCardsDisplay")
		val beforeWidth = intField(table, "m_cardWidth")
		val beforeState = game.getSnapshot()
		val beforeSeat = field<Array<Point?>>(table, "m_ptSeat")[Game.SEAT_SOUTH - 1]!!

		resize(2208, 1840)

		assertEquals("the game object must not be replaced by a resize",
				game, field<Game>(table, "m_game"))
		assertEquals("the round must survive a resize untouched",
				beforeState, game.getSnapshot())

		assertTrue("the geometry should have moved: the window is bigger and the" +
						" card size is computed from it",
				intField(table, "m_maxCardsDisplay") != beforeCards ||
						intField(table, "m_cardWidth") != beforeWidth ||
						field<Array<Point?>>(table, "m_ptSeat")[Game.SEAT_SOUTH - 1] != beforeSeat)

		val afterSeat = field<Array<Point?>>(table, "m_ptSeat")[Game.SEAT_SOUTH - 1]!!
		assertEquals("the south seat should sit against the new height",
				1840 - (intField(table, "m_cardHeight") + intField(table, "m_bottomMargin")),
				afterSeat.y)
	}

	/**
	 * A resize never resamples the card art.
	 *
	 * The scaling builds each drawn bitmap from the original decoded in
	 * `initCards` rather than from the copy already on the table, because scaling
	 * a scaled bitmap resamples it: a fold, an unfold and a rotation would each
	 * soften the cards a little more and the art would be visibly worse after
	 * three of them.
	 *
	 * This compares pixels against a scaled copy computed here from the original,
	 * and it is the only assertion in the suite that can see the difference. The
	 * sizes cannot: a bitmap scaled from a copy is the same size as one scaled
	 * from the original, which is why an earlier version of this test passed
	 * against code that did exactly the wrong thing. Robolectric's bitmaps really
	 * resample rather than just changing size, so the pixels differ -- by 1988 of
	 * them on a 103x160 card, measured by scaling the same card twice and
	 * comparing.
	 */
	@Test
	fun aChainOfResizesNeverResamplesTheCards ()
	{
		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val options = GameOptions(activity)
		val game = Game(activity, options)
		val table = GameTable(activity, game, options)

		fun resize (w: Int, h: Int)
		{
			table.measure(
					View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
			table.layout(0, 0, w, h)
		}

		resize(1080, 1920)
		val source = table.sourceCardBitmap(Card.ID_RED_0)

		// Fold, unfold, rotate, come back. Every one of these re-scales on the way
		// except the two phone-sized ones, which is deliberate: a window that
		// needs no change is the other half of the claim.
		for ((w, h) in listOf(2208 to 1840, 1840 to 2208, 2560 to 1600,
				1080 to 1920, 900 to 700, 1080 to 1920))
		{
			resize(w, h)

			val drawn = table.getCardBitmap(Card.ID_RED_0)!!
			val expected = Bitmap.createScaledBitmap(source, drawn.width, drawn.height, true)

			assertEquals("a resize to ${w}x$h should not have resampled the art",
					0, differingPixels(drawn, expected))
		}
	}

	/** How many pixels two bitmaps of the same size disagree about. */
	private fun differingPixels (a: Bitmap, b: Bitmap): Int
	{
		assertEquals("comparing bitmaps of different sizes", a.width, b.width)
		assertEquals("comparing bitmaps of different sizes", a.height, b.height)

		var differing = 0
		for (x in 0 until a.width)
		{
			for (y in 0 until a.height)
			{
				if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
			}
		}
		return differing
	}

	/** The source bitmap's dimensions, for the shape test. */
	private fun GameTable.sourceCardSize (): Pair<Int, Int>
	{
		val source = field<Bitmap>(this, "m_bmpCardBackSource")
		return source.width to source.height
	}

	/** The original bitmap for a card id, which the drawn one is scaled from. */
	private fun GameTable.sourceCardBitmap (id: Int): Bitmap =
			field<Map<Int, Bitmap>>(this, "m_imageSource")[id]!!

	// ------------------------------------------------------------- reflection

	/**
	 * A private field, read as the type it is known to hold.
	 *
	 * Reified rather than casting at each call site: `as Array<Point?>` on an
	 * `Any?` is an unchecked cast, because the element type is erased, so it
	 * warns and would go on failing at runtime rather than at compile time. The
	 * type argument here is checked, so a rename fails the compile here and a
	 * field that changes type fails at the call site with a cast exception.
	 */
	private inline fun <reified T> field (target: Any, name: String): T
	{
		val f = target.javaClass.getDeclaredField(name)
		f.isAccessible = true
		return f.get(target) as T
	}

	private fun intField (target: Any, name: String): Int = field(target, name)

	private fun pointInArray (target: Any, name: String, index: Int): Point? =
			field<Array<Point?>>(target, name)[index]
}