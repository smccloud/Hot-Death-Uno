package com.smccloud.hotdeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;

import android.content.Context;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

/**
 * Drives the round loop -- the part of Game that nothing else in the suite reaches.
 *
 * <p>{@link HandPlayabilityTest} covers {@code checkCard} and
 * {@code hasValidCards}, and {@link JsonRoundTripTest} covers save and resume, but
 * both put {@code m_currCard}, {@code m_currColor} and {@code m_penalty} in by
 * reflection, because the code that sets them properly is {@code startRound()},
 * which deals and draws off the top. That leaves {@code dealHands},
 * {@code advanceRound}, {@code handleSpecialCards}, {@code assessPenalty},
 * {@code calculateScore}, {@code sortHand} and {@code finishRound} untested --
 * most of Game, and all of it reachable from here.
 *
 * <p>Three things are needed to make it run, and each one is a trap:
 *
 * <ul>
 *   <li><b>The game thread must not be running.</b> {@code Game} extends
 *   {@code Thread}, and {@code GameActivity.onCreate} finishes by calling
 *   {@code m_gt.startGameWhenReady()}, which calls {@code m_game.start()}. So
 *   building the activity with {@code setup()} quietly starts a second thread that
 *   calls {@code startGame()} -- and if the test also calls {@code startGame()},
 *   two threads deal at once. The hands come out with twice the cards they should
 *   have, the draw pile is filled twice over, and {@code sortHand} then nulls the
 *   back half of every hand because fewer cards claim it than the hand's own count
 *   says. It surfaces as a NullPointerException in
 *   {@code checkForAllBastardCards} on the first {@code postDealHands}, which reads
 *   exactly like a real card-rule bug and is not one. Hence {@code get()} rather
 *   than {@code setup()}, and a hand-built GameTable below.
 *   <li><b>All four seats have to be computer players.</b> A {@code HumanPlayer}'s
 *   {@code startTurn} is a spin-wait on a boolean only a tap on the real table
 *   sets, so the turn never returns. The fourth-computer preference therefore has
 *   to be set before the Game is built: its constructor reads it to decide what
 *   goes in each seat.
 *   <li><b>{@code setFastForward(true)}</b>, or every {@code promptUser} sleeps for
 *   the pause length -- up to four seconds, hundreds of times.
 * </ul>
 *
 * <p>{@code run()} is deliberately not called. It is the only caller of
 * {@code waitForNextRound}, an unconditional spin-wait that a tap on the draw pile
 * clears, and it is the one thing that needs a second thread. Driving
 * {@code startGame} and {@code advanceRound} from the test thread is the same loop
 * without the waits, and it is single-threaded, so every assertion below reads
 * state that is not being mutated underneath it.
 *
 * <p>The deal is random -- the deck is shuffled, the dealer is picked at random and
 * the dealer chooses how many cards to deal -- so nothing here asserts a score or
 * a card. Every assertion is an invariant that has to hold whatever came out: no
 * card created or destroyed, scores only ever rising, every hand turned over when
 * the round ends. Run often enough, the shuffles reach card rules that the
 * fixed-deck tests cannot.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner.class)
public class GameRoundLoopTest
{
	/** A round this long is a bug, not a slow shuffle. */
	private static final int MAX_TURNS = 4000;

	@Test
	public void houseRulesPlaysRoundsToAFinish ()
	{
		Game game = newGame(false);

		playRounds(game, 2);
	}

	@Test
	public void standardRulesPlaysRoundsToAFinish ()
	{
		Game game = newGame(true);

		playRounds(game, 2);
	}

	/**
	 * Standard rules deal a fixed 7 and drop every house card, so this exercises a
	 * different set of branches in advanceRound, not merely a different deal. Kept
	 * apart from the round loop because the deal size is the one thing here that is
	 * not random.
	 */
	@Test
	public void standardRulesDealSevenEach ()
	{
		Game game = newGame(true);
		game.startGame();

		for (int seat = 1; seat <= 4; seat++)
		{
			Player p = game.getPlayer(seat - 1);
			assertEquals ("standard rules deal 7, seat " + seat + " got "
					+ p.getHand().getNumCards(), 7, p.getHand().getNumCards());
		}
	}

	// ---------------------------------------------------------------- the round

	/** Plays `count` whole rounds, checking the invariants after each. */
	private void playRounds (Game game, int count)
	{
		game.startGame();

		for (int round = 0; round < count; round++)
		{
			if (round > 0)
			{
				game.startRound();
			}

			int turns = driveRound(game);

			assertTrue ("round " + round + " did not complete", game.getRoundComplete());
			assertTrue ("round " + round + " took " + turns + " turns", turns < MAX_TURNS);

			checkNoCardsWereLost(game, round);
			checkEveryHandIsFaceUp(game, round);
			checkTheDealerWonTheRound(game, round);
			checkScores(game, round);

			// At 1000 the game is over and there is no next round to deal.
			if (game.getWinner() != 0)
			{
				return;
			}
		}
	}

	/**
	 * Runs advanceRound until the round ends, and returns how many turns it took.
	 *
	 * <p>advanceRound returns false when a player runs out of cards, when the game
	 * is stopping, or when there is no current player, and true when the round
	 * carries on. The bound is here so that a failure to terminate reads as a
	 * failed assertion naming the round rather than a build that hangs.
	 */
	private int driveRound (Game game)
	{
		int turns = 0;
		while (game.advanceRound())
		{
			if (++turns > MAX_TURNS)
			{
				fail ("the round never ended after " + turns + " turns");
			}
		}
		return turns;
	}

	// ---------------------------------------------------------------- invariants

	/**
	 * Cards are only ever moved between a hand, the draw pile and the discard pile:
	 * the draw pile and the discard roll into each other, and a played card leaves a
	 * hand for the discard. Nothing creates one and nothing drops one, so the three
	 * have to add back up to the deck.
	 *
	 * <p>This is the assertion most likely to catch a mistranslated line, because it
	 * is the one thing about the loop that holds no matter what the cards were.
	 */
	private void checkNoCardsWereLost (Game game, int round)
	{
		int inHands = 0;
		for (int i = 0; i < 4; i++)
		{
			inHands += game.getPlayer(i).getHand().getNumCards();
		}

		int total = game.getDeck().getNumCards();
		int accounted = inHands
				+ game.getDrawPile().getNumCards()
				+ game.getDiscardPile().getNumCards();

		assertEquals ("after round " + round + " the cards do not add up: "
				+ inHands + " in hands, "
				+ game.getDrawPile().getNumCards() + " in the draw pile, "
				+ game.getDiscardPile().getNumCards() + " in the discard, "
				+ "but the deck holds " + total,
				total, accounted);
	}

	/** finishRound turns every hand face up so the scores can be read off the table. */
	private void checkEveryHandIsFaceUp (Game game, int round)
	{
		for (int seat = 1; seat <= 4; seat++)
		{
			Hand h = game.getPlayer(seat - 1).getHand();
			for (int i = 0; i < h.getNumCards(); i++)
			{
				Card c = h.getCard(i);
				assertTrue ("seat " + seat + " is still holding a card face down at "
						+ "the end of round " + round + ": " + c, c.getFaceUp());
			}
		}
	}

	/**
	 * finishRound makes the player who went out the next dealer, and going out
	 * means an empty hand or all four bastard cards -- advanceRound calls
	 * finishRound on exactly those players.
	 */
	private void checkTheDealerWonTheRound (Game game, int round)
	{
		boolean found = false;
		for (int i = 0; i < 4; i++)
		{
			Player p = game.getPlayer(i);
			boolean wentOut = p.getHand().getNumCards() == 0
					|| game.checkForAllBastardCards(p.getHand());
			if (wentOut && p.getSeat() == game.getDealer().getSeat())
			{
				found = true;
			}
		}
		assertTrue ("nobody who went out is the dealer after round " + round
				+ "; the dealer is seat " + game.getDealer().getSeat(), found);
	}

	/**
	 * A total is at least this round's score, and never negative. The virus penalty
	 * is added to a total rather than subtracted from one, which is what the
	 * compounding bug in issue #1 depends on; this only pins the sign and the
	 * accumulation, so it passes with the bug present.
	 */
	private void checkScores (Game game, int round)
	{
		for (int i = 0; i < 4; i++)
		{
			Player p = game.getPlayer(i);
			assertTrue ("seat " + p.getSeat() + " has a negative total after round "
					+ round + ": " + p.getTotalScore(), p.getTotalScore() >= 0);
			assertTrue ("seat " + p.getSeat() + " totals " + p.getTotalScore()
					+ " but only scored " + p.getLastScore() + " this round",
					p.getTotalScore() >= p.getLastScore());
		}
	}

	// ---------------------------------------------------------------- fixture

	/**
	 * Builds a Game with a GameTable and the activity's buttons attached, without
	 * ever starting the game thread.
	 *
	 * <p>{@code get()} rather than {@code setup()} is the whole trick: setup() runs
	 * onCreate, and onCreate's last act is to start the game thread. So the views
	 * that onCreate would have built are built here instead, by hand and in the same
	 * way -- the round loop toggles the menu panel on every turn and the
	 * fast-forward button whenever a player is ejected, and both dereference views
	 * that only exist once the activity has been created.
	 */
	private Game newGame (boolean standardRules)
	{
		Context app = ApplicationProvider.getApplicationContext();

		PreferenceManager.getDefaultSharedPreferences(app)
			.edit()
			.putBoolean("computer_4th", true)
			.putBoolean("face_up", false)
			.putString("cheat_code", standardRules ? "standardrules" : "")
			.commit();

		GameActivity activity = Robolectric.buildActivity(GameActivity.class).get();
		GameOptions options = new GameOptions(activity);
		Game game = new Game(activity, options);

		// The GameTable constructor is what calls setGameTable, which is what stops
		// Game's redrawTable from dereferencing a null table.
		GameTable table = new GameTable(activity, game, options);

		// RedrawTable positions cards from m_ptMessages, which onSizeChanged works
		// out from the view's real size. Nothing lays the table out here, because
		// it was never added to a parent the way onCreate adds it, so give it a
		// size by hand -- otherwise every redraw throws on the null points.
		table.measure(
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
		table.layout(0, 0, 1080, 1920);

		Button fastForward = (Button) activity.getLayoutInflater()
			.inflate(R.layout.action_button, null);
		View menuPanel = activity.getLayoutInflater().inflate(R.layout.options_menu, null);

		// GameTable reaches the activity through its context, and showMenuButtons
		// reads m_game off it, so the activity has to be holding the same objects.
		set(activity, "m_go", options);
		set(activity, "m_game", game);
		set(activity, "m_gt", table);
		set(activity, "m_btnFastForward", fastForward);
		set(activity, "m_vMenuPanel", menuPanel);
		set(activity, "m_btnMenuDraw", menuPanel.findViewById(R.id.btn_menu_draw));
		set(activity, "m_btnMenuPass", menuPanel.findViewById(R.id.btn_menu_pass));

		game.setFastForward(true);
		return game;
	}

	/** Reflection, so a rename of one of these fails here rather than as an NPE. */
	private void set (Object target, String name, Object value)
	{
		try
		{
			Field f = GameActivity.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("GameActivity." + name + " is gone or renamed: " + e);
		}
	}
}
