package com.smccloud.hotdeath

import java.util.Random
import org.json.*

class CardPile
{
	private var m_numCards = 0
	private val m_cards = arrayOfNulls<Card>(Game.MAX_NUM_CARDS)

	// Written out rather than left implicit: declaring any constructor here means
	// Kotlin stops generating the no-arg one, and Game.java still calls
	// new CardPile(). The array is built by the property initialiser above.
	constructor()
	{
	}

	constructor(o: JSONObject, d: CardDeck)
	{
		val a = o.getJSONArray("cards")
		m_numCards = a.length()
		for (i in 0 until m_numCards)
		{
			m_cards[i] = d.getCard(a.getInt(i))
		}
	}

	fun getNumCards(): Int
	{
		return m_numCards
	}

	fun getCard(i: Int): Card?
	{
		return m_cards[i]
	}

	fun addCard(c: Card)
	{
		m_cards[m_numCards++] = c
	}

	fun drawCard(): Card?
	{
		if (m_numCards < 1)
		{
			return null
		}

		val c = m_cards[m_numCards - 1]
		m_cards[m_numCards - 1] = null
		m_numCards--

		return c
	}

	fun shuffle ()
	{
		shuffle (7)
	}

	fun shuffle (numTimes: Int)
	{
		for (i in 0 until m_numCards)
		{
			// Slots below m_numCards are never null in a live pile; drawCard()
			// only clears one as it drops m_numCards past it.
			m_cards[i]!!.setFaceUp(false)
		}

		val rgen = Random()

		for (i in 0 until numTimes)
		{
			for (j in 0 until m_numCards)
			{
				val k = rgen.nextInt(m_numCards)

				val cTemp = m_cards[j]
				m_cards[j] = m_cards[k]
				m_cards[k] = cTemp
			}
		}
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