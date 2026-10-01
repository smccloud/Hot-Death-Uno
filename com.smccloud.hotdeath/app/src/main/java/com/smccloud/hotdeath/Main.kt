package com.smccloud.hotdeath

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Insets
import android.preference.PreferenceManager
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.view.WindowInsets
import android.widget.TextView
import android.text.method.ScrollingMovementMethod

class Main : Activity(), OnClickListener
{
	@Deprecated("Deprecated in Java")
	override fun onCreateDialog(id: Int): Dialog?
	{
		var dlg: Dialog? = null
		when (id)
		{
			DIALOG_ABOUT ->
			{
				dlg = Dialog(this)
				dlg!!.setContentView(R.layout.dlg_about)
			}
			DIALOG_HELP ->
			{
				dlg = Dialog(this)
				dlg!!.setContentView(R.layout.dlg_help)
			}
		}

		return dlg
	}

	@Deprecated("Deprecated in Java")
	override fun onPrepareDialog(id: Int, d: Dialog)
	{
		val text: TextView
		when (id)
		{
			DIALOG_ABOUT ->
			{
				d.setTitle(this.getString(R.string.dlg_about_title));

				text = d.findViewById<TextView>(R.id.text)
				text.setMovementMethod(ScrollingMovementMethod.getInstance())

				try
				{
					val app_ver = packageManager.getPackageInfo(packageName, 0).versionName
					var str_about = this.getString(R.string.dlg_about_text)

					str_about = String.format(str_about, app_ver)
					text.setText(str_about)
				}
				catch (e: Exception)
				{

				}

			}

			DIALOG_HELP ->
			{
				d.setTitle(this.getString(R.string.dlg_help_title));

				text = d.findViewById<TextView>(R.id.text)
				text.setMovementMethod(ScrollingMovementMethod.getInstance())
				text.setText(this.getString(R.string.dlg_help_text));

			}
		}
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

		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
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
			this.showDialog(DIALOG_HELP)

		} else if (id == R.id.btn_about) {
			this.showDialog(DIALOG_ABOUT)

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
