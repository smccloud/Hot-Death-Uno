package com.smccloud.hotdeath

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Covers which preferences a computer seat takes its difficulty from, issue #7.
 *
 * The settings screen has three skill/aggression pairs, under categories titled
 * West, North and East. South is the human's seat. When `computer_4th` replaces
 * the human there is no fourth pair to read, and `readAggressionAndSkill` falls
 * through to North's. That is a decision rather than an oversight, and the
 * decision is recorded in the comment on the method; these tests are what stop it
 * from drifting back into looking accidental.
 *
 * Each pair is given a distinct skill and aggression here, so a seat reading the
 * wrong pair fails on both halves rather than passing because two settings
 * happened to agree. Skill is 0, 1, 2 across the three pairs and aggression is
 * -6, 0, 6, both from the ranges in `arrays.xml`, so no pair is a copy of
 * another and every seat is distinguishable from every other.
 *
 * What a fourth pair would cost is recorded at the method, not repeated here:
 * two more keys in `Prefs`, two more `ListPreference` entries in
 * `preferences.xml`, and a `player4` category title. If someone adds one, the
 * `southReadsNorths` test below fails and says what changed.
 */
// PAUSED, which is the Robolectric 4.x default. Nothing here is posted or
// waited on: the test writes preferences, builds an activity with get() so
// onCreate never runs, and reads two fields back off the player by reflection.
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComputerDifficultyTest
{
	private lateinit var m_options: GameOptions

	// One distinct (skill, aggression) per preference pair. Skill 0/1/2 and
	// aggression -6/0/6, so the three pairs are three different answers.
	private val m_p1 = listOf(0, -6)
	private val m_p2 = listOf(1, 0)
	private val m_p3 = listOf(2, 6)

	@Before
	fun setUp ()
	{
		Prefs.defaultSharedPreferences(
			ApplicationProvider.getApplicationContext())
			.edit()
			.putString("p1_skill", m_p1[0].toString())
			.putString("p1_aggression", m_p1[1].toString())
			.putString("p2_skill", m_p2[0].toString())
			.putString("p2_aggression", m_p2[1].toString())
			.putString("p3_skill", m_p3[0].toString())
			.putString("p3_aggression", m_p3[1].toString())
			.commit()

		m_options = GameOptions(Robolectric.buildActivity(GameActivity::class.java).get())
	}

	/**
	 * Reads a seat's skill and aggression back off the player.
	 *
	 * `m_skill` and `m_aggression` are `protected` in `Player`, which is neither
	 * package-private nor open for a test to reach, so reflection is the honest
	 * option -- the same choice `HandPlayabilityTest` makes for `Game`'s fields.
	 * A rename fails here rather than as a null at the assertion below.
	 */
	private fun difficultyOf (seat: Int): Pair<Int, Int>
	{
		val activity = Robolectric.buildActivity(GameActivity::class.java).get()
		val player = ComputerPlayer(Game(activity, m_options), m_options)
		player.setSeat(seat)

		player.readAggressionAndSkill()

		return readInt(player, "m_skill") to readInt(player, "m_aggression")
	}

	/** The three opponents each read their own pair. */
	@Test
	fun eachComputerSeatReadsItsOwnSettings ()
	{
		assertEquals ("West is p1", m_p1[0] to m_p1[1], difficultyOf(Game.SEAT_WEST))
		assertEquals ("North is p2", m_p2[0] to m_p2[1], difficultyOf(Game.SEAT_NORTH))
		assertEquals ("East is p3", m_p3[0] to m_p3[1], difficultyOf(Game.SEAT_EAST))
	}

	/**
	 * The decision issue #7 asked to be made explicit: the fourth computer plays at
	 * North's difficulty.
	 *
	 * This is the test that would fail if someone added a fourth pair of
	 * preferences, and it is meant to -- a new knob for the 4th seat is a
	 * deliberate act with preferences.xml and Prefs behind it, not something to
	 * arrive at by editing an `else`.
	 */
	@Test
	fun southReadsNorths ()
	{
		assertEquals ("South takes p2, the same pair as North",
				m_p2[0] to m_p2[1], difficultyOf(Game.SEAT_SOUTH))
	}

	/**
	 * And South is not being handed West's or East's by accident. The mapping is a
	 * chain of `else if`, so a reordered comparison would show up as a copy of the
	 * wrong pair while every other test still passed.
	 */
	@Test
	fun southReadsNorthsAndNotTheOthers ()
	{
		val south = difficultyOf(Game.SEAT_SOUTH)

		assertEquals ("must not be West's", false, south == (m_p1[0] to m_p1[1]))
		assertEquals ("must not be East's", false, south == (m_p3[0] to m_p3[1]))
	}

	/** The three pairs are genuinely distinct, so the tests above can tell them apart. */
	@Test
	fun theThreePairsAreDistinguishable ()
	{
		val pairs = listOf(m_p1, m_p2, m_p3)

		assertEquals ("p1 and p2 must differ", false, m_p1 == m_p2)
		assertEquals ("p1 and p3 must differ", false, m_p1 == m_p3)
		assertEquals ("p2 and p3 must differ", false, m_p2 == m_p3)
		assertEquals (3, pairs.distinct().size)
	}

	private fun readInt (target: Any, name: String): Int
	{
		var c: Class<*>? = target.javaClass
		while (c != null)
		{
			try
			{
				val f = c.getDeclaredField(name)
				f.isAccessible = true
				return f.getInt(target)
			}
			catch (e: NoSuchFieldException)
			{
				c = c.superclass
			}
		}
		throw AssertionError("${target.javaClass.simpleName}.$name is gone or renamed")
	}
}
