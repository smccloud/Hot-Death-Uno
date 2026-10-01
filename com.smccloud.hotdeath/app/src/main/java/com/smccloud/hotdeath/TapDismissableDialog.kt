package com.smccloud.hotdeath

import android.app.Dialog
import android.content.Context
import android.view.MotionEvent

/**
 * A dialog that dismisses on a tap, but not on a drag. Used for the in-game card
 * help, where a tap means "close this" and a scroll means "I missed".
 */
class TapDismissableDialog(context: Context) : Dialog(context)
{
	// internal rather than private: these were package-private in the Java, and
	// Kotlin has no package-private. Nothing outside this class reads them --
	// they are per-touch state -- but `internal` keeps the same audience instead
	// of quietly narrowing it, which is what Game.kt did with the same situation.
	internal var _down_x = 0
	internal var _down_y = 0
	internal var _threshold = 7

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if (event.action == MotionEvent.ACTION_DOWN)
		{
			_down_x = event.x.toInt()
			_down_y = event.y.toInt()
		}

		if (event.action == MotionEvent.ACTION_UP)
		{
			if (Math.abs(event.x.toInt() - _down_x) > _threshold)
			{
				return true
			}
			if (Math.abs(event.y.toInt() - _down_y) > _threshold)
			{
				return true
			}

			this.dismiss()
		}

		return true
	}
}
