package com.smccloud.hotdeath

import android.content.Context
import org.json.*

class Card(
	private var m_deckIndex: Int,
	private var m_color: Int,
	private var m_value: Int,
	private var m_id: Int,
	private var m_pointValue: Int,
	private var m_pointMultiplier: Double,
	private var m_cumulativePenalty: Int,
	private var m_highestCardMatch: Int)
{
	companion object
	{
		const val COLOR_RED = 1
		const val COLOR_GREEN = 2
		const val COLOR_BLUE = 3
		const val COLOR_YELLOW = 4
		const val COLOR_WILD = 5

		const val VAL_D = 11
		const val VAL_S = 12
		const val VAL_R = 13
		const val VAL_D_SPREAD = 14
		const val VAL_S_DOUBLE = 15
		const val VAL_R_SKIP = 16
		const val VAL_WILD = 17
		const val VAL_WILD_DRAWFOUR = 18

		const val ID_RED_0 = 100
		const val ID_RED_1 = 101
		const val ID_RED_2 = 102
		const val ID_RED_3 = 103
		const val ID_RED_4 = 104
		const val ID_RED_5 = 105
		const val ID_RED_6 = 106
		const val ID_RED_7 = 107
		const val ID_RED_8 = 108
		const val ID_RED_9 = 109
		const val ID_RED_D = 110
		const val ID_RED_S = 111
		const val ID_RED_R = 112
		const val ID_GREEN_0 = 113
		const val ID_GREEN_1 = 114
		const val ID_GREEN_2 = 115
		const val ID_GREEN_3 = 116
		const val ID_GREEN_4 = 117
		const val ID_GREEN_5 = 118
		const val ID_GREEN_6 = 119
		const val ID_GREEN_7 = 120
		const val ID_GREEN_8 = 121
		const val ID_GREEN_9 = 122
		const val ID_GREEN_D = 123
		const val ID_GREEN_S = 124
		const val ID_GREEN_R = 125
		const val ID_BLUE_0 = 126
		const val ID_BLUE_1 = 127
		const val ID_BLUE_2 = 128
		const val ID_BLUE_3 = 129
		const val ID_BLUE_4 = 130
		const val ID_BLUE_5 = 131
		const val ID_BLUE_6 = 132
		const val ID_BLUE_7 = 133
		const val ID_BLUE_8 = 134
		const val ID_BLUE_9 = 135
		const val ID_BLUE_D = 136
		const val ID_BLUE_S = 137
		const val ID_BLUE_R = 138
		const val ID_YELLOW_0 = 139
		const val ID_YELLOW_1 = 140
		const val ID_YELLOW_2 = 141
		const val ID_YELLOW_3 = 142
		const val ID_YELLOW_4 = 143
		const val ID_YELLOW_5 = 144
		const val ID_YELLOW_6 = 145
		const val ID_YELLOW_7 = 146
		const val ID_YELLOW_8 = 147
		const val ID_YELLOW_9 = 148
		const val ID_YELLOW_D = 149
		const val ID_YELLOW_S = 150
		const val ID_YELLOW_R = 151
		const val ID_WILD = 152
		const val ID_WILD_DRAWFOUR = 153
		const val ID_WILD_HOS = 154
		const val ID_WILD_HD = 155
		const val ID_WILD_MYSTERY = 156
		const val ID_WILD_DB = 157
		const val ID_RED_0_HD = 158
		const val ID_RED_2_GLASNOST = 159
		const val ID_RED_5_MAGIC = 160
		const val ID_RED_D_SPREADER = 161
		const val ID_RED_S_DOUBLE = 162
		const val ID_RED_R_SKIP = 163
		const val ID_GREEN_0_QUITTER = 164
		const val ID_GREEN_3_AIDS = 165
		const val ID_GREEN_4_IRISH = 166
		const val ID_GREEN_D_SPREADER = 167
		const val ID_GREEN_S_DOUBLE = 168
		const val ID_GREEN_R_SKIP = 169
		const val ID_BLUE_0_FUCKYOU = 170
		const val ID_BLUE_2_SHIELD = 171
		const val ID_BLUE_D_SPREADER = 172
		const val ID_BLUE_S_DOUBLE = 173
		const val ID_BLUE_R_SKIP = 174
		const val ID_YELLOW_0_SHITTER = 175
		const val ID_YELLOW_1_MAD = 176
		const val ID_YELLOW_69 = 177
		const val ID_YELLOW_D_SPREADER = 178
		const val ID_YELLOW_S_DOUBLE = 179
		const val ID_YELLOW_R_SKIP = 180
	}

	private var m_hand: Hand? = null
	private var m_currentValue = 0
	private var m_faceup = false

	constructor(deckIndex: Int, color: Int, value: Int, id: Int, pointValue: Int) :
		this(deckIndex, color, value, id, pointValue, 1.0, 0, 0)

	constructor(deckIndex: Int, color: Int, value: Int, id: Int, pointValue: Int, pointMultiplier: Double) :
		this(deckIndex, color, value, id, pointValue, pointMultiplier, 0, 0)

	constructor(deckIndex: Int, color: Int, value: Int, id: Int, pointValue: Int, pointMultiplier: Double, cumulativePenalty: Int) :
		this(deckIndex, color, value, id, pointValue, pointMultiplier, cumulativePenalty, 0)

	constructor(o: JSONObject) :
		this(
			o.getInt("deckIndex"),
			o.getInt("color"),
			o.getInt("value"),
			o.getInt("id"),
			o.getInt("pointValue"),
			// getDouble, not getInt: the field is a double, and toJSON writes it as
			// one. getInt truncates toward zero, so the 0.5 Holy Defender resumed as
			// 0.0.
			o.getDouble("pointMultiplier"),
			o.getInt("cumulativePenalty"),
			o.getInt("highestCardMatch"))
	{
		m_currentValue = o.getInt("currentValue")
		m_faceup = o.getBoolean("faceup")
	}

	fun getHand (): Hand?
	{
		return m_hand
	}

	fun setHand (h: Hand?)
	{
		m_hand = h
	}

	fun getDeckIndex (): Int
	{
		return m_deckIndex
	}

	fun getColor (): Int
	{
		return m_color
	}

	fun getValue (): Int
	{
		return m_value
	}

	fun getID (): Int
	{
		return m_id
	}

	fun getPointValue(): Int
	{
		return m_pointValue
	}

	fun getPointMultiplier(): Double
	{
		return m_pointMultiplier
	}

	fun getCumulativePenalty(): Int
	{
		return m_cumulativePenalty
	}

	fun getHighestCardMatch(): Int
	{
		return m_highestCardMatch
	}

	fun setCurrentValue(cv: Int)
	{
		m_currentValue = cv
	}

	fun getCurrentValue(): Int
	{
		return m_currentValue
	}

	fun getFaceUp (): Boolean
	{
		return m_faceup
	}

	fun setFaceUp (f: Boolean)
	{
		m_faceup = f
	}

	fun toString (ctx: Context): String
	{
		return toString (ctx, true)
	}

	fun toString(ctx: Context, familyFriendly: Boolean): String
	{
		var strColor = ""
		var strValue = ""

		when (m_color)
		{
			COLOR_RED -> strColor = ctx.getString (R.string.cardcolor_red)
			COLOR_GREEN -> strColor = ctx.getString (R.string.cardcolor_green)
			COLOR_BLUE -> strColor = ctx.getString (R.string.cardcolor_blue)
			COLOR_YELLOW -> strColor = ctx.getString (R.string.cardcolor_yellow)
		}

		when (m_id)
		{
			ID_WILD -> strValue = ctx.getString (R.string.cardval_wild)
			ID_WILD_DRAWFOUR -> strValue = ctx.getString (R.string.cardval_wild_drawfour)
			ID_WILD_HD -> strValue = ctx.getString (R.string.cardname_wild_hd)
			ID_WILD_DB -> strValue = ctx.getString (R.string.cardname_wild_db)
			ID_WILD_HOS -> strValue = ctx.getString (R.string.cardname_wild_hos)
			ID_WILD_MYSTERY -> strValue = ctx.getString (R.string.cardname_wild_mystery)
			ID_RED_0_HD -> strValue = ctx.getString (R.string.cardname_red_0_hd)
			ID_RED_2_GLASNOST -> strValue = ctx.getString (R.string.cardname_red_2_glasnost)
			ID_RED_5_MAGIC -> strValue = ctx.getString (R.string.cardname_red_5_magic)
			ID_GREEN_0_QUITTER -> strValue = ctx.getString (R.string.cardname_green_0_quitter)
			ID_GREEN_3_AIDS ->
				if (familyFriendly)
				{
					strValue = ctx.getString (R.string.cardname_green_3_aids_ff)
				}
				else
				{
					strValue = ctx.getString (R.string.cardname_green_3_aids)
				}
			ID_GREEN_4_IRISH -> strValue = ctx.getString (R.string.cardname_green_4_irish)
			ID_BLUE_0_FUCKYOU ->
				if (familyFriendly)
				{
					strValue = ctx.getString (R.string.cardname_blue_0_fuck_you_ff)
				}
				else
				{
					strValue = ctx.getString (R.string.cardname_blue_0_fuck_you)
				}
			ID_BLUE_2_SHIELD -> strValue = ctx.getString (R.string.cardname_blue_2_shield)
			ID_YELLOW_0_SHITTER ->
				if (familyFriendly)
				{
					strValue = ctx.getString (R.string.cardname_yellow_0_shitter_ff)
				}
				else
				{
					strValue = ctx.getString (R.string.cardname_yellow_0_shitter)
				}
			ID_YELLOW_1_MAD -> strValue = ctx.getString (R.string.cardname_yellow_1_mad)
			ID_YELLOW_69 -> strValue = ctx.getString (R.string.cardname_yellow_69)
		}

		// The empty string the id switch falls through with is how a card with no
		// name of its own reaches the value switch below.
		if (strValue != "")
		{
			return strValue
		}

		when (m_value)
		{
			VAL_D -> strValue = ctx.getString (R.string.cardval_d)
			VAL_S -> strValue = ctx.getString (R.string.cardval_s)
			VAL_R -> strValue = ctx.getString (R.string.cardval_r)
			VAL_D_SPREAD -> strValue = ctx.getString (R.string.cardval_d_spread)
			VAL_S_DOUBLE -> strValue = ctx.getString (R.string.cardval_s_double)
			VAL_R_SKIP -> strValue = ctx.getString (R.string.cardval_r_skip)
			else -> strValue = "" + m_value
		}

		// The separator is added here rather than carried by the trailing space on
		// the cardcolor_* strings, because that space does not survive the build.
		// The compiled resources.arsc holds "Green" and never "Green ", so
		// `strColor + strValue` was producing "Green5" in the shipped app -- in the
		// card help dialog's title, in the log line for every play, and in the
		// "North draws Green5" the draw message renders. The comment here used to
		// say the trailing space was the separator, and it read as though the space
		// were deliberate; it was never there at runtime, so nothing doubled up
		// either and the labels have been unspaced this whole time.
		//
		// `trim()` rather than removing the space from the four strings, because
		// the resources are also read as labels elsewhere and a change there would
		// not be picked up by anything.
		//
		// An unknown colour leaves strColor empty, and a leading space in front of
		// the value would be worse than none.
		if (strColor.isEmpty())
		{
			return strValue
		}

		return strColor.trim() + " " + strValue
	}

	fun toJSON (): JSONObject
	{
		val o = JSONObject ()

		o.put("deckIndex", m_deckIndex)
		o.put("color", m_color)
		o.put("value", m_value)
		o.put("currentValue", m_currentValue)
		o.put("pointValue", m_pointValue)
		o.put("pointMultiplier", m_pointMultiplier)
		o.put("cumulativePenalty", m_cumulativePenalty)
		o.put("highestCardMatch", m_highestCardMatch)
		o.put("id", m_id)
		o.put("faceup", m_faceup)

		return o
	}
}