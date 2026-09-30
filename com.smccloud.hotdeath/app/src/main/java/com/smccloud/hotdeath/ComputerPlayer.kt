package com.smccloud.hotdeath

import java.util.Random
//import android.util.Log
import org.json.*


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

	override fun chooseColor(): Int
	{
		m_game!!.waitABit()

		// FIXME -- we need a real strategy
		var maxCount = 0
		var maxColor = 0

		if (m_hand!!.getNumCards() == 0)
		{
			return Card.COLOR_RED
		}

		for (i in Card.COLOR_RED until Card.COLOR_WILD)
		{
			val cnt = m_hand!!.countSuit(i)
			if (cnt > maxCount)
			{
				maxCount = cnt
				maxColor = i
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
		// this is only here so that we can have a 4th computer player
		// if we are testing.
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
			var wild_count = 0
			for (i in 0 until m_hand!!.getNumCards())
			{
				val tc = m_hand!!.getCard(i)!!
				if (tc.getColor() == Card.COLOR_WILD)
				{
					wild_count++
				}
			}

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

				// TODO: improve this AI
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
						if (wild_count < opponent_card_count - 1)
						{
							testval = 0
						}
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

					if (considerColorBalance)
					{
						val colorBalanceImprovement = computeChangeInColorBalance(tc)

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
				}

				if (testval >= maxpointval)
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
