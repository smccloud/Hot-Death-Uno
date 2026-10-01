package com.smccloud.hotdeath

import android.app.Activity
import org.json.*
import android.content.SharedPreferences
import android.content.res.Configuration
import android.app.Dialog
import android.graphics.Insets
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.GridView
import android.widget.AdapterView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import android.widget.RelativeLayout
import android.widget.Button

// Every field name here is unchanged and every field is nullable, because
// GameRoundLoopTest reaches seven of them by name:
//
//     Field f = GameActivity.class.getDeclaredField(name);
//     f.setAccessible(true);
//     f.set(target, value);
//
// for m_go, m_game, m_gt, m_btnFastForward, m_vMenuPanel, m_btnMenuDraw and
// m_btnMenuPass. A rename fails that test loudly rather than as an NPE, which is
// the point of it. A nullable Kotlin property still compiles to a plain field of
// the same name, and `f.set` with a non-null value of the right type works on it
// unchanged -- so `GameOptions?` is `GameOptions` on the JVM, not
// `Optional<GameOptions>`. onDestroy nulls three of them, so nullable is also the
// honest reading.
class GameActivity : Activity()
{
	private var m_dlgCardCatalog: Dialog? = null
	private var m_dlgCardHelp: Dialog? = null

	private var m_vMenuPanel: View? = null

	private var m_btnFastForward: Button? = null
	private var m_btnMenuDraw: Button? = null
	private var m_btnMenuPass: Button? = null
	private var m_btnMenuHelp: Button? = null

	private var m_gt: GameTable? = null
	private var m_game: Game? = null
	private var m_go: GameOptions? = null

	fun getCardImageID (id: Int): Int
	{
		return m_gt!!.getCardImageID (id)
	}

	// Array<Int?> and not Array<Int>, because Int[] on the JVM is a different
	// type and CardImageAdapter.java assigns this straight to an Integer[].
	fun getCardIDs (): Array<Int?>
	{
		return m_gt!!.getCardIDs()
	}

	fun getGame (): Game?
	{
		return m_game
	}

	// Non-null return rather than Button?, deliberately: GameTable.kt calls this
	// with no check, and the Java returned a raw Button that would have thrown on
	// a null. `!!` throws in the same place instead of quietly passing a null
	// into setVisibility, and it keeps GameTable.kt untouched.
	fun getBtnFastForward (): Button
	{
		return m_btnFastForward!!
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val startup_mode = intent.getIntExtra(STARTUP_MODE, STARTUP_MODE_NEW)

		m_go = GameOptions (this)

		m_game = null
		if (startup_mode == STARTUP_MODE_CONTINUE)
		{
			val prefs = PreferenceManager.getDefaultSharedPreferences(this)
			// Nullable because getString is @Nullable, exactly as it was in Java.
			// JSONObject's constructor takes a platform String!, so a null here
			// NPEs out of the constructor rather than being caught below -- which
			// is the exposure the Java had too.
			val s = prefs.getString("gamestate", "")

			try
			{
				val o = JSONObject (s)
				m_game = Game (o, this, m_go!!)
			}
			catch (e: JSONException)
			{
				// FIXME: not sure what to do here if we couldn't load the object from JSON
			}
		}

		// we're here either because the user chose "New game" or because we couldn't
		// parse the game state JSON
		if (m_game == null)
		{
			m_game = Game (this, m_go!!)
		}

		m_gt = GameTable(this, m_game!!, m_go!!)
		m_gt!!.setId(View.generateViewId())

		val l = RelativeLayout (this)

		m_btnFastForward = layoutInflater.inflate(R.layout.action_button, null) as Button
		m_btnFastForward!!.setText(getString(R.string.lbl_fast_forward))
		m_btnFastForward!!.setId(View.generateViewId())
		m_btnFastForward!!.setVisibility(View.INVISIBLE)
		m_btnFastForward!!.setOnClickListener (View.OnClickListener {
			m_btnFastForward!!.setVisibility (View.INVISIBLE)
			m_game!!.setFastForward (true)
		})

		m_vMenuPanel = layoutInflater.inflate(R.layout.options_menu, null)
		m_vMenuPanel!!.setId(View.generateViewId())
		m_vMenuPanel!!.setVisibility(View.INVISIBLE)

		val scale = m_gt!!.getContext().resources.displayMetrics.density
		val btn_width = (160 * scale + 0.5f).toInt()
		val btn_height = (42 * scale + 0.5f).toInt()

		l.addView(m_gt!!)

		var lp: RelativeLayout.LayoutParams

		lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		lp.addRule(RelativeLayout.ALIGN_BOTTOM, m_gt!!.getId())
		lp.addRule(RelativeLayout.CENTER_HORIZONTAL, m_gt!!.getId())
		lp.bottomMargin = (8 * scale + 0.5f).toInt()
		//lp.width = btn_width
		lp.height = btn_height

		l.addView(m_vMenuPanel, lp)

		lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		lp.addRule(RelativeLayout.ABOVE, m_vMenuPanel!!.getId())
		lp.addRule(RelativeLayout.CENTER_HORIZONTAL, m_gt!!.getId())
		lp.bottomMargin = (8 * scale + 0.5f).toInt()
		lp.width = btn_width
		lp.height = btn_height

		l.addView(m_btnFastForward, lp)

		setContentView (l)

		applyEdgeToEdgeInsets()


		// The type argument is spelled out rather than inferred from the property,
		// because the property is what GameRoundLoopTest sets by name: it has to
		// stay a Button field and not widen to View.
		m_btnMenuDraw = findViewById<Button>(R.id.btn_menu_draw)
		m_btnMenuPass = findViewById<Button>(R.id.btn_menu_pass)
		m_btnMenuHelp = findViewById<Button>(R.id.btn_menu_help)

		m_btnMenuDraw!!.setOnClickListener (View.OnClickListener {
			m_game!!.drawPileTapped()
			showMenuButtons()
		})
		m_btnMenuPass!!.setOnClickListener (View.OnClickListener {
			m_game!!.humanPlayerPass()
			showMenuButtons()
		})
		m_btnMenuHelp!!.setOnClickListener (View.OnClickListener {
			showCardCatalog()
		})

		m_gt!!.setBottomMargin((58 * scale + 0.5f).toInt())

		m_gt!!.invalidate(); // force view to be laid out before we start the game
		m_gt!!.requestFocus()

		m_gt!!.startGameWhenReady();
	}


	/**
	 * Targeting API 35+ means edge-to-edge is enforced and can no longer be opted
	 * out of, so the window no longer reserves room for the status or navigation
	 * bars. Pad the content frame by the system bar and display cutout insets
	 * instead: the RelativeLayout shrinks, GameTable.onSizeChanged() recomputes all
	 * of its geometry from the new size, and the options menu moves above the
	 * navigation bar. Uses the platform WindowInsets API (available from API 30)
	 * so the project stays dependency-free.
	 */
	private fun applyEdgeToEdgeInsets ()
	{
		val content = findViewById<View>(android.R.id.content)
		content.setOnApplyWindowInsetsListener (View.OnApplyWindowInsetsListener { v, insets ->
			val bars = insets.getInsets (WindowInsets.Type.systemBars() or
					WindowInsets.Type.displayCutout())
			v.setPadding (bars.left, bars.top, bars.right, bars.bottom)
			WindowInsets.CONSUMED
		})
	}


	override fun openOptionsMenu() {
		super.invalidateOptionsMenu();
		super.openOptionsMenu();
		val config = resources.configuration
		if ((config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) > Configuration.SCREENLAYOUT_SIZE_LARGE) {
			val originalScreenLayout = config.screenLayout
			config.screenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE
			super.openOptionsMenu();
			config.screenLayout = originalScreenLayout
		} else {
			super.openOptionsMenu();
		}
	}

	override fun onPause ()
	{
		if (m_dlgCardHelp != null && m_dlgCardHelp!!.isShowing())
		{
			m_dlgCardHelp!!.dismiss();
		}
		if (m_dlgCardCatalog != null && m_dlgCardCatalog!!.isShowing())
		{
			m_dlgCardCatalog!!.dismiss();
		}

		intent.putExtra(STARTUP_MODE, STARTUP_MODE_CONTINUE);

		super.onPause();

		m_game!!.pause();
		val gamestate = m_game!!.getSnapshot()

		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
		val editor = prefs.edit();
		editor.putString("gamestate", gamestate);
		editor.commit();
	}

	override fun onResume ()
	{
		super.onResume ();
		m_game!!.unpause();
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		//ignore orientation change  (use in conjunction with settings in the manifest)
		super.onConfigurationChanged(newConfig);
	}

	override fun onDestroy() {
		m_game!!.shutdown ();
		m_game = null;
		m_gt = null;
		m_go = null;

		super.onDestroy ();
	}

	fun showCardHelp ()
	{
		if (m_dlgCardHelp == null)
		{
			m_dlgCardHelp = TapDismissableDialog(this)
			m_dlgCardHelp!!.setContentView(R.layout.dlg_card_help);
		}

		// `!!` throughout rather than a local val, because m_dlgCardHelp is a
		// mutable field and Kotlin will not smart-cast it back after the null
		// check above. The Java would have thrown here too.
		val cid = m_gt!!.getHelpCardID();
		val c = m_gt!!.getCardByID (cid);
		if (c != null)
		{
			m_dlgCardHelp!!.setTitle(m_game!!.cardToString(c));

			val text = m_dlgCardHelp!!.findViewById<TextView>(R.id.text);
			text.setText(m_gt!!.getCardHelpText(cid));

			val image = m_dlgCardHelp!!.findViewById<ImageView>(R.id.image);
			image.setImageBitmap(m_gt!!.getCardBitmap(cid));
		}

		m_dlgCardHelp!!.show();
	}

	fun showCardCatalog ()
	{
		if (m_dlgCardCatalog == null)
		{
			m_dlgCardCatalog = Dialog(this)
			m_dlgCardCatalog!!.requestWindowFeature(Window.FEATURE_NO_TITLE);
			m_dlgCardCatalog!!.setContentView(R.layout.dlg_card_catalog);
			val gridview = m_dlgCardCatalog!!.findViewById<GridView>(R.id.gridview);
			gridview.setAdapter(CardImageAdapter(this));

			gridview.setOnItemClickListener(AdapterView.OnItemClickListener { parent, v, position, id ->
				val cia = (parent as GridView).adapter as CardImageAdapter
				val cardids = cia.getCardIDs()

				// `!!` on the index, and this is a consequence of CardImageAdapter
				// becoming Kotlin rather than a change of its own. That class used to
				// return a Java Integer[], which Kotlin sees as the platform type
				// Array<Int!>! -- assignable to Int without complaint. It now declares
				// Array<Int?> honestly, because it is built with arrayOfNulls and
				// filled from GameTable's own nullable array. The JVM signature is
				// identical either way; only what Kotlin can see changed, and the
				// Java auto-unboxed at this exact line.
				this.m_gt!!.setHelpCardID (cardids[position]!!)
				//this.showDialog(DIALOG_CARD_HELP);
				showCardHelp();
			});
		}

		m_dlgCardCatalog!!.show();
	}


	fun showMenuButtons ()
	{
		if (m_game!!.getCurrPlayer() is HumanPlayer)
		{
			if (m_game!!.getCurrPlayerUnderAttack() || m_game!!.getCurrPlayerDrawn())
			{
				m_btnMenuDraw!!.setEnabled(false);
				// .toInt() on both of these, and it is the same trap as the int-to-
				// float widening in 1.4.0: 0xff7f7f7f is 4,286,543,487, which is
				// past Int32 max, so Kotlin types the literal Long and
				// setTextColor(Long) resolves to nothing. Java read it as an int.
				m_btnMenuDraw!!.setTextColor(0xff7f7f7f.toInt());
				m_btnMenuPass!!.setEnabled(true);
				m_btnMenuPass!!.setTextColor(0xffffffff.toInt());
			}
			else
			{
				m_btnMenuDraw!!.setEnabled(true);
				m_btnMenuDraw!!.setTextColor(0xffffffff.toInt());
				m_btnMenuPass!!.setEnabled(false);
				m_btnMenuPass!!.setTextColor(0xff7f7f7f.toInt());
			}
		}
		else
		{
			m_btnMenuDraw!!.setEnabled(false);
			m_btnMenuPass!!.setEnabled(false);
		}


		m_vMenuPanel!!.setVisibility(View.VISIBLE);
	}

	fun hideMenuButtons ()
	{
		m_vMenuPanel!!.setVisibility(View.INVISIBLE);
	}

	companion object
	{
		// const val in a companion compiles to a static field on the outer class,
		// so Main.java's GameActivity.STARTUP_MODE keeps working unchanged. A
		// plain `val` here would have forced every one of those four call sites
		// to become GameActivity.Companion.STARTUP_MODE.
		const val STARTUP_MODE = "com.smccloud.hotdeath.startup_mode"

		const val STARTUP_MODE_NEW = 1
		const val STARTUP_MODE_CONTINUE = 2

		const val DIALOG_CARD_HELP = 0
		const val DIALOG_CARD_CATALOG = 1
	}
}
