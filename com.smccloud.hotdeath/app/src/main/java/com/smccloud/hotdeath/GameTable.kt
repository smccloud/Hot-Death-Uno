package com.smccloud.hotdeath

import android.os.Handler
import android.util.Log

import android.app.AlertDialog

import android.content.DialogInterface

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.content.Context
import java.util.HashMap

import android.graphics.*
import android.content.res.Resources

// Every method name in here is the Java spelling, capitalisation included, and
// that is deliberate. RedrawTable, ShowCardHelp, Toast, PromptForVictim,
// PromptForNumCardsToDeal and PromptForColor would all read as lowerCamelCase
// now that nothing outside this class is Java-only, but six of the seven are
// called from Game.kt and the seventh is not called at all. Renaming them buys
// nothing and would put a second file in the diff for a conversion whose whole
// claim is that it moved a file and changed nothing else.
//
// The one name that cannot stay is comfortable is Toast: the method and
// android.widget.Toast would collide, so the import is dropped and the class is
// written out in full at the two places it is needed. A qualified package name
// cannot be shadowed by a member, so that resolves where `Toast.makeText` would
// not.
class GameTable private constructor(context: Context) : View(context)
{
	private var m_cardoffset = IntArray(4)
	private var m_currentDrag = IntArray(4)

	private var m_maxCardsDisplay = 7

	private val m_drawMatrix = Matrix()

	private var m_ptDiscardPile: Point? = null
	private var m_ptDrawPile: Point? = null

	private val m_ptSeat = arrayOfNulls<Point>(4)
	private val m_ptEmoticon = arrayOfNulls<Point>(4)
	private val m_ptPlayerIndicator = arrayOfNulls<Point>(4)
	private val m_ptCardBadge = arrayOfNulls<Point>(4)
	private val m_ptScoreText = arrayOfNulls<Point>(4)
	private var m_ptDirColor: Point? = null
	private var m_ptWinningMessage: Point? = null
	private var m_ptMessages: Point? = null

	private val m_handBoundingRect = arrayOfNulls<Rect>(4)
	private var m_drawPileBoundingRect: Rect? = null
	private var m_discardPileBoundingRect: Rect? = null

	private var m_leftMargin = 0
	private var m_rightMargin = 0
	private var m_topMargin = 0
	private var m_bottomMargin = 0

	private var m_bottomMarginExternal = 0

	private var m_cardSpacing = 0
	private var m_cardSpacingHuman = 0

	private var m_maxWidthHand = 0
	private var m_maxHeightHand = 0

	private var m_maxWidthHandHuman = 0

	// FIXME: make resolution independent (at least just query the bitmaps for their width and height)
	/*  LDPI
	private int m_cardWidth = 43;
	private int m_cardHeight = 59;
	*/
	private var m_cardWidth = 0
	private var m_cardHeight = 0

	private var m_emoticonWidth = 0
	private var m_emoticonHeight = 0

	private var m_ptTouchDown: Point? = null
	private var m_heldSteady = false
	private var m_waitingForTouchAndHold = false
	private var m_touchAndHold = false
	private var m_touchDrawPile = false
	private var m_touchDiscardPile = false
	private var m_touchSeat = 0

	private val m_cardIDs = arrayOfNulls<Int>(81)
	private val m_cardLookup = HashMap<Int, Card>()
	private val m_imageIDLookup = HashMap<Int, Int>()
	private val m_imageLookup = HashMap<Int, Bitmap>()
	private val m_cardHelpLookup = HashMap<Int, Int>()

	private lateinit var m_bmpCardBack: Bitmap

	private lateinit var m_bmpDirColorCCW: Bitmap
	private lateinit var m_bmpDirColorCCWRed: Bitmap
	private lateinit var m_bmpDirColorCCWGreen: Bitmap
	private lateinit var m_bmpDirColorCCWBlue: Bitmap
	private lateinit var m_bmpDirColorCCWYellow: Bitmap
	private lateinit var m_bmpDirColorCW: Bitmap
	private lateinit var m_bmpDirColorCWRed: Bitmap
	private lateinit var m_bmpDirColorCWGreen: Bitmap
	private lateinit var m_bmpDirColorCWBlue: Bitmap
	private lateinit var m_bmpDirColorCWYellow: Bitmap
	private lateinit var m_bmpEmoticonAggressor: Bitmap
	private lateinit var m_bmpEmoticonVictim: Bitmap
	private val m_bmpPlayerIndicator = Array(5) { arrayOfNulls<Bitmap>(4) }
	private val m_bmpWinningMessage = arrayOfNulls<Bitmap>(4)
	private lateinit var m_bmpCardBadge: Bitmap

	private lateinit var m_paintTable: Paint
	private lateinit var m_paintTableText: Paint
	private lateinit var m_paintScoreText: Paint
	private lateinit var m_paintCardBadgeText: Paint

	private var m_readyToStartGame = false
	private var m_waitingToStartGame = false

	private val m_handler = Handler()

	private var m_toast: android.widget.Toast? = null

	private var m_helpCardID = -1

	// Both are nulled by shutdown(), so both are nullable here and every
	// dereference below is an explicit `!!`. That is the same bargain Game.kt
	// made with its own m_go and m_gt: the Java threw a NullPointerException on
	// a table whose activity has gone away, and `!!` throws it in exactly the
	// same place rather than quietly turning the bug into a no-op.
	private var m_game: Game? = null
	private var m_go: GameOptions? = null

	fun setHelpCardID (id: Int)
	{
		m_helpCardID = id
	}

	fun getHelpCardID (): Int
	{
		return m_helpCardID
	}

	// Nullable returns, like the Java's, because these are all HashMap.get.
	// GameActivity.java is the only caller and it already null-checks the card,
	// so it costs that caller nothing.
	fun getCardByID (id: Int): Card?
	{
		return m_cardLookup[id]
	}

	fun getCardImageID(id: Int): Int
	{
		// `!!` rather than an elvis: the Java returned an int and auto-unboxed
		// the Integer, so a missing card threw. -1 would not.
		return m_imageIDLookup[id]!!
	}

	fun getCardHelpText (id: Int): Int
	{
		// Unboxed in the Java too -- GameActivity hands the result straight to
		// setText, which takes a resource id.
		return m_cardHelpLookup[id]!!
	}

	fun getCardBitmap (id: Int): Bitmap?
	{
		return m_imageLookup[id]
	}

	fun getCardIDs(): Array<Int?>
	{
		return m_cardIDs
	}


	constructor(context: Context, g: Game, go: GameOptions) : this(context)
	{
		setBackgroundResource(R.drawable.table_background)

		isFocusable = true
		isFocusableInTouchMode = true
		id = ID

		m_go = go
		m_game = g
		m_game!!.setGameTable (this)

		val scale = context.resources.displayMetrics.density

		m_paintTable = Paint()
		m_paintTable.setColor(resources.getColor(R.color.table_background))

		m_paintTableText = Paint(Paint.ANTI_ALIAS_FLAG)
		m_paintTableText.setColor(resources.getColor(R.color.table_text))
		m_paintTableText.setTextAlign(Paint.Align.CENTER)
		m_paintTableText.setTextSize(12 * scale)
		m_paintTableText.setTypeface(Typeface.DEFAULT)

		m_paintScoreText = Paint(Paint.ANTI_ALIAS_FLAG)
		m_paintScoreText.setColor(resources.getColor(R.color.score_text))
		m_paintScoreText.setTextSize(12 * scale)
		m_paintScoreText.setTypeface(Typeface.DEFAULT_BOLD)

		m_paintCardBadgeText = Paint(Paint.ANTI_ALIAS_FLAG)
		m_paintCardBadgeText.setColor(resources.getColor(R.color.card_badge_text))
		m_paintCardBadgeText.setTextAlign(Paint.Align.CENTER)
		m_paintCardBadgeText.setTextSize(14 * scale)
		m_paintCardBadgeText.setTypeface(Typeface.DEFAULT_BOLD)

		initCards()

		m_cardHeight = m_bmpCardBack.height
		m_cardWidth = m_bmpCardBack.width

		m_emoticonHeight = m_bmpEmoticonAggressor.height
		m_emoticonWidth = m_bmpEmoticonAggressor.width
	}

	fun shutdown ()
	{
		m_game = null
		m_go = null
	}


	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		m_leftMargin = m_cardWidth / 4
		m_rightMargin = m_cardWidth / 4
		m_topMargin = m_cardHeight / 3
		m_bottomMargin = m_cardHeight / 3 + m_bottomMarginExternal

		if (h < 4.5 * m_cardHeight)
		{
			// probably landscape on a small device...
			m_topMargin = m_cardHeight / 4
			m_bottomMargin = m_cardHeight / 4 + m_bottomMarginExternal
			m_ptDrawPile = Point (w / 2 - 5 * m_cardWidth / 4, h / 2 - m_cardHeight / 2)
			m_ptDiscardPile = Point (w / 2 + m_cardWidth / 4, h / 2 - m_cardHeight / 2)
			m_ptDirColor = Point (m_ptDiscardPile!!.x + 2 * m_cardWidth + m_bmpDirColorCCW.width / 4 - m_bmpPlayerIndicator[0][0]!!.width, h / 2 - m_bmpDirColorCCW.width / 2)
		}
		else
		{
			// portrait
			m_ptDrawPile = Point (w / 2 - 5 * m_cardWidth / 4, h / 2 - m_cardHeight)
			m_ptDiscardPile = Point (w / 2 + m_cardWidth / 4, h / 2 - m_cardHeight)
			m_ptDirColor = Point (w /2 - m_bmpDirColorCCW.width / 2, h / 2 + m_cardHeight / 4)
		}

		m_ptPlayerIndicator[Game.SEAT_NORTH - 1] = Point (m_ptDirColor!!.x + m_bmpDirColorCCW.width / 2 - m_bmpPlayerIndicator[0][0]!!.width / 2, m_ptDirColor!!.y - m_bmpPlayerIndicator[0][0]!!.height)
		m_ptPlayerIndicator[Game.SEAT_EAST - 1] = Point (m_ptDirColor!!.x + m_bmpDirColorCCW.width, m_ptDirColor!!.y + m_bmpDirColorCCW.height / 2 -  m_bmpPlayerIndicator[0][0]!!.height / 2)
		m_ptPlayerIndicator[Game.SEAT_SOUTH - 1] = Point (m_ptDirColor!!.x + m_bmpDirColorCCW.width / 2 - m_bmpPlayerIndicator[0][0]!!.width / 2, m_ptDirColor!!.y + m_bmpPlayerIndicator[0][0]!!.height)
		m_ptPlayerIndicator[Game.SEAT_WEST - 1] = Point (m_ptDirColor!!.x - m_bmpPlayerIndicator[0][0]!!.width, m_ptDirColor!!.y + m_bmpDirColorCCW.height / 2 -  m_bmpPlayerIndicator[0][0]!!.height / 2)

		val numstr = "0"
		val textBounds = Rect()
		m_paintScoreText.getTextBounds(numstr, 0, numstr.length, textBounds)

		m_cardSpacing = m_cardWidth / 2
		m_cardSpacingHuman = 2 * (m_cardWidth / 3)

		// figure out what the maximum number of cards you can display will be

		// calculate max cards in layout 1 (N/S cards live between E/W cards)

		var humanPlayerArea = w - 2 * m_cardWidth - 2 * m_leftMargin - 2 * m_rightMargin
		var maxNumHumanCards = (humanPlayerArea - m_cardWidth) / m_cardSpacingHuman + 1

		var computerPlayerArea = h - m_topMargin - m_bottomMargin - (textBounds.height() * 1.2).toInt()
		var maxNumComputerCards = (computerPlayerArea - m_cardHeight) / m_cardSpacing + 1

		var maxCardsLayout1 = if (maxNumComputerCards > maxNumHumanCards) maxNumHumanCards else maxNumComputerCards

		// calculate max cards in layout 2 (E/W cards live between N/S cards)

		humanPlayerArea = w - m_leftMargin - m_rightMargin
		maxNumHumanCards = (humanPlayerArea - m_cardWidth) / m_cardSpacingHuman + 1

		computerPlayerArea = h - 2 * m_cardHeight - 2 * m_topMargin - 2 * m_bottomMargin
		maxNumComputerCards = (computerPlayerArea - m_cardHeight) / m_cardSpacing + 1

		val maxCardsLayout2 = if (maxNumComputerCards > maxNumHumanCards) maxNumHumanCards else maxNumComputerCards

		m_maxCardsDisplay = if (maxCardsLayout1 > maxCardsLayout2) maxCardsLayout1 else maxCardsLayout2

		Log.d("HDU", "[onSizeChanged] maxCardsLayout1: " + maxCardsLayout1)
		Log.d("HDU", "[onSizeChanged] maxCardsLayout2: " + maxCardsLayout2)
		Log.d("HDU", "[onSizeChanged] m_maxCardsDisplay: " + m_maxCardsDisplay)


		m_maxWidthHand = (m_maxCardsDisplay - 1) * m_cardSpacing + m_cardWidth
		m_maxHeightHand = (m_maxCardsDisplay - 1) * m_cardSpacing + m_cardHeight

		m_maxWidthHandHuman = (m_maxCardsDisplay - 1) * m_cardSpacingHuman + m_cardWidth

		m_ptSeat[Game.SEAT_NORTH - 1] = Point (w / 2, m_topMargin)
		m_ptSeat[Game.SEAT_EAST - 1] = Point (w - (m_cardWidth + m_rightMargin), h / 2)
		m_ptSeat[Game.SEAT_SOUTH - 1] = Point (w / 2, h - (m_cardHeight + m_bottomMargin))
		m_ptSeat[Game.SEAT_WEST - 1] = Point (m_leftMargin, h / 2)

		m_ptWinningMessage = Point (m_ptSeat[Game.SEAT_SOUTH - 1]!!.x - m_bmpWinningMessage[0]!!.width / 2, m_ptSeat[Game.SEAT_SOUTH - 1]!!.y - m_bmpWinningMessage[0]!!.height * 5 / 4)

		m_ptEmoticon[Game.SEAT_NORTH - 1] = Point (m_ptSeat[Game.SEAT_NORTH - 1]!!.x - m_emoticonWidth / 2, m_ptSeat[Game.SEAT_NORTH - 1]!!.y + m_cardHeight * 11 / 10)
		m_ptEmoticon[Game.SEAT_EAST - 1] = Point (m_ptSeat[Game.SEAT_EAST - 1]!!.x - m_emoticonWidth - m_cardWidth / 10, m_ptSeat[Game.SEAT_EAST - 1]!!.y - m_emoticonHeight / 2)
		m_ptEmoticon[Game.SEAT_SOUTH - 1] = Point (m_ptSeat[Game.SEAT_SOUTH - 1]!!.x - m_emoticonWidth / 2, m_ptSeat[Game.SEAT_SOUTH - 1]!!.y - m_emoticonHeight - m_cardHeight / 10)
		m_ptEmoticon[Game.SEAT_WEST - 1] = Point (m_ptSeat[Game.SEAT_WEST - 1]!!.x + m_cardWidth * 11 / 10, m_ptSeat[Game.SEAT_WEST - 1]!!.y - m_emoticonHeight / 2)

		m_ptCardBadge[Game.SEAT_NORTH - 1] = Point (m_ptSeat[Game.SEAT_NORTH - 1]!!.x + m_maxWidthHand / 2 - m_bmpCardBadge.width / 2,
				m_ptSeat[Game.SEAT_NORTH - 1]!!.y + m_cardHeight - m_bmpCardBadge.height  / 2)
		m_ptCardBadge[Game.SEAT_EAST - 1] = Point (m_ptSeat[Game.SEAT_EAST - 1]!!.x + m_cardWidth - m_bmpCardBadge.width / 2,
				m_ptSeat[Game.SEAT_EAST - 1]!!.y + m_maxHeightHand / 2 - m_bmpCardBadge.height / 2)
		m_ptCardBadge[Game.SEAT_SOUTH - 1] = Point (m_ptSeat[Game.SEAT_SOUTH - 1]!!.x + m_maxWidthHandHuman / 2 - m_bmpCardBadge.width / 2,
				m_ptSeat[Game.SEAT_SOUTH - 1]!!.y + m_cardHeight - m_bmpCardBadge.height / 2)
		m_ptCardBadge[Game.SEAT_WEST - 1] = Point (m_ptSeat[Game.SEAT_WEST - 1]!!.x + m_cardWidth - m_bmpCardBadge.width / 2,
				m_ptSeat[Game.SEAT_WEST - 1]!!.y + m_maxHeightHand / 2 - m_bmpCardBadge.height / 2)

		m_ptScoreText[Game.SEAT_NORTH - 1] = Point (m_ptSeat[Game.SEAT_NORTH - 1]!!.x,
				m_ptSeat[Game.SEAT_NORTH - 1]!!.y - (textBounds.height() * 1.1).toInt())
		m_ptScoreText[Game.SEAT_EAST - 1] = Point (m_ptSeat[Game.SEAT_EAST - 1]!!.x + m_cardWidth,
			m_ptSeat[Game.SEAT_EAST - 1]!!.y - m_maxHeightHand / 2 - (textBounds.height() * 1.1).toInt())
		m_ptScoreText[Game.SEAT_SOUTH - 1] = Point (m_ptSeat[Game.SEAT_SOUTH - 1]!!.x,
				m_ptSeat[Game.SEAT_SOUTH - 1]!!.y + m_cardHeight + (textBounds.height() * 1.5).toInt())
		m_ptScoreText[Game.SEAT_WEST - 1] = Point (m_ptSeat[Game.SEAT_WEST - 1]!!.x,
				m_ptSeat[Game.SEAT_WEST - 1]!!.y - m_maxHeightHand / 2 - (textBounds.height() * 1.1).toInt())

		m_ptMessages = Point (m_ptSeat[Game.SEAT_SOUTH - 1]!!.x, m_ptSeat[Game.SEAT_SOUTH - 1]!!.y - 3 * m_cardHeight / 4)

		super.onSizeChanged(w, h, oldw, oldh)

		m_readyToStartGame = true
		if (m_waitingToStartGame)
		{
			m_waitingToStartGame = false
			m_game!!.start ()
		}
	}

	fun setBottomMargin (m: Int) {
		m_bottomMarginExternal = m
	}


	fun startGameWhenReady ()
	{
		if (m_readyToStartGame)
		{
			m_game!!.start ()
			return
		}

		m_waitingToStartGame = true
	}

	fun showFastForwardButton (show: Boolean)
	{
		val a = context as GameActivity
		if (show)
		{
			a.getBtnFastForward().setVisibility(View.VISIBLE)
		}
		else
		{
			a.getBtnFastForward().setVisibility(View.INVISIBLE)
		}
	}

	fun showMenuButton (show: Boolean)
	{
		val a = context as GameActivity
		if (show)
		{
			a.showMenuButtons()
		}
		else
		{
			a.hideMenuButtons()
		}
	}

	private val m_touchAndHoldTask = Runnable
	{

		// if something cancelled the wait (like ACTION_UP, ACTION_CANCEL, or a
		// large enough ACTION_MOVE), we don't show card help
		if (!m_waitingForTouchAndHold)
		{
			return@Runnable
		}

		m_touchAndHold = true

		// only show card help while it's the human player's turn or the
		// round is complete
		val p = m_game!!.getCurrPlayer()
		if (!((p is HumanPlayer)
				|| (m_game!!.getRoundComplete())))
		{
			return@Runnable
		}

		// only show card help for face-up cards!
		val c = findTouchedCard (m_ptTouchDown!!)
		if (c == null)
		{
			return@Runnable
		}
		if (!c.getFaceUp())
		{
			return@Runnable
		}

		val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
		v.vibrate (100)
		ShowCardHelp(c)
	}


	private fun heldSteadyHand(): Boolean
	{
		if (m_touchSeat == 0)
		{
			return false
		}

		return m_heldSteady
	}

	private fun heldSteadyDraw(): Boolean
	{
		// check for draw (DOWN/UP in the draw pile)
		if (!m_touchDrawPile)
		{
			return false
		}

		return m_heldSteady
	}

	private fun heldSteadyDiscard(): Boolean
	{
		// check for draw (DOWN/UP in the draw pile)
		if (!m_touchDiscardPile)
		{
			return false
		}

		return m_heldSteady
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if (event.action == MotionEvent.ACTION_CANCEL)
		{
			m_handler.removeCallbacks(m_touchAndHoldTask)
			m_waitingForTouchAndHold = false
			return true
		}

		if (event.action == MotionEvent.ACTION_DOWN)
		{
			val x = event.x.toInt()
			val y = event.y.toInt()

			m_ptTouchDown = Point (x, y)
			m_touchAndHold = false
			m_heldSteady = true

			m_touchDiscardPile = false
			m_touchDrawPile = false
			m_touchSeat = 0
			if ((m_handBoundingRect[Game.SEAT_SOUTH - 1] != null)
					&& m_handBoundingRect[Game.SEAT_SOUTH - 1]!!.contains(x, y))
			{
				m_touchSeat = Game.SEAT_SOUTH
			}
			else if ((m_handBoundingRect[Game.SEAT_WEST - 1] != null)
				&& m_handBoundingRect[Game.SEAT_WEST - 1]!!.contains(x, y))
			{
				m_touchSeat = Game.SEAT_WEST
			}
			else if ((m_handBoundingRect[Game.SEAT_NORTH - 1] != null)
					&& m_handBoundingRect[Game.SEAT_NORTH - 1]!!.contains(x, y))
			{
				m_touchSeat = Game.SEAT_NORTH
			}
			else if ((m_handBoundingRect[Game.SEAT_EAST - 1] != null)
					&& m_handBoundingRect[Game.SEAT_EAST - 1]!!.contains(x, y))
			{
				m_touchSeat = Game.SEAT_EAST
			}

			if (m_touchSeat != 0)
			{
				m_waitingForTouchAndHold = true
				m_handler.postDelayed (m_touchAndHoldTask, 1000)

				m_ptTouchDown = Point (x, y)
				return true
			}

			if (m_drawPileBoundingRect != null && m_drawPileBoundingRect!!.contains (x, y))
			{
				m_touchDrawPile = true
			}

			if (m_discardPileBoundingRect != null && m_discardPileBoundingRect!!.contains (x, y))
			{
				m_waitingForTouchAndHold = true
				m_handler.postDelayed (m_touchAndHoldTask, 1000)

				m_touchDiscardPile = true
			}

			return true
		}
		else if (event.action == MotionEvent.ACTION_UP)
		{
			if (m_touchAndHold)
			{
				return true
			}

			m_waitingForTouchAndHold = false

			// if we haven't moved from the card we originally touched down on,
			// we'll play that card.
			if (heldSteadyHand())
			{
				handCardTapped (m_touchSeat, m_ptTouchDown!!)
				return true
			}

			if (heldSteadyDraw())
			{
				drawPileTapped ()
				return true
			}

			if (heldSteadyDiscard())
			{
				discardPileTapped ()
				return true
			}

			// if we're letting up on a drag, commit the drag value
			if (m_touchSeat != 0)
			{
				val idx = m_touchSeat - 1
				if (m_currentDrag[idx] != 0)
				{
					m_cardoffset[idx] += m_currentDrag[idx]

					// set bounds properly
					val p = m_game!!.getPlayer(idx)!!
					val ncards = p.getHand()!!.getNumCards()

					if (m_cardoffset[idx] >= ncards - m_maxCardsDisplay)
					{
						m_cardoffset[idx] = ncards - m_maxCardsDisplay
					}

					if (m_cardoffset[idx] < 0)
					{
						m_cardoffset[idx] = 0
					}

					m_currentDrag[idx] = 0
				}
				m_touchSeat = 0
				return true
			}

			return true
		}
		else if (event.action == MotionEvent.ACTION_MOVE)
		{
			if (m_touchSeat != 0)
			{
				val spacing = if (m_game!!.getPlayer(m_touchSeat - 1)!! is HumanPlayer)
					m_cardSpacingHuman
				else
					m_cardSpacing

				var cardoffset: Int

				if (m_touchSeat == Game.SEAT_NORTH || m_touchSeat == Game.SEAT_SOUTH)
				{
					val distx = event.x.toInt() - m_ptTouchDown!!.x
					cardoffset = distx / (spacing / 2)
				}
				else
				{
					val disty = event.y.toInt() - m_ptTouchDown!!.y
					cardoffset = disty / spacing
				}

				if (cardoffset != 0)
				{
					if (m_heldSteady)
					{
						Log.d("HDU", "[ACTION_MOVE] cardoffset = " + cardoffset + ", m_heldSteady=false now")
						m_waitingForTouchAndHold = false
						m_handler.removeCallbacks(m_touchAndHoldTask)
						m_heldSteady = false
					}
				}

				// invert the offset, as a slide to the left means increase the offset
				m_currentDrag[m_touchSeat - 1] = 0 - cardoffset
				this.invalidate()

				return true
			}
		}

		return super.onTouchEvent(event)
	}

	private fun drawPileTapped ()
	{
		m_game!!.drawPileTapped()
	}

	private fun discardPileTapped ()
	{
		m_game!!.discardPileTapped()
	}

	private fun findTouchedCardHand (seat: Int, pt: Point): Card?
	{
		val r = m_handBoundingRect[seat - 1]
		if (r == null)
		{
			return null
		}

		if (!r.contains(pt.x, pt.y))
		{
			return null
		}

		val spacing = if (m_game!!.getPlayer(seat - 1)!! is HumanPlayer)
			m_cardSpacingHuman
		else
			m_cardSpacing

		var idx = 0
		when (seat)
		{
			Game.SEAT_NORTH, Game.SEAT_SOUTH ->
			{
				idx = (pt.x - r.left) / spacing
			}

			Game.SEAT_WEST, Game.SEAT_EAST ->
			{
				idx = (pt.y - r.top) / spacing
			}
		}

		val p = m_game!!.getPlayer(seat - 1)!!
		val h = p.getHand()!!

		var numcardsshowing = h.getNumCards() - m_cardoffset[seat - 1]
		numcardsshowing = if (numcardsshowing > m_maxCardsDisplay) m_maxCardsDisplay else numcardsshowing

		if (idx >= numcardsshowing)
		{
			idx = numcardsshowing - 1
		}
		idx += m_cardoffset[seat - 1]


		return h.getCard(idx)
	}

	private fun findTouchedCardDiscardPile (pt: Point): Card?
	{
		if (m_discardPileBoundingRect!!.contains(pt.x, pt.y))
		{
			val numcards = m_game!!.getDiscardPile()!!.getNumCards()
			return m_game!!.getDiscardPile()!!.getCard(numcards - 1)
		}

		return null
	}

	private fun findTouchedCard (pt: Point): Card?
	{
		if (m_touchDiscardPile)
		{
			return findTouchedCardDiscardPile (pt)
		}
		if (m_touchSeat != 0)
		{
			return findTouchedCardHand (m_touchSeat, pt)
		}

		return null
	}

	private fun handCardTapped (seat: Int, pt: Point)
	{
		if (!m_game!!.roundIsActive())
		{
			return
		}

		val p = m_game!!.getPlayer(seat - 1)!!
		if (p is HumanPlayer)
		{
			val c = findTouchedCardHand (seat, pt)

			if (c != null)
			{
				(p as HumanPlayer).turnDecisionPlayCard (c)
			}
		}
	}


	fun RedrawTable ()
	{
		this.invalidate();
	}

	override fun onDraw(canvas: Canvas)
	{
		// canvas.drawRect(0, 0, width, height, m_paintTable);

		// draw the color and direction indicator

		var bmp: Bitmap? = null

		val curr_color = m_game!!.getCurrColor()

		if (m_game!!.getDirection() == Game.DIR_CCLOCKWISE)
		{
			when (curr_color)
			{
				Card.COLOR_WILD -> bmp = m_bmpDirColorCCW
				Card.COLOR_RED -> bmp = m_bmpDirColorCCWRed
				Card.COLOR_GREEN -> bmp = m_bmpDirColorCCWGreen
				Card.COLOR_BLUE -> bmp = m_bmpDirColorCCWBlue
				Card.COLOR_YELLOW -> bmp = m_bmpDirColorCCWYellow
			}
		}
		else
		{
			when (curr_color)
			{
				Card.COLOR_WILD -> bmp = m_bmpDirColorCW
				Card.COLOR_RED -> bmp = m_bmpDirColorCWRed
				Card.COLOR_GREEN -> bmp = m_bmpDirColorCWGreen
				Card.COLOR_BLUE -> bmp = m_bmpDirColorCWBlue
				Card.COLOR_YELLOW -> bmp = m_bmpDirColorCWYellow
			}
		}

		// before the deal, we don't have a direction
		if (bmp == null)
		{
			return
		}

		m_drawMatrix.reset()
		m_drawMatrix.setScale(1f, 1f)
		m_drawMatrix.setTranslate(m_ptDirColor!!.x.toFloat(), m_ptDirColor!!.y.toFloat())
		canvas.drawBitmap(bmp, m_drawMatrix, null)

		displayScore (canvas)

		var x = 0
		var y = 0

		// draw the hands

		for (i in 0 until 4)
		{
			val p = m_game!!.getPlayer(i)!!


			// don't draw ejected players' cards

			if (p.getActive())
			{
				RedrawHand (canvas, i + 1)
			}
		}

		val p = m_game!!.getCurrPlayer()
		if (p != null)
		{
			val pt = m_ptPlayerIndicator[p.getSeat() - 1]!!

			m_drawMatrix.reset()
			m_drawMatrix.setScale(1f, 1f)
			m_drawMatrix.setTranslate(pt.x.toFloat(), pt.y.toFloat())

			canvas.drawBitmap(m_bmpPlayerIndicator[curr_color - 1][p.getSeat() - 1]!!, m_drawMatrix, null)
		}

		if (m_game!!.getFastForward())
		{
			return
		}

		// draw the discard pile

		var pile = m_game!!.getDiscardPile()
		val numCardsInPlay = pile!!.getNumCards()
		val deck = m_game!!.getDeck ()

		var skip = 16
		if (deck != null)
		{
			if (deck.getNumCards () > 108)
			{
				skip = 32
			}
		}

		// Bound to a val rather than re-null-checking `pile`, so the smart cast is
		// not doing any work here. `pile` is reassigned just below.
		val discardPile = pile
		if (discardPile != null)
		{
			// A while loop rather than Kotlin's for, because the body rewrites i:
			// on the last pass it snaps i to the index of the top card, and the
			// trailing i += skip then steps past it and ends the loop. A for
			// cannot reassign its counter, and changing the order would drop the
			// top card -- which is the one card the player can actually see.
			var i = 0
			while (i < numCardsInPlay)
			{
				// make sure that the top card is drawn...
				if (i >= numCardsInPlay - skip)
				{
					i = numCardsInPlay - 1
				}

				val c = discardPile.getCard(i)
				if (c != null)
				{
					// FIXME -- make resolution independent
					x = m_ptDiscardPile!!.x + (i.toFloat() / skip.toFloat()).toInt() * 2
					y = m_ptDiscardPile!!.y + (i.toFloat() / skip.toFloat()).toInt() * 2
					c.setFaceUp(true)

					this.drawCard (canvas, c, x, y, true)
				}

				i += skip
			}
		}


		m_discardPileBoundingRect = Rect(m_ptDiscardPile!!.x, m_ptDiscardPile!!.y, x + m_cardWidth, y + m_cardHeight)

		// draw the draw pile

		pile = m_game!!.getDrawPile()


		val drawPile = pile


		if (drawPile != null)
		{
			x = m_ptDrawPile!!.x
			y = m_ptDrawPile!!.y
			val numCardsInPile = drawPile.getNumCards()
			var i = 0
			while (i < numCardsInPile)
			{
				if (i >= numCardsInPile - skip)
				{
					i = numCardsInPile - 1
				}

				val c = drawPile.getCard(i)
				if (c != null)
				{
					this.drawCard (canvas, c, x, y, false)
					// FIXME -- make resolution independent!
					x += 2
					y += 2
				}

				i += skip
			}
		}

		m_drawPileBoundingRect = Rect(m_ptDrawPile!!.x, m_ptDrawPile!!.y, x + m_cardWidth, y + m_cardHeight)



		if (m_game!!.getWinner() != 0)
		{
			m_drawMatrix.reset()
			m_drawMatrix.setScale(1f, 1f)
			m_drawMatrix.setTranslate(m_ptWinningMessage!!.x.toFloat(), m_ptWinningMessage!!.y.toFloat())

			canvas.drawBitmap(m_bmpWinningMessage[m_game!!.getWinner() - 1]!!, m_drawMatrix, null)
		}

		drawPenalty(canvas)
	}

	private fun RedrawHand (cv: Canvas, seat: Int)
	{
		val p = m_game!!.getPlayer(seat - 1)!!
		val h = p.getHand()
		if (h == null)
		{
			return
		}

		var x = 0
		var y = 0
		var dx = 0
		var dy = 0
		val numcards = h.getNumCards()

		// keep the offsets sane
		if (m_cardoffset[seat-1] > numcards - m_maxCardsDisplay)
		{
			m_cardoffset[seat-1] = numcards - m_maxCardsDisplay
		}
		if (m_cardoffset[seat-1] < 0)
		{
			m_cardoffset[seat-1] = 0
		}

		// apply the current drag
		var cardoffset = m_cardoffset[seat - 1] + m_currentDrag[seat - 1]
		if (cardoffset > numcards - m_maxCardsDisplay)
		{
			cardoffset = numcards - m_maxCardsDisplay
		}
		if (cardoffset < 0)
		{
			cardoffset = 0
		}

		var numcardsshowing = numcards - m_cardoffset[seat - 1]
		numcardsshowing = if (numcardsshowing > m_maxCardsDisplay) m_maxCardsDisplay else numcardsshowing

		var handWidth = 0
		var handHeight = 0

		val spacing = if (p is HumanPlayer)
			m_cardSpacingHuman
		else
			m_cardSpacing

		when (seat) {
			Game.SEAT_SOUTH ->
			{
				dx = spacing
				dy = 0
				handWidth = (numcardsshowing - 1) * spacing + m_cardWidth
				x = m_ptSeat[Game.SEAT_SOUTH - 1]!!.x - handWidth / 2
				y = m_ptSeat[Game.SEAT_SOUTH - 1]!!.y
				m_handBoundingRect[Game.SEAT_SOUTH - 1] = Rect(x, y, x + handWidth, y + m_cardHeight)
			}
			Game.SEAT_WEST ->
			{
				dx = 0
				dy = spacing
				handHeight = (numcardsshowing - 1) * spacing + m_cardHeight
				x = m_ptSeat[Game.SEAT_WEST - 1]!!.x
				y = m_ptSeat[Game.SEAT_WEST - 1]!!.y - handHeight / 2
				m_handBoundingRect[Game.SEAT_WEST - 1] = Rect(x, y, x + m_cardWidth, y + handHeight)
			}
			Game.SEAT_NORTH ->
			{
				dx = spacing
				dy = 0
				handWidth = (numcardsshowing - 1) * spacing + m_cardWidth
				x = m_ptSeat[Game.SEAT_NORTH - 1]!!.x - handWidth / 2
				y = m_ptSeat[Game.SEAT_NORTH - 1]!!.y
				m_handBoundingRect[Game.SEAT_NORTH - 1] = Rect(x, y, x + handWidth, y + m_cardHeight)
			}
			Game.SEAT_EAST ->
			{
				dx = 0
				dy = spacing
				handHeight = (numcardsshowing - 1) * spacing + m_cardHeight
				x = m_ptSeat[Game.SEAT_EAST - 1]!!.x
				y = m_ptSeat[Game.SEAT_EAST - 1]!!.y - handHeight / 2
				m_handBoundingRect[Game.SEAT_EAST - 1] = Rect(x, y, x + m_cardWidth, y + handHeight)
			}
		}

		// draw the cards that are on the table

		var stop = numcards
		if (cardoffset + m_maxCardsDisplay < numcards)
		{
			stop = cardoffset + m_maxCardsDisplay
		}

		for (j in cardoffset until stop)
		{
			val c = h.getCard(j)
			if (c == null)
			{
				continue
			}

			this.drawCard (cv, c, x, y, c.getFaceUp())

			x += dx
			y += dy
		}

		if (numcards > m_maxCardsDisplay)
		{
			val pt = m_ptCardBadge[seat - 1]!!

			m_drawMatrix.reset()
			m_drawMatrix.setScale(1f, 1f)
			m_drawMatrix.setTranslate(pt.x.toFloat(), pt.y.toFloat())

			cv.drawBitmap(m_bmpCardBadge, m_drawMatrix, null)

			val fx = (pt.x + m_bmpCardBadge.width / 2).toFloat()
			val textBounds = Rect()
			val numstr = "" + numcards

			m_paintCardBadgeText.getTextBounds(numstr, 0, numstr.length, textBounds)
			val fy = (pt.y + m_bmpCardBadge.height / 2 + (textBounds.height() / 2)).toFloat()

			cv.drawText(numstr, fx, fy, m_paintCardBadgeText)
		}
	}

	private fun initCards ()
	{
		/*
		 * I admit -- this code is nasty; it started with a simple lookup HashMap,
		 * and gradually grew into 4 separate ones.  This could be a LOT cleaner.
		 * I also don't like that I have to create all these card objects when there
		 * are already card objects in the card deck.  But this was more convenient,
		 * and it's hard to imagine that these objects are really taking up a lot of
		 * RAM in the grand scheme of things.
		 */
		val res: Resources = context.resources

		val opt = BitmapFactory.Options()
		//opt.inScaled = false;

		m_bmpCardBack = BitmapFactory.decodeResource(res, R.drawable.card_back, opt)

		m_imageIDLookup.put (Card.ID_RED_0, R.drawable.card_red_0)
		m_imageLookup.put (Card.ID_RED_0, BitmapFactory.decodeResource(res, R.drawable.card_red_0, opt))
		m_cardHelpLookup.put (Card.ID_RED_0, R.string.cardhelp_0)
		m_cardLookup.put (Card.ID_RED_0, Card(-1, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0, 0))

		m_imageIDLookup.put (Card.ID_RED_1, R.drawable.card_red_1)
		m_imageLookup.put (Card.ID_RED_1, BitmapFactory.decodeResource(res, R.drawable.card_red_1, opt))
		m_cardHelpLookup.put (Card.ID_RED_1, R.string.cardhelp_1)
		m_cardLookup.put (Card.ID_RED_1, Card(-1, Card.COLOR_RED, 1, Card.ID_RED_1, 1))

		m_imageIDLookup.put (Card.ID_RED_2, R.drawable.card_red_2)
		m_imageLookup.put (Card.ID_RED_2, BitmapFactory.decodeResource(res, R.drawable.card_red_2, opt))
		m_cardHelpLookup.put (Card.ID_RED_2, R.string.cardhelp_2)
		m_cardLookup.put (Card.ID_RED_2, Card(-1, Card.COLOR_RED, 2, Card.ID_RED_2, 2))

		m_imageIDLookup.put (Card.ID_RED_3, R.drawable.card_red_3)
		m_imageLookup.put (Card.ID_RED_3, BitmapFactory.decodeResource(res, R.drawable.card_red_3, opt))
		m_cardHelpLookup.put (Card.ID_RED_3, R.string.cardhelp_3)
		m_cardLookup.put (Card.ID_RED_3, Card(-1, Card.COLOR_RED, 3, Card.ID_RED_3, 3))

		m_imageIDLookup.put (Card.ID_RED_4, R.drawable.card_red_4)
		m_imageLookup.put (Card.ID_RED_4, BitmapFactory.decodeResource(res, R.drawable.card_red_4, opt))
		m_cardHelpLookup.put (Card.ID_RED_4, R.string.cardhelp_4)
		m_cardLookup.put (Card.ID_RED_4, Card(-1, Card.COLOR_RED, 4, Card.ID_RED_4, 4))

		m_imageIDLookup.put (Card.ID_RED_5, R.drawable.card_red_5)
		m_imageLookup.put (Card.ID_RED_5, BitmapFactory.decodeResource(res, R.drawable.card_red_5, opt))
		m_cardHelpLookup.put (Card.ID_RED_5, R.string.cardhelp_5)
		m_cardLookup.put (Card.ID_RED_5, Card(-1, Card.COLOR_RED, 5, Card.ID_RED_5, 5))

		m_imageIDLookup.put (Card.ID_RED_6, R.drawable.card_red_6)
		m_imageLookup.put (Card.ID_RED_6, BitmapFactory.decodeResource(res, R.drawable.card_red_6, opt))
		m_cardHelpLookup.put (Card.ID_RED_6, R.string.cardhelp_6)
		m_cardLookup.put (Card.ID_RED_6, Card(-1, Card.COLOR_RED, 6, Card.ID_RED_6, 6))

		m_imageIDLookup.put (Card.ID_RED_7, R.drawable.card_red_7)
		m_imageLookup.put (Card.ID_RED_7, BitmapFactory.decodeResource(res, R.drawable.card_red_7, opt))
		m_cardHelpLookup.put (Card.ID_RED_7, R.string.cardhelp_7)
		m_cardLookup.put (Card.ID_RED_7, Card(-1, Card.COLOR_RED, 7, Card.ID_RED_7, 7))

		m_imageIDLookup.put (Card.ID_RED_8, R.drawable.card_red_8)
		m_imageLookup.put (Card.ID_RED_8, BitmapFactory.decodeResource(res, R.drawable.card_red_8, opt))
		m_cardHelpLookup.put (Card.ID_RED_8, R.string.cardhelp_8)
		m_cardLookup.put (Card.ID_RED_8, Card(-1, Card.COLOR_RED, 8, Card.ID_RED_8, 8))

		m_imageIDLookup.put (Card.ID_RED_9, R.drawable.card_red_9)
		m_imageLookup.put (Card.ID_RED_9, BitmapFactory.decodeResource(res, R.drawable.card_red_9, opt))
		m_cardHelpLookup.put (Card.ID_RED_9, R.string.cardhelp_9)
		m_cardLookup.put (Card.ID_RED_9, Card(-1, Card.COLOR_RED, 9, Card.ID_RED_9, 9))

		m_imageIDLookup.put (Card.ID_RED_D, R.drawable.card_red_d)
		m_imageLookup.put (Card.ID_RED_D, BitmapFactory.decodeResource(res, R.drawable.card_red_d, opt))
		m_cardHelpLookup.put (Card.ID_RED_D, R.string.cardhelp_d)
		m_cardLookup.put (Card.ID_RED_D, Card(-1, Card.COLOR_RED, Card.VAL_D, Card.ID_RED_D, 20))

		m_imageIDLookup.put (Card.ID_RED_S, R.drawable.card_red_s)
		m_imageLookup.put (Card.ID_RED_S, BitmapFactory.decodeResource(res, R.drawable.card_red_s, opt))
		m_cardHelpLookup.put (Card.ID_RED_S, R.string.cardhelp_s)
		m_cardLookup.put (Card.ID_RED_S, Card(-1, Card.COLOR_RED, Card.VAL_S, Card.ID_RED_S, 20))

		m_imageIDLookup.put (Card.ID_RED_R, R.drawable.card_red_r)
		m_imageLookup.put (Card.ID_RED_R, BitmapFactory.decodeResource(res, R.drawable.card_red_r, opt))
		m_cardHelpLookup.put (Card.ID_RED_R, R.string.cardhelp_r)
		m_cardLookup.put (Card.ID_RED_R, Card(-1, Card.COLOR_RED, Card.VAL_R, Card.ID_RED_R, 20))

		m_imageIDLookup.put (Card.ID_GREEN_0, R.drawable.card_green_0)
		m_imageLookup.put (Card.ID_GREEN_0, BitmapFactory.decodeResource(res, R.drawable.card_green_0, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_0, R.string.cardhelp_0)
		m_cardLookup.put (Card.ID_GREEN_0, Card(-1, Card.COLOR_GREEN, 0, Card.ID_GREEN_0_QUITTER, 0))

		m_imageIDLookup.put (Card.ID_GREEN_1, R.drawable.card_green_1)
		m_imageLookup.put (Card.ID_GREEN_1, BitmapFactory.decodeResource(res, R.drawable.card_green_1, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_1, R.string.cardhelp_1)
		m_cardLookup.put (Card.ID_GREEN_1, Card(-1, Card.COLOR_GREEN, 1, Card.ID_GREEN_1, 1))

		m_imageIDLookup.put (Card.ID_GREEN_2, R.drawable.card_green_2)
		m_imageLookup.put (Card.ID_GREEN_2, BitmapFactory.decodeResource(res, R.drawable.card_green_2, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_2, R.string.cardhelp_2)
		m_cardLookup.put (Card.ID_GREEN_2, Card(-1, Card.COLOR_GREEN, 2, Card.ID_GREEN_2, 2))

		m_imageIDLookup.put (Card.ID_GREEN_3, R.drawable.card_green_3)
		m_imageLookup.put (Card.ID_GREEN_3, BitmapFactory.decodeResource(res, R.drawable.card_green_3, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_3, R.string.cardhelp_3)
		m_cardLookup.put (Card.ID_GREEN_3, Card(-1, Card.COLOR_GREEN, 3, Card.ID_GREEN_3, 3))

		m_imageIDLookup.put (Card.ID_GREEN_4, R.drawable.card_green_4)
		m_imageLookup.put (Card.ID_GREEN_4, BitmapFactory.decodeResource(res, R.drawable.card_green_4, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_4, R.string.cardhelp_4)
		m_cardLookup.put (Card.ID_GREEN_4, Card(-1, Card.COLOR_GREEN, 4, Card.ID_GREEN_4, 4))

		m_imageIDLookup.put (Card.ID_GREEN_5, R.drawable.card_green_5)
		m_imageLookup.put (Card.ID_GREEN_5, BitmapFactory.decodeResource(res, R.drawable.card_green_5, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_5, R.string.cardhelp_5)
		m_cardLookup.put (Card.ID_GREEN_5, Card(-1, Card.COLOR_GREEN, 5, Card.ID_GREEN_5, 5))

		m_imageIDLookup.put (Card.ID_GREEN_6, R.drawable.card_green_6)
		m_imageLookup.put (Card.ID_GREEN_6, BitmapFactory.decodeResource(res, R.drawable.card_green_6, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_6, R.string.cardhelp_6)
		m_cardLookup.put (Card.ID_GREEN_6, Card(-1, Card.COLOR_GREEN, 6, Card.ID_GREEN_6, 6))

		m_imageIDLookup.put (Card.ID_GREEN_7, R.drawable.card_green_7)
		m_imageLookup.put (Card.ID_GREEN_7, BitmapFactory.decodeResource(res, R.drawable.card_green_7, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_7, R.string.cardhelp_7)
		m_cardLookup.put (Card.ID_GREEN_7, Card(-1, Card.COLOR_GREEN, 7, Card.ID_GREEN_7, 7))

		m_imageIDLookup.put (Card.ID_GREEN_8, R.drawable.card_green_8)
		m_imageLookup.put (Card.ID_GREEN_8, BitmapFactory.decodeResource(res, R.drawable.card_green_8, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_8, R.string.cardhelp_8)
		m_cardLookup.put (Card.ID_GREEN_8, Card(-1, Card.COLOR_GREEN, 8, Card.ID_GREEN_8, 8))

		m_imageIDLookup.put (Card.ID_GREEN_9, R.drawable.card_green_9)
		m_imageLookup.put (Card.ID_GREEN_9, BitmapFactory.decodeResource(res, R.drawable.card_green_9, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_9, R.string.cardhelp_9)
		m_cardLookup.put (Card.ID_GREEN_9, Card(-1, Card.COLOR_GREEN, 9, Card.ID_GREEN_9, 9))

		m_imageIDLookup.put (Card.ID_GREEN_D, R.drawable.card_green_d)
		m_imageLookup.put (Card.ID_GREEN_D, BitmapFactory.decodeResource(res, R.drawable.card_green_d, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_D, R.string.cardhelp_d)
		m_cardLookup.put (Card.ID_GREEN_D, Card(-1, Card.COLOR_GREEN, Card.VAL_D, Card.ID_GREEN_D, 20))

		m_imageIDLookup.put (Card.ID_GREEN_S, R.drawable.card_green_s)
		m_imageLookup.put (Card.ID_GREEN_S, BitmapFactory.decodeResource(res, R.drawable.card_green_s, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_S, R.string.cardhelp_s)
		m_cardLookup.put (Card.ID_GREEN_S, Card(-1, Card.COLOR_GREEN, Card.VAL_S, Card.ID_GREEN_S, 20))

		m_imageIDLookup.put (Card.ID_GREEN_R, R.drawable.card_green_r)
		m_imageLookup.put (Card.ID_GREEN_R, BitmapFactory.decodeResource(res, R.drawable.card_green_r, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_R, R.string.cardhelp_r)
		m_cardLookup.put (Card.ID_GREEN_R, Card(-1, Card.COLOR_GREEN, Card.VAL_R, Card.ID_GREEN_R, 20))

		m_imageIDLookup.put (Card.ID_BLUE_0, R.drawable.card_blue_0)
		m_imageLookup.put (Card.ID_BLUE_0, BitmapFactory.decodeResource(res, R.drawable.card_blue_0, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_0, R.string.cardhelp_0)
		m_cardLookup.put (Card.ID_BLUE_0, Card(-1, Card.COLOR_BLUE, 0, Card.ID_BLUE_0, 0))

		m_imageIDLookup.put (Card.ID_BLUE_1, R.drawable.card_blue_1)
		m_imageLookup.put (Card.ID_BLUE_1, BitmapFactory.decodeResource(res, R.drawable.card_blue_1, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_1, R.string.cardhelp_1)
		m_cardLookup.put (Card.ID_BLUE_1, Card(-1, Card.COLOR_BLUE, 1, Card.ID_BLUE_1, 1))

		m_imageIDLookup.put (Card.ID_BLUE_2, R.drawable.card_blue_2)
		m_imageLookup.put (Card.ID_BLUE_2, BitmapFactory.decodeResource(res, R.drawable.card_blue_2, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_2, R.string.cardhelp_2)
		m_cardLookup.put (Card.ID_BLUE_2, Card(-1, Card.COLOR_BLUE, 2, Card.ID_BLUE_2, 2))

		m_imageIDLookup.put (Card.ID_BLUE_3, R.drawable.card_blue_3)
		m_imageLookup.put (Card.ID_BLUE_3, BitmapFactory.decodeResource(res, R.drawable.card_blue_3, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_3, R.string.cardhelp_3)
		m_cardLookup.put (Card.ID_BLUE_3, Card(-1, Card.COLOR_BLUE, 3, Card.ID_BLUE_3, 3))

		m_imageIDLookup.put (Card.ID_BLUE_4, R.drawable.card_blue_4)
		m_imageLookup.put (Card.ID_BLUE_4, BitmapFactory.decodeResource(res, R.drawable.card_blue_4, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_4, R.string.cardhelp_4)
		m_cardLookup.put (Card.ID_BLUE_4, Card(-1, Card.COLOR_BLUE, 4, Card.ID_BLUE_4, 4))

		m_imageIDLookup.put (Card.ID_BLUE_5, R.drawable.card_blue_5)
		m_imageLookup.put (Card.ID_BLUE_5, BitmapFactory.decodeResource(res, R.drawable.card_blue_5, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_5, R.string.cardhelp_5)
		m_cardLookup.put (Card.ID_BLUE_5, Card(-1, Card.COLOR_BLUE, 5, Card.ID_BLUE_5, 5))

		m_imageIDLookup.put (Card.ID_BLUE_6, R.drawable.card_blue_6)
		m_imageLookup.put (Card.ID_BLUE_6, BitmapFactory.decodeResource(res, R.drawable.card_blue_6, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_6, R.string.cardhelp_6)
		m_cardLookup.put (Card.ID_BLUE_6, Card(-1, Card.COLOR_BLUE, 6, Card.ID_BLUE_6, 6))

		m_imageIDLookup.put (Card.ID_BLUE_7, R.drawable.card_blue_7)
		m_imageLookup.put (Card.ID_BLUE_7, BitmapFactory.decodeResource(res, R.drawable.card_blue_7, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_7, R.string.cardhelp_7)
		m_cardLookup.put (Card.ID_BLUE_7, Card(-1, Card.COLOR_BLUE, 7, Card.ID_BLUE_7, 7))

		m_imageIDLookup.put (Card.ID_BLUE_8, R.drawable.card_blue_8)
		m_imageLookup.put (Card.ID_BLUE_8, BitmapFactory.decodeResource(res, R.drawable.card_blue_8, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_8, R.string.cardhelp_8)
		m_cardLookup.put (Card.ID_BLUE_8, Card(-1, Card.COLOR_BLUE, 8, Card.ID_BLUE_8, 8))

		m_imageIDLookup.put (Card.ID_BLUE_9, R.drawable.card_blue_9)
		m_imageLookup.put (Card.ID_BLUE_9, BitmapFactory.decodeResource(res, R.drawable.card_blue_9, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_9, R.string.cardhelp_9)
		m_cardLookup.put (Card.ID_BLUE_9, Card(-1, Card.COLOR_BLUE, 9, Card.ID_BLUE_9, 9))

		m_imageIDLookup.put (Card.ID_BLUE_D, R.drawable.card_blue_d)
		m_imageLookup.put (Card.ID_BLUE_D, BitmapFactory.decodeResource(res, R.drawable.card_blue_d, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_D, R.string.cardhelp_d)
		m_cardLookup.put (Card.ID_BLUE_D, Card(-1, Card.COLOR_BLUE, Card.VAL_D, Card.ID_BLUE_D, 20))

		m_imageIDLookup.put (Card.ID_BLUE_S, R.drawable.card_blue_s)
		m_imageLookup.put (Card.ID_BLUE_S, BitmapFactory.decodeResource(res, R.drawable.card_blue_s, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_S, R.string.cardhelp_s)
		m_cardLookup.put (Card.ID_BLUE_S, Card(-1, Card.COLOR_BLUE, Card.VAL_S, Card.ID_BLUE_S, 20))

		m_imageIDLookup.put (Card.ID_BLUE_R, R.drawable.card_blue_r)
		m_imageLookup.put (Card.ID_BLUE_R, BitmapFactory.decodeResource(res, R.drawable.card_blue_r, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_R, R.string.cardhelp_r)
		m_cardLookup.put (Card.ID_BLUE_R, Card(-1, Card.COLOR_BLUE, Card.VAL_R, Card.ID_BLUE_R, 20))

		m_imageIDLookup.put (Card.ID_YELLOW_0, R.drawable.card_yellow_0)
		m_imageLookup.put (Card.ID_YELLOW_0, BitmapFactory.decodeResource(res, R.drawable.card_yellow_0, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_0, R.string.cardhelp_0)
		m_cardLookup.put (Card.ID_YELLOW_0, Card(-1, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0, 0))

		m_imageIDLookup.put (Card.ID_YELLOW_1, R.drawable.card_yellow_1)
		m_imageLookup.put (Card.ID_YELLOW_1, BitmapFactory.decodeResource(res, R.drawable.card_yellow_1, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_1, R.string.cardhelp_1)
		m_cardLookup.put (Card.ID_YELLOW_1, Card(-1, Card.COLOR_YELLOW, 1, Card.ID_YELLOW_1, 1))

		m_imageIDLookup.put (Card.ID_YELLOW_2, R.drawable.card_yellow_2)
		m_imageLookup.put (Card.ID_YELLOW_2, BitmapFactory.decodeResource(res, R.drawable.card_yellow_2, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_2, R.string.cardhelp_2)
		m_cardLookup.put (Card.ID_YELLOW_2, Card(-1, Card.COLOR_YELLOW, 2, Card.ID_YELLOW_2, 2))

		m_imageIDLookup.put (Card.ID_YELLOW_3, R.drawable.card_yellow_3)
		m_imageLookup.put (Card.ID_YELLOW_3, BitmapFactory.decodeResource(res, R.drawable.card_yellow_3, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_3, R.string.cardhelp_3)
		m_cardLookup.put (Card.ID_YELLOW_3, Card(-1, Card.COLOR_YELLOW, 3, Card.ID_YELLOW_3, 3))

		m_imageIDLookup.put (Card.ID_YELLOW_4, R.drawable.card_yellow_4)
		m_imageLookup.put (Card.ID_YELLOW_4, BitmapFactory.decodeResource(res, R.drawable.card_yellow_4, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_4, R.string.cardhelp_4)
		m_cardLookup.put (Card.ID_YELLOW_4, Card(-1, Card.COLOR_YELLOW, 4, Card.ID_YELLOW_4, 4))

		m_imageIDLookup.put (Card.ID_YELLOW_5, R.drawable.card_yellow_5)
		m_imageLookup.put (Card.ID_YELLOW_5, BitmapFactory.decodeResource(res, R.drawable.card_yellow_5, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_5, R.string.cardhelp_5)
		m_cardLookup.put (Card.ID_YELLOW_5, Card(-1, Card.COLOR_YELLOW, 5, Card.ID_YELLOW_5, 5))

		m_imageIDLookup.put (Card.ID_YELLOW_6, R.drawable.card_yellow_6)
		m_imageLookup.put (Card.ID_YELLOW_6, BitmapFactory.decodeResource(res, R.drawable.card_yellow_6, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_6, R.string.cardhelp_6)
		m_cardLookup.put (Card.ID_YELLOW_6, Card(-1, Card.COLOR_YELLOW, 6, Card.ID_YELLOW_6, 6))

		m_imageIDLookup.put (Card.ID_YELLOW_7, R.drawable.card_yellow_7)
		m_imageLookup.put (Card.ID_YELLOW_7, BitmapFactory.decodeResource(res, R.drawable.card_yellow_7, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_7, R.string.cardhelp_7)
		m_cardLookup.put (Card.ID_YELLOW_7, Card(-1, Card.COLOR_YELLOW, 7, Card.ID_YELLOW_7, 7))

		m_imageIDLookup.put (Card.ID_YELLOW_8, R.drawable.card_yellow_8)
		m_imageLookup.put (Card.ID_YELLOW_8, BitmapFactory.decodeResource(res, R.drawable.card_yellow_8, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_8, R.string.cardhelp_8)
		m_cardLookup.put (Card.ID_YELLOW_8, Card(-1, Card.COLOR_YELLOW, 8, Card.ID_YELLOW_8, 8))

		m_imageIDLookup.put (Card.ID_YELLOW_9, R.drawable.card_yellow_9)
		m_imageLookup.put (Card.ID_YELLOW_9, BitmapFactory.decodeResource(res, R.drawable.card_yellow_9, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_9, R.string.cardhelp_9)
		m_cardLookup.put (Card.ID_YELLOW_9, Card(-1, Card.COLOR_YELLOW, 9, Card.ID_YELLOW_9, 9))

		m_imageIDLookup.put (Card.ID_YELLOW_D, R.drawable.card_yellow_d)
		m_imageLookup.put (Card.ID_YELLOW_D, BitmapFactory.decodeResource(res, R.drawable.card_yellow_d, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_D, R.string.cardhelp_d)
		m_cardLookup.put (Card.ID_YELLOW_D, Card(-1, Card.COLOR_YELLOW, Card.VAL_D, Card.ID_YELLOW_D, 20))

		m_imageIDLookup.put (Card.ID_YELLOW_S, R.drawable.card_yellow_s)
		m_imageLookup.put (Card.ID_YELLOW_S, BitmapFactory.decodeResource(res, R.drawable.card_yellow_s, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_S, R.string.cardhelp_s)
		m_cardLookup.put (Card.ID_YELLOW_S, Card(-1, Card.COLOR_YELLOW, Card.VAL_S, Card.ID_YELLOW_S, 20))

		m_imageIDLookup.put (Card.ID_YELLOW_R, R.drawable.card_yellow_r)
		m_imageLookup.put (Card.ID_YELLOW_R, BitmapFactory.decodeResource(res, R.drawable.card_yellow_r, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_R, R.string.cardhelp_r)
		m_cardLookup.put (Card.ID_YELLOW_R, Card(-1, Card.COLOR_YELLOW, Card.VAL_R, Card.ID_YELLOW_R, 20))


		m_imageIDLookup.put (Card.ID_WILD, R.drawable.card_wild)
		m_imageLookup.put (Card.ID_WILD, BitmapFactory.decodeResource(res, R.drawable.card_wild, opt))
		m_cardHelpLookup.put (Card.ID_WILD, R.string.cardhelp_wild)
		m_cardLookup.put (Card.ID_WILD, Card(-1, Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD, 50))

		m_imageIDLookup.put (Card.ID_WILD_DRAWFOUR, R.drawable.card_wild_drawfour)
		m_imageLookup.put (Card.ID_WILD_DRAWFOUR, BitmapFactory.decodeResource(res, R.drawable.card_wild_drawfour, opt))
		m_cardHelpLookup.put (Card.ID_WILD_DRAWFOUR, R.string.cardhelp_wild_drawfour)
		m_cardLookup.put (Card.ID_WILD_DRAWFOUR, Card(-1, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_DRAWFOUR, 50))

		m_imageIDLookup.put (Card.ID_WILD_HOS, R.drawable.card_wild_hos)
		m_imageLookup.put (Card.ID_WILD_HOS, BitmapFactory.decodeResource(res, R.drawable.card_wild_hos, opt))
		m_cardHelpLookup.put (Card.ID_WILD_HOS, R.string.cardhelp_wild_hos)
		m_cardLookup.put (Card.ID_WILD_HOS, Card(-1, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_HOS, 0))

		m_imageIDLookup.put (Card.ID_WILD_HD, R.drawable.card_wild_hd)
		m_imageLookup.put (Card.ID_WILD_HD, BitmapFactory.decodeResource(res, R.drawable.card_wild_hd, opt))
		m_cardHelpLookup.put (Card.ID_WILD_HD, R.string.cardhelp_wild_hd)
		m_cardLookup.put (Card.ID_WILD_HD, Card(-1, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_HD, 100))

		m_imageIDLookup.put (Card.ID_WILD_MYSTERY, R.drawable.card_wild_mystery)
		m_imageLookup.put (Card.ID_WILD_MYSTERY, BitmapFactory.decodeResource(res, R.drawable.card_wild_mystery, opt))
		m_cardHelpLookup.put (Card.ID_WILD_MYSTERY, R.string.cardhelp_wild_mystery)
		m_cardLookup.put (Card.ID_WILD_MYSTERY, Card(-1, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_MYSTERY, 0))

		m_imageIDLookup.put (Card.ID_WILD_DB, R.drawable.card_wild_db)
		m_imageLookup.put (Card.ID_WILD_DB, BitmapFactory.decodeResource(res, R.drawable.card_wild_db, opt))
		m_cardHelpLookup.put (Card.ID_WILD_DB, R.string.cardhelp_wild_db)
		m_cardLookup.put (Card.ID_WILD_DB, Card(-1, Card.COLOR_WILD, Card.VAL_WILD_DRAWFOUR, Card.ID_WILD_DB, 100))

		m_imageIDLookup.put (Card.ID_RED_0_HD, R.drawable.card_red_0_hd)
		m_imageLookup.put (Card.ID_RED_0_HD, BitmapFactory.decodeResource(res, R.drawable.card_red_0_hd, opt))
		if (m_go!!.getFamilyFriendly())
		{
			m_cardHelpLookup.put (Card.ID_RED_0_HD, R.string.cardhelp_red_0_hd_ff)
		}
		else
		{
			m_cardHelpLookup.put (Card.ID_RED_0_HD, R.string.cardhelp_red_0_hd)
		}
		m_cardLookup.put (Card.ID_RED_0_HD, Card(-1, Card.COLOR_RED, 0, Card.ID_RED_0_HD, 0, 0.5))

		m_imageIDLookup.put (Card.ID_RED_2_GLASNOST, R.drawable.card_red_2_glasnost)
		m_imageLookup.put (Card.ID_RED_2_GLASNOST, BitmapFactory.decodeResource(res, R.drawable.card_red_2_glasnost, opt))
		m_cardHelpLookup.put (Card.ID_RED_2_GLASNOST, R.string.cardhelp_red_2_glasnost)
		m_cardLookup.put (Card.ID_RED_2_GLASNOST, Card(-1, Card.COLOR_RED, 2, Card.ID_RED_2_GLASNOST, 75))

		m_imageIDLookup.put (Card.ID_RED_5_MAGIC, R.drawable.card_red_5_magic)
		m_imageLookup.put (Card.ID_RED_5_MAGIC, BitmapFactory.decodeResource(res, R.drawable.card_red_5_magic, opt))
		m_cardHelpLookup.put (Card.ID_RED_5_MAGIC, R.string.cardhelp_red_5_magic)
		m_cardLookup.put (Card.ID_RED_5_MAGIC, Card(-1, Card.COLOR_RED, 5, Card.ID_RED_5_MAGIC, -5))

		m_imageIDLookup.put (Card.ID_RED_D_SPREADER, R.drawable.card_red_d_spreader)
		m_imageLookup.put (Card.ID_RED_D_SPREADER, BitmapFactory.decodeResource(res, R.drawable.card_red_d_spreader, opt))
		m_cardHelpLookup.put (Card.ID_RED_D_SPREADER, R.string.cardhelp_d_spread)
		m_cardLookup.put (Card.ID_RED_D_SPREADER, Card(-1, Card.COLOR_RED, Card.VAL_D_SPREAD, Card.ID_RED_D_SPREADER, 60))

		m_imageIDLookup.put (Card.ID_RED_S_DOUBLE, R.drawable.card_red_s_double)
		m_imageLookup.put (Card.ID_RED_S_DOUBLE, BitmapFactory.decodeResource(res, R.drawable.card_red_s_double, opt))
		m_cardHelpLookup.put (Card.ID_RED_S_DOUBLE, R.string.cardhelp_s_double)
		m_cardLookup.put (Card.ID_RED_S_DOUBLE, Card(-1, Card.COLOR_RED, Card.VAL_S_DOUBLE, Card.ID_RED_S_DOUBLE, 40))

		m_imageIDLookup.put (Card.ID_RED_R_SKIP, R.drawable.card_red_r_skip)
		m_imageLookup.put (Card.ID_RED_R_SKIP, BitmapFactory.decodeResource(res, R.drawable.card_red_r_skip, opt))
		m_cardHelpLookup.put (Card.ID_RED_R_SKIP, R.string.cardhelp_r_skip)
		m_cardLookup.put (Card.ID_RED_R_SKIP, Card(-1, Card.COLOR_RED, Card.VAL_R_SKIP, Card.ID_RED_R_SKIP, 40))

		m_imageIDLookup.put (Card.ID_GREEN_0_QUITTER, R.drawable.card_green_0_quitter)
		m_imageLookup.put (Card.ID_GREEN_0_QUITTER, BitmapFactory.decodeResource(res, R.drawable.card_green_0_quitter, opt))
		if (m_go!!.getFamilyFriendly())
		{
			m_cardHelpLookup.put (Card.ID_GREEN_0_QUITTER, R.string.cardhelp_green_0_quitter_ff)
		}
		else
		{
			m_cardHelpLookup.put (Card.ID_GREEN_0_QUITTER, R.string.cardhelp_green_0_quitter)
		}
		m_cardLookup.put (Card.ID_GREEN_0_QUITTER, Card(-1, Card.COLOR_GREEN, 0, Card.ID_GREEN_0_QUITTER, 100))

		if (m_go!!.getFamilyFriendly())
		{
			m_imageIDLookup.put (Card.ID_GREEN_3_AIDS, R.drawable.card_green_3_aids_ff)
			m_imageLookup.put (Card.ID_GREEN_3_AIDS, BitmapFactory.decodeResource(res, R.drawable.card_green_3_aids_ff, opt))
			m_cardHelpLookup.put (Card.ID_GREEN_3_AIDS, R.string.cardhelp_green_3_aids_ff)
		}
		else
		{
			m_imageIDLookup.put (Card.ID_GREEN_3_AIDS, R.drawable.card_green_3_aids)
			m_imageLookup.put (Card.ID_GREEN_3_AIDS, BitmapFactory.decodeResource(res, R.drawable.card_green_3_aids, opt))
			m_cardHelpLookup.put (Card.ID_GREEN_3_AIDS, R.string.cardhelp_green_3_aids)
		}
		m_cardLookup.put (Card.ID_GREEN_3_AIDS, Card(-1, Card.COLOR_GREEN, 3, Card.ID_GREEN_3_AIDS, 3, 1.0, 10))

		m_imageIDLookup.put (Card.ID_GREEN_4_IRISH, R.drawable.card_green_4_irish)
		m_imageLookup.put (Card.ID_GREEN_4_IRISH, BitmapFactory.decodeResource(res, R.drawable.card_green_4_irish, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_4_IRISH, R.string.cardhelp_green_4_irish)
		m_cardLookup.put (Card.ID_GREEN_4_IRISH, Card(-1, Card.COLOR_GREEN, 4, Card.ID_GREEN_4_IRISH, 75))

		m_imageIDLookup.put (Card.ID_GREEN_D_SPREADER, R.drawable.card_green_d_spreader)
		m_imageLookup.put (Card.ID_GREEN_D_SPREADER, BitmapFactory.decodeResource(res, R.drawable.card_green_d_spreader, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_D_SPREADER, R.string.cardhelp_d_spread)
		m_cardLookup.put (Card.ID_GREEN_D_SPREADER, Card(-1, Card.COLOR_GREEN, Card.VAL_D_SPREAD, Card.ID_GREEN_D_SPREADER, 60))

		m_imageIDLookup.put (Card.ID_GREEN_S_DOUBLE, R.drawable.card_green_s_double)
		m_imageLookup.put (Card.ID_GREEN_S_DOUBLE, BitmapFactory.decodeResource(res, R.drawable.card_green_s_double, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_S_DOUBLE, R.string.cardhelp_s_double)
		m_cardLookup.put (Card.ID_GREEN_S_DOUBLE, Card(-1, Card.COLOR_GREEN, Card.VAL_S_DOUBLE, Card.ID_GREEN_S_DOUBLE, 40))

		m_imageIDLookup.put (Card.ID_GREEN_R_SKIP, R.drawable.card_green_r_skip)
		m_imageLookup.put (Card.ID_GREEN_R_SKIP, BitmapFactory.decodeResource(res, R.drawable.card_green_r_skip, opt))
		m_cardHelpLookup.put (Card.ID_GREEN_R_SKIP, R.string.cardhelp_r_skip)
		m_cardLookup.put (Card.ID_GREEN_R_SKIP, Card(-1, Card.COLOR_GREEN, Card.VAL_R_SKIP, Card.ID_GREEN_R_SKIP, 40))

		if (m_go!!.getFamilyFriendly())
		{
			m_imageIDLookup.put (Card.ID_BLUE_0_FUCKYOU, R.drawable.card_blue_0_fuckyou_ff)
			m_imageLookup.put (Card.ID_BLUE_0_FUCKYOU, BitmapFactory.decodeResource(res, R.drawable.card_blue_0_fuckyou_ff, opt))
			m_cardHelpLookup.put (Card.ID_BLUE_0_FUCKYOU, R.string.cardhelp_blue_0_fuck_you_ff)
		}
		else
		{
			m_imageIDLookup.put (Card.ID_BLUE_0_FUCKYOU, R.drawable.card_blue_0_fuckyou)
			m_imageLookup.put (Card.ID_BLUE_0_FUCKYOU, BitmapFactory.decodeResource(res, R.drawable.card_blue_0_fuckyou, opt))
			m_cardHelpLookup.put (Card.ID_BLUE_0_FUCKYOU, R.string.cardhelp_blue_0_fuck_you)
		}
		m_cardLookup.put (Card.ID_BLUE_0_FUCKYOU, Card(-1, Card.COLOR_BLUE, 0, Card.ID_BLUE_0_FUCKYOU, 0, 2.0))

		m_imageIDLookup.put (Card.ID_BLUE_2_SHIELD, R.drawable.card_blue_2_shield)
		m_imageLookup.put (Card.ID_BLUE_2_SHIELD, BitmapFactory.decodeResource(res, R.drawable.card_blue_2_shield, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_2_SHIELD, R.string.cardhelp_blue_2_shield)
		m_cardLookup.put (Card.ID_BLUE_2_SHIELD, Card(-1, Card.COLOR_BLUE, 2, Card.ID_BLUE_2_SHIELD, 0, 1.0, 0, 1))

		m_imageIDLookup.put (Card.ID_BLUE_D_SPREADER, R.drawable.card_blue_d_spreader)
		m_imageLookup.put (Card.ID_BLUE_D_SPREADER, BitmapFactory.decodeResource(res, R.drawable.card_blue_d_spreader, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_D_SPREADER, R.string.cardhelp_d_spread)
		m_cardLookup.put (Card.ID_BLUE_D_SPREADER, Card(-1, Card.COLOR_BLUE, Card.VAL_D_SPREAD, Card.ID_BLUE_D_SPREADER, 60))

		m_imageIDLookup.put (Card.ID_BLUE_S_DOUBLE, R.drawable.card_blue_s_double)
		m_imageLookup.put (Card.ID_BLUE_S_DOUBLE, BitmapFactory.decodeResource(res, R.drawable.card_blue_s_double, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_S_DOUBLE, R.string.cardhelp_s_double)
		m_cardLookup.put (Card.ID_BLUE_S_DOUBLE, Card(-1, Card.COLOR_BLUE, Card.VAL_S_DOUBLE, Card.ID_BLUE_S_DOUBLE, 40))

		m_imageIDLookup.put (Card.ID_BLUE_R_SKIP, R.drawable.card_blue_r_skip)
		m_imageLookup.put (Card.ID_BLUE_R_SKIP, BitmapFactory.decodeResource(res, R.drawable.card_blue_r_skip, opt))
		m_cardHelpLookup.put (Card.ID_BLUE_R_SKIP, R.string.cardhelp_r_skip)
		m_cardLookup.put (Card.ID_BLUE_R_SKIP, Card(-1, Card.COLOR_BLUE, Card.VAL_R_SKIP, Card.ID_BLUE_R_SKIP, 40))

		if (m_go!!.getFamilyFriendly())
		{
			m_imageIDLookup.put (Card.ID_YELLOW_0_SHITTER, R.drawable.card_yellow_0_shitter_ff)
			m_imageLookup.put (Card.ID_YELLOW_0_SHITTER, BitmapFactory.decodeResource(res, R.drawable.card_yellow_0_shitter_ff, opt))
			m_cardHelpLookup.put (Card.ID_YELLOW_0_SHITTER, R.string.cardhelp_yellow_0_shitter_ff)
		}
		else
		{
			m_imageIDLookup.put (Card.ID_YELLOW_0_SHITTER, R.drawable.card_yellow_0_shitter)
			m_imageLookup.put (Card.ID_YELLOW_0_SHITTER, BitmapFactory.decodeResource(res, R.drawable.card_yellow_0_shitter, opt))
			m_cardHelpLookup.put (Card.ID_YELLOW_0_SHITTER, R.string.cardhelp_yellow_0_shitter)
		}
		m_cardLookup.put (Card.ID_YELLOW_0_SHITTER, Card(-1, Card.COLOR_YELLOW, 0, Card.ID_YELLOW_0_SHITTER, 0))

		m_imageIDLookup.put (Card.ID_YELLOW_1_MAD, R.drawable.card_yellow_1_mad)
		m_imageLookup.put (Card.ID_YELLOW_1_MAD, BitmapFactory.decodeResource(res, R.drawable.card_yellow_1_mad, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_1_MAD, R.string.cardhelp_yellow_1_mad)
		m_cardLookup.put (Card.ID_YELLOW_1_MAD, Card(-1, Card.COLOR_YELLOW, 1, Card.ID_YELLOW_1_MAD, 100))

		m_imageIDLookup.put (Card.ID_YELLOW_69, R.drawable.card_yellow_69)
		m_imageLookup.put (Card.ID_YELLOW_69, BitmapFactory.decodeResource(res, R.drawable.card_yellow_69, opt))
		if (m_go!!.getFamilyFriendly())
		{
			m_cardHelpLookup.put (Card.ID_YELLOW_69, R.string.cardhelp_yellow_69_ff)
		}
		else
		{
			m_cardHelpLookup.put (Card.ID_YELLOW_69, R.string.cardhelp_yellow_69)
		}
		m_cardLookup.put (Card.ID_YELLOW_69, Card(-1, Card.COLOR_YELLOW, 6, Card.ID_YELLOW_69, 6))

		m_imageIDLookup.put (Card.ID_YELLOW_D_SPREADER, R.drawable.card_yellow_d_spreader)
		m_imageLookup.put (Card.ID_YELLOW_D_SPREADER, BitmapFactory.decodeResource(res, R.drawable.card_yellow_d_spreader, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_D_SPREADER, R.string.cardhelp_d_spread)
		m_cardLookup.put (Card.ID_YELLOW_D_SPREADER, Card(-1, Card.COLOR_YELLOW, Card.VAL_D_SPREAD, Card.ID_YELLOW_D_SPREADER, 60))

		m_imageIDLookup.put (Card.ID_YELLOW_S_DOUBLE, R.drawable.card_yellow_s_double)
		m_imageLookup.put (Card.ID_YELLOW_S_DOUBLE, BitmapFactory.decodeResource(res, R.drawable.card_yellow_s_double, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_S_DOUBLE, R.string.cardhelp_s_double)
		m_cardLookup.put (Card.ID_YELLOW_S_DOUBLE, Card(-1, Card.COLOR_YELLOW, Card.VAL_S_DOUBLE, Card.ID_YELLOW_S_DOUBLE, 40))

		m_imageIDLookup.put (Card.ID_YELLOW_R_SKIP, R.drawable.card_yellow_r_skip)
		m_imageLookup.put (Card.ID_YELLOW_R_SKIP, BitmapFactory.decodeResource(res, R.drawable.card_yellow_r_skip, opt))
		m_cardHelpLookup.put (Card.ID_YELLOW_R_SKIP, R.string.cardhelp_r_skip)
		m_cardLookup.put (Card.ID_YELLOW_R_SKIP, Card(-1, Card.COLOR_YELLOW, Card.VAL_R_SKIP, Card.ID_YELLOW_R_SKIP, 40))

		m_bmpDirColorCCW = BitmapFactory.decodeResource(res, R.drawable.ccw, opt)
		m_bmpDirColorCCWRed = BitmapFactory.decodeResource(res, R.drawable.ccw_red, opt)
		m_bmpDirColorCCWBlue = BitmapFactory.decodeResource(res, R.drawable.ccw_blue, opt)
		m_bmpDirColorCCWGreen = BitmapFactory.decodeResource(res, R.drawable.ccw_green, opt)
		m_bmpDirColorCCWYellow = BitmapFactory.decodeResource(res, R.drawable.ccw_yellow, opt)

		m_bmpDirColorCW = BitmapFactory.decodeResource(res, R.drawable.cw, opt)
		m_bmpDirColorCWRed = BitmapFactory.decodeResource(res, R.drawable.cw_red, opt)
		m_bmpDirColorCWBlue = BitmapFactory.decodeResource(res, R.drawable.cw_blue, opt)
		m_bmpDirColorCWGreen = BitmapFactory.decodeResource(res, R.drawable.cw_green, opt)
		m_bmpDirColorCWYellow = BitmapFactory.decodeResource(res, R.drawable.cw_yellow, opt)

		m_bmpPlayerIndicator[Card.COLOR_RED - 1][Game.SEAT_SOUTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_red_south, opt)
		m_bmpPlayerIndicator[Card.COLOR_GREEN - 1][Game.SEAT_SOUTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_green_south, opt)
		m_bmpPlayerIndicator[Card.COLOR_BLUE - 1][Game.SEAT_SOUTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_blue_south, opt)
		m_bmpPlayerIndicator[Card.COLOR_YELLOW - 1][Game.SEAT_SOUTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_yellow_south, opt)
		m_bmpPlayerIndicator[Card.COLOR_WILD - 1][Game.SEAT_SOUTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_south, opt)

		m_bmpPlayerIndicator[Card.COLOR_RED - 1][Game.SEAT_WEST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_red_west, opt)
		m_bmpPlayerIndicator[Card.COLOR_GREEN - 1][Game.SEAT_WEST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_green_west, opt)
		m_bmpPlayerIndicator[Card.COLOR_BLUE - 1][Game.SEAT_WEST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_blue_west, opt)
		m_bmpPlayerIndicator[Card.COLOR_YELLOW - 1][Game.SEAT_WEST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_yellow_west, opt)
		m_bmpPlayerIndicator[Card.COLOR_WILD - 1][Game.SEAT_WEST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_west, opt)

		m_bmpPlayerIndicator[Card.COLOR_RED - 1][Game.SEAT_NORTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_red_north, opt)
		m_bmpPlayerIndicator[Card.COLOR_GREEN - 1][Game.SEAT_NORTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_green_north, opt)
		m_bmpPlayerIndicator[Card.COLOR_BLUE - 1][Game.SEAT_NORTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_blue_north, opt)
		m_bmpPlayerIndicator[Card.COLOR_YELLOW - 1][Game.SEAT_NORTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_yellow_north, opt)
		m_bmpPlayerIndicator[Card.COLOR_WILD - 1][Game.SEAT_NORTH - 1] = BitmapFactory.decodeResource(res, R.drawable.player_north, opt)

		m_bmpPlayerIndicator[Card.COLOR_RED - 1][Game.SEAT_EAST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_red_east, opt)
		m_bmpPlayerIndicator[Card.COLOR_GREEN - 1][Game.SEAT_EAST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_green_east, opt)
		m_bmpPlayerIndicator[Card.COLOR_BLUE - 1][Game.SEAT_EAST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_blue_east, opt)
		m_bmpPlayerIndicator[Card.COLOR_YELLOW - 1][Game.SEAT_EAST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_yellow_east, opt)
		m_bmpPlayerIndicator[Card.COLOR_WILD - 1][Game.SEAT_EAST - 1] = BitmapFactory.decodeResource(res, R.drawable.player_east, opt)

		m_bmpWinningMessage[Game.SEAT_SOUTH - 1] = BitmapFactory.decodeResource(res, R.drawable.winner_south, opt)
		m_bmpWinningMessage[Game.SEAT_WEST - 1] = BitmapFactory.decodeResource(res, R.drawable.winner_west, opt)
		m_bmpWinningMessage[Game.SEAT_NORTH - 1] = BitmapFactory.decodeResource(res, R.drawable.winner_north, opt)
		m_bmpWinningMessage[Game.SEAT_EAST - 1] = BitmapFactory.decodeResource(res, R.drawable.winner_east, opt)


		m_bmpCardBadge = BitmapFactory.decodeResource(res, R.drawable.card_badge, opt)

		m_bmpEmoticonAggressor = BitmapFactory.decodeResource(res, R.drawable.emoticon_aggressor, opt)
		m_bmpEmoticonVictim = BitmapFactory.decodeResource(res, R.drawable.emoticon_victim, opt)

		var i = 0

		m_cardIDs[i++] = Card.ID_RED_0
		m_cardIDs[i++] = Card.ID_RED_0_HD
		m_cardIDs[i++] = Card.ID_RED_1
		m_cardIDs[i++] = Card.ID_RED_2
		m_cardIDs[i++] = Card.ID_RED_2_GLASNOST
		m_cardIDs[i++] = Card.ID_RED_3
		m_cardIDs[i++] = Card.ID_RED_4
		m_cardIDs[i++] = Card.ID_RED_5
		m_cardIDs[i++] = Card.ID_RED_5_MAGIC
		m_cardIDs[i++] = Card.ID_RED_6
		m_cardIDs[i++] = Card.ID_RED_7
		m_cardIDs[i++] = Card.ID_RED_8
		m_cardIDs[i++] = Card.ID_RED_9
		m_cardIDs[i++] = Card.ID_RED_D
		m_cardIDs[i++] = Card.ID_RED_D_SPREADER
		m_cardIDs[i++] = Card.ID_RED_S
		m_cardIDs[i++] = Card.ID_RED_S_DOUBLE
		m_cardIDs[i++] = Card.ID_RED_R
		m_cardIDs[i++] = Card.ID_RED_R_SKIP

		m_cardIDs[i++] = Card.ID_GREEN_0
		m_cardIDs[i++] = Card.ID_GREEN_0_QUITTER
		m_cardIDs[i++] = Card.ID_GREEN_1
		m_cardIDs[i++] = Card.ID_GREEN_2
		m_cardIDs[i++] = Card.ID_GREEN_3
		m_cardIDs[i++] = Card.ID_GREEN_3_AIDS
		m_cardIDs[i++] = Card.ID_GREEN_4
		m_cardIDs[i++] = Card.ID_GREEN_4_IRISH
		m_cardIDs[i++] = Card.ID_GREEN_5
		m_cardIDs[i++] = Card.ID_GREEN_6
		m_cardIDs[i++] = Card.ID_GREEN_7
		m_cardIDs[i++] = Card.ID_GREEN_8
		m_cardIDs[i++] = Card.ID_GREEN_9
		m_cardIDs[i++] = Card.ID_GREEN_D
		m_cardIDs[i++] = Card.ID_GREEN_D_SPREADER
		m_cardIDs[i++] = Card.ID_GREEN_S
		m_cardIDs[i++] = Card.ID_GREEN_S_DOUBLE
		m_cardIDs[i++] = Card.ID_GREEN_R
		m_cardIDs[i++] = Card.ID_GREEN_R_SKIP

		m_cardIDs[i++] = Card.ID_BLUE_0
		m_cardIDs[i++] = Card.ID_BLUE_0_FUCKYOU
		m_cardIDs[i++] = Card.ID_BLUE_1
		m_cardIDs[i++] = Card.ID_BLUE_2
		m_cardIDs[i++] = Card.ID_BLUE_2_SHIELD
		m_cardIDs[i++] = Card.ID_BLUE_3
		m_cardIDs[i++] = Card.ID_BLUE_4
		m_cardIDs[i++] = Card.ID_BLUE_5
		m_cardIDs[i++] = Card.ID_BLUE_6
		m_cardIDs[i++] = Card.ID_BLUE_7
		m_cardIDs[i++] = Card.ID_BLUE_8
		m_cardIDs[i++] = Card.ID_BLUE_9
		m_cardIDs[i++] = Card.ID_BLUE_D
		m_cardIDs[i++] = Card.ID_BLUE_D_SPREADER
		m_cardIDs[i++] = Card.ID_BLUE_S
		m_cardIDs[i++] = Card.ID_BLUE_S_DOUBLE
		m_cardIDs[i++] = Card.ID_BLUE_R
		m_cardIDs[i++] = Card.ID_BLUE_R_SKIP

		m_cardIDs[i++] = Card.ID_YELLOW_0
		m_cardIDs[i++] = Card.ID_YELLOW_0_SHITTER
		m_cardIDs[i++] = Card.ID_YELLOW_1
		m_cardIDs[i++] = Card.ID_YELLOW_1_MAD
		m_cardIDs[i++] = Card.ID_YELLOW_2
		m_cardIDs[i++] = Card.ID_YELLOW_3
		m_cardIDs[i++] = Card.ID_YELLOW_4
		m_cardIDs[i++] = Card.ID_YELLOW_5
		m_cardIDs[i++] = Card.ID_YELLOW_6
		m_cardIDs[i++] = Card.ID_YELLOW_69
		m_cardIDs[i++] = Card.ID_YELLOW_7
		m_cardIDs[i++] = Card.ID_YELLOW_8
		m_cardIDs[i++] = Card.ID_YELLOW_9
		m_cardIDs[i++] = Card.ID_YELLOW_D
		m_cardIDs[i++] = Card.ID_YELLOW_D_SPREADER
		m_cardIDs[i++] = Card.ID_YELLOW_S
		m_cardIDs[i++] = Card.ID_YELLOW_S_DOUBLE
		m_cardIDs[i++] = Card.ID_YELLOW_R
		m_cardIDs[i++] = Card.ID_YELLOW_R_SKIP

		m_cardIDs[i++] = Card.ID_WILD
		m_cardIDs[i++] = Card.ID_WILD_DRAWFOUR
		m_cardIDs[i++] = Card.ID_WILD_HOS
		m_cardIDs[i++] = Card.ID_WILD_HD
		m_cardIDs[i++] = Card.ID_WILD_MYSTERY
		m_cardIDs[i++] = Card.ID_WILD_DB
	}


	private fun drawCard (cv: Canvas, c: Card, x: Int, y: Int, faceup: Boolean)
	{
		m_drawMatrix.reset()
		m_drawMatrix.setScale(1f, 1f)
		m_drawMatrix.setTranslate(x.toFloat(), y.toFloat())

		val b: Bitmap?
		if (faceup || m_go!!.getFaceUp())
		{
			b = m_imageLookup[c.getID()]
		}
		else
		{
			b = m_bmpCardBack
			/*
			 * show some cards upside down -- this doesn't look as good as I thought it would
			 */
			/*
			Random rgen = new Random();
			int orientation = rgen.nextInt(100);
			if (orientation < 25)
			{
				m_drawMatrix.postRotate(180, x + b.getWidth() / 2, y + b.getHeight() / 2);
			}
			*/
		}


		// `!!` for the same reason as getCardBitmap's callers see a null: the Java
		// passed the missing Bitmap straight into drawBitmap and died there.
		cv.drawBitmap(b!!, m_drawMatrix, null)
	}


	private fun drawPenalty(cv: Canvas)
	{
		// draw penalty!
		val p = m_game!!.getPenalty()!!

		if (p.getType() == Penalty.PENTYPE_NONE)
		{
			return
		}

		if (p.getType() == Penalty.PENTYPE_CARD)
		{
			//val nc = p.GetNumCards()
		}

		var pt: Point

		val pv = p.getVictim()
		if (pv != null)
		{
			pt = m_ptEmoticon[pv.getSeat() - 1]!!

			m_drawMatrix.reset()
			m_drawMatrix.setScale(1f, 1f)
			m_drawMatrix.setTranslate(pt.x.toFloat(), pt.y.toFloat())

			cv.drawBitmap(m_bmpEmoticonVictim, m_drawMatrix, null)
		}

		val pa = p.getGeneratingPlayer()
		if (pa != null)
		{
			pt = m_ptEmoticon[pa.getSeat() - 1]!!

			m_drawMatrix.reset()
			m_drawMatrix.setScale(1f, 1f)
			m_drawMatrix.setTranslate(pt.x.toFloat(), pt.y.toFloat())

			cv.drawBitmap(m_bmpEmoticonAggressor, m_drawMatrix, null)
		}

	}


	fun ShowCardHelp (c: Card)
	{
		m_helpCardID = c.getID()

		val a = context as GameActivity
		//a.showDialog(GameActivity.DIALOG_CARD_HELP);
		a.showCardHelp()
	}

	fun Toast (msg: String)
	{
		// not sure exactly how long it takes to fade out a Toast, but we're going to
		// show the toast for a duration that's a little lower than the game delay
		// to accommodate some fade out time.
		if (m_toast == null)
		{
			m_toast = android.widget.Toast.makeText(context, msg, m_game!!.getDelay() - 500)
			m_toast!!.setGravity(Gravity.TOP or Gravity.CENTER, 0, m_ptMessages!!.y)
		}
		else
		{
			m_toast!!.setText(msg)
		}

		m_toast!!.show()
	}

	fun displayScore (canvas: Canvas)
	{
		for (i in 0 until 4)
		{
			if ((i == Game.SEAT_SOUTH - 1) || (i == Game.SEAT_NORTH - 1))
			{
				m_paintScoreText.setTextAlign(Paint.Align.CENTER)
			}
			else if (i == Game.SEAT_WEST - 1)
			{
				m_paintScoreText.setTextAlign(Paint.Align.LEFT)
			}
			else if (i == Game.SEAT_EAST - 1)
			{
				m_paintScoreText.setTextAlign(Paint.Align.RIGHT)
			}

			val msg: String
			if (!m_game!!.getRoundComplete())
			{
				msg = "" + m_game!!.getPlayer(i)!!.getTotalScore()
			}
			else
			{
				val p = m_game!!.getPlayer(i)!!

				val lastScore = p.getLastScore()
				val virusPenalty = p.getLastVirusPenalty()
				val totalScore = p.getTotalScore()

				if (lastScore < 0)
				{
					msg = String.format (m_game!!.getString(R.string.msg_round_score_negative), totalScore - lastScore - virusPenalty, 0 - lastScore, virusPenalty, totalScore)
				}
				else
				{
					msg = String.format (m_game!!.getString(R.string.msg_round_score_positive), totalScore - lastScore - virusPenalty, lastScore, virusPenalty, totalScore)
				}
			}
			canvas.drawText(msg,
				m_ptScoreText[i]!!.x.toFloat(), m_ptScoreText[i]!!.y.toFloat(),
				m_paintScoreText)
		}
	}

	fun PromptForVictim (): Int
	{
		var count = 0
		if (m_game!!.getPlayer(Game.SEAT_WEST - 1)!!.getActive())
		{
			count++
		}
		if (m_game!!.getPlayer(Game.SEAT_NORTH - 1)!!.getActive())
		{
			count++
		}
		if (m_game!!.getPlayer(Game.SEAT_EAST - 1)!!.getActive())
		{
			count++
		}

		val items = arrayOfNulls<CharSequence>(count)
		count = 0
		if (m_game!!.getPlayer(Game.SEAT_WEST - 1)!!.getActive())
		{
			items[count] = m_game!!.getString(R.string.seat_west)
			count++
		}
		if (m_game!!.getPlayer(Game.SEAT_NORTH - 1)!!.getActive())
		{
			items[count] = m_game!!.getString(R.string.seat_north)
			count++
		}
		if (m_game!!.getPlayer(Game.SEAT_EAST - 1)!!.getActive())
		{
			items[count] = m_game!!.getString(R.string.seat_east)
			count++
		}

		AlertDialog.Builder(context)
		.setCancelable(false)
		.setTitle(R.string.prompt_victim)
		.setItems(items,
			DialogInterface.OnClickListener
			{ _, i ->
				// The Java walked the list by decrementing its own index, so the
				// first test has to be against a mutable copy -- a lambda
				// parameter is a val.
				var idx = i
				val p = m_game!!.getCurrPlayer()
				if (p is HumanPlayer)
				{
					if (m_game!!.getPlayer(Game.SEAT_WEST - 1)!!.getActive())
					{
						if (idx == 0)
						{
							(p as HumanPlayer).setVictim(Game.SEAT_WEST)
							return@OnClickListener
						}
						idx--
					}
					if (m_game!!.getPlayer(Game.SEAT_NORTH - 1)!!.getActive())
					{
						if (idx == 0)
						{
							(p as HumanPlayer).setVictim(Game.SEAT_NORTH)
							return@OnClickListener
						}
						idx--
					}
					if (m_game!!.getPlayer(Game.SEAT_EAST - 1)!!.getActive())
					{
						if (idx == 0)
						{
							(p as HumanPlayer).setVictim(Game.SEAT_EAST)
							return@OnClickListener
						}
						idx--
					}
				}
			})
			.show();

		return 0
	}

	fun PromptForNumCardsToDeal (): Int
	{
		AlertDialog.Builder(context)
			.setCancelable(false)
			.setTitle(R.string.prompt_deal)
			.setItems(R.array.deal_values,
				DialogInterface.OnClickListener
				{ _, i ->
					val p = m_game!!.getDealer()
					if (p is HumanPlayer)
					{
						(p as HumanPlayer).setNumCardsToDeal(i + 5)
					}
				})
				.show();
		return 0;
	}

	fun PromptForColor (): Int
	{
		AlertDialog.Builder(context)
			.setCancelable(false)
			.setTitle(R.string.prompt_color)
			.setItems(R.array.colors,
				DialogInterface.OnClickListener
				{ _, i ->
					val p = m_game!!.getCurrPlayer()
					if (p is HumanPlayer)
					{
						(p as HumanPlayer).setColor(i + 1)
					}
				})
				.show();
		return 0
	}

	companion object
	{
		private const val ID = 42
	}
}
