package com.smccloud.hotdeath

import org.json.JSONObject

import com.smccloud.hotdeath.R

open class HumanPlayer : Player
{
	private var m_turnDecision = false
	private var m_colorDecision = false
	private var m_victimDecision = false

	private var m_numCardsToDealDecision = false

	// Written out rather than left implicit: declaring any constructor here means
	// Kotlin stops generating a no-arg one, and both delegate straight up.
	constructor(g: Game?, go: GameOptions?) : super(g, go)
	{
	}

	constructor(o: JSONObject, g: Game, go: GameOptions?) : super(o, g, go)
	{
	}

	fun turnDecisionPass()
	{
		m_wantsToPass = true
		m_turnDecision = true
	}

	fun turnDecisionDrawCard()
	{
		m_wantsToDraw = true
		m_turnDecision = true
	}

	fun turnDecisionPlayCard(c: Card)
	{
		// when the human player is playing, we're in a spin-wait mode, waiting for
		// m_turnDecision to turn true.  So if we make a valid play here, we'll
		// set that bool to true.

		if (!m_hand!!.isInHand(c))
		{
			return
		}

		// checkCard is package-private in Game.java and resolves from here
		// because this file is in the same package.
		if (m_game!!.checkCard(m_hand!!, c, true))
		{
			m_playingCard = c
			m_wantsToPlayCard = true
			m_lastDrawn = null
			m_turnDecision = true
		}
		else
		{
			m_game!!.promptUser(m_game!!.getString (R.string.msg_card_no_good), false)
		}
	}

	// called to wait for the user to make a move -- either to play a
	// card, draw, or pass
	override fun startTurn()
	{
		m_wantsToPass = false
		m_wantsToPlayCard = false
		m_wantsToDraw = false

		m_turnDecision = false
		while (m_turnDecision == false)
		{
			try
			{
				Thread.sleep(100)
			}
			catch (e: InterruptedException)
			{
			}
			if (m_game!!.getStopping())
			{
				return
			}
		}

		return
	}


	override fun getNumCardsToDeal (): Int
	{
		m_game!!.promptForNumCardsToDeal()

		m_numCardsToDealDecision = false
		while (m_numCardsToDealDecision == false)
		{
			try
			{
				Thread.sleep(100)
			}
			catch (e: InterruptedException)
			{
			}
			if (m_game!!.getStopping())
			{
				return 0
			}
		}

		return m_numCardsToDeal
	}

	// called after user selects from the alert dialog
	fun setNumCardsToDeal (numCardsToDeal: Int)
	{
		m_numCardsToDeal = numCardsToDeal
		m_numCardsToDealDecision = true
	}


	override fun chooseColor(): Int
	{
		m_game!!.promptForColor ()

		m_colorDecision = false
		while (m_colorDecision == false)
		{
			try
			{
				Thread.sleep(100)
			}
			catch (e: InterruptedException)
			{
			}
			if (m_game!!.getStopping())
			{
				return 0
			}
		}

		return m_chosenColor
	}

	// called after user selects from the alert dialog
	fun setColor (color: Int)
	{
		m_chosenColor = color
		m_colorDecision = true
	}


	override fun chooseVictim()
	{
		// if there's only one other active player, it is silly to
		// prompt the user for the victim..
		var activeplayercount = 0
		var onlyactiveplayer = 0
		if (m_game!!.getPlayer(Game.SEAT_WEST - 1)!!.getActive())
		{
			activeplayercount++
			onlyactiveplayer = Game.SEAT_WEST
		}
		if (m_game!!.getPlayer(Game.SEAT_NORTH - 1)!!.getActive())
		{
			activeplayercount++
			onlyactiveplayer = Game.SEAT_NORTH
		}
		if (m_game!!.getPlayer(Game.SEAT_EAST - 1)!!.getActive())
		{
			activeplayercount++
			onlyactiveplayer = Game.SEAT_EAST
		}

		if (activeplayercount == 1)
		{
			m_victimDecision = true
			m_chosenVictim = onlyactiveplayer
			return
		}

		// ok -- we've got more than one, so we prompt the user...
		m_game!!.promptForVictim()

		m_victimDecision = false
		while (m_victimDecision == false)
		{
			try
			{
				Thread.sleep(100)
			}
			catch (e: InterruptedException)
			{
			}
			if (m_game!!.getStopping())
			{
				return
			}
		}
	}

	// called after user selects from the alert dialog
	fun setVictim (victim: Int)
	{
		m_chosenVictim = victim
		m_victimDecision = true
	}




}
