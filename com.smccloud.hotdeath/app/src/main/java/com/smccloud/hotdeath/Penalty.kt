package com.smccloud.hotdeath

import org.json.JSONObject

class Penalty
{
	companion object
	{
		const val PENTYPE_NONE = 0
		const val PENTYPE_CARD = 1
		const val PENTYPE_EJECT = 2
		const val PENTYPE_FACEUP = 3

		// What each penalty card is worth, in cards drawn. These used to be bare
		// literals at the six addCards call sites in Game.handleSpecialCards, one
		// per card, with a seventh value for Mystery Draw worked out from the card
		// it covered. Nothing tied them to each other or to the messages, so a
		// number in one place and the count a player is told in another could
		// disagree. Here they cannot: there is one number per card, and the
		// messages are formatted from the penalty's own running total.
		//
		// Deliberately not on Card. These are what the card costs *whoever is
		// next*, which is a property of the penalty it throws rather than of the
		// card itself -- the same Draw Four card costs 4 as a penalty and 50
		// points as a hand, and both numbers are true and unrelated.
		const val COUNT_DRAWFOUR = 4
		const val COUNT_HOT_DEATH = 8
		const val COUNT_DELAYED_BLAST = 4
		const val COUNT_HARVESTER = 4

		// The only fixed Mystery Draw count. Every other Mystery Draw is worth
		// whatever number the card beneath it shows, so it has no constant here
		// and Game works it out at the call site.
		const val COUNT_YELLOW_69 = 69
	}

	private var m_generatingPlayer: Player? = null
	private var m_victim: Player? = null
	private var m_secondaryVictim: Player? = null
	private var m_type = 0
	private var m_origCard: Card? = null

	private var m_numcards = 0

	constructor()
	{
		reset()
	}

	// The generating player and the card are both absent for some penalties, so
	// these arguments stay nullable; PenaltyTest leans on that. The Game is
	// nullable too, but only because JsonRoundTripTest reloads a penalty that has
	// no players at all and so never has to ask it for one.
	constructor(o: JSONObject, g: Game?, d: CardDeck)
	{
		reset()

		m_type = o.getInt("type")
		m_numcards = o.getInt("numcards")

		var n = o.getInt("generatingPlayer")
		if (n == 0)
		{
			m_generatingPlayer = null
		}
		else
		{
			m_generatingPlayer = g!!.getPlayer(n - 1)
		}

		n = o.getInt("victim")
		if (n == 0)
		{
			m_victim = null
		}
		else
		{
			m_victim = g!!.getPlayer(n - 1)
		}

		n = o.getInt("secondaryVictim")
		if (n == 0)
		{
			m_secondaryVictim = null
		}
		else
		{
			m_secondaryVictim = g!!.getPlayer(n - 1)
		}

		n = o.getInt("origCard")
		if (n == -1)
		{
			m_origCard = null
		}
		else
		{
			m_origCard = d.getCard(n)
		}
	}

	fun getGeneratingPlayer (): Player?
	{
		return m_generatingPlayer
	}

	fun setGeneratingPlayer(p: Player?)
	{
		m_generatingPlayer = p
	}

	fun getVictim (): Player?
	{
		return m_victim
	}

	fun setVictim(p: Player?)
	{
		m_victim = p
	}

	fun getSecondaryVictim (): Player?
	{
		return m_secondaryVictim
	}

	fun setSecondaryVictim(p: Player?)
	{
		m_secondaryVictim = p
	}

	fun getOrigCard(): Card?
	{
		return m_origCard
	}

	fun getType(): Int
	{
		return m_type
	}

	fun setType(t: Int)
	{
		m_type = t
	}

	fun getNumCards(): Int
	{
		return m_numcards
	}

	fun reset()
	{
		m_origCard = null
		m_numcards = 0
		m_type = PENTYPE_NONE
		m_generatingPlayer = null
		m_victim = null
		m_secondaryVictim = null
	}

	fun addCards(c: Card?, n: Int, p: Player?, pVictim: Player?)
	{
		if (c != null)
		{
			m_origCard = c
		}

		m_numcards += n
		m_type = PENTYPE_CARD
		m_generatingPlayer = p
		m_victim = pVictim
	}

	fun setNumCards(c: Card?, n: Int, p: Player?, pVictim: Player?)
	{
		if (c != null)
		{
			m_origCard = c
		}

		m_numcards = n
		m_type = PENTYPE_CARD
		m_generatingPlayer = p
		m_victim = pVictim
	}

	fun setEject(c: Card?, p: Player?, pVictim: Player?)
	{
		if (c != null)
		{
			m_origCard = c
		}

		m_type = PENTYPE_EJECT
		m_generatingPlayer = p
		m_victim = pVictim
	}

	fun setFaceup(c: Card?, p: Player?, pVictim: Player?)
	{
		if (c != null)
		{
			m_origCard = c
		}

		m_type = PENTYPE_FACEUP
		m_generatingPlayer = p
		m_victim = pVictim
	}

	fun toJSON (): JSONObject
	{
		val o = JSONObject ()

		o.put ("type", m_type)
		o.put ("numcards", m_numcards)

		if (m_generatingPlayer != null)
		{
			o.put ("generatingPlayer", m_generatingPlayer!!.getSeat())
		}
		else
		{
			o.put ("generatingPlayer", 0)
		}

		if (m_victim != null)
		{
			o.put ("victim", m_victim!!.getSeat())
		}
		else
		{
			o.put ("victim", 0)
		}

		if (m_secondaryVictim != null)
		{
			o.put ("secondaryVictim", m_secondaryVictim!!.getSeat())
		}
		else
		{
			o.put ("secondaryVictim", 0)
		}

		if (m_origCard != null)
		{
			o.put ("origCard", m_origCard!!.getDeckIndex())
		}
		else
		{
			o.put ("origCard", -1)
		}

		return o
	}
}