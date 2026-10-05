package com.smccloud.hotdeath

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Insets
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.view.WindowInsets
import android.widget.TextView
import android.text.method.ScrollingMovementMethod

class Main : Activity(), OnClickListener
{
	/**
	 * The dialog currently on screen, if any.
	 *
	 * Held only so a second dialog replaces the first and so onDestroy can
	 * dismiss it. `Activity.showDialog` used to do both -- it cached the dialog
	 * it built from onCreateDialog and dismissed the lot when the activity went
	 * away -- and it was deprecated in API 13. Building the dialog here instead
	 * means the reference is this field, so both halves have to be explicit.
	 */
	private var m_dialog: Dialog? = null

	/**
	 * Builds and shows one of the two informational dialogs.
	 *
	 * What `showDialog` + `onCreateDialog` + `onPrepareDialog` did, in one pass:
	 * inflate the layout, then set the title and the text. The order is the same
	 * (content view first, then the title, then the text), which is what
	 * onPrepareDialog relied on to find R.id.text.
	 */
	private fun showInfoDialog (id: Int)
	{
		val dlg = Dialog(this)

		when (id)
		{
			DIALOG_ABOUT ->
			{
				dlg.setContentView(R.layout.dlg_about)
				dlg.setTitle(this.getString(R.string.dlg_about_title))

				// Null rather than the raw template: the template carries a %s,
				// and the original code on a failure here never called setText at
				// all, leaving the body of the dialog empty. Showing the
				// unformatted string instead would put a literal "%s" on screen,
				// which is the one outcome neither of those is.
				val text = aboutText()
				if (text != null)
				{
					dlg.setInfoText(text)
				}
			}
			DIALOG_HELP ->
			{
				dlg.setContentView(R.layout.dlg_help)
				dlg.setTitle(this.getString(R.string.dlg_help_title))
				dlg.setInfoText(this.getString(R.string.dlg_help_text))
			}
		}

		m_dialog?.dismiss()
		m_dialog = dlg
		dlg.show()
	}

	/**
	 * Points the dialog's text view at a string and makes it scrollable.
	 *
	 * Both dialogs share one layout shape, so this is the half of the two
	 * onPrepareDialog branches that never differed. A format string with no
	 * arguments is passed through untouched: `getString` with no arguments
	 * returns the template, specifiers and all, which is how the help text is
	 * loaded and why the about text has to be formatted by hand.
	 */
	private fun Dialog.setInfoText (text: String)
	{
		val view = findViewById<TextView>(R.id.text)
		view.setMovementMethod(ScrollingMovementMethod.getInstance())
		view.setText(text)
	}

	/**
	 * The about text with the running version substituted into it, or null if the
	 * version could not be read.
	 *
	 * The version comes from the package manager, which throws if the package has
	 * gone missing underneath us. The original swallowed that and left the text
	 * view empty, so a failure here produces an empty dialog rather than a
	 * crash or a template with a %s still in it -- see the caller.
	 */
	private fun aboutText (): String?
	{
		val text = this.getString(R.string.dlg_about_text)

		try
		{
			val app_ver = packageManager.getPackageInfo(packageName, 0).versionName
			return String.format(text, app_ver)
		}
		catch (e: Exception)
		{
			return null
		}
	}

	override fun onDestroy()
	{
		m_dialog?.dismiss()
		m_dialog = null
		super.onDestroy()
	}

	/** Called when the activity is first created. */
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		applyEdgeToEdgeInsets();

		findViewById<View>(R.id.btn_continue).setOnClickListener(this);
		findViewById<View>(R.id.btn_new_game).setOnClickListener(this);
		findViewById<View>(R.id.btn_settings).setOnClickListener(this);
		findViewById<View>(R.id.btn_help).setOnClickListener(this);
		findViewById<View>(R.id.btn_about).setOnClickListener(this);
		findViewById<View>(R.id.btn_exit).setOnClickListener(this);
	}

	/** See GameActivity.applyEdgeToEdgeInsets(): edge-to-edge is mandatory at targetSdk 35+. */
	private fun applyEdgeToEdgeInsets() {
		val content = findViewById<View>(android.R.id.content)
		content.setOnApplyWindowInsetsListener (View.OnApplyWindowInsetsListener { v, insets ->
			val bars = insets.getInsets (WindowInsets.Type.systemBars() or
					WindowInsets.Type.displayCutout())
			v.setPadding (bars.left, bars.top, bars.right, bars.bottom)
			WindowInsets.CONSUMED
		})
	}

	// public, and deliberately so: Activity declares onResume() protected and the
	// Java widened it. Kotlin would inherit protected if the keyword were dropped,
	// so it is written out. Nothing calls this from outside -- it is here because a
	// mechanical translation should not quietly change visibility, not because the
	// wider one is wanted.
	public override fun onResume ()
	{
		super.onResume ();

		val continueButton = findViewById<View>(R.id.btn_continue)
		continueButton.setOnClickListener(this);

		val prefs = Prefs.defaultSharedPreferences(this)
		val s = prefs.getString("gamestate", "")

		// `==` on a String?, which is a value comparison in Kotlin. The Java used
		// `==` on a String, which is reference comparison and only worked because
		// the default is an interned literal. Both agree on every value this can
		// actually hold, so the behaviour is unchanged -- but the known-bug note in
		// README about Main comparing preference strings with == is now describing
		// something Kotlin has quietly fixed, and that is worth knowing rather than
		// rediscovering.
		if (s == "")
		{
			continueButton.setVisibility(View.GONE);
		}
		else
		{
			continueButton.setVisibility(View.VISIBLE);
		}
	}

	override fun onClick(v: View) {
		// An if/else chain rather than a `when`: AGP 8 generates non-final R
		// fields (android.nonFinalResIds defaults to true), so R.id.* is not a
		// compile-time constant and cannot be used as a branch condition in
		// either language. Kept as it was for the same reason.
		val id = v.id

		if (id == R.id.btn_new_game) {
			val intent = Intent(this, GameActivity::class.java)
			intent.putExtra(GameActivity.STARTUP_MODE, GameActivity.STARTUP_MODE_NEW)
			startActivity(intent)

		} else if (id == R.id.btn_continue) {
			val intent = Intent(this, GameActivity::class.java)
			intent.putExtra(GameActivity.STARTUP_MODE, GameActivity.STARTUP_MODE_CONTINUE)
			startActivity(intent)

		} else if (id == R.id.btn_settings) {
			startActivity (Intent (this, Prefs::class.java))

		} else if (id == R.id.btn_help) {
			showInfoDialog(DIALOG_HELP)

		} else if (id == R.id.btn_about) {
			showInfoDialog(DIALOG_ABOUT)

		} else if (id == R.id.btn_exit) {
			finish();
		}
	}

	companion object
	{
		const val DIALOG_ABOUT = 0
		const val DIALOG_HELP = 1
	}
}
