package com.smccloud.hotdeath

import java.util.Random
import android.util.Log

import com.smccloud.hotdeath.R
import org.json.*

// The private no-arg primary constructor exists only so the supertype can be
// initialised: Kotlin needs a primary constructor before it will let a class
// extend Thread at all, and Game's two real constructors take different
// arguments. It is private, so callers still see exactly the two Java
// constructors there were.
class Game private constructor() : Thread()
{
	companion object
	{
		const val MAX_NUM_CARDS = 216

		const val SEAT_SOUTH = 1
		const val SEAT_WEST = 2
		const val SEAT_NORTH = 3
		const val SEAT_EAST = 4

		const val DIR_CLOCKWISE = 1
		const val DIR_CCLOCKWISE = 2
	}

	private var m_stopping = false

	private var m_roundComplete = true
	private var m_waitingToStartRound = false

	// Novice mode: set while waitABit is blocked on a tap instead of a
	// delay. Deliberately not m_waitingToStartRound -- see tableTapped().
	private var m_waitingToAdvance = false
	private var m_gameOver = false
	private var m_winner = 0

	private var m_gt: GameTable? = null
	private var m_go: GameOptions? = null
	private var m_ga: GameActivity? = null
	private var m_players: Array<Player?> = arrayOfNulls<Player>(4)

	private var m_startPlayer: Player? = null
	private var m_currPlayer: Player? = null
	private var m_nextPlayerPreset: Player? = null
	private var m_dealer: Player? = null
	private var m_numCardsToDeal = 0
	private var m_currCard: Card? = null
	private var m_prevCard: Card? = null
	private var m_deck: CardDeck? = null
	private var m_drawPile: CardPile? = null
	private var m_discardPile: CardPile? = null
	private var m_numCardsPlayed = 0
	private var m_direction = 0
	private var m_currColor = 0
	private var m_cardsPlayed = 0
	private var m_forceDrawing = false

	private var m_fastForward = false

	// Null only in the plain constructor, until startRound() builds one. The
	// resuming constructor either sets it or rethrows, so a half-built Game is
	// not reachable -- see the catch at the end of that constructor.
	//
	// Every dereference in this file is therefore `m_penalty!!`, which keeps the
	// NullPointerException the Java threw on a game that has not been reset yet
	// rather than quietly papering over it with a default. That case is the
	// plain constructor's alone now; it used to include the malformed-save case
	// as well, which is what issue #2 was about.
	private var m_penalty: Penalty? = null

	private var m_lastCardCheckedIsDefender = false

	// java.lang.Object rather than Any: this lock is used with wait() and
	// notifyAll(), which Kotlin does not expose on Any.
	private val m_pauseLock = java.lang.Object()
	private var m_paused = false
	private var m_resumingSavedGame = false

	private var m_snapshot: JSONObject? = null

	fun getStopping (): Boolean
	{
		return m_stopping
	}

	fun setFastForward (ff: Boolean)
	{
		Log.d("HDU", "setFastForward ("
				+ (if (ff) "true"  else "false")
				+ ")")

		m_fastForward = ff
	}

	fun getFastForward (): Boolean
	{
		return m_fastForward
	}

	fun getWinner (): Int
	{
		return m_winner
	}

	fun setGameTable (gt: GameTable)
	{
		m_gt = gt
	}

	fun getDealer(): Player?
	{
		return m_dealer
	}

	fun getRoundComplete (): Boolean
	{
		return m_roundComplete
	}

	fun getStartPlayer(): Player?
	{
		return m_startPlayer
	}

	fun getCurrPlayer(): Player?
	{
		return m_currPlayer
	}

	fun getCurrPlayerUnderAttack (): Boolean
	{
		return m_penalty!!.getType() != Penalty.PENTYPE_NONE
	}

	fun getCurrPlayerDrawn(): Boolean
	{
		return (m_currPlayer!!.getLastDrawn() != null)
	}

	fun getPlayer(i: Int): Player?
	{
		return m_players[i]
	}

	fun getLastPlayedCard(): Card?
	{
		return m_currCard
	}

	fun getNumCardsPlayed(): Int
	{
		return m_numCardsPlayed
	}

	fun getDrawPile(): CardPile?
	{
		return m_drawPile
	}

	fun getDiscardPile(): CardPile?
	{
		return m_discardPile
	}

	// Nullable, and the Java was too in everything but name: the plain
	// constructor leaves m_deck null and only resetRound() builds one.
	// GameTable.java:845 and CardImageAdapter.java:27 call this, and they are
	// Java, so a nullable return costs them nothing and keeps the Java's
	// NullPointerException on a game that has not been reset yet.
	fun getDeck (): CardDeck?
	{
		return m_deck
	}

	fun getCurrColor(): Int
	{
		return m_currColor
	}

	fun getDirection(): Int
	{
		return m_direction
	}

	fun getPenalty(): Penalty?
	{
		return m_penalty
	}

	private fun waitUntilUnpaused ()
	{
		synchronized (m_pauseLock) {
		    while (m_paused) {
		        try {
		            m_pauseLock.wait()
		        } catch (e: InterruptedException) {
		        }
		    }
		}
	}

	fun shutdown ()
	{
		if (!m_stopping)
		{
			// on the first call, we'll set a flag
			m_stopping = true
			Log.d("HDU", "Game thread shutdown requested...")
			return
		}

		// on the second call, we'll really shut down; but also be careful
		// in case we get called more than two times...
		Log.d("HDU", "Game thread nulling out references and exiting...")
		if  (m_gt != null)
    	{
    		m_gt!!.shutdown ()
    		m_go!!.shutdown ()

    		for (i in 0 until m_players.size)
    		{
    			m_players[i]!!.shutdown()
    		}
    	}
		m_go = null
		m_ga = null
		m_gt = null
	}

	constructor(gamestate: JSONObject, ga: GameActivity, go: GameOptions) : this()
	{
		m_go = go
		m_ga = ga
		m_penalty = null

		try
		{
			val o = gamestate.getJSONObject("state")

			m_snapshot = gamestate

			m_deck = CardDeck(gamestate.getJSONObject("deck"))
			m_drawPile = CardPile(gamestate.getJSONObject("drawPile"), m_deck!!)
			m_discardPile = CardPile(gamestate.getJSONObject("discardPile"), m_deck!!)

			m_currColor = o.getInt("currColor")
			m_direction = o.getInt("direction")
			m_cardsPlayed = o.getInt("cardsPlayed")
			m_roundComplete = o.getBoolean("roundComplete")

			val nCurrCard = o.getInt("currCard")
			if (nCurrCard != -1)
			{
				m_currCard = m_deck!!.getCard(nCurrCard)
			}
			else
			{
				m_currCard = null
			}

			val nCurrPlayer = o.getInt("currPlayer") - 1
			val nDealer = o.getInt("dealer") - 1

			val a = gamestate.getJSONArray ("players")

			if (m_go!!.getComputer4th())
			{
				m_players[0] = ComputerPlayer (a.getJSONObject(0), this, m_go)
			}
			else
			{
				m_players[0] = HumanPlayer (a.getJSONObject(0), this, m_go)
			}

			m_players[1] = ComputerPlayer (a.getJSONObject(1), this, m_go)
			m_players[2] = ComputerPlayer (a.getJSONObject(2), this, m_go)
			m_players[3] = ComputerPlayer (a.getJSONObject(3), this, m_go)

			(m_players[0])!!.setSeat (SEAT_SOUTH)
			(m_players[1])!!.setSeat (SEAT_WEST)
			(m_players[2])!!.setSeat (SEAT_NORTH)
			(m_players[3])!!.setSeat (SEAT_EAST)

			(m_players[0])!!.setLeftOpp (m_players[1])
			(m_players[1])!!.setLeftOpp (m_players[2])
			(m_players[2])!!.setLeftOpp (m_players[3])
			(m_players[3])!!.setLeftOpp (m_players[0])

			(m_players[0])!!.setRightOpp (m_players[3])
			(m_players[2])!!.setRightOpp (m_players[1])
			(m_players[1])!!.setRightOpp (m_players[0])
			(m_players[3])!!.setRightOpp (m_players[2])

			m_penalty = Penalty(gamestate.getJSONObject("penalty"), this, m_deck!!)

			m_currPlayer = m_players[nCurrPlayer]
			m_dealer = m_players[nDealer]

			m_resumingSavedGame = true
		}
		catch (e: JSONException)
		{
			// Rethrown rather than swallowed. Returning from here hands back a Game
			// that stopped part-way through the parse: m_penalty and m_currCard are
			// still null, so the first checkCard throws a NullPointerException off
			// one of the `m_penalty!!` sites some way into play, with nothing in the
			// stack trace pointing at the save file. GameActivity catches this and
			// falls back to a new game, which is what its own comment there already
			// says happens when the JSON will not parse -- that fallback was dead
			// code for as long as this catch pretended the resume had worked.
			//
			// org.json names the key it could not find in the message, so this line
			// is the only place that says *which* field of the save is wrong.
			Log.e("HDU", "Could not resume the saved game: " + e.message, e)
			throw e
		}
	}

	fun getSnapshot (): String
	{
		if (m_gameOver)
		{
			// when game is over, we don't save a snapshot
			return ""
		}

		if (m_snapshot == null)
		{
			return ""
		}

		return m_snapshot!!.toString()
	}

	fun toJSON (): JSONObject
	{
		synchronized (m_pauseLock) {
			val o = JSONObject ()

			try
			{
				val o2 = JSONObject ()
				o2.put("dealer", m_dealer!!.getSeat())
				o2.put("currPlayer", m_currPlayer!!.getSeat())
				o2.put("currColor", m_currColor)
				o2.put("direction", m_direction)
				o2.put("cardsPlayed", m_cardsPlayed)
				o2.put("roundComplete", m_roundComplete)

				if (m_currCard != null)
				{
					o2.put("currCard", m_currCard!!.getDeckIndex())
				}
				else
				{
					o2.put ("currCard", -1)
				}

				o.put("state", o2)
				o.put("deck", m_deck!!.toJSON ())
				o.put("drawPile", m_drawPile!!.toJSON ())
				o.put("discardPile", m_discardPile!!.toJSON ())
				o.put("penalty", m_penalty!!.toJSON())

				val a = JSONArray ()
				for (i in 0 until 4)
				{
					a.put (m_players[i]!!.toJSON ())
				}
				o.put("players", a)
			}
			catch (e: JSONException)
			{
				Log.d("HDU", "JSON exception in Game.toJSON(): " + e.message);
			}

			return o
		}
	}

	/**
	 * Clear a novice-mode tap wait, so backgrounding the app mid-pause releases
	 * the game thread rather than leaving it in the wait.
	 *
	 * Pausing is the common case and shutdown() is the other one, so both have to
	 * let the loop go. Paused is the right time for it: the player has left, the
	 * table is not being watched, and there is no tap coming. On resume the game
	 * carries on rather than demanding a tap for a pause nobody saw the end of.
	 */
	fun pause ()
	{
		synchronized (m_pauseLock)
		{
			m_paused = true
		}

		m_waitingToAdvance = false
	}

	fun unpause ()
	{
		synchronized (m_pauseLock)
		{
			m_paused = false
			m_pauseLock.notifyAll()
		}
	}


	constructor(ga: GameActivity, go: GameOptions) : this()
	{
		m_go = go
		m_ga = ga
		m_penalty = null

		m_deck = null
		m_drawPile = null
		m_discardPile = null

		m_direction = DIR_CLOCKWISE

		if (m_go!!.getComputer4th())
		{
			m_players[0] = ComputerPlayer (this, m_go)
		}
		else
		{
			m_players[0] = HumanPlayer (this, m_go)
		}

		m_players[1] = ComputerPlayer (this, m_go)
		m_players[2] = ComputerPlayer (this, m_go)
		m_players[3] = ComputerPlayer (this, m_go)

		(m_players[0])!!.setSeat (SEAT_SOUTH)
		(m_players[1])!!.setSeat (SEAT_WEST)
		(m_players[2])!!.setSeat (SEAT_NORTH)
		(m_players[3])!!.setSeat (SEAT_EAST)

		(m_players[0])!!.setLeftOpp (m_players[1])
		(m_players[1])!!.setLeftOpp (m_players[2])
		(m_players[2])!!.setLeftOpp (m_players[3])
		(m_players[3])!!.setLeftOpp (m_players[0])

		(m_players[0])!!.setRightOpp (m_players[3])
		(m_players[2])!!.setRightOpp (m_players[1])
		(m_players[1])!!.setRightOpp (m_players[0])
		(m_players[3])!!.setRightOpp (m_players[2])
	}


	fun resetRound()
	{
		m_direction = DIR_CLOCKWISE
		m_deck =        CardDeck ()
		m_drawPile =    CardPile ()
		m_discardPile = CardPile ()
		m_cardsPlayed = 0
		m_roundComplete = false

		for (i in 0 until 4)
		{
			m_players[i]!!.resetRound()
		}
	}


	fun resetGame()
	{
		m_gameOver = false
		m_winner = 0

		for (i in 0 until 4)
		{
			m_players[i]!!.resetGame()
		}

		val rgen = Random()
		val dealer = rgen.nextInt(4)
		m_dealer = m_players[dealer]
	}


	fun dealHands()
	{
		waitUntilUnpaused ()

		var p = m_dealer!!.getLeftOpp()!!
		val msg = String.format (getString(R.string.msg_dealing), seatToString(m_dealer!!.getSeat()), m_numCardsToDeal)
		promptUser(msg)

		// use this mechanism to set up scenarios for testing edge cases
		val debugDeal = false
		if (android.os.Debug.isDebuggerConnected() && debugDeal)
		{
			// Every entry of this array is commented out, which is why it is
			// empty: the blocks below are the notes for whichever scenario you
			// are chasing. Uncomment one and `hands` picks it up.
			//
			// try to get draw 4s stacked with hotdeath on top, and a magic 5 to null it
			//	{Card.ID_RED_5_MAGIC, Card.ID_WILD_DRAWFOUR},
			//	{Card.ID_BLUE_0, Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_WILD_DB},
			//	{Card.ID_RED_3, Card.ID_RED_4, Card.ID_RED_5, Card.ID_WILD_DRAWFOUR},
			//	{Card.ID_GREEN_6, Card.ID_GREEN_7, Card.ID_GREEN_8, Card.ID_WILD_HD}
			//
			// All four bastard cards...
			//	{Card.ID_BLUE_0_FUCKYOU, Card.ID_GREEN_0_QUITTER, Card.ID_YELLOW_0_SHITTER, Card.ID_RED_0_HD, Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_BLUE_3},
			//	{Card.ID_GREEN_1, Card.ID_GREEN_2, Card.ID_GREEN_3, Card.ID_GREEN_4, Card.ID_GREEN_5, Card.ID_GREEN_6, Card.ID_GREEN_7},
			//	{Card.ID_RED_1, Card.ID_RED_2, Card.ID_RED_3, Card.ID_RED_4, Card.ID_RED_5, Card.ID_RED_6, Card.ID_RED_7},
			//	{Card.ID_YELLOW_1, Card.ID_YELLOW_2, Card.ID_YELLOW_3, Card.ID_YELLOW_4, Card.ID_YELLOW_5, Card.ID_YELLOW_6, Card.ID_YELLOW_7}
			//
			// this makes a mystery on a 69 highly likely
			//	{Card.ID_YELLOW_6, Card.ID_WILD_MYSTERY, Card.ID_YELLOW_0_SHITTER},
			//	{Card.ID_YELLOW_3, Card.ID_YELLOW_4,
			//			Card.ID_RED_1, Card.ID_RED_2, Card.ID_RED_3, Card.ID_RED_4, Card.ID_RED_5, Card.ID_RED_6, Card.ID_RED_7, Card.ID_RED_8, Card.ID_RED_D, Card.ID_RED_R, Card.ID_RED_S, Card.ID_RED_S_DOUBLE, Card.ID_RED_R_SKIP,
			//			Card.ID_GREEN_1, Card.ID_GREEN_2, Card.ID_GREEN_3, Card.ID_GREEN_4, Card.ID_GREEN_5, Card.ID_GREEN_6, Card.ID_GREEN_7, Card.ID_GREEN_8, Card.ID_GREEN_9, Card.ID_GREEN_D, Card.ID_GREEN_R, Card.ID_GREEN_S, Card.ID_GREEN_S_DOUBLE, Card.ID_GREEN_R_SKIP,
			//			Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_BLUE_3, Card.ID_BLUE_4, Card.ID_BLUE_5, Card.ID_BLUE_6, Card.ID_BLUE_7, Card.ID_BLUE_8, Card.ID_BLUE_9, Card.ID_BLUE_D, Card.ID_BLUE_R, Card.ID_BLUE_S, Card.ID_BLUE_S_DOUBLE, Card.ID_BLUE_R_SKIP
			//	},
			//	{Card.ID_YELLOW_1, Card.ID_YELLOW_2},
			//	{Card.ID_YELLOW_69, Card.ID_RED_9
			//
			// get the south player ejected
			//	{Card.ID_YELLOW_3, Card.ID_YELLOW_4,
			//		Card.ID_RED_1, Card.ID_RED_2, Card.ID_RED_3, Card.ID_RED_4, Card.ID_RED_5, Card.ID_RED_6, Card.ID_RED_7, Card.ID_RED_8, Card.ID_RED_9, Card.ID_RED_D, Card.ID_RED_R, Card.ID_RED_S, Card.ID_RED_S_DOUBLE, Card.ID_RED_R_SKIP,
			//		Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_BLUE_3, Card.ID_BLUE_4, Card.ID_BLUE_5, Card.ID_BLUE_6, Card.ID_BLUE_7, Card.ID_BLUE_8, Card.ID_BLUE_9, Card.ID_BLUE_D, Card.ID_BLUE_R, Card.ID_BLUE_S, Card.ID_BLUE_S_DOUBLE, Card.ID_BLUE_R_SKIP
			//	},
			//	{Card.ID_YELLOW_1, Card.ID_YELLOW_2},
			//	{Card.ID_GREEN_1, Card.ID_GREEN_2, Card.ID_GREEN_3, Card.ID_GREEN_4, Card.ID_GREEN_5, Card.ID_GREEN_6, Card.ID_GREEN_7, Card.ID_GREEN_8, Card.ID_GREEN_9, Card.ID_GREEN_D, Card.ID_GREEN_R, Card.ID_GREEN_S, Card.ID_GREEN_S_DOUBLE, Card.ID_GREEN_R_SKIP},
			//	{Card.ID_YELLOW_1_MAD, Card.ID_GREEN_0_QUITTER}
			//
			// get East to stack a Draw Four on a Hot Death so we can see what happens
			// with the Magic 5
			//	{Card.ID_RED_5_MAGIC, Card.ID_RED_1, Card.ID_RED_2},
			//	{Card.ID_GREEN_3, Card.ID_GREEN_4},
			//	{Card.ID_WILD_HD, Card.ID_BLUE_5, Card.ID_BLUE_6},
			//	{Card.ID_WILD_DRAWFOUR, Card.ID_RED_7, Card.ID_RED_8}
			//
			// put south player under attack
			//	{Card.ID_RED_1, Card.ID_RED_2, Card.ID_WILD_DRAWFOUR},
			//	{Card.ID_GREEN_3, Card.ID_GREEN_4, Card.ID_WILD_DRAWFOUR},
			//	{Card.ID_BLUE_5, Card.ID_BLUE_6, Card.ID_WILD_DRAWFOUR},
			//	{Card.ID_RED_7, Card.ID_RED_8, Card.ID_WILD_DRAWFOUR}
			//
			// stick player one with 69 and the shitter
			//	{Card.ID_YELLOW_69, Card.ID_YELLOW_0_SHITTER, Card.ID_WILD_DRAWFOUR, Card.ID_WILD_DRAWFOUR},
			//	{Card.ID_RED_1},
			//	{Card.ID_RED_2},
			//	{Card.ID_GREEN_3_AIDS, Card.ID_BLUE_2_SHIELD, Card.ID_GREEN_4_IRISH}
			//
			// want a retaliation thrown on a delayed blast
			//	{Card.ID_BLUE_0_FUCKYOU, Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_BLUE_3},
			//	{Card.ID_RED_1, Card.ID_RED_2, Card.ID_RED_3, Card.ID_RED_3},
			//	{Card.ID_GREEN_1, Card.ID_GREEN_2, Card.ID_WILD_DB},
			//	{Card.ID_YELLOW_1, Card.ID_YELLOW_2, Card.ID_YELLOW_3}
			val hands = arrayOfNulls<IntArray>(0)

			for (i in 0 until 4)
			{
				p = m_players[i]!!
				val hCards = hands[i]!!
				for (j in hCards.indices)
				{
					for (k in 0 until m_deck!!.getNumCards())
					{
						val c = m_deck!!.getCard(k)!!
						if ((c.getHand() == null) && (c.getID() == hCards[j]))
						{
							if (p.getSeat() == SEAT_SOUTH)
							{
								c.setFaceUp(true)
							}
							else
							{
								c.setFaceUp(false)
							}
							p.addCardToHand(c)
							break
						}
					}
				}
			}

			for (i in 0 until m_deck!!.getNumCards())
			{
				val c = m_deck!!.getCard(i)!!
				if (c.getHand() == null)
				{
					m_drawPile!!.addCard(c)
				}
			}
		}
		else
		{
			// A while loop rather than `for (i in ...)`, because i has to survive
			// into the loop below: the deal runs to 4 * m_numCardsToDeal and then
			// the cheat pass picks up from exactly that index. A for-loop would
			// shadow i and restart the cheat pass at zero.
			var i = 0
			while (i < 4 * m_numCardsToDeal)
			{
				val c = m_deck!!.getCard(i)!!

				if (p.getSeat() == SEAT_SOUTH)
				{
					c.setFaceUp(true)
				}
				else
				{
					c.setFaceUp(false)
				}

				p.addCardToHand (c);

				p = p.getLeftOpp()!!
				i++
			}

			var cheatlevel = m_go!!.getCheatLevel();

			while (i < m_deck!!.getNumCards())
			{
				var c = m_deck!!.getCard(i)!!

				// MWUAHAHAHA
				if (cheatlevel > 0)
				{
					if (c.getID() == Card.ID_RED_0_HD
						|| c.getID() == Card.ID_RED_2_GLASNOST
						|| c.getID() == Card.ID_RED_5_MAGIC
						|| c.getID() == Card.ID_RED_D_SPREADER
						|| c.getID() == Card.ID_YELLOW_69
						|| c.getID() == Card.ID_GREEN_D_SPREADER
						|| c.getID() == Card.ID_WILD_MYSTERY
						|| c.getID() == Card.ID_GREEN_3_AIDS
						|| c.getID() == Card.ID_WILD_DB
						|| c.getID() == Card.ID_BLUE_2_SHIELD
						|| c.getID() == Card.ID_GREEN_4_IRISH
						|| c.getID() == Card.ID_WILD_DRAWFOUR
						)
					{
						c.setFaceUp(true);
						c = (m_players[SEAT_SOUTH - 1])!!.getHand()!!.swapCard(c)!!
						c.setFaceUp(false);
						cheatlevel--;
					}
				}

				m_drawPile!!.addCard(c);
				i++
			}
		}

		for (i in 0 until 4)
		{
			val h = (m_players[i])!!.getHand()!!
			sortHand(h);
		}
	}


	fun rolloverDiscardPile(): Int
	{
		val numPlayedCards = m_discardPile!!.getNumCards();

		if (numPlayedCards > 1)
		{
			val topCard = m_discardPile!!.drawCard()!!;

			var msg: String
			if (numPlayedCards > 2)
			{
				msg = String.format (getString(R.string.msg_shuffling_discard), numPlayedCards - 1);
			}
			else
			{
				msg = getString (R.string.msg_shuffling_discard_1);
			}

			promptUser (msg);

			for (i in 1 until numPlayedCards) {
				val tc = m_discardPile!!.drawCard()!!
				tc.setFaceUp(false);
				m_drawPile!!.addCard(tc);
			}
			m_drawPile!!.shuffle();
			m_discardPile!!.addCard(topCard);

			return numPlayedCards - 1;
		}

		// we got here b/c there is only one card in the discard
		// pile, and it's the face up card, so we can't roll it over...
		if (!m_forceDrawing)
		{
			redrawTable();
			promptUser (getString(R.string.msg_discard_empty));
		}

		return 0;
	}


	fun drawCard (): Card?
	{
		var c: Card? = null;

		// if we purged the draw and discard piles on the last draw (like
		// a big draw 69), we might end up with no draw pile at all.
		if (m_drawPile!!.getNumCards() == 0)
		{
			// try to roll over the discard pile
			if (rolloverDiscardPile() > 0)
			{
				c = m_drawPile!!.drawCard();
			}
			else
			{
			}

		}
		else
		{
			c = m_drawPile!!.drawCard();
		}

		// do we need to reset the draw pile?  It's best to do this
		// immediately after we draw the last card in the draw pile
		// so that we don't have an empty draw pile on screen
		if (m_drawPile!!.getNumCards() == 0)
		{
			rolloverDiscardPile();
		}

		return c;
	}


	fun startRound()
	{
		waitUntilUnpaused ();

		resetRound();
		m_startPlayer = m_dealer!!.getLeftOpp();

		m_penalty = Penalty ();

		m_currCard = null;
		m_prevCard = null;

		m_deck!!.reset(m_go!!.getStandardRules(), m_go!!.getOneDeck());
		m_deck!!.shuffle();

		for (i in 0 until 4)
		{
			((m_players[i])!!.getHand()!!).reset();
		}

		if (m_go!!.getStandardRules())
		{
			m_numCardsToDeal = 7;
		}
		else
		{
			m_numCardsToDeal = m_dealer!!.getNumCardsToDeal();

			if (m_stopping)
			{
				return;
			}
		}

		dealHands();
		postDealHands();
	}

	private fun postDealHands ()
	{
		do
		{
			// FIXME!!! the dealer is supposed to eat penalties...
			m_currCard = m_drawPile!!.drawCard()
			if (m_currCard != null)
			{
				m_currColor = m_currCard!!.getColor();
				m_discardPile!!.addCard (m_currCard!!);
				m_currCard!!.setFaceUp(true);
			}
		} while (m_currColor == Card.COLOR_WILD);

		m_startPlayer = m_dealer!!.getLeftOpp();
		m_numCardsPlayed = 0;

		m_currPlayer = m_startPlayer;

		redrawTable();

		for (i in 0 until 4)
		{
			val h = (m_players[i])!!.getHand()!!;
			if (checkForAllBastardCards(h))
			{
				redrawTable ();
				gotAllBastardCards (m_players[i]!!);
				finishRound(m_players[i]!!);

				return;
			}
		}
	}

	private fun runRound ()
	{
		while (true)
		{
			m_snapshot = this.toJSON();
			waitUntilUnpaused ();

			if (m_roundComplete)
			{
				// this could conceivably happen if the round ends immediately after the deal
				return;
			}

			if (m_stopping)
			{
				return;
			}

			if (advanceRound() == false)
			{
				break;
			}
		}
	}

	override fun run ()
	{
		if (m_resumingSavedGame)
		{
			m_resumingSavedGame = false;
			if (m_roundComplete)
			{
				waitForNextRound ();
				startRound ();
			}
		}
		else
		{
			startGame ();
		}

		while (!m_gameOver)
		{
			runRound ();
			if (m_stopping)
			{
				shutdown ();
				Log.d("HDU", "exiting Game.run()...");
				return;
			}
			if (!m_gameOver)
			{
				waitForNextRound();
				startRound ();
			}
		}

		// call shutdown() once; when user backs out of the activity, shutdown() will
		// get called for the second time, releasing all big references
		shutdown();

		Log.d("HDU", "exiting Game.run()...");
	}

	fun startGame()
	{
		resetGame();
		startRound();
	}


	fun getNextPlayer (currentplayer: Player? = null): Player
	{
		var notdone = true;

		var p: Player? = currentplayer ?: m_currPlayer;

		while (notdone)
		{
			p = (if (m_direction == DIR_CLOCKWISE)
				p!!.getLeftOpp()
				else
				p!!.getRightOpp());

			if (p!!.getActive())
			{
				notdone = false;
			}
		}

		return p!!;
	}


	fun nextPlayer(): Player
	{
		m_currPlayer!!.resetLastDrawn();

		return getNextPlayer();
	}

	fun advanceRound(): Boolean
	{
		// if for some reason we don't have a current player, bail out
		if (m_currPlayer == null)
		{
			return false;
		}

		if (m_currPlayer is HumanPlayer)
		{
			promptUser(getString(R.string.msg_your_play), false);
			showMenuButton(true);
		}
		else
		{
			showMenuButton(false);
		}

		m_currPlayer!!.startTurn();

		if (m_stopping)
		{
			return false;
		}

		if ((m_currPlayer!!.getHand()!!).hasValidCards(this)
				&& !(m_currPlayer!!.getWantsToPass()))
		{
			if (m_currPlayer!!.getWantsToPlayCard())
			{
				m_prevCard = m_currCard;
				m_currCard = m_currPlayer!!.getPlayingCard();
				m_currPlayer!!.getHand()!!.removeCard(m_currCard!!);

				logCardPlay(m_currPlayer!!, m_currCard!!);

				m_cardsPlayed++;

				m_currCard!!.setFaceUp(true);
				m_discardPile!!.addCard(m_currCard!!);

				m_numCardsPlayed++;

				redrawTable();

				m_currColor = m_currCard!!.getColor();
				if (m_currColor == Card.COLOR_WILD)
				{
					m_currColor = m_currPlayer!!.chooseColor();

					if (m_stopping)
					{
						return false;
					}

					val msg = String.format(getString (R.string.msg_color_chosen), seatToString(m_currPlayer!!.getSeat()), colorToString(m_currColor));
					redrawTable ();
					Log.d("HDU", msg);
					//promptUser(msg);
				}

				handleSpecialCards();
				if (m_stopping)
				{
					return false;
				}

				// if previous player set us up, and we did not throw something
				// that would negate the penalty, then we get penalized now
				if ((m_penalty!!.getType() != Penalty.PENTYPE_NONE)
					&& ((m_penalty!!.getVictim() == m_currPlayer)
							|| m_penalty!!.getSecondaryVictim() == m_currPlayer))
				{
					assessPenalty();
				}

				// go to next player
				if (m_nextPlayerPreset != null)
				{
					m_currPlayer = m_nextPlayerPreset;
				}
				else
				{
					m_currPlayer = nextPlayer();
				}


				// if we just threw something that set up the next player, and he has
				// no defender, hit him now
				if (m_penalty!!.getType() != Penalty.PENTYPE_NONE)
				{
					if (m_go!!.getStandardRules())
					{
						assessPenalty();
					}
					else
					{
						val ndef = checkForDefender(m_currPlayer!!.getHand()!!);
						if (ndef > 0)
						{
							if (m_currPlayer is HumanPlayer)
							{
								if (ndef == 1)
								{
									promptUser(getString (R.string.msg_you_have_defender));
								}
								else
								{
									promptUser (String.format(getString (R.string.msg_you_have_defenders), ndef));
								}
							}
						}
						else
						{
							assessPenalty();
						}
					}
				}

				redrawTable();
			}

			else if (m_currPlayer!!.getWantsToDraw() && (m_currPlayer!!.getLastDrawn() == null))
			{
				m_currPlayer!!.drawCard();
				redrawTable ();

				if (m_currPlayer is HumanPlayer)
				{
					val msg = String.format(getString (R.string.msg_player_draws_specific_card), seatToString(m_currPlayer!!.getSeat()), cardToString(m_currPlayer!!.getLastDrawn()!!));
					Log.d("HDU", msg);
					promptUser(msg);
				}
			}

			else if (m_currPlayer!!.getWantsToPass() && (m_currPlayer!!.getLastDrawn() != null))
			{
				if (m_penalty!!.getType() != Penalty.PENTYPE_NONE)
				{
					assessPenalty();
				}

				m_currPlayer = nextPlayer();
				redrawTable ();
			}
		}

		else
		{
			if (m_penalty!!.getType() != Penalty.PENTYPE_NONE)
			{
				assessPenalty();
			}

			else
			{
				if (m_currPlayer!!.getLastDrawn() != null)
				{
					m_currPlayer = nextPlayer();
				}
				else
				{
					m_currPlayer!!.drawCard();

					sortHand (m_currPlayer!!.getHand()!!);
					redrawTable();

					val msg = String.format(getString (R.string.msg_player_draws_card), seatToString(m_currPlayer!!.getSeat()));
					Log.d("HDU", msg);
					if (m_currPlayer!!.getSeat() != SEAT_SOUTH)
					{
						promptUser (msg);
					}

					if (!(m_currPlayer!!.getHand()!!).hasValidCards(this))
					{
						m_currPlayer = nextPlayer();
					}
				}
			}
			redrawTable ();
		}

		for (i in 0 until 4)
		{
			val h = (m_players[i])!!.getHand()!!;

			var hasAllBastardCards = false;
			if (checkForAllBastardCards(h)) {
				gotAllBastardCards (m_players[i]!!);
				hasAllBastardCards = true;
			}

			// check for a winner
			if (hasAllBastardCards || (h.getNumCards() == 0))
			{
				if (m_penalty!!.getType() == Penalty.PENTYPE_NONE)
				{
					finishRound(m_players[i]!!);
					return false;
				}
			}
		}

		return true;
	}

	// internal, not public: the Java had this package-private and the only caller
	// is ComputerPlayer.kt. `internal` keeps the same audience; public would widen
	// it, and the alternative of leaving it public would just be a lie about who
	// uses it. No Java calls it, so the JVM name mangling is harmless.
	internal fun getLastCardCheckedIsDefender (): Boolean
	{
		return m_lastCardCheckedIsDefender;
	}

	// Public, and that is the one deliberate visibility change in this file. It
	// was package-private in the Java and Hand.java plus HandPlayabilityTest.java
	// both call it, which works because all three are in this package. Kotlin has
	// no package-private, and `internal` would compile this to
	// checkCard$app_debug and break both of those Java call sites outright.
	fun checkCard(h: Hand, c: Card, interactive: Boolean): Boolean
	{
		if (!(h.isInHand(c))) return false;

		val value = m_currCard!!.getValue();
		val id = m_currCard!!.getID();

		val cvalue = c.getValue();
		val cid = c.getID();

		val bHasMatch = h.hasColorMatch (m_currColor);

		m_lastCardCheckedIsDefender = true;

		if (m_penalty!!.getType() != Penalty.PENTYPE_NONE)
		{
			val tid = m_penalty!!.getOrigCard()!!.getID();
			val pvalue = m_penalty!!.getOrigCard()!!.getValue();

			// if the penalty's original card is not the current
			// card, we're dealing with defenders.  At this point, we
			// can only throw defenders (we can't stack more)
			val defenderAlreadyThrown = (m_penalty!!.getOrigCard() != m_currCard);

			// can play aids, fuckyou, and holydefender on various cards
			if (((cid == Card.ID_BLUE_0_FUCKYOU)
				|| (cid == Card.ID_RED_0_HD)
				|| (cid == Card.ID_GREEN_3_AIDS))
				&& ((pvalue == Card.VAL_WILD_DRAWFOUR)
				   || (tid == Card.ID_GREEN_0_QUITTER)
				   || (tid == Card.ID_RED_2_GLASNOST)))
			{
				return true;
			}

			// assuming we're not dealing with a defender on top of the
			// draw four, we can stack drawfours (except on the harvester
			// of sorrows and mystery)
			if (!(m_go!!.getStandardRules())) {
				if (!defenderAlreadyThrown
					&& (pvalue == Card.VAL_WILD_DRAWFOUR)
					&& (tid != Card.ID_WILD_HOS) && (tid != Card.ID_WILD_MYSTERY)) {
					if (cvalue == Card.VAL_WILD_DRAWFOUR)
					{
						return true;
					}
				}
			}

			// magic 5 is a defender against the hot death wild card only
			// (although it can be played on any card)
			if ((tid == Card.ID_WILD_HD) && (cid == Card.ID_RED_5_MAGIC))
			{
				return true;
			}

			return false;
		}

		m_lastCardCheckedIsDefender = false;

		// if player holds 69, he can throw 6s on 9s and vice-versa
		if (((cvalue == 6) && (value == 9)) || ((cvalue == 9) && (value == 6)))
		{
			for (i in 0 until h.getNumCards())
			{
				val tc = h.getCard(i)!!;
				if (tc.getID() == Card.ID_YELLOW_69)
				{
					return true;
				}
			}
		}

		if (cid == Card.ID_YELLOW_0_SHITTER)
		{
			if ((id == Card.ID_RED_0_HD)
			   || (id == Card.ID_RED_5_MAGIC)
			   || (h.getNumCards() == 1))
			{
				return true;
			}
			else
			{
				return false;
			}
		}

		// 69 can be played on 6 or 9; likewise, 6 or 9 can be played on 69
		if ((id == Card.ID_YELLOW_69) && ((cvalue == 6) || (cvalue == 9))) return true;
		if ((cid == Card.ID_YELLOW_69) && ((value == 6) || (value == 9))) return true;

		// can play magic red 5 on any card
		if (cid == Card.ID_RED_5_MAGIC) return true;

		// the variants of D, S, and R
		if ((value == Card.VAL_D) && (cvalue == Card.VAL_D_SPREAD)) return true;
		if ((value == Card.VAL_D_SPREAD) && (cvalue == Card.VAL_D)) return true;
		if ((value == Card.VAL_R) && (cvalue == Card.VAL_R_SKIP)) return true;
		if ((value == Card.VAL_R_SKIP) && (cvalue == Card.VAL_R)) return true;
		if ((value == Card.VAL_S) && (cvalue == Card.VAL_S_DOUBLE)) return true;
		if ((value == Card.VAL_S_DOUBLE) && (cvalue == Card.VAL_S)) return true;

		if (m_go!!.getStandardRules())
		{
			// cannot play wild draw four if you've got a matching card
			if (bHasMatch && (c.getValue() == Card.VAL_WILD_DRAWFOUR))
			{
				return false;
			}
		}

		// cards of same color, wild cards, or cards of equal value
		if ((c.getColor() == m_currColor) || (c.getColor() == Card.COLOR_WILD)
				|| (cvalue == value)) {
			return true;
		}

		return false;
	}


	fun finishRound(p: Player)
	{
		m_fastForward = false;
		showFastForwardButton(false);

		m_dealer = p;

		calculateScore(p);
		m_roundComplete = true;

		for (i in 0 until 4)
		{
			m_players[i]!!.setActive(true);
			((m_players[i])!!.getHand()!!).setFaceUp(true);
			sortHand ((m_players[i])!!.getHand()!!);
		}

		redrawTable();
		val msg = String.format (getString(R.string.msg_declare_round_winner), seatToString(p.getSeat()));
		promptUser(msg);

		var minScore = 1000000;
		var minPlayer = 0;
		val gameEndScore = (if (m_go!!.getStandardRules()) 500 else 1000);

		for (i in 0 until 4)
		{
			if (m_players[i]!!.getTotalScore() < minScore)
			{
				minScore = m_players[i]!!.getTotalScore();
				minPlayer = i;
			}
			if (m_players[i]!!.getTotalScore() >= gameEndScore)
			{
				m_gameOver = true;
			}
		}

		m_snapshot = this.toJSON();

		if (m_gameOver)
		{
			m_winner = m_players[minPlayer]!!.getSeat();
			redrawTable();
		}

	}

	fun roundIsActive (): Boolean
	{
		if (m_waitingToStartRound)
		{
			return false;
		}
		if (m_gameOver)
		{
			return false;
		}

		return true;
	}

	private fun waitForNextRound ()
	{
		val msg = getString(R.string.msg_tap_draw_pile);
		promptUser(msg);

		m_waitingToStartRound = true;
		while (m_waitingToStartRound)
		{
			try
			{
				Thread.sleep(100);
			}
			catch (e: InterruptedException)
			{

			}
		}
	}

	/**
	 * Any tap on the table, called before the piles sort out what was hit.
	 *
	 * This is deliberately not `m_waitingToStartRound`, and it must not be folded
	 * into `drawPileTapped` either. That method clears the round-start wait and
	 * then, if the current player is human, calls `turnDecisionDrawCard` -- so
	 * reusing it for a novice-mode tap would hand the player a draw decision they
	 * never made, on a turn that has not started yet. `HumanPlayer.startTurn`
	 * resets that flag, so the decision would be discarded, but only by luck: the
	 * tap would mean two things at once and one of them would win by accident.
	 *
	 * Separate, and with no side effect beyond the wait, is the whole point.
	 */
	fun tableTapped ()
	{
		m_waitingToAdvance = false;
	}

	/**
	 * True while a novice-mode pause is waiting for a tap.
	 *
	 * Exists so a test can tell "the wait released because the table was tapped"
	 * from "the wait released because the game was paused", which look identical
	 * from the game thread.
	 */
	fun waitingToAdvance (): Boolean
	{
		return m_waitingToAdvance;
	}

	fun drawPileTapped ()
	{
		if (m_waitingToStartRound)
		{
			m_waitingToStartRound = false;
		}

		if (!(m_currPlayer is HumanPlayer))
		{
			return;
		}

		(m_currPlayer as HumanPlayer).turnDecisionDrawCard();
	}


	fun discardPileTapped ()
	{
		if (m_waitingToStartRound)
		{
			m_waitingToStartRound = false;
		}
	}

	private fun calculateScore(pWinner: Player)
	{
		val newscore = IntArray(4);

		var maxscore = 0;
		for (i in 0 until 4)
		{
			val h = (m_players[i])!!.getHand()!!;

			if (checkForAllBastardCards(h))
			{
				newscore[i] = 0;
			}
			else
			{
				newscore[i] = h.calculateValue(true);
			}

			if (newscore[i] > maxscore)
			{
				maxscore = newscore[i];
			}

		}

		// if a player has the shitter, he gets the worst score of all hands
		for (i in 0 until 4)
		{
			val h = (m_players[i])!!.getHand()!!;
			if (checkForAllBastardCards(h))
			{
				// unless he's got all 4 bastard cards, in which case, he
				// gets 0 points
			}
			else
			{
				for (j in 0 until h.getNumCards())
				{
					val c = h.getCard(j)!!;

					if (c.getID() == Card.ID_YELLOW_0_SHITTER)
					{
						newscore[i] = maxscore;
					}
				}
			}

			(m_players[i])!!.setLastScore(newscore[i]);

			if (m_players[i] == pWinner)
			{
				(m_players[i])!!.setLastVirusPenalty(0);
			}
			else
			{
				(m_players[i])!!.setLastVirusPenalty(m_players[i]!!.getVirusPenalty());
			}

			(m_players[i])!!.setTotalScore ((m_players[i])!!.getTotalScore()
											+ newscore[i]
											+ m_players[i]!!.getLastVirusPenalty());
		}

	}



	fun sortHand (h: Hand)
	{
		val cards = arrayOfNulls<Card>(MAX_NUM_CARDS);

		var p = 0;
		val cd = m_deck!!.getCards();

		if (m_go!!.getFaceUp())
		{
			for (i in 0 until m_deck!!.getNumCards())
			{
				val c = cd[i]!!;
				if (c.getHand() == h)
				{
					cards[p++] = c;
				}
			}
		}

		else
		{
			// sort according to deck order, but do faceup cards first
			for (i in 0 until m_deck!!.getNumCards())
			{
				val c = cd[i]!!;
				if (c.getHand() == h && c.getFaceUp())
				{
					cards[p++] = c;
				}
			}

			for (i in 0 until m_deck!!.getNumCards())
			{
				val c = cd[i]!!;
				if (c.getHand() == h && !(c.getFaceUp()))
				{
					cards[p++] = c;
				}
			}
		}

		h.reorderCards(cards);
	}


	private fun redrawTable ()
	{
		m_ga!!.runOnUiThread(Runnable {
			m_gt!!.RedrawTable()
		})
	}

	private fun showFastForwardButton (show: Boolean)
	{
		m_ga!!.runOnUiThread(Runnable {
			 m_gt!!.showFastForwardButton(show)
		})
	}

	private fun showMenuButton (show: Boolean)
	{
		m_ga!!.runOnUiThread(Runnable {
			m_gt!!.showMenuButton(show)
		})
	}


	fun promptForNumCardsToDeal()
	{
		m_ga!!.runOnUiThread(Runnable {
					m_gt!!.PromptForNumCardsToDeal()
		})
	}

	fun promptForVictim()
	{
		m_ga!!.runOnUiThread(Runnable {
					m_gt!!.PromptForVictim()
		})
	}

	fun promptForColor()
	{
		m_ga!!.runOnUiThread(Runnable {
					m_gt!!.PromptForColor()
		})
	}



	fun humanPlayerPass ()
	{
		if (!(m_currPlayer is HumanPlayer))
		{
			return;
		}

		(m_currPlayer as HumanPlayer).turnDecisionPass();
	}

	// internal, like the Java's package-private: the only callers are inside this
	// package and all of them are Kotlin now, so the mangled JVM name is fine.
	internal fun promptUser(msg: String)
	{
		promptUser(msg, true);
	}

	internal fun promptUser(msg: String, wait: Boolean)
	{
		if (m_fastForward)
		{
			return;
		}

		m_ga!!.runOnUiThread(Runnable {
			Log.d("HDU", "[promptUser] prompt: " + msg);
			m_gt!!.Toast(msg);
		})
		if (wait)
		{
			waitABit ();
		}
	}


	internal fun promptForDrawCard()
	{
		promptUser (getString(R.string.msg_prompt_draw));
	}



	// this routine looks in the hand for a card
	// like the Blue Shield
	fun checkForShield(h: Hand): Boolean
	{
		for (i in 0 until h.getNumCards())
		{
			val id = (h.getCard(i)!!).getID();

			if (id == Card.ID_BLUE_2_SHIELD)
			{
				(h.getCard(i)!!).setFaceUp(true);
				return true;
			}
		}

		return false;
	}



	/* check for defenders against stacked draw 4s
	     - Fuck You
		 - Holy Defender
		 - Virus card
		 - Harvester of Sorrows
	*/
	fun checkForDefender(h: Hand): Int
	{
		val c = m_penalty!!.getOrigCard()!!;
		val prevID = c.getID();
		val prevVal = c.getValue();

		// if the penalty's original card is not the current
		// card, we're dealing with defenders.  At this point, we
		// can only throw defenders (we can't stack more)
		val defenderAlreadyThrown = (c != m_currCard);

		var defenderCount = 0;
		// we can stack on all draw fours except HOS and mystery draw
		if ((prevVal == Card.VAL_WILD_DRAWFOUR) && (prevID != Card.ID_WILD_HOS))
		{
			for (i in 0 until h.getNumCards())
			{
				val id = (h.getCard(i)!!).getID();
				val value = (h.getCard(i)!!).getValue();

				// all draw fours can stack, except for mystery draw
				if (!defenderAlreadyThrown) {
					if ((value == Card.VAL_WILD_DRAWFOUR) && (prevID != Card.ID_WILD_MYSTERY) && (id != Card.ID_WILD_MYSTERY))
					{
						defenderCount++;
					}
				}

				// no stacking on AIDS -- it gets too messy because it would
				// throw the penalty back against the direction of play

				if (id == Card.ID_RED_0_HD)
				{
					defenderCount++;
				}
				if (id == Card.ID_BLUE_0_FUCKYOU)
				{
					defenderCount++;
				}
				if (id == Card.ID_GREEN_3_AIDS)
				{
					defenderCount++;
				}

				// magic red 5 nulls the hotdeath card
				if ((id == Card.ID_RED_5_MAGIC) && (prevID == Card.ID_WILD_HD))
				{
					defenderCount++;
				}
			}

		}

		if (m_penalty!!.getVictim() == m_currPlayer)
		{
			if (prevID == Card.ID_RED_0_HD)
			{
			}

			if (prevID == Card.ID_RED_2_GLASNOST)
			{
				for (i in 0 until h.getNumCards())
				{
					val id = (h.getCard(i)!!).getID();
					if ((id == Card.ID_RED_0_HD)
						|| (id == Card.ID_GREEN_3_AIDS)
						|| (id == Card.ID_BLUE_0_FUCKYOU))
					{
						defenderCount++;
					}
				}
			}

			if (prevID == Card.ID_GREEN_0_QUITTER)
			{
				for (i in 0 until h.getNumCards())
				{
					val id = (h.getCard(i)!!).getID();
					if ((id == Card.ID_RED_0_HD)
						|| (id == Card.ID_GREEN_3_AIDS)
						|| (id == Card.ID_BLUE_0_FUCKYOU))
					{
						defenderCount++;
					}
				}
			}

			if (prevID == Card.ID_YELLOW_1_MAD)
			{
			}
		}

		return defenderCount;
	}


	fun checkForAllBastardCards(h: Hand): Boolean
	{
		var bastardCount = 0;
		for (i in 0 until h.getNumCards())
		{
			val c = h.getCard(i)!!;

			val id = c.getID();

			if (id == Card.ID_RED_0_HD) bastardCount++;
			if (id == Card.ID_GREEN_0_QUITTER) bastardCount++;
			if (id == Card.ID_BLUE_0_FUCKYOU) bastardCount++;
			if (id == Card.ID_YELLOW_0_SHITTER) bastardCount++;
		}

		if (bastardCount == 4)
		{
			return true;
		}

		return false;
	}


	fun gotAllBastardCards(p: Player)
	{
		var msg: String
		msg = String.format (getString(R.string.msg_all_bastard_cards), seatToString(p.getSeat()));
		promptUser (msg);

		val h = p.getHand()!!;
		val numcards = h.getNumCards();
		for (i in 0 until numcards)
		{
			val c = h.getCard(i)!!;
			c.setFaceUp(true);
		}

		redrawTable();
	}



	fun getActivePlayerCount(): Int
	{
		var count = 0;
		for (i in 0 until 4)
		{
			if (m_players[i]!!.getActive()) count++;
		}
		return count;
	}

	/**
	 * Throw a card penalty at the next player and say so.
	 *
	 * Five of the six penalty cards did the same four things in five copies: add to
	 * the penalty, work out whether this was the first card or a card on top of one,
	 * pick between two message resources, and format three arguments into whichever
	 * was right. The only thing that varied was the card's worth and its two strings,
	 * and both are now arguments -- the worth as a `Penalty.COUNT_*`, because the
	 * numbers were bare literals at each call site and could drift away from what the
	 * messages claimed.
	 *
	 * `stacked` is read from the penalty's count *before* the add. The four cards
	 * with a fixed worth used to be tested afterwards, as `getNumCards() > 4` and so
	 * on, which is only equivalent because each of them adds exactly its own worth
	 * every time -- read the other way round it would be wrong for any card that
	 * does not. Mystery Draw is worth whatever number it covers, so it was already
	 * doing this the honest way and is the reason the other four can now be.
	 *
	 * The player named as the source is whoever played *this* card. `addCards`
	 * overwrites the generating player every time, so on a stack that is the player
	 * who stacked rather than whoever started the penalty, and there is nowhere left
	 * to read the original from -- `m_origCard` is overwritten on the same call.
	 * That is why the stacked strings say "stacked by" and not "from".
	 *
	 * `from` is a parameter rather than read from `m_currPlayer` because the Delayed
	 * Blast branch advances the turn before it adds: its generator is the player
	 * who played the card, not the one now on turn.
	 */
	private fun addCardPenalty (from: Player?, numCards: Int, firstMsg: Int, stackedMsg: Int)
	{
		val stacked = m_penalty!!.getNumCards() > 0;

		m_penalty!!.addCards (m_currCard, numCards, from, getNextPlayer());

		// The count read back off the penalty rather than taken as `numCards`: on a
		// stack the player is told the running total, which is what they actually
		// have to draw.
		val msg = String.format (
				getString (if (stacked) stackedMsg else firstMsg),
				seatToString (m_penalty!!.getVictim()!!.getSeat()),
				m_penalty!!.getNumCards(),
				seatToString (m_penalty!!.getGeneratingPlayer()!!.getSeat()));
		promptUser (msg);
	}

	fun handleSpecialCards()
	{
		val currVal = m_currCard!!.getValue();
		val currID  = m_currCard!!.getID();
		m_nextPlayerPreset = null;

		if (currVal == Card.VAL_R)
		{
			m_direction = (if (m_direction == DIR_CLOCKWISE) DIR_CCLOCKWISE else DIR_CLOCKWISE);
			Log.d("HDU", "direction change: " + directionToString(m_direction));

			if (getActivePlayerCount() == 2)
			{
				m_currPlayer = nextPlayer();
			}
		}

		if (currVal == Card.VAL_R_SKIP)
		{
			m_direction = (if (m_direction == DIR_CLOCKWISE) DIR_CCLOCKWISE else DIR_CLOCKWISE);
			Log.d("HDU", "direction change: " + directionToString(m_direction));

			m_currPlayer = nextPlayer();
		}

		if ((currVal == Card.VAL_S)
			|| (currVal == Card.VAL_S_DOUBLE))
		{
			m_currPlayer = nextPlayer();
		}

		// double skip (only if more than 2 players left in game)
		if ((currVal == Card.VAL_S_DOUBLE) && (getActivePlayerCount() > 2))
		{
			m_currPlayer = nextPlayer();
		}

		if (currVal == Card.VAL_D)
		{
			val victim = nextPlayer();

			forceDraw(victim, 2);
			if (!(m_go!!.getStandardRules()))
			{
				m_currPlayer = nextPlayer();
			}
		}

		// spreaders
		if (currVal == Card.VAL_D_SPREAD)
		{
			// we're going to manipulate the m_currPlayer just so that
			// the drawing engine will point at each player as he draws
			val realCurrPlayer = m_currPlayer;

			// by default, the player who threw the spreader will play
			// again, unless somebody's got the shield
			var victim = m_currPlayer;
			m_nextPlayerPreset = m_currPlayer;
			for (i in 0 until 3)
			{
				victim = getNextPlayer(victim);

				// once we've gone around the table, bail out
				if (victim == realCurrPlayer)
				{
					break;
				}
				if (!victim.getActive())
				{
					continue;
				}

				if (checkForShield(victim.getHand()!!))
				{
					// somebody's got the shield
					m_nextPlayerPreset = victim;

					val msg = String.format (getString(R.string.msg_has_blue_shield), seatToString(victim.getSeat()));
					promptUser (msg);

					forceDraw(m_currPlayer!!, 2);

					continue;
				}

				forceDraw(victim, 2);
			}
			m_currPlayer = realCurrPlayer;
		}

		// check the wild draw fours
		if (currID == Card.ID_WILD_DRAWFOUR)
		{
			addCardPenalty (m_currPlayer, Penalty.COUNT_DRAWFOUR,
					R.string.msg_penalty_first_drawfour,
					R.string.msg_penalty_stacked_drawfour)
		}

		else if (currID == Card.ID_WILD_HD)
		{
			addCardPenalty (m_currPlayer, Penalty.COUNT_HOT_DEATH,
					R.string.msg_penalty_first_wild_hd,
					R.string.msg_penalty_stacked_wild_hd)
		}

		else if (currID == Card.ID_WILD_DB)
		{
			val p = m_currPlayer;

			if (getActivePlayerCount() > 2)
			{
				m_currPlayer = nextPlayer();
			}

			addCardPenalty (p, Penalty.COUNT_DELAYED_BLAST,
					R.string.msg_penalty_first_wild_db,
					R.string.msg_penalty_stacked_wild_db)
		}

		else if (currID == Card.ID_WILD_HOS)
		{
			addCardPenalty (m_currPlayer, Penalty.COUNT_HARVESTER,
					R.string.msg_penalty_first_wild_hos,
					R.string.msg_penalty_stacked_wild_hos)
		}

		else if (currID == Card.ID_WILD_MYSTERY)
		{
			val prevVal = m_prevCard!!.getValue();
			val prevID  = m_prevCard!!.getID();

			if (prevID == Card.ID_YELLOW_69)
			{
				addCardPenalty (m_currPlayer, Penalty.COUNT_YELLOW_69,
						R.string.msg_penalty_first_wild_mystery,
						R.string.msg_penalty_stacked_wild_mystery)
			}
			else if (prevVal > 0 && prevVal < 10)
			{
				addCardPenalty (m_currPlayer, prevVal,
						R.string.msg_penalty_first_wild_mystery,
						R.string.msg_penalty_stacked_wild_mystery)
			}
			else
			{
				// Mystery thrown on top of a non-numbered card -- just the same as a
				// wild card, so no penalty is added and the victim draws nothing.
				//
				// getNextPlayer() is called here for the message alone. It was
				// already being called on every branch that adds a penalty, to name
				// the victim, and it only walks the seating order without touching
				// turn state -- the code that moves m_currPlayer is the two lines
				// above, not this call.
				val msg = String.format (getString(R.string.msg_penalty_null_wild_mystery),
						seatToString(getNextPlayer().getSeat()));
				promptUser (msg);
				return;
			}
		}

		// check other special cards
		if ((currID == Card.ID_RED_0_HD) && (m_penalty!!.getVictim() == m_currPlayer))
		{
			m_penalty!!.setGeneratingPlayer(m_currPlayer);
			m_penalty!!.setVictim(getNextPlayer());

			val msg = String.format (getString(R.string.msg_holy_defender), seatToString(m_penalty!!.getVictim()!!.getSeat()));
			promptUser (msg);
		}

		if (currID == Card.ID_RED_2_GLASNOST)
		{
			m_currPlayer!!.chooseVictim();
			if (m_stopping)
			{
				return;
			}
			val victim = m_currPlayer!!.getChosenVictim();
			// we'll set the victim after prompting the user for it
			m_penalty!!.setFaceup(m_currCard, m_currPlayer, m_players[victim - 1]);
		}

		// if the magic red 5 is played on the hot death wild, it nulls it
		if ((currID == Card.ID_RED_5_MAGIC) && (m_prevCard!!.getID() == Card.ID_WILD_HD)
			 && (m_penalty!!.getVictim() == m_currPlayer))
		{
			m_penalty!!.reset();

			val msg = getString(R.string.msg_magic_5);
			promptUser (msg);
		}

		if (currID == Card.ID_GREEN_0_QUITTER)
		{
			if (getActivePlayerCount() > 2)
			{
				m_penalty!!.setEject(m_currCard, m_currPlayer, getNextPlayer());
			}
		}

		if ((currID == Card.ID_GREEN_3_AIDS)
			&& (m_penalty!!.getVictim() == m_currPlayer))
		{
			val g = m_penalty!!.getGeneratingPlayer();
			m_penalty!!.setVictim(g);
			m_penalty!!.setGeneratingPlayer(m_currPlayer);
			m_penalty!!.setSecondaryVictim(m_currPlayer);

			val msg = String.format(getString(R.string.msg_sharing_penalty), seatToString(m_penalty!!.getVictim()!!.getSeat()));
			promptUser (msg);
		}

		if ((currID == Card.ID_BLUE_0_FUCKYOU)
			&& ((m_penalty!!.getVictim() == m_currPlayer) || (m_penalty!!.getSecondaryVictim() == m_currPlayer)))
		{
			m_penalty!!.setVictim(m_penalty!!.getGeneratingPlayer());
			m_penalty!!.setSecondaryVictim(null);
			m_penalty!!.setGeneratingPlayer(m_currPlayer);

			m_direction = (if (m_direction == DIR_CLOCKWISE) DIR_CCLOCKWISE else DIR_CLOCKWISE);
			redrawTable();

			val msg = String.format (getString(R.string.msg_sending_penalty), seatToString(m_penalty!!.getVictim()!!.getSeat()));

			Log.d("HDU", "direction change: " + directionToString(m_direction));
			promptUser (msg);
		}

		if (currID == Card.ID_YELLOW_1_MAD)
		{
			if (getActivePlayerCount() > 3)
			{
				m_currPlayer!!.chooseVictim();
				if (m_stopping)
				{
					return;
				}
				val victim = m_currPlayer!!.getChosenVictim();

				// we'll set the victim after prompting the user for it
				m_penalty!!.setEject(m_currCard, m_currPlayer, m_players[victim - 1]);
				m_penalty!!.setSecondaryVictim(m_currPlayer);
			}
		}
	}

	fun assessPenalty()
	{
		if (m_penalty!!.getType() == Penalty.PENTYPE_NONE)
		{
			return;
		}

		val pVictim = m_penalty!!.getVictim();
		val pVictim2 = m_penalty!!.getSecondaryVictim();

		if (pVictim == null)
		{
			return;
		}

		var h = pVictim.getHand()!!;
		var msg: String

		if (m_penalty!!.getType() == Penalty.PENTYPE_CARD)
		{
			var numcards = m_penalty!!.getNumCards();
			if (pVictim2 != null)
			{
				// divide by 2 (and round up)
				numcards = (numcards + 1) / 2;
			}

			// check for the luck of the irish card (if numcards = 0, there's no point)
			if (numcards > 0)
			{
				for (i in 0 until h.getNumCards())
				{
					val id = (h.getCard(i)!!).getID();
					if (id == Card.ID_GREEN_4_IRISH)
					{
						numcards--;
						h.getCard(i)!!.setFaceUp(true);

						val msg_player = getString(R.string.msg_luck_of_irish);
						promptUser (msg_player);

						break;
					}
				}
			}

			forceDraw(pVictim, numcards);
			m_currPlayer = pVictim;

			if (!(m_go!!.getStandardRules()))
			{
				m_currPlayer = nextPlayer();
			}

			if (pVictim2 != null)
			{
				h = pVictim2.getHand()!!;
				numcards = (m_penalty!!.getNumCards() + 1) / 2;
				// check for the luck of the irish card
				for (i in 0 until h.getNumCards())
				{
					val id = (h.getCard(i)!!).getID();
					if (id == Card.ID_GREEN_4_IRISH)
					{
						numcards--;
						(h.getCard(i)!!).setFaceUp(true);
						break;
					}
				}
				forceDraw(pVictim2, numcards);
			}
		}
		else if (m_penalty!!.getType() == Penalty.PENTYPE_FACEUP)
		{
			h.setFaceUp(true);

			if (m_players[SEAT_SOUTH - 1] is HumanPlayer)
			{
				msg = String.format (getString(R.string.msg_player_faceup), seatToString(pVictim.getSeat()));
				promptUser (msg);
			}

			if (pVictim2 != null)
			{
				h = pVictim2.getHand()!!;
				for (i in 0 until h.getNumCards())
				{
					val c = h.getCard(i)!!;
					c.setFaceUp(true);
				}
				if (m_players[SEAT_SOUTH - 1] is HumanPlayer)
				{
					msg = String.format (getString(R.string.msg_player_faceup), seatToString(pVictim2.getSeat()));
					promptUser (msg);
				}
			}

		}
		else if (m_penalty!!.getType() == Penalty.PENTYPE_EJECT)
		{
			pVictim.setActive(false);

			msg = String.format (getString(R.string.msg_player_ejected), seatToString (pVictim.getSeat()));
			redrawTable();
			promptUser (msg);

			if (pVictim == m_players[SEAT_SOUTH - 1])
			{
				showFastForwardButton (true);
			}

			if (pVictim2 != null)
			{
				pVictim2.setActive(false);

				msg = String.format (getString(R.string.msg_player_ejected), seatToString (pVictim2.getSeat()));
				redrawTable();
				promptUser (msg);

				if (pVictim2 == m_players[SEAT_SOUTH - 1])
				{
					showFastForwardButton (true);
				}
			}

			if ((m_currPlayer == pVictim) || (m_currPlayer == pVictim2))
			{
				m_currPlayer = nextPlayer();
			}

			// FIXME!!! My personal rule -- if we end up with only
			// one player left, that player wins
			if (getActivePlayerCount() == 1)
			{
				val th = m_currPlayer!!.getHand()!!;
				th.reset();
				m_penalty!!.reset();
				var pWinner: Player? = null;
				for (i in 0 until 4)
				{
					if (m_players[i]!!.getActive())
					{
						pWinner = m_players[i];
					}
				}
				finishRound(pWinner!!);
			}
		}

		m_penalty!!.reset();
	}

	// gets the pause delay in milliseconds
	fun getDelay (): Int
	{
		if (m_fastForward)
		{
			return 0;
		}

		if (!m_players[SEAT_SOUTH - 1]!!.getActive())
		{
			return 250;
		}

		val delay = m_go!!.getPauseLength();

		return when (delay)
		{
			0 -> 700
			1 -> 1200
			2 -> 1700
			3 -> 2900
			else -> 4000
		}
	}

	/**
	 * Pause the way the game is set up to pause: for a fixed delay normally, or
	 * until the table is tapped in novice mode.
	 *
	 * This is the branch point rather than each call site, because the timed pause
	 * reaches play from everywhere -- every promptUser that waits, and four
	 * deliberate think-pauses in ComputerPlayer -- and novice mode is a choice
	 * between the two kinds of pause, not a third thing. Changing it here means
	 * every one of those sites follows, including the ones that pause between two
	 * computer players, which is what a beginner following the game wants.
	 *
	 * `m_stopping` is checked every pass, and that is the point of the loop rather
	 * than a nicety. This is a spin of Thread.sleep(100) on the game thread, and
	 * an unbounded one is a shutdown hazard: background the app mid-pause and the
	 * game thread sits here forever. pause() clears the flag for the ordinary
	 * case, and m_stopping covers the rest.
	 *
	 * Zero delay still returns early, before the tap wait. Very fast is a speed
	 * choice and novice mode is a pacing choice; asking for both means asking for
	 * the faster one, which is what a return there preserves.
	 */
	fun waitABit()
	{
		val delay = this.getDelay();

		if (delay == 0)
		{
			return;
		}

		if (m_go!!.getNoviceMode())
		{
			promptUser (getString (R.string.msg_tap_to_continue), false);

			m_waitingToAdvance = true;
			while (m_waitingToAdvance && !m_stopping)
			{
				try
				{
					Thread.sleep(100);
				}
				catch (e: InterruptedException)
				{
				}
			}
			m_waitingToAdvance = false;

			return;
		}

		try
		{
			Thread.sleep (delay.toLong());
		}
		catch (e: InterruptedException)
		{
			// do nothing for now...
		}
	}

	private fun forceDraw(p: Player, numcards: Int)
	{
		// manipulate the m_currPlayer so that the drawing engine will
		// point at the player who is drawing; we'll put it back when done.
		val realCurrPlayer = m_currPlayer;
		m_currPlayer = p;
		redrawTable();

		val msg = String.format(getString(R.string.msg_player_drawing), seatToString(p.getSeat()), numcards);
		Log.d("HDU", msg);
		promptUser (msg);

		var notEnoughCards = false;
		m_forceDrawing = true;
		for (i in 0 until numcards)
		{
			val c = drawCard();

			if (c != null)
			{
				p.addCardToHand(c);
				if ((p.getSeat() == SEAT_SOUTH) || m_go!!.getFaceUp())
				{
					c.setFaceUp(true);
				}
			}
			else
			{
				notEnoughCards = true;
				break;
			}
		}
		m_forceDrawing = false;

		if (notEnoughCards)
		{
			promptUser (getString(R.string.msg_discard_empty));
		}

		sortHand (p.getHand()!!);
		redrawTable();
		m_currPlayer = realCurrPlayer;
	}

	private fun logCardPlay (p: Player, c: Card)
	{
		Log.d("HDU", seatToString (p.getSeat()) + " plays " + cardToString(c));
	}

	/**
	 * Say that a card was accepted on the human's turn.
	 *
	 * The counterpart to the "That card's no good" that a rejected play produces.
	 * Both halves of that pair are emitted from `HumanPlayer.turnDecisionPlayCard`,
	 * so the two sit next to each other rather than one in Game and one in the
	 * player.
	 *
	 * The sentence is built here rather than in `HumanPlayer` because `seatToString`
	 * is private to this file and the card descriptor needs `m_gt` for a context.
	 * Both already existed; this only puts them in one place.
	 *
	 * `wait` is false, as it is for the rejection: the play has been accepted and
	 * the game thread is about to make it happen, so blocking the UI thread to let
	 * a toast fade would stall the turn it is confirming.
	 */
	internal fun promptCardPlayed (p: Player, c: Card)
	{
		promptUser (String.format (
				getString (R.string.msg_card_played),
				seatToString (p.getSeat()),
				cardToString (c)), false);
	}

	fun cardToString (c: Card): String
	{
		return c.toString(m_gt!!.getContext(), m_go!!.getFamilyFriendly());
	}

	private fun directionToString (dir: Int): String
	{
		if (dir == DIR_CLOCKWISE)
		{
			return getString(R.string.direction_clockwise);
		}

		return getString(R.string.direction_counterclockwise);
	}

	private fun colorToString (c: Int): String
	{
		return when (c)
		{
			Card.COLOR_BLUE -> getString(R.string.cardcolor_blue)
			Card.COLOR_GREEN -> getString(R.string.cardcolor_green)
			Card.COLOR_RED -> getString(R.string.cardcolor_red)
			Card.COLOR_YELLOW -> getString(R.string.cardcolor_yellow)
			else -> ""
		}
	}

	private fun seatToString (seat: Int): String
	{
		return when (seat)
		{
			SEAT_NORTH -> getString(R.string.seat_north)
			SEAT_EAST -> getString(R.string.seat_east)
			SEAT_SOUTH -> getString(R.string.seat_south)
			SEAT_WEST -> getString(R.string.seat_west)
			else -> ""
		}
	}


	/**
	 * Convenience function; lets us retrieve resource strings with minimal syntax;
	 * also lets the player objects retrieve strings without knowledge of the
	 * Activity/View/Context.
	 * @param resid
	 * @return
	 */
	fun getString(resid: Int): String
	{
		return m_gt!!.getContext().getString(resid);
	}


}
