package com.smccloud.hotdeath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Covers the penalty messages, issue #3: they were written from the thrower's
 * point of view, so a player mid-turn read "North threw a Draw Four - 4 to
 * South..." and had to work out who owed what. They now lead with the player who
 * has to act.
 *
 * Two things are under test and they are not the same thing.
 *
 * **The wording.** Every one of the ten counted strings is formatted here with
 * real arguments and checked for the shape the rewrite is about: the victim's
 * seat name appears, and it appears *first*. Formatting them is also the only way
 * to catch a mismatch between the format specifiers and the arguments Game
 * passes, which throws at runtime -- on a penalty card, in front of a player, with
 * no test having said so.
 *
 * **The counts.** `Penalty.COUNT_*` is where the numbers moved to, out of bare
 * literals at six `addCards` call sites in `Game.handleSpecialCards` that nothing
 * tied to what the player is told. These are pinned because they are the thing
 * that would otherwise drift: a card's worth changing in one place and not the
 * other is exactly the bug the centralisation exists to prevent.
 *
 * Robolectric because these are `R.string` resources, and the values come from
 * `strings.xml` through the generated `R` class rather than being written out
 * again here. A test that hardcoded the expected sentences would keep passing if
 * `strings.xml` were edited, which is the whole failure mode.
 *
 * `handleSpecialCards` itself is not driven. It needs `m_currCard` set by a real
 * turn, and the game loop that gets there plays out at random -- a test that
 * wanted a Draw Four would be playing until one appeared. What is pinned here is
 * the message the code formats, which is the half that changed; the arithmetic
 * that decides *when* each branch runs is `PenaltyTest` and the instrumented
 * `PenaltyStackTest`, and neither is touched by this release.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PenaltyMessageTest
{
	private val m_south = "South"
	private val m_north = "North"

	/**
	 * The raw format string behind a resource id, straight out of `strings.xml`.
	 *
	 * Resolved rather than written out, which is the point: a test that hardcoded
	 * the sentences would keep passing if `strings.xml` were edited, and editing
	 * `strings.xml` is the whole thing this release changes. `getString(resId)`
	 * with no arguments returns the template, format specifiers and all.
	 */
	private fun template (msgRes: Int): String
	{
		return RuntimeEnvironment.getApplication().getString (msgRes)
	}

	/**
	 * Formats one of the ten counted penalty messages as `Game.addCardPenalty`
	 * does: victim, cards owed, thrower.
	 *
	 * `String.format` on the resolved template rather than `getString(resId, args)`
	 * because the throw is under test below: `getString` swallows a
	 * `MissingFormatArgumentException` into an `Resources.NotFoundException`, which
	 * says nothing about which argument was missing.
	 */
	private fun penalty (msgRes: Int, victim: String, cards: Int, thrower: String): String
	{
		return String.format (template (msgRes), victim, cards, thrower)
	}

	/** First resource, stacked resource, and the card's name, for each counted penalty. */
	private val m_counted = listOf (
			Triple(R.string.msg_penalty_first_drawfour, R.string.msg_penalty_stacked_drawfour, "Draw Four"),
			Triple(R.string.msg_penalty_first_wild_hd, R.string.msg_penalty_stacked_wild_hd, "Hot Death"),
			Triple(R.string.msg_penalty_first_wild_db, R.string.msg_penalty_stacked_wild_db, "Delayed Blast"),
			Triple(R.string.msg_penalty_first_wild_hos, R.string.msg_penalty_stacked_wild_hos, "Harvester of Sorrows"),
			Triple(R.string.msg_penalty_first_wild_mystery, R.string.msg_penalty_stacked_wild_mystery, "Mystery Draw"))

	/**
	 * The message says what the player who has to act is about to do, before it
	 * says anything about who caused it. This is the issue as an assertion.
	 *
	 * Asserted on "<victim> draws" rather than on the victim's name appearing
	 * first, because both hold for the old wording by accident. The old strings
	 * also opened with argument one, and the victim was passed as argument one,
	 * so "starts with South" was true of "North threw a Draw Four - 4 to South"
	 * as well: the name was there and it was first, just saying the wrong thing
	 * about it. Only the verb distinguishes them.
	 */
	@Test
	fun everyPenaltyMessageLeadsWithWhatTheVictimMustDo ()
	{
		for ((first, stacked, name) in m_counted)
		{
			for (msgRes in listOf (first, stacked))
			{
				val text = penalty (msgRes, m_south, 4, m_north)

				assertTrue (
						"$name (msg $msgRes) should open by telling the victim they are drawing, got: $text",
						text.startsWith ("$m_south draws"))
			}
		}
	}

	/**
	 * The thrower is still named, and named second. Keeping it matters -- dropping
	 * it would lose what the old wording carried, who to be annoyed at -- and
	 * keeping it second is the other half of the change, so the name is required
	 * to come after the draw rather than merely to appear.
	 */
	@Test
	fun everyPenaltyMessageNamesTheThrowerAfterTheDraw ()
	{
		for ((first, stacked, name) in m_counted)
		{
			for (msgRes in listOf (first, stacked))
			{
				val text = penalty (msgRes, m_south, 4, m_north)

				val drawAt = text.indexOf ("$m_south draws")
				val throwerAt = text.indexOf (m_north)

				// drawAt is asserted non-negative here too, and not left to the test
				// above. Without it this one passes vacuously against the old
				// wording: drawAt would be -1, and any index of "North" beats -1.
				assertTrue ("$name (msg $msgRes) should say what the victim is drawing, got: $text",
						drawAt >= 0)
				assertTrue ("$name (msg $msgRes) should still say who threw it, got: $text",
						throwerAt >= 0)
				assertTrue ("$name (msg $msgRes) should name the thrower after the "
						+ "victim's draw, got: $text", throwerAt > drawAt)
			}
		}
	}

	/**
	 * First and stacked stay distinct strings saying different things about the
	 * same numbers. `addCards` overwrites the generating player on every call, so
	 * on a stack the name is whoever stacked; the stacked wording says so rather
	 * than implying they started it.
	 */
	@Test
	fun stackedAndFirstReadDifferently ()
	{
		for ((first, stacked, name) in m_counted)
		{
			val firstText = penalty (first, m_south, 8, m_north)
			val stackedText = penalty (stacked, m_south, 8, m_north)

			assertNotEquals ("$name should not use the same sentence for a first "
					+ "card and a stacked one", firstText, stackedText)
			assertTrue ("$name's stacked wording should say who stacked it, got: $stackedText",
					stackedText.contains ("stacked"))
		}
	}

	/**
	 * The count in the message is the running total, not the card's worth. A
	 * stacked Draw Four is still four cards each time, so the victim is told
	 * eight -- and the count arrives as a format argument rather than baked into
	 * the string, which is the whole reason it can differ per card.
	 */
	@Test
	fun aStackedPenaltyReportsTheRunningTotal ()
	{
		for ((first, stacked, name) in m_counted)
		{
			val text = penalty (stacked, m_south, 8, m_north)

			assertTrue ("$name should report the total owed, got: $text", text.contains ("8"))
			assertTrue ("$name should not report a single card's worth on a stack, got: $text",
					! text.contains (" draws 4 cards"))
		}
	}

	/**
	 * The one message with no count, because there is no count: a Mystery Draw onto
	 * a non-number costs the victim nothing. The old wording never said so -- it
	 * described the thrower and left the player who had to act to infer that they
	 * were fine.
	 */
	@Test
	fun mysteryDrawOnANonNumberSaysNothingIsDrawn ()
	{
		val text = String.format (template (R.string.msg_penalty_null_wild_mystery), m_south)

		assertTrue ("should say the victim draws nothing, got: $text", text.contains ("nothing"))
		assertTrue ("should still say which card it was, got: $text", text.contains ("Mystery Draw"))
		assertTrue ("should lead with the victim, got: $text", text.startsWith (m_south))
	}

	/**
	 * The counts themselves, from `Penalty`. These are the numbers the issue's
	 * table gives, and they are what the messages above are formatted against --
	 * pinning both is what stops the two drifting apart again.
	 */
	@Test
	fun penaltyCountsAreWhatTheCardCosts ()
	{
		assertEquals (4, Penalty.COUNT_DRAWFOUR)
		assertEquals (8, Penalty.COUNT_HOT_DEATH)
		assertEquals (4, Penalty.COUNT_DELAYED_BLAST)
		assertEquals (4, Penalty.COUNT_HARVESTER)
		assertEquals (69, Penalty.COUNT_YELLOW_69)
	}

	/**
	 * Each counted string takes exactly three arguments, and exactly those three.
	 *
	 * Both halves of that are worth checking, and they fail differently.
	 *
	 * A *missing* argument throws `MissingFormatArgumentException`, so formatting
	 * with two arguments and requiring a throw catches a specifier that lost one.
	 *
	 * A *surplus* specifier cannot be caught that way, because handing
	 * `String.format` more arguments than the template wants is legal and silently
	 * ignored -- it is the specifier that needs an argument, not the argument
	 * that is extra. So the template itself is read, and the argument indices it
	 * references have to be 1, 2 and 3 and nothing else. A `%4$s` left in
	 * `strings.xml` is invisible to every other test here, and throws on a penalty
	 * card in front of a player.
	 */
	@Test
	fun everyCountedMessageTakesExactlyThreeArguments ()
	{
		for ((first, stacked, name) in m_counted)
		{
			for (msgRes in listOf (first, stacked))
			{
				var threw = false
				try
				{
					String.format (template (msgRes), m_south, 4)
				}
				catch (e: java.util.MissingFormatArgumentException)
				{
					threw = true
				}
				assertTrue ("$name (msg $msgRes) should need three arguments, not two", threw)

				assertEquals ("$name (msg $msgRes) should reference arguments 1, 2 and 3 and no others",
						setOf (1, 2, 3), specifierIndices (template (msgRes)))
			}
		}
	}

	/**
	 * The one-argument string really is one argument, which is the case a blanket
	 * three-argument call would break. `handleSpecialCards` formats it with the
	 * victim alone, and nothing in the app ever passes it a second value.
	 */
	@Test
	fun theNonNumberMessageTakesOnlyOneArgument ()
	{
		String.format (template (R.string.msg_penalty_null_wild_mystery), m_south)

		assertEquals ("the non-number message takes exactly one argument",
				setOf (1),
				specifierIndices (template (R.string.msg_penalty_null_wild_mystery)))
	}

	/**
	 * The argument indices a format string references, as a set.
	 *
	 * Read off the raw template, which is the only place a stray specifier is
	 * visible: once `String.format` has run, the output no longer says how many
	 * arguments it wanted. A duplicated index is not caught either -- it renders
	 * the same value twice -- but nothing here can produce one, and a test that
	 * claimed to would be asserting that no code is wrong rather than that any is
	 * right.
	 */
	private fun specifierIndices (fmt: String): Set<Int>
	{
		return Regex("%(\\d+)\\$[a-zA-Z]")
			.findAll (fmt)
			.map { it.groupValues[1].toInt() }
			.toSet()
	}
}
