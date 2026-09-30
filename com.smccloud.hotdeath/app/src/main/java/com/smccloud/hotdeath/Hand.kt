package com.smccloud.hotdeath

import java.util.Random
import org.json.*

class Hand
{
	private var m_player: Player?
	private var m_cards: Array<Card?>
	private var m_numCards: Int

	fun getCards(): Array<Card?>
	{
		return m_cards
	}

	fun getNumCards(): Int
	{
		return m_numCards
	}

	// Nullable because it returns null for an out-of-range index, which the
	// Java did too: HandTest asserts on getCard(-1), getCard(1) on a one-card
	// hand, and getCard(Game.MAX_NUM_CARDS).
	fun getCard(i: Int): Card?
	{
		if (i < 0 || i > m_numCards - 1)
		{
			return null
		}
		return m_cards[i]
	}

	// Nullable, and not incidentally: HandTest and JsonRoundTripTest build
	// hands with new Hand(null) in 40-odd places, and CardTest does the same.
	// A non-null parameter here would compile an Intrinsics.checkNotNullParameter
	// onto the entry and turn all of those into runtime failures.
	constructor(p: Player?)
	{
		m_player = p
		m_numCards = 0
		m_cards = arrayOfNulls<Card>(Game.MAX_NUM_CARDS)
	}

	constructor(o: JSONObject, p: Player?, d: CardDeck)
	{
		m_player = p
		m_numCards = 0
		m_cards = arrayOfNulls<Card>(Game.MAX_NUM_CARDS)

		val a = o.getJSONArray("cards")
		val numCards = a.length()
		for (i in 0 until numCards)
		{
			// A bad deck index gives a null card, and the Java handed that null
			// straight to addCard, which threw on the setHand. Same here.
			this.addCard (d.getCard(a.getInt(i))!!)
		}
	}

	fun reset()
	{
		for (i in 0 until m_numCards)
		{
			m_cards[i]!!.setHand(null)
		}
		m_cards = arrayOfNulls<Card>(Game.MAX_NUM_CARDS)
		m_numCards = 0
	}

	fun addCard (c: Card)
	{
		m_cards[m_numCards++] = c
		c.setHand(this)
	}

	fun swapCard (c: Card): Card?
	{
		val rgen = Random()
		val cnum = rgen.nextInt (m_numCards)

		val oc = m_cards[cnum]

		m_cards[cnum] = c
		c.setHand(this)
		// cnum is below m_numCards, so the slot it picks is a live card.
		oc!!.setHand(null)
		return oc
	}

	fun setFaceUp(f: Boolean)
	{
		for (i in 0 until m_numCards)
		{
			m_cards[i]!!.setFaceUp(f)
		}
	}

	fun removeCard (c: Card)
	{
		var bRemoving = false

		for (i in 0 until m_numCards)
		{
			if (m_cards[i] == c)
			{
				bRemoving = true
			}
			if (bRemoving)
			{
				val cnew = if (i == m_numCards - 1) null else m_cards[i+1]
				m_cards[i] = cnew
			}
		}
		if (bRemoving)
		{
			c.setHand(null)
			m_cards[m_numCards - 1] = null
			m_numCards--
		}
	}

	fun isInHand(c: Card): Boolean
	{
		for (i in 0 until m_numCards)
		{
			if (m_cards[i] == c) return true
		}
		return false
	}

	fun hasColorMatch(color: Int): Boolean
	{
		for (i in 0 until m_numCards)
		{
			val c = m_cards[i]!!
			if (color == c.getColor())
			{
				return true
			}
		}
		return false
	}

	fun isInHand(color: Int, value: Int): Boolean
	{
		for (i in 0 until m_numCards)
		{
			if ((m_cards[i]!!.getColor() == color)
			 && (m_cards[i]!!.getValue() == value))
			{
				return true
			}
		}
		return false
	}

	fun countSuit(color: Int): Int
	{
		var total = 0
		for (i in 0 until m_numCards)
		{
			if ((m_cards[i]!!.getColor()) == color)
			{
				total++
			}
		}
		return total
	}

	fun getLowestCard(color: Int): Card?
	{
		var retval: Card? = null

		var lowestval = 1000000
		for (i in 0 until m_numCards)
		{
			if ((color > 0) && (m_cards[i]!!.getColor() != color))
			{
				continue
			}

			val value = m_cards[i]!!.getValue()
			if (value < lowestval)
			{
				retval = m_cards[i]
				lowestval = value
			}
		}

		return retval
	}

	fun getHighestCard(color: Int): Card?
	{
		var retval: Card? = null

		var highestval = -1
		for (i in 0 until m_numCards)
		{
			if ((color > 0) && (m_cards[i]!!.getColor() != color))
			{
				continue
			}

			val value = m_cards[i]!!.getValue()
			if (value > highestval)
			{
				retval = m_cards[i]
				highestval = value
			}
		}

		return retval
	}

	fun getHighestNonTrump(color: Int): Card?
	{
		var retval: Card? = null

		var highestval = -1
		for (i in 0 until m_numCards)
		{
			if ((m_cards[i]!!.getColor()) == color)
			{
				continue
			}

			val value = m_cards[i]!!.getValue()
			if (value > highestval)
			{
				retval = m_cards[i]
				highestval = value
			}
		}

		return retval
	}

	fun replaceCard(c: Card, newc: Card)
	{
		for (i in 0 until m_numCards)
		{
			if (m_cards[i] == c)
			{
				m_cards[i] = newc
			}
		}
	}

	// Array<Card?>, because Game.sortHand builds a sparse `new Card[MAX_NUM_CARDS]`
	// and fills only the prefix it found; the Java signature was Card[] either way.
	fun reorderCards(cards: Array<Card?>)
	{
		for (i in 0 until m_numCards)
		{
			m_cards[i] = cards[i]
		}
	}

	fun hasValidCards(g: Game): Boolean
	{
		for (i in 0 until m_numCards)
		{
			// checkCard is package-private in Game.java. That is visible from
			// here: Kotlin resolves Java's package-private within the same
			// package, which is all this ever needed -- see the note in TODO.md.
			if (g.checkCard(this, m_cards[i]!!, false))
			{
				return true
			}
		}
		return false
	}

	fun calculateValue(): Int
	{
		return this.calculateValue(false)
	}

	fun calculateValue(isfinal: Boolean): Int
	{
		return this.calculateValue(isfinal, null)
	}

	/*
	This routine was overhauled for version 1.01.  It now has all sorts of
	special card knowledge, which I had tried to avoid.  The upshot is that
	the Cards have all sorts of variables that are useless now (like
	multipliers, etc.)

	To add new types of cards, you'd have to fix this routine, the CheckCard(),
	CheckForDefender(), and HandleSpecialCards() routines in Game.cpp.

	*/
	fun calculateValue(isfinal: Boolean, withoutCard: Card?): Int
	{
		var highest = 0
		var highestNum = 0
		var total = 0

		var cFuckYou: Card? = null
		var cShitter: Card? = null
		var cQuitter: Card? = null
		var cMystery: Card? = null
		var cBlueShield: Card? = null
		var cSixtyNine: Card? = null
		var cMagic5: Card? = null
		var cHolyDefender: Card? = null

		// Step 1.  Does the prior code check for an All-Bastards combination on the
		// final forced-draw (draw forced when a player goes out by laying a "draw" card)?
		// If not, check for this combination.  If true, then no further processing necessary.

		// THIS IS DONE IN Game.cpp; at the end of each AdvanceRound, we check to see
		// if anybody has the bastard cards.  This should also kick in at the end of
		// the game on a forced draw.

		// Step 2.  Check for 1000 point combination.  If true, then these
		// cards need to be skipped for all future processing, and set "total = 1000"
		for (i in 0 until m_numCards)
		{
			val c = m_cards[i]!!
			if (c == withoutCard)
			{
				continue
			}

			if (c.getID() == Card.ID_BLUE_0_FUCKYOU)
			{
				cFuckYou = c
			}
			if (c.getID() == Card.ID_YELLOW_0_SHITTER)
			{
				cShitter = c
			}
			if (c.getID() == Card.ID_GREEN_0_QUITTER)
			{
				cQuitter = c
			}
		}

		var bFullMonty = false
		if ((cFuckYou != null) && (cQuitter != null))
		{
			bFullMonty = true
			total = 1000

			// give the cards a value of 500 so that computer players will
			// want to unload the cards ASAP
			cFuckYou.setCurrentValue(500)
			cQuitter.setCurrentValue(500)
		}
		else
		{
			// if we've got the Shitter but not the others, we give a pseudo
			// value of 150 to the card so that computer players will want
			// to unload it ASAP
			if (cShitter != null)
			{
				cShitter.setCurrentValue(150)
			}
		}

		// ... and it has to survive step 3.  Step 3 ends every card it visits with
		// that card's real point value, and the Shitter's is 0, so without this the
		// 150 is gone before ComputerPlayer ever reads it.  Skipping the Shitter in
		// step 3, the way the F.U. and Quitter are already skipped under bFullMonty,
		// is what keeps it.  Re-applying 150 after the loop would work just as well,
		// but it is the fragile half of the two: any later setCurrentValue added to
		// step 3 would silently undo the repair.
		val bShitterPseudoValue = (cShitter != null) && !bFullMonty

		// Step 3.  This is the Big Step...  Process all "fixed value" cards.  This
		// step includes ALL CARDS except Mystery Wild, Blue Shield, 69, Magic 5,
		// F.U., and Holy Def.  It SHOULD include the shitter and virus VALUES, but
		// not their PENALTIES (you'll need to set a variable to indicate the shitter's
		// presence, and/or also increment a "number of virus infections" variable, for
		// use in later steps).  During this step, highest value card and highest numerical
		// card variables should be determined, .  Set "total += [each card's value]"
		for (i in 0 until m_numCards)
		{
			val c = m_cards[i]!!

			if (c == withoutCard)
			{
				continue
			}

			val id = c.getID()
			// if we're getting the 1000 point penalty, we don't need to
			// look at these three cards again
			if (bFullMonty
				&& ((id == Card.ID_BLUE_0_FUCKYOU)
			    || (id == Card.ID_YELLOW_0_SHITTER)
				|| (id == Card.ID_GREEN_0_QUITTER)))
			{
				continue
			}

			// A lone Shitter (or a Shitter beside a F.U. with no Quitter, which
			// does not make a full monty either) is not scored in step 3 for the
			// same reason the three above are not: its pseudo-value from step 2
			// stands, and step 10's floor is written against that value.
			if (bShitterPseudoValue
				&& (id == Card.ID_YELLOW_0_SHITTER))
			{
				continue
			}

			// increment the virus penalty if this is the end of the round.
			// m_player is null on a hand built by the tests, but that branch
			// needs isfinal and a green 3, and no test that has a null player
			// asks for a final score -- the four that do (HandPlayabilityTest)
			// use a real ComputerPlayer.  !! rather than ?. keeps the Java's NPE
			// for the case where it ever does happen with a real game.
			if (isfinal && (id == Card.ID_GREEN_3_AIDS))
			{
				m_player!!.setVirusPenalty(m_player!!.getVirusPenalty() + 10)
			}

			// don't assess points for these cards yet
			if (id == Card.ID_WILD_MYSTERY)
			{
				cMystery = c
				continue
			}

			if (id == Card.ID_BLUE_2_SHIELD)
			{
				cBlueShield = c
				continue
			}

			if (id == Card.ID_YELLOW_69) {
				cSixtyNine = c
				continue
			}

			if (id == Card.ID_RED_5_MAGIC)
			{
				cMagic5 = c
				continue
			}

			if (id == Card.ID_BLUE_0_FUCKYOU)
			{
				continue
			}

			if (id == Card.ID_RED_0_HD)
			{
				cHolyDefender = c
				continue
			}

			val pv = c.getPointValue()

			if (pv > highest) highest = pv

			if ((c.getValue() > 0) && (c.getValue() < 10)
				&& (c.getValue() > highestNum))
				highestNum = c.getValue()

			c.setCurrentValue(pv)
			total += pv
		}


		// Step 4.  Using "higest numerical card" variable from step 3, calculate
		// Mystery Wild value (if applicable).  If this value is higher than "Highest
		// Value Card" from previous step, adjust that variable as well (so that the ?W
		// could be the highest value card, something that is not possible in the
		// current code). Set "total += [?W value]"
		if (cMystery != null)
		{
			var pv: Int

			if (highestNum > 0)
			{
				pv = 10 * highestNum
			}
			else
			{
				pv = 10
			}

			if (pv > highest)
			{
				highest = pv
			}

			cMystery.setCurrentValue(pv)
			total += pv
		}


		// Step 5.  Using "Highest Value Card" variable from last two steps, calculate
		// Blue Shield value.  Set "total += [BS value]"
		if (cBlueShield != null)
		{
			cBlueShield.setCurrentValue(highest)
			total += highest
		}


		// Step 6.  If 69 card exists, then set "total = 69"
		if (cSixtyNine != null)
		{
			cSixtyNine.setCurrentValue(69 - total)
			total = 69
		}

		// Step 7.  If M5 card exists, then set "total -= 5"
		if (cMagic5 != null)
		{
			cMagic5.setCurrentValue(-5)
			total -= 5
		}

		// Step 8.  (Assuming not skipped due to Step 2) If F.U.
		// card exists, then set "total *= 2"
		if (!bFullMonty && (cFuckYou != null))
		{
			cFuckYou.setCurrentValue(total)
			total *= 2
		}

		// Step 9.  If Holy Def. card
		// exists, then set "total /= 2" (rounded up, if necessary)
		if (cHolyDefender != null)
		{
			val newtotal = (total + 1) / 2
			cHolyDefender.setCurrentValue(newtotal - total)
			total = newtotal
		}

		// Step 10.  You now have a good "total" with which to calculate the shitter
		// penalty.  If another players's hand is higher than "total" then set
		// "total = [other player's hand]"

		// When the round is over, the game object applies the shitter penalty, since the game
		// has knowledge of all final scores and can apply the highest one.
		// However, for mid-game score estimates, we can apply our artificial value for
		// the shitter right here.
		if (cShitter != null && !isfinal)
		{
			if (total < cShitter.getCurrentValue())
			{
				total = cShitter.getCurrentValue ()
			}
		}

		// Step 11.  Did the player win this hand?  If not, set "total += (10 * number
		// of virus infections)".
		// ALSO DONE IN Game.cpp, because we want to display the score and the virus penalty
		// separately.

		return total
	}

	fun toJSON (): JSONObject
	{
		val a = JSONArray ()
		for (i in 0 until m_numCards)
		{
			val c = m_cards[i]!!
			a.put(c.getDeckIndex())
		}

		val o = JSONObject ()
		o.put ("cards", a)

		return o
	}
}
