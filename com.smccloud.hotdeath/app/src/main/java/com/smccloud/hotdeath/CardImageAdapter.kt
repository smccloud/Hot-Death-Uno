package com.smccloud.hotdeath

import java.util.HashMap

import android.widget.BaseAdapter
import android.content.Context
import android.view.ViewGroup
import android.view.View
import android.widget.ImageView
import android.widget.GridView
import android.widget.AbsListView

// getView's two ViewGroup/View parameters are platform types on the Android
// side, so Kotlin lets the nullability be declared here rather than inherited.
//
// convertView is View? and not View, because the recycling check below is the
// whole point of the method: on the first pass it is genuinely null, and
// declaring it non-null would be a lie the compiler is entitled to act on.
class CardImageAdapter(c: Context) : BaseAdapter()
{
	private val mContext: Context = c
	private val m_cardIDs: Array<Int?>
	private val m_thumbIDs: Array<Int?>

	fun getCardIDs(): Array<Int?>
	{
		return m_cardIDs
	}

	init
	{
		val ga = c as GameActivity

		// query the current card deck to see what cards are actually in use
		val g = ga.getGame()!!
		val d = g.getDeck()!!
		val cary = d.getCards()

		val usedIDs = HashMap<Int, Boolean>()
		for (i in cary.indices)
		{
			if (usedIDs.containsKey(cary[i]!!.getID()))
			{
				continue
			}

			usedIDs.put (cary[i]!!.getID(), true)
		}

		// go through all cards in order and add them to the array
		val cardids = ga.getCardIDs()

		var idx = 0
		m_thumbIDs = arrayOfNulls(usedIDs.size)
		m_cardIDs = arrayOfNulls(usedIDs.size)
		for (i in cardids.indices)
		{
			if (usedIDs.containsKey(cardids[i]!!))
			{
				m_cardIDs[idx] = cardids[i]
				m_thumbIDs[idx] = ga.getCardImageID(cardids[i]!!)
				idx++
			}
		}
	}

	override fun getCount(): Int
	{
		return m_thumbIDs.size
	}

	override fun getItem(position: Int): Any?
	{
		return null
	}

	override fun getItemId(position: Int): Long
	{
		return 0
	}

	// create a new ImageView for each item referenced by the Adapter
	override fun getView(position: Int, convertView: View?, parent: ViewGroup): View
	{

		val imageView: ImageView
		if (convertView == null) {  // if it's not recycled, initialize some attributes
			imageView = ImageView(mContext)
			val scale = imageView.context.resources.displayMetrics.density
			// AbsListView.LayoutParams, not GridView.LayoutParams as the Java had
			// it. GridView declares no nested LayoutParams of its own -- it inherits
			// AbsListView's -- and Java resolves an inherited nested class through
			// the subclass name. Kotlin does not, so the same spelling is an
			// unresolved reference here. Same class either way; only the name it is
			// reachable under changed.
			imageView.setLayoutParams(AbsListView.LayoutParams((85 * scale + 0.5f).toInt(), (85 * scale + 0.5f).toInt()))
			imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
			imageView.setPadding(8, 8, 8, 8)
		} else {
			imageView = convertView as ImageView
		}

		imageView.setImageResource(m_thumbIDs[position]!!)
		return imageView
	}

}
