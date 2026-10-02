package com.smccloud.hotdeath

import java.util.Random
//import android.util.Log
import org.json.*

/**
 * What a wild scores when the seat would rather keep it, issue #6.
 *
 * Below the worst score any non-wild can reach, and above the -1000 that
 * maxpointval starts at, and both of those matter. Above -1000 so a wild in a
 * hand of wilds is still the card played rather than a draw; below every
 * non-wild so a wild is played only when the hand is otherwise stuck.
 */
private const val WILD_HOARD_VALUE = -60


open class ComputerPlayer : Player
{
	// Written out rather than left implicit: declaring any constructor here means
	// Kotlin stops generating a no-arg one, and both delegate straight up.
	constructor(g: Game?, go: GameOptions?) : super(g, go)
	{
	}

	constructor(o: JSONObject, g: Game, go: GameOptions?) : super(o, g, go)
	{
	}

	override fun chooseNumCardsToDeal ()
	{
		val rgen = Random()
		m_numCardsToDeal = rgen.nextInt(11) + 5
	}

	override fun startTurn ()
	{
		this.m_wantsToDraw = false
		this.m_wantsToPass = false
		this.m_wantsToPlayCard = false

		m_game!!.waitABit ()

		if (!m_hand!!.hasValidCards(m_game!!))
		{
			// if we have no valid cards, we either pass or draw, depending on
			// whether we've already drawn...

			if (this.m_lastDrawn != null)
			{
				// if we just drew a card, then we have to pass
				this.m_wantsToPass = true
				return
			}

			// otherwise, we can draw
			this.m_wantsToDraw = true
			return
		}

		this.playCard()

		return
	}

	// Widening the Java's protected to public here is the same change it needed
	// on Player: Game.java calls this through a Player reference, and Kotlin
	// `internal` would mangle the name out from under it.
	override fun drawCard ()
	{
		m_game!!.waitABit ()
		super.drawCard()
	}

	/**
	 * Declare a colour, which is the only place a computer seat leads a colour.
	 *
	 * A seat leads by playing a wild -- Draw Four, the Mystery, the Hot Death --
	 * or one of the specials that ignores the current colour, and then naming
	 * here what the next player has to follow. Everything else it plays was
	 * already constrained to the colour on the table, so this method and nothing
	 * else is where "what colour do I want this round to be" gets asked.
	 *
	 * The Java had `FIXME -- we need a real strategy` here and took the colour it
	 * held the most of. That is half a strategy: it maximises the number of
	 * cards the seat can follow its own lead with, and it is all a player
	 * watching would see the Expert doing wrong -- leading the colour the whole
	 * table can already match.
	 *
	 * So the count still decides, and what the seat can follow is still the first
	 * thing it counts, but a colour the opponents are likely to be holding is
	 * charged for. Expert takes the cheapest colour by that measure; Skill 0 and 1
	 * keep the colour they held most of, which is the behaviour they always had.
	 */
	override fun chooseColor(): Int
	{
		m_game!!.waitABit()

		// Skill is read here rather than trusted from an earlier call because
		// this method is reachable without playCard having run -- Game asks for
		// the colour after it has taken the card, and the two are not always in
		// that order. m_skill defaults to 1, so a stale read would quietly play
		// an Expert seat at Strong.
		readAggressionAndSkill()

		var maxCount = 0
		var maxColor = 0

		if (m_hand!!.getNumCards() == 0)
		{
			return Card.COLOR_RED
		}

		if (m_skill >= 2)
		{
			// Score every colour the seat can actually follow with, and lead the
			// one where holding cards costs least. A colour it holds nothing of is
			// never a candidate: naming a colour it cannot play is a wasted lead
			// whatever the opponents are holding.
			var bestScore = Double.NEGATIVE_INFINITY

			for (i in Card.COLOR_RED until Card.COLOR_WILD)
			{
				val held = m_hand!!.countSuit(i)
				if (held == 0)
				{
					continue
				}

				val score = held - colorPressure(i)

				if (score > bestScore)
				{
					bestScore = score
					maxColor = i
				}
			}
		}

		if (maxColor == 0)
		{
			for (i in Card.COLOR_RED until Card.COLOR_WILD)
			{
				val cnt = m_hand!!.countSuit(i)
				if (cnt > maxCount)
				{
					maxCount = cnt
					maxColor = i
				}
			}
		}

		m_chosenColor = maxColor

		if (maxColor == 0)
		{
			// should never happen, but just in case...
			return Card.COLOR_RED
		}

		return m_chosenColor
	}

	/**
	 * How many cards of [color] the table cannot account for, sub-item 2.
	 *
	 * Three public facts go in: the deck is a known list of cards, this seat can
	 * count its own hand, and the discard pile is face up. What is left over is
	 * either in the draw pile or in an opponent's hand, and the difference between
	 * those two is what makes the leftover worth measuring.
	 *
	 * Deliberately not `getDeck().getCard(i).getHand()`. That would return the
	 * exact contents of every opponent's hand -- the deck holds references to the
	 * same Card objects the hands do -- which is a stronger answer than a player
	 * at the table could ever have. Counting the leftovers keeps the AI inside the
	 * information a player is given.
	 *
	 * Wilds are excluded by the caller summing only the four colours, which is
	 * right for the question being asked: a wild is not a card an opponent can
	 * match a lead with.
	 */
	private fun countUnaccountedInColor (color: Int): Int
	{
		val deck = m_game!!.getDeck() ?: return 0

		var inDeck = 0
		val deckCards = deck.getCards()
		for (i in 0 until deck.getNumCards())
		{
			if (deckCards[i]!!.getColor() == color)
			{
				inDeck++
			}
		}

		var inDiscard = 0
		val discard = m_game!!.getDiscardPile()
		if (discard != null)
		{
			for (i in 0 until discard.getNumCards())
			{
				if (discard.getCard(i)!!.getColor() == color)
				{
					inDiscard++
				}
			}
		}

		// Floored at 0 rather than allowed to go negative: a hand holding a copy
		// the deck does not list means the deck was rebuilt underneath a live
		// hand, and a negative "unaccounted" would make the probabilities below
		// mean the opposite of what they say.
		val unaccounted = inDeck - m_hand!!.countSuit(color) - inDiscard

		return if (unaccounted > 0) unaccounted else 0
	}

	/**
	 * The chance that an opponent holding [numCards] cards holds at least one
	 * [color] card, between 0 and 1.
	 *
	 * The model is the plain one: an opponent's cards are a uniform draw from the
	 * cards the table cannot account for, which is what shuffling and dealing
	 * leaves you with. So the chance that none of theirs is [color] is
	 *
	 *     (pool - inHands)/pool * (pool - inHands - 1)/(pool - 1) * ...
	 *
	 * over their [numCards], and the answer is one minus that.
	 *
	 * `inHands` is not the whole leftover count of the colour. Whatever is in the
	 * draw pile is not in anybody's hand, and the draw pile is a uniform share of
	 * the leftovers, so of the [color] leftovers only
	 *
	 *     leftover * (pool - drawPile) / pool
	 *
	 * can be in a hand. Charging the draw pile its share is what makes this an
	 * estimate that tightens as the round runs: early, when the draw pile holds
	 * most of what nobody can see, it says "probably"; late, when the draw pile is
	 * nearly gone and the leftovers really are in hands, it says nearly certainly.
	 */
	private fun chanceHoldsColor (color: Int, numCards: Int): Double
	{
		if (numCards <= 0)
		{
			return 0.0
		}

		var pool = 0
		var inPool = 0

		for (i in Card.COLOR_RED until Card.COLOR_WILD)
		{
			val unaccounted = countUnaccountedInColor(i)
			pool += unaccounted

			if (i == color)
			{
				inPool = unaccounted
			}
		}

		// An opponent cannot be holding more cards than the table cannot
		// account for. Asking about more anyway is a position the model cannot
		// describe, and the answer is "no idea", which here means "no chance of
		// holding the colour" -- the safe direction, since this only ever makes a
		// lead look safer to follow than it is, and never makes it look safe to
		// ignore a threat.
		if (pool <= 0 || numCards > pool)
		{
			return 0.0
		}

		val drawPileSize = m_game!!.getDrawPile()?.getNumCards() ?: 0

		// `inPool * (pool - drawPileSize)` on its own is the missing half of this
		// line and it is not a rounding argument, it is the whole discount. It
		// asks how many cards of this colour are unaccounted for and how many of
		// the unaccounted cards the draw pile could be holding, and multiplying
		// those two gives a number that is bigger than the pool -- by up to a
		// factor of `pool`, since the draw pile's share of the colour is already
		// inside `inPool`. Without the division the answer saturates the clamp
		// below for any draw pile small enough to matter, so the estimate read
		// "certain" from the first turn of a round and stopped tightening until
		// the draw pile was nearly gone.
		//
		// Integer division floors, so a colour can come out as 0 while there is
		// still one card of it unaccounted for. That is the right direction to err:
		// it charges the colour nothing and lets the plain card count lead, rather
		// than inventing a threat that is not there.
		var inHands = inPool * (pool - drawPileSize) / pool

		if (inHands > pool)
		{
			inHands = pool
		}
		if (inHands <= 0)
		{
			return 0.0
		}

		// No pigeonhole shortcut for the case where `numCards > pool - inHands`.
		// There would be fewer non-colour cards in the pool than cards in the
		// opponent's hand, so the answer is 1, and the loop already reaches it:
		// the numerators run `pool - inHands`, `pool - inHands - 1`, ... and the
		// factor at `i = pool - inHands` is exactly zero, which zeroes the product
		// before any numerator can go negative. Checked over every pool, inHands
		// and numCards up to 60, the loop and the shortcut agree to the last bit
		// and neither leaves [0,1].
		var noneOfThem = 1.0

		for (i in 0 until numCards)
		{
			noneOfThem *= (pool - inHands - i).toDouble() / (pool - i).toDouble()
		}

		return 1.0 - noneOfThem
	}

	/**
	 * What leading [color] is expected to cost, in cards held.
	 *
	 * One card of chance from each active opponent, weighted by how close they
	 * are to going out: the same card is worth much more from a player on their
	 * last card, who can play it and win the round outright, than from a player
	 * with a hand full. `4 / (numCards + 1)` is 2.0 at one card, 1.0 at three,
	 * and about 0.36 at ten.
	 *
	 * The result is subtracted from the count of [color] in this seat's hand, so
	 * what it is really saying is: lead the colour you can most afford to have
	 * followed at you.
	 */
	private fun colorPressure (color: Int): Double
	{
		var pressure = 0.0

		for (i in 0 until 4)
		{
			val p = m_game!!.getPlayer(i)!!

			if (p == this)
			{
				continue
			}

			if (!p.getActive())
			{
				continue
			}

			val numCards = p.getHand()!!.getNumCards()

			pressure += chanceHoldsColor(color, numCards) * (4.0 / (numCards + 1.0))
		}

		return pressure
	}

	/**
	 * Take this seat's difficulty from the preferences.
	 *
	 * There are three pairs of settings for three opponents, and the settings
	 * screen says so: the categories are titled West, North and East, because
	 * those are the three seats a computer holds in a normal game. South is the
	 * human's.
	 *
	 * So the `else` below is not a fourth setting that ran out -- it is South,
	 * the seat the human would otherwise occupy, playing when `computer_4th` has
	 * replaced them. It takes North's pair. That is a choice, and it was the
	 * cheaper of the two available ones: a fourth pair would mean a fourth pair
	 * of keys in Prefs, a fourth category in preferences.xml, and four
	 * difficulty controls on the main settings screen for a mode that leaves no
	 * human in the game at all. A game with four computers is a simulation, and
	 * difficulty knobs for a simulation are not a setting anybody asked for.
	 *
	 * `computer_4th` is off by default and its summary calls it "mostly useful
	 * for testing", so this is a testing affordance that is reachable rather than
	 * one that is hidden.
	 *
	 * The summary string says the fourth seat plays at North's difficulty, so
	 * somebody who turns the option on is not left guessing which of the three
	 * sections it belongs to.
	 *
	 * `else` rather than an explicit `SEAT_SOUTH` test on purpose: a seat of 0
	 * would also land here, and refusing to set anything would leave the class
	 * defaults, which is a different behaviour to reason about in a path that
	 * cannot currently be reached. Game's constructors call setSeat on all four
	 * seats before any of them plays.
	 */
	fun readAggressionAndSkill()
	{
		if (m_seat == Game.SEAT_WEST)
		{
			m_aggression = m_go!!.getP1Agg()
			m_skill = m_go!!.getP1Skill()
		}
		else if (m_seat == Game.SEAT_NORTH)
		{
			m_aggression = m_go!!.getP2Agg()
			m_skill = m_go!!.getP2Skill()
		}
		else if (m_seat == Game.SEAT_EAST)
		{
			m_aggression = m_go!!.getP3Agg()
			m_skill = m_go!!.getP3Skill()
		}
		else
		{
			m_aggression = m_go!!.getP2Agg()
			m_skill = m_go!!.getP2Skill()
		}
	}


	fun playCard()
	{
		readAggressionAndSkill();

		var maxpointval = -1000
		var bestcard: Card? = null

		// The balance change of whichever card is currently in bestcard, for the
		// Expert tie-break. Positive infinity so the first legal card takes the slot
		// outright rather than having to beat an opinion nobody holds yet.
		var bestBalanceChange = Double.POSITIVE_INFINITY

		m_hand!!.calculateValue();

		m_wantsToPass = false;

		// if we just drew something, we can either hold it or throw it;
		// for now, we always throw it if it's valid
		if (m_lastDrawn != null)
		{
			if (m_game!!.checkCard(m_hand!!, m_lastDrawn!!, false))
			{
				m_playingCard = m_lastDrawn
			}
			else
			{
				m_wantsToPass = true
			}
		}

		if (bestcard == null)
		{
			// sophisticated players can hold onto expensive wild cards until later
			// in the game.
			val opponent_card_count = this.getMinCardsRemaining();

			// The one case where hoarding a wild is the wrong instinct: somebody is
			// on their last card. A wild is the only card in the deck that changes
			// the colour at will, so it is the only way to stop a player who is
			// about to go out from simply following whatever colour we lead -- and
			// drawing four is a card we would rather hold, but not worth holding if
			// it is the difference between winning and losing the round.
			val wild_is_worth_spending = (opponent_card_count <= 1)

			//Log.d("HDU", "Looking for card to play...");
			for (i in 0 until m_hand!!.getNumCards())
			{
				val tc = m_hand!!.getCard(i)!!

				//Log.d("HDU", " - considering card: " + m_game.cardToString(tc));

				if (!m_game!!.checkCard(m_hand!!, tc, false))
				{
					continue
				}

				val bIsDefender = m_game!!.getLastCardCheckedIsDefender()
				// if we've got a defender, play it
				if (bIsDefender)
				{
					bestcard = tc
					break
				}

				// otherwise, try to find the one with the highest point value
				// so we can toss it out of our hand

				// see issue #6 for the wider AI work
				//   - throw the mystery draw on numbered cards
				//   - don't throw MAD when point count in hand is too high (unless player count < 4)
				//   - consider the damage of offensive cards; look to hit players with low card counts
				//   - advanced: throw the MAD card to catch an opponent and force him over 1000 points,
				//     even if you have hundreds of points, if you're assured of winning

				var testval = 0

				if (this.m_skill >= 1)
				{
					// Strong and Expert
					if (tc.getColor() == Card.COLOR_WILD)
					{
						// Hoard it.
						//
						// The Java here read
						//
						//     if (wild_count < opponent_card_count - 1) testval = 0
						//
						// with testval already 0 from its initialiser, so both arms
						// assigned 0 and the condition chose nothing. Hoarding is what
						// the condition was reaching for, and `wild_count` went with
						// it: the count it was testing no longer has a use.
						//
						// Scoring below the worst a non-wild can reach is what makes
						// this hoard rather than a preference. Every numbered card is
						// worth its own value, the MAD card bottoms out at -20, and a
						// Magic 5 -- legal on anything -- is -5, so with the colour
						// balance penalty on top the lowest any of them can score is
						// -45. A wild at -60 loses to all of them and is reached only
						// when nothing else in the hand is legal, which is exactly
						// "the hand is otherwise stuck".
						//
						// It stays above maxpointval's initialiser of -1000 so that a
						// wild in a hand of wilds is still played; if it scored lower,
						// bestcard would stay null and the seat would draw instead of
						// playing the only card it holds.
						testval = if (wild_is_worth_spending) 0 else WILD_HOARD_VALUE
					}
					else
					{
						testval = tc.getCurrentValue()
					}

					if ((tc.getID() == Card.ID_YELLOW_1_MAD) && (m_game!!.getActivePlayerCount() > 3))
					{
						// don't throw the MAD card if the value of your own hand
						// will be too high
						val newHandValue = m_hand!!.calculateValue(false, tc)

						if (newHandValue < 10)
						{
							testval = 100
						}
						else if (newHandValue < 20)
						{
							testval = 70
						}
						else if (newHandValue < 50)
						{
							testval = 0
						}
						else
						{
							testval = -20
						}
					}

					if (tc.getID() == Card.ID_YELLOW_69)
					{
						// it's hard to directly calculate the value of the 69 without looking
						// at the whole hand -- if you've got hundreds of points in your hand,
						// the 69 is a good card to keep, because it locks your score in at
						// 69, no matter how much other junk you've got in your hand.
						val oldHandValue = m_hand!!.calculateValue()
						val newHandValue = m_hand!!.calculateValue(false, tc)

						testval = oldHandValue - newHandValue
					}

					if (tc.getID() == Card.ID_WILD_MYSTERY)
					{
						// You've GOT to throw the mystery draw on a 69!  That's the whole fun of the
						// game.  You want to avoid throwing it on non-numbered cards, and the higher the
						// numbered card, the more you want to throw it...
						val lpc = m_game!!.getLastPlayedCard()!!
						val lpv = lpc.getValue()

						if (lpc.getID() == Card.ID_YELLOW_69)
						{
							testval = 200
						}
else if (lpv > 0)
					{
						if(lpv < 5)
						{
							testval = 15
						}
						else if (lpv < 8)
						{
							testval = 30
						}
						else if (lpv < 10)
						{
							testval = 50
						}
					}

					// Falling out of that ladder is not a vote to play it. The comment
					// above says to keep the Mystery off non-numbered cards, and a
					// wild on a wild or a special is the case that used to score 0 and
					// win a tie against any other worthless card. It now falls through
					// to WILD_HOARD_VALUE like any other wild, so the sentence is the
					// code rather than the intention.

					}
				}
				else
				{
					// even weak players can do this
					testval = tc.getCurrentValue()
				}

				//Log.d("HDU", "   - testval: " + testval);

				if (this.m_skill >= 2)
				{
					// Expert
					var considerColorBalance = false

					// the higher the aggression, the longer the player is willing to try to
					// maintain color balance in his hand; for example, an aggression level 6
					// is willing to wait until the opponent has 2 cards left; it's a bit like
					// a game of chicken...
					if ((opponent_card_count + m_aggression / 3) > 3)
					{
						considerColorBalance = true
					}

					// Computed whether or not the aggression threshold opened the
					// bonus below, because the tie-break further down needs it on
					// every card and this is the one pass over the hand that
					// produces it.
					val colorBalanceImprovement = computeChangeInColorBalance(tc)

					if (considerColorBalance)
					{
						// getting closer to 0 is a good thing
						if (colorBalanceImprovement < -0.5)
						{
							// this could really be a good thing to play
							testval += 40
						}
						else if (colorBalanceImprovement < -0.25)
						{
							// this could be a good thing to play
							testval += 20
						}
						else if (colorBalanceImprovement > 0.5)
						{
							// really don't want to play this
							testval -= 40
						}
						else if (colorBalanceImprovement > 0.25)
						{
							// don't want to play this
							testval -= 20
						}

						//Log.d("HDU", "   - colorbalanceimprovement: " + colorBalanceImprovement + ", testval: " + testval);


					}

					// The tie-break, and the other half of sub-item 3. Above, the
					// balance figure scores a card; it never chose between two
					// cards of equal score, because `>=` handed those to whichever
					// one the hand happened to store last. Two numbered cards of
					// the same value in two colours are the common case, and the
					// choice between them was the order of the hand array.
					//
					// This picks the one that leaves the hand best balanced, so
					// the colour the seat leads in `chooseColor` is the colour it
					// then actually plays.
					//
					// `isFinite` is doing real work: computeChangeInColorBalance
					// divides by the balance before the play, and a hand whose four
					// suit counts are all equal has a balance of 0, so the change
					// is 0/0 or x/0 and comes back NaN or infinite. Every
					// comparison against NaN is false, which would silently make
					// this tie-break drop the card instead of keeping it, so it is
					// asked explicitly whether there is an opinion at all.
					//
					// Skill 0 and 1 keep `>=` and the last card wins, which is
					// arbitrary but is the behaviour they have always had, and the
					// point of issue #6 was the Expert play rather than a rewrite of
					// the other two.
					if ((testval > maxpointval)
						|| ((testval == maxpointval)
							&& colorBalanceImprovement.isFinite()
							&& bestBalanceChange.isFinite()
							&& (colorBalanceImprovement < bestBalanceChange)))
					{
						maxpointval = testval
						bestBalanceChange = colorBalanceImprovement
						bestcard = tc
					}
				}
				else if (testval >= maxpointval)
				{
					maxpointval = testval
					bestcard = tc
				}
			}
		}

		if (bestcard != null)
		{
			m_playingCard = bestcard
			m_wantsToPlayCard = true
		}
		else
		{
			// nothing to play
			this.m_wantsToDraw = true
		}

	}

	fun computeChangeInColorBalance (c: Card): Double
	{
		val balanceBefore = computeColorBalance(null)
		val balanceAfter = computeColorBalance(c)

		val delta = (balanceAfter - balanceBefore) / balanceBefore

		return delta
	}

	// Nullable because computeChangeInColorBalance asks for the "before" figure
	// by passing null, which means "do not subtract a card".
	fun computeColorBalance (c: Card?): Double
	{
		val colorTotals = IntArray(4)

		for (i in 0 until m_hand!!.getNumCards())
		{
			when (m_hand!!.getCard(i)!!.getColor())
			{
				Card.COLOR_RED -> colorTotals[0]++
				Card.COLOR_GREEN -> colorTotals[1]++
				Card.COLOR_BLUE -> colorTotals[2]++
				Card.COLOR_YELLOW -> colorTotals[3]++
			}
		}

		if (c != null)
		{
			when (c.getColor())
			{
				Card.COLOR_RED -> colorTotals[0]--
				Card.COLOR_GREEN -> colorTotals[1]--
				Card.COLOR_BLUE -> colorTotals[2]--
				Card.COLOR_YELLOW -> colorTotals[3]--
			}
		}

		// int/int, then widened -- the Java computed the mean with integer
		// division and only then stored it in a double, so a hand of 5 cards
		// averages 1, not 1.25. The intermediate is spelled out because Kotlin
		// will not implicitly widen an Int into a Double; writing / 4.0 here
		// would quietly change every colour-balance score the Expert AI computes.
		val avgcount = (colorTotals[0] + colorTotals[1] + colorTotals[2] + colorTotals[3]) / 4
		val avg = avgcount.toDouble()

		var balance = 0.0

		for (i in 0 until 4)
		{
			balance += Math.pow ((colorTotals[i] - avg), 2.0)
		}

		balance = Math.sqrt (balance)

		return balance
	}


	// find the minimum remaining cards of all other players at the table
	fun getMinCardsRemaining(): Int
	{
		var min_cards = 1000000

		for (i in 0 until 4)
		{
			val p = m_game!!.getPlayer(i)!!

			if (p == this)
			{
				continue
			}

			if (p.getActive() == false)
			{
				continue
			}

			val num_cards = p.getHand()!!.getNumCards()
			if (num_cards < min_cards)
			{
				min_cards = num_cards
			}
		}

		return min_cards
	}


	override fun addCardToHand(c: Card)
	{
		super.addCardToHand(c);

		readAggressionAndSkill();
	}


	override fun finishTrick()
	{
		readAggressionAndSkill();
	}




	override fun chooseVictim()
	{
		m_game!!.waitABit ();

		// weak skill level
		if (m_skill == 0)
		{
			// find the first player who is still active
			if (m_aggression > 3)
			{
				// be nasty and go after the south player first
				for (i in 0..3)
				{
					// don't punish yourself
					if (m_seat == i + 1)
					{
						continue
					}
					if ((m_game!!.getPlayer(i)!!).getActive())
					{
						m_chosenVictim = i + 1
						return
					}
				}
			}
			else
			{
				// find the next player, ccw from East
				for (i in 3 downTo 0)
				{
					// don't punish yourself
					if (m_seat == i + 1)
					{
						continue
					}
					if ((m_game!!.getPlayer(i)!!).getActive())
					{
						m_chosenVictim = i + 1
						return
					}
				}

			}
		}

		// strong and expert
		var minpoints = 1000000
		var minplayer = 0

		// find the next player
		for (i in 3 downTo 0)
		{
			// don't punish yourself
			if (m_seat == i + 1)
			{
				continue
			}

			if (!(m_game!!.getPlayer(i)!!).getActive())
			{
				continue
			}

			var score = (m_game!!.getPlayer(i)!!).getTotalScore()

			// artificially inflate or deflate south's score based on
			// player aggression
			if (i == Game.SEAT_SOUTH - 1)
			{
				score -= 25 * m_aggression
			}
			if (score < minpoints)
			{
				minplayer = i + 1
				minpoints = score
			}
		}

		m_chosenVictim = minplayer
	}




}
