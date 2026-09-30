package com.smccloud.hotdeath

import java.util.Random
import org.json.*

open class Player
{
	// m_game, m_go and m_hand are all nullable because shutdown() nulls all
	// three, and m_hand is null from the constructor until resetRound() builds
	// one. The Java callers reach them through getters and dereference without
	// a check, so they NPE exactly where they used to.
	protected var m_game: Game? = null
	protected var m_go: GameOptions? = null
	protected var m_skill = 1		// medium
	protected var m_aggression = 0	// neutral
	protected var m_hand: Hand? = null
	protected var m_leftOpp: Player? = null
	protected var m_rightOpp: Player? = null
	protected var m_seat = 0
	protected var m_passing = false
	protected var m_othersVoids: Array<BooleanArray> = Array(4) { BooleanArray(4) }

	protected var m_wantsToDraw = false
	protected var m_wantsToPlayCard = false
	protected var m_wantsToPass = false
	protected var m_playingCard: Card? = null
	protected var m_chosenColor = 0
	protected var m_chosenVictim = 0
	protected var m_numCardsToDeal = 0

	protected var m_lastScore = 0
	protected var m_lastVirusPenalty = 0
	protected var m_totalScore = 0

	protected var m_lastDrawn: Card? = null

	// Assigned by resetRound(), read by getActive(). Left non-nullable rather
	// than defaulted: the Java left it false until resetRound, and a default of
	// false here would be the same value, so this says the same thing.
	protected var m_active = false

	protected var m_virusPenalty = 0

	// Never assigned anywhere in the buildable sources -- the only reference is
	// in Game.java.new, which is not a .java file and so is not compiled.
	// Kept because getChangedLastClicked() is public API; see TODO.md.
	protected var m_changedLastClicked: Card? = null

	constructor(g: Game?, go: GameOptions?)
	{
		m_game = g
		m_go = go
	}

	fun getWantsToDraw (): Boolean
	{
		return m_wantsToDraw
	}

	fun getWantsToPlayCard (): Boolean
	{
		return m_wantsToPlayCard
	}

	fun getWantsToPass (): Boolean
	{
		return m_wantsToPass
	}

	fun getPlayingCard (): Card?
	{
		return m_playingCard
	}

	fun getChosenColor (): Int
	{
		return m_chosenColor
	}

	fun getChosenVictim (): Int
	{
		return m_chosenVictim
	}

	fun setLeftOpp(p: Player?)
	{
		m_leftOpp = p
	}

	fun getLeftOpp(): Player?
	{
		return m_leftOpp
	}

	fun setRightOpp(p: Player?)
	{
		m_rightOpp = p
	}

	fun getRightOpp(): Player?
	{
		return m_rightOpp
	}

	fun getSeat(): Int
	{
		return m_seat
	}

	fun setSeat(s: Int)
	{
		m_seat = s
	}

	fun getVirusPenalty(): Int
	{
		return m_virusPenalty
	}

	fun setVirusPenalty(p: Int)
	{
		m_virusPenalty = p
	}

	fun getLastVirusPenalty(): Int
	{
		return m_lastVirusPenalty
	}

	fun setLastVirusPenalty(p: Int)
	{
		m_lastVirusPenalty = p
	}

	fun getHand(): Hand?
	{
		return m_hand
	}

	open fun finishTrick()
	{
		return
	}


	fun getLastScore(): Int
	{
		return m_lastScore
	}

	fun setLastScore(s: Int)
	{
		m_lastScore = s
	}

	fun getTotalScore(): Int
	{
		return m_totalScore
	}

	fun setTotalScore(s: Int)
	{
		m_totalScore = s
	}

	fun getLastDrawn(): Card?
	{
		return m_lastDrawn
	}

	fun resetLastDrawn()
	{
		m_lastDrawn = null
	}

	fun getActive(): Boolean
	{
		return m_active
	}

	fun setActive(a: Boolean)
	{
		m_active = a
	}

	fun shutdown ()
	{
		m_game = null
		m_go = null
		m_hand = null
	}

	open fun addCardToHand (c: Card)
	{
		m_hand!!.addCard (c)
	}

	fun resetRound()
	{
		for (i in 0 until 4)
		{
			for (j in 0 until 4)
			{
				m_othersVoids[i][j] = false
			}
		}

		m_hand = Hand(this)
		m_active = true
		m_passing = false
	}

	fun resetGame()
	{
		m_lastScore = 0
		m_virusPenalty = 0
		if (android.os.Debug.isDebuggerConnected())
		{
			m_totalScore = 0
		}
		else
		{
			m_totalScore = 0
		}
		resetRound()
	}

	open fun chooseNumCardsToDeal ()
	{
	}

	open fun startTurn()
	{
	}

	open fun chooseColor(): Int
	{
		return Card.COLOR_WILD
	}

	open fun chooseVictim()
	{
		return
	}

	fun getChangedLastClicked (): Card?
	{
		return m_changedLastClicked
	}

	open fun getNumCardsToDeal(): Int
	{
		val rgen = Random()
		return rgen.nextInt (11) + 5
	}

	// public, not protected: the Java had it protected and reached it from
	// Game.java:1015 and :1053, which works only because both are in this
	// package. Kotlin has no package-private, and `internal` would mangle the
	// JVM name to drawCard$app_debug and break those two call sites.
	open fun drawCard()
	{
		val c = m_game!!.drawCard()

		// we shouldn't get null except in the rarest of circumstances
		// (when draw pile is empty and there is only one card on the
		// table).  But it _can_ happen.
		if (c == null)
		{
			return
		}

		if (m_seat == Game.SEAT_SOUTH)
		{
			c.setFaceUp(true)
		}

		m_hand!!.addCard (c)
		m_game!!.sortHand(m_hand!!)
		m_lastDrawn = c
	}

	fun toJSON (): JSONObject
	{
		val o = JSONObject ()
		o.put ("active", m_active)
		o.put ("totalScore", m_totalScore)
		o.put ("lastScore", m_lastScore)
		if (m_lastDrawn != null)
		{
			o.put ("lastDrawn", m_lastDrawn!!.getDeckIndex())
		}
		else
		{
			o.put ("lastDrawn", -1)
		}
		o.put ("virusPenalty", m_virusPenalty)
		o.put ("lastVirusPenalty", m_lastVirusPenalty)
		o.put ("hand", m_hand!!.toJSON())

		return o
	}

	constructor(o: JSONObject, g: Game, go: GameOptions?)
	{
		m_game = g
		m_go = go

		m_hand = Hand (o.getJSONObject("hand"), this, g.getDeck())
		m_totalScore = o.getInt("totalScore")
		m_lastScore = o.getInt("lastScore")
		m_virusPenalty = o.getInt("virusPenalty")
		m_lastVirusPenalty = o.getInt("lastVirusPenalty")
		m_active = o.getBoolean("active")

		val nLastDrawn = o.getInt("lastDrawn")
		if (nLastDrawn != -1)
		{
			m_lastDrawn = g.getDeck().getCard(nLastDrawn)
		}
		else
		{
			m_lastDrawn = null
		}
	}

}
