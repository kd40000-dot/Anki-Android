package com.gpl.rpg.AndorsTrail.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gpl.rpg.AndorsTrail.AndorsTrailApplication;
import com.gpl.rpg.AndorsTrail.AndorsTrailPreferences;
import com.gpl.rpg.AndorsTrail.Dialogs;
import com.gpl.rpg.AndorsTrail.R;
import com.gpl.rpg.AndorsTrail.activity.MainActivity;
import com.gpl.rpg.AndorsTrail.anki.AnkiCombatReviewClient;
import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.controller.CombatController;
import com.gpl.rpg.AndorsTrail.controller.listeners.ActorConditionListener;
import com.gpl.rpg.AndorsTrail.controller.listeners.ActorStatsListener;
import com.gpl.rpg.AndorsTrail.controller.listeners.CombatSelectionListener;
import com.gpl.rpg.AndorsTrail.controller.listeners.CombatTurnListener;
import com.gpl.rpg.AndorsTrail.model.ability.ActorCondition;
import com.gpl.rpg.AndorsTrail.model.ability.ActorConditionType;
import com.gpl.rpg.AndorsTrail.model.actor.Actor;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.util.Coord;

public final class CombatView extends RelativeLayout implements CombatSelectionListener, CombatTurnListener, ActorStatsListener, ActorConditionListener {
	private final RangeBar playerAPBar;
	private final Button attackMoveButton;
	private final Button endTurnButton;
	private final Button fleeButton;
	private final ImageButton monsterInfo;
	private final RangeBar monsterHealth;
	private final ImageButton monsterConditionsButton;
	private final RelativeLayout activeConditionsBar;
	private DisplayActiveActorConditionIcons activeConditions;
	private final ViewGroup monsterBar;
	private final ViewGroup actionBar;
	private final TextView monsterActionText;

	private final LinearLayout ankiQuiz;
	private final TextView ankiPreviousRating;
	private final TextView ankiStatus;
	private final TextView ankiQuestion;
	private final EditText ankiInput;
	private final Button ankiReveal;
	private final TextView ankiFeedback;
	private final TextView ankiAnswer;
	private final LinearLayout ankiRatings;
	private final Button ankiAgain;
	private final Button ankiHard;
	private final Button ankiGood;
	private final Button ankiEasy;
	private final Button ankiFlee;
	private final Button ankiRecover;
	private final AnkiCombatReviewClient ankiClient = new AnkiCombatReviewClient();
	private final CombatController.AnkiCombatSession ankiSession;
	private final Handler ankiMainHandler = new Handler(Looper.getMainLooper());
	private boolean suppressAnkiTextWatcher = false;
	private long lastSubmissionProbeOperation = -1L;

	private final Runnable ankiHealthCheck = new Runnable() {
		@Override
		public void run() {
			try {
				runAnkiHealthCheck();
			} finally {
				if (isAttachedToWindow()) {
					ankiMainHandler.postDelayed(this, 600);
				}
			}
		}
	};

	private final WorldContext world;
	private final ControllerContext controllers;
	private final Resources res;
	private final AndorsTrailPreferences preferences;
	private final Player player;
	private final Animation displayAnimation;
	private final Animation hideAnimation;
	private final Animation displayConditionsButtonAnimation;
	private final Animation hideConditionsButtonAnimation;
	private final Animation displayConditionsBarAnimation;
	private final Animation hideConditionsBarAnimation;

	private Monster currentMonster;
	private boolean conditionsBarToggled = false;

	public CombatView(final Context context, AttributeSet attr) {
		super(context, attr);
		AndorsTrailApplication app = AndorsTrailApplication.getApplicationFromActivityContext(context);
		this.world = app.getWorld();
		this.player = world.model.player;
		this.controllers = app.getControllerContext();
		this.ankiSession = controllers.combatController.getAnkiCombatSession();
		this.preferences = app.getPreferences();
		this.res = getResources();

		setFocusable(false);
		inflate(context, R.layout.combatview, this);
		//Prevents mis-taps from registering as main area taps by going through the combat view.
		findViewById(R.id.combatview_fixedarea).setClickable(true);

		final CombatController c = controllers.combatController;
		attackMoveButton = findViewById(R.id.combatview_moveattack);
		//Enable marquee if text is too long.
		attackMoveButton.setSelected(true);
		attackMoveButton.setOnClickListener((View v) -> {
			c.executeMoveAttack(0, 0);
			MainView mv = ((MainActivity) getContext()).getMainView();
			mv.post(mv::requestFocus);
		});

		endTurnButton = findViewById(R.id.combatview_endturn);
		//Enable marquee if text is too long.
		endTurnButton.setSelected(true);
		endTurnButton.setOnClickListener((View v) -> {
			c.endPlayerTurn();
			((MainActivity) getContext()).getMainView().post(this::requestFocus);
		});

		fleeButton = findViewById(R.id.combatview_flee);
		//Enable marquee if text is too long.
		fleeButton.setSelected(true);
		fleeButton.setOnClickListener((View v) -> {
			c.startFlee();
			// After fleeing, set the focus back to the main view so the player can choose a direction.
			MainView mv = ((MainActivity) getContext()).getMainView();
			mv.post(mv::requestFocus);
		});

		playerAPBar = (RangeBar) findViewById(R.id.combatview_status);
		playerAPBar.init(R.drawable.ui_progress_ap, R.string.status_ap);

		monsterInfo = (ImageButton) findViewById(R.id.combatview_monsterinfo);
		monsterInfo.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				Dialogs.showMonsterInfo(context, currentMonster);
			}
		});

		monsterHealth = (RangeBar) findViewById(R.id.combatview_monsterhealth);
		monsterHealth.init(R.drawable.ui_progress_health, R.string.combat_monsterhealth);
		monsterBar = (ViewGroup) findViewById(R.id.combatview_monsterbar);
		actionBar = (ViewGroup) findViewById(R.id.combatview_actionbar);
		monsterActionText = (TextView) findViewById(R.id.combatview_monsterismoving);

		ankiQuiz = (LinearLayout) findViewById(R.id.combatview_anki_quiz);
		ankiPreviousRating = (TextView) findViewById(R.id.combatview_anki_previous_rating);
		ankiStatus = (TextView) findViewById(R.id.combatview_anki_status);
		ankiQuestion = (TextView) findViewById(R.id.combatview_anki_question);
		ankiInput = (EditText) findViewById(R.id.combatview_anki_input);
		ankiReveal = (Button) findViewById(R.id.combatview_anki_reveal);
		ankiFeedback = (TextView) findViewById(R.id.combatview_anki_feedback);
		ankiAnswer = (TextView) findViewById(R.id.combatview_anki_answer);
		ankiRatings = (LinearLayout) findViewById(R.id.combatview_anki_ratings);
		ankiAgain = (Button) findViewById(R.id.combatview_anki_again);
		ankiHard = (Button) findViewById(R.id.combatview_anki_hard);
		ankiGood = (Button) findViewById(R.id.combatview_anki_good);
		ankiEasy = (Button) findViewById(R.id.combatview_anki_easy);
		ankiFlee = (Button) findViewById(R.id.combatview_anki_flee);
		ankiRecover = (Button) findViewById(R.id.combatview_anki_recover);

		configureRatingButtonColors();

		ankiFlee.setOnClickListener((View v) -> {
			hideAnkiKeyboardAndClearFocus();
			controllers.combatController.startFlee();
			MainView mv = ((MainActivity) getContext()).getMainView();
			mv.post(mv::requestFocus);
		});

		ankiReveal.setOnClickListener((View v) -> revealAnkiAnswer());
		ankiAgain.setOnClickListener((View v) -> chooseAnkiRating(1));
		ankiHard.setOnClickListener((View v) -> chooseAnkiRating(2));
		ankiGood.setOnClickListener((View v) -> chooseAnkiRating(3));
		ankiEasy.setOnClickListener((View v) -> chooseAnkiRating(4));
		ankiRecover.setOnClickListener((View v) -> recoverAnkiQuiz());

		ankiInput.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
				if (!suppressAnkiTextWatcher
						&& ankiSession.phase == CombatController.AnkiCombatSession.Phase.QUESTION) {
					ankiSession.typedText = s == null ? "" : s.toString();
				}
			}
			@Override public void afterTextChanged(Editable s) {}
		});

		ankiInput.setOnEditorActionListener((v, actionId, event) -> {
			if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.QUESTION) {
				revealAnkiAnswer();
				return true;
			}
			return false;
		});

		monsterConditionsButton = (ImageButton) findViewById(R.id.combatview_monsterconditions_button);
		monsterConditionsButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				toggleConditionsBarVisibility();
			}
		});
		
		activeConditionsBar = (RelativeLayout) findViewById(R.id.combatview_activeconditions);
		activeConditions = new DisplayActiveActorConditionIcons(controllers, world, context, activeConditionsBar);
		
		
//		monsterBar.setBackgroundColor(res.getColor(color.transparent));
//		actionBar.setBackgroundColor(res.getColor(color.transparent));

		displayAnimation = AnimationUtils.loadAnimation(context, R.anim.showcombatbar);
		hideAnimation = AnimationUtils.loadAnimation(context, R.anim.hidecombatbar);
		hideAnimation.setAnimationListener(new AnimationListener() {
			@Override public void onAnimationStart(Animation animation) {}
			@Override public void onAnimationRepeat(Animation animation) {}
			@Override public void onAnimationEnd(Animation arg0) {
				CombatView.this.setVisibility(View.GONE);
			}
		});
		
		displayConditionsButtonAnimation = AnimationUtils.loadAnimation(context, R.anim.showmonsterconditionbutton);
		hideConditionsButtonAnimation = AnimationUtils.loadAnimation(context, R.anim.hidemonsterconditionbutton);
		hideConditionsButtonAnimation.setAnimationListener(new AnimationListener() {
			@Override public void onAnimationStart(Animation animation) {}
			@Override public void onAnimationRepeat(Animation animation) {}
			@Override public void onAnimationEnd(Animation arg0) {
				monsterConditionsButton.setVisibility(View.GONE);
			}
		});

		displayConditionsBarAnimation = AnimationUtils.loadAnimation(context, R.anim.showmonsterconditionbar);
		hideConditionsBarAnimation = AnimationUtils.loadAnimation(context, R.anim.hidemonsterconditionbar);
		hideConditionsBarAnimation.setAnimationListener(new AnimationListener() {
			@Override public void onAnimationStart(Animation animation) {}
			@Override public void onAnimationRepeat(Animation animation) {}
			@Override public void onAnimationEnd(Animation arg0) {
				activeConditionsBar.setVisibility(View.GONE);
			}
		});
	}

	private static final int ANSWER_GOOD_COLOR = Color.rgb(123, 216, 143);
	private static final int ANSWER_BAD_COLOR = Color.rgb(240, 113, 120);
	private static final int ANSWER_MISSED_COLOR = Color.rgb(255, 180, 0);
	private static final int ANSWER_NEUTRAL_COLOR = Color.rgb(218, 218, 218);

	private int resolveThemeColor(int attr, int fallback) {
		TypedValue value = new TypedValue();
		if (getContext().getTheme().resolveAttribute(attr, value, true)) {
			if (value.resourceId != 0) {
				try {
					return getResources().getColor(value.resourceId);
				} catch (Exception ignored) {
				}
			}
			if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
					&& value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
				return value.data;
			}
		}
		return fallback;
	}

	private ColorStateList ratingColorStateList(int color) {
		int disabled = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color));
		return new ColorStateList(
				new int[][]{
						new int[]{-android.R.attr.state_enabled},
						new int[]{}
				},
				new int[]{disabled, color});
	}

	private int ratingColorForEase(int ease) {
		switch (ease) {
			case 1:
				return ANSWER_BAD_COLOR;
			case 2:
				return resolveThemeColor(
						R.attr.ui_theme_playername_light_color,
						Color.rgb(255, 180, 0));
			case 3:
				return ANSWER_GOOD_COLOR;
			case 4:
				return resolveThemeColor(
						R.attr.ui_theme_reward_light_color,
						Color.rgb(94, 227, 241));
			default:
				return ANSWER_NEUTRAL_COLOR;
		}
	}

	private void configureRatingButtonColors() {
		ankiAgain.setTextColor(ratingColorStateList(ratingColorForEase(1)));
		ankiHard.setTextColor(ratingColorStateList(ratingColorForEase(2)));
		ankiGood.setTextColor(ratingColorStateList(ratingColorForEase(3)));
		ankiEasy.setTextColor(ratingColorStateList(ratingColorForEase(4)));
	}

	private void renderPreviousRatingIndicator() {
		int ease = controllers.combatController.getLastAnkiRecordedEase();
		if (ease < 1 || ease > 4) {
			ankiPreviousRating.setText("");
			ankiPreviousRating.setVisibility(View.GONE);
			return;
		}

		StringBuilder dots = new StringBuilder();
		for (int i = 0; i < ease; i++) {
			if (i > 0) dots.append(" ");
			dots.append("•");
		}
		ankiPreviousRating.setText(dots.toString());
		ankiPreviousRating.setTextColor(ratingColorForEase(ease));
		ankiPreviousRating.setVisibility(View.VISIBLE);
	}

	private static String normalizeForComparison(String value) {
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static int editDistance(String a, String b) {
		String left = normalizeForComparison(a);
		String right = normalizeForComparison(b);
		int[][] dp = new int[left.length() + 1][right.length() + 1];
		for (int i = 0; i <= left.length(); i++) dp[i][0] = i;
		for (int j = 0; j <= right.length(); j++) dp[0][j] = j;
		for (int i = 1; i <= left.length(); i++) {
			for (int j = 1; j <= right.length(); j++) {
				int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
				dp[i][j] = Math.min(
						Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
						dp[i - 1][j - 1] + cost);
			}
		}
		return dp[left.length()][right.length()];
	}

	private static String closestAnswerAlternative(String typed, String answer) {
		String[] alternatives = answer == null ? new String[]{""} : answer.split("\\|", -1);
		String best = alternatives.length == 0 ? "" : alternatives[0].trim();
		int bestDistance = editDistance(typed, best);
		for (int i = 1; i < alternatives.length; i++) {
			String candidate = alternatives[i].trim();
			int distance = editDistance(typed, candidate);
			if (distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static final class DiffPiece {
		final Character typed;
		final Character expected;
		final boolean match;

		DiffPiece(Character typed, Character expected, boolean match) {
			this.typed = typed;
			this.expected = expected;
			this.match = match;
		}
	}

	private static java.util.List<DiffPiece> alignAnswer(String typedRaw, String expectedRaw) {
		String typed = typedRaw == null ? "" : typedRaw.trim();
		String expected = expectedRaw == null ? "" : expectedRaw.trim();
		String typedCmp = typed.toLowerCase(java.util.Locale.ROOT);
		String expectedCmp = expected.toLowerCase(java.util.Locale.ROOT);

		int n = typed.length();
		int m = expected.length();
		int[][] dp = new int[n + 1][m + 1];
		for (int i = 0; i <= n; i++) dp[i][0] = i;
		for (int j = 0; j <= m; j++) dp[0][j] = j;
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= m; j++) {
				int cost = typedCmp.charAt(i - 1) == expectedCmp.charAt(j - 1) ? 0 : 1;
				dp[i][j] = Math.min(
						Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
						dp[i - 1][j - 1] + cost);
			}
		}

		java.util.ArrayList<DiffPiece> reversed = new java.util.ArrayList<>();
		int i = n;
		int j = m;
		while (i > 0 || j > 0) {
			if (i > 0 && j > 0) {
				boolean match = typedCmp.charAt(i - 1) == expectedCmp.charAt(j - 1);
				int cost = match ? 0 : 1;
				if (dp[i][j] == dp[i - 1][j - 1] + cost) {
					reversed.add(new DiffPiece(typed.charAt(i - 1), expected.charAt(j - 1), match));
					i--;
					j--;
					continue;
				}
			}
			if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
				reversed.add(new DiffPiece(typed.charAt(i - 1), null, false));
				i--;
				continue;
			}
			reversed.add(new DiffPiece(null, expected.charAt(j - 1), false));
			j--;
		}
		java.util.Collections.reverse(reversed);
		return reversed;
	}

	private static void appendColored(SpannableStringBuilder out, CharSequence text, int color, boolean bold) {
		int start = out.length();
		out.append(text);
		int end = out.length();
		if (end <= start) return;
		out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		if (bold) {
			out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	private CharSequence buildAnswerComparison(String typed, String fullAnswer, boolean neutralBlank) {
		String expected = closestAnswerAlternative(typed, fullAnswer);
		java.util.List<DiffPiece> pieces = alignAnswer(typed, expected);
		SpannableStringBuilder out = new SpannableStringBuilder();

		appendColored(out, "You:  ", ANSWER_NEUTRAL_COLOR, true);
		if (typed == null || typed.trim().isEmpty()) {
			appendColored(out, "(blank)", neutralBlank ? ANSWER_NEUTRAL_COLOR : ANSWER_BAD_COLOR, false);
		} else {
			for (DiffPiece piece : pieces) {
				if (piece.typed == null) continue;
				appendColored(out, String.valueOf(piece.typed),
						piece.match ? ANSWER_GOOD_COLOR : ANSWER_BAD_COLOR,
						!piece.match);
			}
		}

		out.append("\n");
		appendColored(out, "Answer:  ", ANSWER_NEUTRAL_COLOR, true);
		for (DiffPiece piece : pieces) {
			if (piece.expected == null) continue;
			appendColored(out, String.valueOf(piece.expected),
					piece.match ? ANSWER_GOOD_COLOR : ANSWER_MISSED_COLOR,
					!piece.match);
		}
		return out;
	}

	private boolean sameCard(AnkiCombatReviewClient.ReviewCard a, AnkiCombatReviewClient.ReviewCard b) {
		return a != null && b != null && a.noteId == b.noteId && a.cardOrd == b.cardOrd;
	}

	private void beginAnkiQuestion(AnkiCombatReviewClient.ReviewCard card) {
		ankiSession.bypassForCurrentTurn = false;
		ankiSession.card = card;
		ankiSession.resetAttempt();
		card.shownAtMs = System.currentTimeMillis();
		ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.QUESTION);
		controllers.combatController.setAnkiQuizGateActive(true);
		syncAnkiUiFromSession(false);
	}

	private void loadAnkiCardForPlayerTurn() {
		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;
		if (ankiSession.bypassForCurrentTurn) {
			controllers.combatController.setAnkiQuizGateActive(false);
			ankiQuiz.setVisibility(View.GONE);
			actionBar.setVisibility(View.VISIBLE);
			return;
		}

		if (ankiSession.retryActive && ankiSession.retryCard != null
				&& ankiSession.phase == CombatController.AnkiCombatSession.Phase.IDLE) {
			beginAnkiQuestion(ankiSession.retryCard);
			return;
		}

		if (ankiSession.phase != CombatController.AnkiCombatSession.Phase.IDLE) {
			controllers.combatController.setAnkiQuizGateActive(true);
			syncAnkiUiFromSession(false);
			return;
		}

		final long operation = ++ankiSession.operationId;
		ankiSession.errorMessage = "";
		ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.LOADING);
		controllers.combatController.setAnkiQuizGateActive(true);
		syncAnkiUiFromSession(false);

		final android.content.ContentResolver resolver = getContext().getApplicationContext().getContentResolver();
		new Thread(() -> {
			AnkiCombatReviewClient.ReviewCard loaded = null;
			Exception failure = null;
			try {
				loaded = ankiClient.getNextCard(resolver);
			} catch (Exception e) {
				failure = e;
			}

			final AnkiCombatReviewClient.ReviewCard result = loaded;
			final Exception error = failure;
			ankiMainHandler.post(() -> {
				if (operation != ankiSession.operationId
						|| ankiSession.phase != CombatController.AnkiCombatSession.Phase.LOADING) {
					return;
				}

				if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) {
					ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
					return;
				}

				if (error != null) {
					String detail = error.getMessage();
					if (error instanceof SecurityException) {
						detail = "AnkiDroid Retry provider access was denied. Install/update the compatible AnkiDroid Retry build.";
					}
					fallbackToNormalCombat("Could not load Anki card: " + detail);
					return;
				}

				if (result == null) {
					fallbackToNormalCombat("No Anki cards are due.");
				} else {
					beginAnkiQuestion(result);
				}
			});
		}, "AnkiCombatCardLoad").start();
	}

	private void setInputTextWithoutWatcher(String text) {
		String desired = text == null ? "" : text;
		if (desired.contentEquals(ankiInput.getText())) return;
		suppressAnkiTextWatcher = true;
		ankiInput.setText(desired);
		ankiInput.setSelection(ankiInput.length());
		suppressAnkiTextWatcher = false;
	}

	private void hideAnkiKeyboardAndClearFocus() {
		ankiInput.clearFocus();
		InputMethodManager imm =
				(InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.hideSoftInputFromWindow(ankiInput.getWindowToken(), 0);
		}
	}

	private void hideKeyboardIfQuizInputNotVisible() {
		if (ankiQuiz.getVisibility() != View.VISIBLE || ankiInput.getVisibility() != View.VISIBLE) {
			hideAnkiKeyboardAndClearFocus();
		}
	}

	/**
	 * Make combat typing frictionless. A newly shown question (including after
	 * rotation/retry) should immediately own focus and reopen the Android IME.
	 * Do not continuously force it back open if the user manually dismisses it;
	 * only reacquire focus when the field actually lost focus.
	 */
	private void focusAnkiInputAndShowKeyboard() {
		if (ankiSession.phase != CombatController.AnkiCombatSession.Phase.QUESTION) return;
		if (ankiInput.getVisibility() != View.VISIBLE) return;

		ankiInput.setEnabled(true);
		ankiInput.setFocusable(true);
		ankiInput.setFocusableInTouchMode(true);

		if (ankiInput.hasFocus()) return;

		ankiInput.postDelayed(() -> {
			if (!isAttachedToWindow()
					|| ankiSession.phase != CombatController.AnkiCombatSession.Phase.QUESTION
					|| ankiInput.getVisibility() != View.VISIBLE) {
				return;
			}

			ankiInput.requestFocus();
			ankiInput.setSelection(ankiInput.length());
			InputMethodManager imm =
					(InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				imm.showSoftInput(ankiInput, InputMethodManager.SHOW_IMPLICIT);
			}
		}, 80);
	}

	private void prepareQuizFrame() {
		controllers.combatController.setAnkiQuizGateActive(true);
		actionBar.setVisibility(View.GONE);
		monsterActionText.setVisibility(View.GONE);
		ankiQuiz.setVisibility(View.VISIBLE);
		renderPreviousRatingIndicator();
		ankiRecover.setVisibility(View.GONE);
	}

	private void renderLoadingState() {
		prepareQuizFrame();
		ankiStatus.setText(ankiSession.errorMessage.isEmpty() ? "Loading Anki card…" : ankiSession.errorMessage);
		ankiQuestion.setText("");
		ankiInput.setVisibility(View.GONE);
		hideKeyboardIfQuizInputNotVisible();
		ankiReveal.setVisibility(View.GONE);
		ankiFeedback.setVisibility(View.GONE);
		ankiAnswer.setVisibility(View.GONE);
		ankiRatings.setVisibility(View.GONE);
		if (!ankiSession.errorMessage.isEmpty()
				|| System.currentTimeMillis() - ankiSession.phaseStartedAt > 5000) {
			ankiRecover.setVisibility(View.VISIBLE);
		}
	}

	private void renderQuestionState() {
		AnkiCombatReviewClient.ReviewCard card = ankiSession.card;
		if (card == null) {
			ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
			loadAnkiCardForPlayerTurn();
			return;
		}

		prepareQuizFrame();
		ankiStatus.setText("");
		ankiStatus.setVisibility(View.GONE);
		ankiQuestion.setText(card.question);
		setInputTextWithoutWatcher(ankiSession.typedText);
		ankiInput.setEnabled(true);
		ankiInput.setVisibility(View.VISIBLE);
		ankiReveal.setEnabled(true);
		ankiReveal.setVisibility(View.VISIBLE);
		ankiFeedback.setVisibility(View.GONE);
		ankiAnswer.setVisibility(View.GONE);
		ankiRatings.setVisibility(View.GONE);
		setRatingButtonsEnabled(true);

		setRatingLabel(ankiAgain, "Again", card.nextIntervals, 0);
		setRatingLabel(ankiHard, "Hard", card.nextIntervals, 1);
		setRatingLabel(ankiGood, "Good", card.nextIntervals, 2);
		setRatingLabel(ankiEasy, "Easy", card.nextIntervals, 3);

		focusAnkiInputAndShowKeyboard();

		if (!ankiSession.errorMessage.isEmpty()) {
			ankiStatus.setText(ankiSession.errorMessage);
			ankiStatus.setVisibility(View.VISIBLE);
			ankiRecover.setVisibility(View.VISIBLE);
		}
	}

	private void renderAnswerState(boolean submitting) {
		AnkiCombatReviewClient.ReviewCard card = ankiSession.card;
		if (card == null) {
			ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
			loadAnkiCardForPlayerTurn();
			return;
		}

		prepareQuizFrame();
		ankiQuestion.setText(card.question);
		setInputTextWithoutWatcher(ankiSession.typedText);
		// Keep the EditText enabled/focused so Android does not dismiss the keyboard
		// between reveal -> rating -> next combat question. Changes made after reveal
		// are ignored because the attempt result has already been captured.
		ankiInput.setEnabled(true);
		ankiInput.setVisibility(View.VISIBLE);
		ankiReveal.setVisibility(View.GONE);

		// The highlighted comparison itself is the feedback. Keep the old feedback
		// view hidden; the integrated game UI does not need separate result labels.
		ankiFeedback.setText("");
		ankiFeedback.setVisibility(View.GONE);
		ankiAnswer.setText(buildAnswerComparison(
				ankiSession.typedText,
				card.answer,
				ankiSession.neutralFirstExposure));
		ankiAnswer.setVisibility(View.VISIBLE);
		ankiRatings.setVisibility(View.VISIBLE);

		boolean correctRetry =
				ankiSession.typedCorrect
						&& ankiSession.retryActive
						&& sameCard(ankiSession.retryCard, card);
		int againIndex = 0;
		setRatingLabel(ankiAgain, "Again", card.nextIntervals, againIndex);
		setRatingLabel(ankiHard, "Hard", card.nextIntervals, correctRetry ? againIndex : 1);
		setRatingLabel(ankiGood, "Good", card.nextIntervals, correctRetry ? againIndex : 2);
		setRatingLabel(ankiEasy, "Easy", card.nextIntervals, correctRetry ? againIndex : 3);

		setRatingButtonsEnabled(!submitting);

		if (submitting) {
			ankiStatus.setText("Saving…");
			ankiStatus.setVisibility(View.VISIBLE);
			if (System.currentTimeMillis() - ankiSession.phaseStartedAt > 30000) {
				ankiRecover.setVisibility(View.VISIBLE);
			}
		} else if (!ankiSession.errorMessage.isEmpty()) {
			ankiStatus.setText(ankiSession.errorMessage);
			ankiStatus.setVisibility(View.VISIBLE);
			ankiRecover.setVisibility(View.VISIBLE);
		} else {
			ankiStatus.setText("");
			ankiStatus.setVisibility(View.GONE);
		}
	}

	private void syncAnkiUiFromSession(boolean allowLoad) {
		if (!isAttachedToWindow()) return;

		if (!world.model.uiSelections.isInCombat) {
			ankiQuiz.setVisibility(View.GONE);
			hideAnkiKeyboardAndClearFocus();
			return;
		}

		if (!world.model.uiSelections.isPlayersCombatTurn) {
			ankiQuiz.setVisibility(View.GONE);
			hideAnkiKeyboardAndClearFocus();
			return;
		}

		switch (ankiSession.phase) {
			case IDLE:
				controllers.combatController.setAnkiQuizGateActive(false);
				ankiQuiz.setVisibility(View.GONE);
				hideAnkiKeyboardAndClearFocus();
				actionBar.setVisibility(View.VISIBLE);
				if (allowLoad && !ankiSession.bypassForCurrentTurn) loadAnkiCardForPlayerTurn();
				break;
			case LOADING:
				renderLoadingState();
				break;
			case QUESTION:
				renderQuestionState();
				break;
			case ANSWER:
				renderAnswerState(false);
				break;
			case SUBMITTING:
				renderAnswerState(true);
				break;
		}
	}

	private void setRatingLabel(Button button, String name, String[] intervals, int index) {
		String interval = intervals != null && index < intervals.length ? intervals[index] : "";
		SpannableStringBuilder label = new SpannableStringBuilder();
		int nameStart = label.length();
		label.append(name);
		label.setSpan(new StyleSpan(Typeface.BOLD), nameStart, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		if (interval != null && !interval.isEmpty()) {
			label.append("\n");
			int intervalStart = label.length();
			label.append(interval);
			label.setSpan(new RelativeSizeSpan(0.78f), intervalStart, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		button.setText(label);
	}

	private void revealAnkiAnswer() {
		if (ankiSession.phase != CombatController.AnkiCombatSession.Phase.QUESTION
				|| ankiSession.card == null) return;

		ankiSession.typedText = ankiInput.getText().toString();
		boolean retry = ankiSession.retryActive && sameCard(ankiSession.retryCard, ankiSession.card);

		ankiSession.neutralFirstExposure =
				!retry && ankiSession.card.reps == 0 && ankiSession.typedText.trim().isEmpty();
		ankiSession.typedCorrect =
				!ankiSession.neutralFirstExposure
						&& ankiClient.isCorrect(ankiSession.typedText, ankiSession.card.answer);
		ankiSession.errorMessage = "";
		ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.ANSWER);
		syncAnkiUiFromSession(false);
	}

	private void setRatingButtonsEnabled(boolean enabled) {
		ankiAgain.setEnabled(enabled);
		ankiHard.setEnabled(enabled);
		ankiGood.setEnabled(enabled);
		ankiEasy.setEnabled(enabled);
	}

	private void chooseAnkiRating(int tappedEase) {
		if (ankiSession.phase != CombatController.AnkiCombatSession.Phase.ANSWER
				|| ankiSession.card == null) return;

		final AnkiCombatReviewClient.ReviewCard card = ankiSession.card;
		final boolean retry = ankiSession.retryActive && sameCard(ankiSession.retryCard, card);
		final boolean wasCorrect = ankiSession.typedCorrect;
		final boolean wasNeutral = ankiSession.neutralFirstExposure;

		if (!wasNeutral && !wasCorrect && tappedEase == 1) {
			ankiSession.retryActive = true;
			ankiSession.retryCard = card;
			ankiSession.card = null;
			ankiSession.operationId++;
			ankiSession.resetAttempt();
			ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
			controllers.combatController.skipAnkiQuizTurn();
			return;
		}

		final int effectiveEase = (wasCorrect && retry) ? 1 : tappedEase;
		final long operation = ++ankiSession.operationId;
		ankiSession.pendingTappedEase = tappedEase;
		ankiSession.pendingEffectiveEase = effectiveEase;
		ankiSession.pendingShouldAttack = wasCorrect;
		ankiSession.errorMessage = "";
		ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.SUBMITTING);
		lastSubmissionProbeOperation = -1L;
		syncAnkiUiFromSession(false);

		final android.content.ContentResolver resolver = getContext().getApplicationContext().getContentResolver();
		new Thread(() -> {
			boolean saved = false;
			Exception failure = null;
			try {
				saved = ankiClient.answerCard(resolver, card, effectiveEase);
			} catch (Exception e) {
				failure = e;
			}

			final boolean success = saved;
			final Exception error = failure;
			ankiMainHandler.post(() -> {
				if (operation != ankiSession.operationId
						|| ankiSession.phase != CombatController.AnkiCombatSession.Phase.SUBMITTING) {
					return;
				}

				if (success) {
					finishSubmittedReview(operation);
				} else {
					ankiSession.errorMessage = error == null
							? "AnkiDroid did not accept the rating. You can tap a rating again."
							: "Could not save rating: " + error.getMessage();
					ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.ANSWER);
					syncAnkiUiFromSession(false);
				}
			});
		}, "AnkiCombatRatingSave").start();
	}

	private void finishSubmittedReview(long operation) {
		if (operation != ankiSession.operationId) return;
		boolean shouldAttack = ankiSession.pendingShouldAttack;
		int recordedEase = ankiSession.pendingEffectiveEase;
		controllers.combatController.setLastAnkiRecordedEase(recordedEase);
		ankiSession.resetAll();
		// Prevent the watchdog from loading another card in the small window before
		// the attack/skip-turn transition completes.
		ankiSession.bypassForCurrentTurn = true;

		if (!world.model.uiSelections.isInCombat) return;
		if (shouldAttack) {
			controllers.combatController.executeAnkiQuizAttack();
		} else if (world.model.uiSelections.isPlayersCombatTurn) {
			controllers.combatController.skipAnkiQuizTurn();
		}
	}

	private String ratingName(int ease) {
		switch (ease) {
			case 1: return "Again";
			case 2: return "Hard";
			case 3: return "Good";
			case 4: return "Easy";
			default: return "?";
		}
	}

	private void probeStalledSubmission() {
		if (ankiSession.phase != CombatController.AnkiCombatSession.Phase.SUBMITTING
				|| ankiSession.card == null) return;

		final long operation = ankiSession.operationId;
		if (lastSubmissionProbeOperation == operation) return;
		lastSubmissionProbeOperation = operation;
		final AnkiCombatReviewClient.ReviewCard card = ankiSession.card;
		final android.content.ContentResolver resolver = getContext().getApplicationContext().getContentResolver();

		new Thread(() -> {
			int reps = -1;
			try {
				reps = ankiClient.getCardReps(resolver, card);
			} catch (Exception ignored) {
			}
			final int currentReps = reps;
			ankiMainHandler.post(() -> {
				if (operation != ankiSession.operationId
						|| ankiSession.phase != CombatController.AnkiCombatSession.Phase.SUBMITTING) {
					return;
				}

				if (currentReps > card.reps) {
					finishSubmittedReview(operation);
				} else if (currentReps >= 0) {
					// We could verify that the review was not committed, so make the UI usable again.
					ankiSession.operationId++;
					ankiSession.errorMessage = "The rating save timed out and was not recorded. Please choose a rating again.";
					ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.ANSWER);
					syncAnkiUiFromSession(false);
				} else {
					ankiSession.errorMessage = "Still waiting for AnkiDroid. Tap Recover quiz to check again.";
					lastSubmissionProbeOperation = -1L;
					syncAnkiUiFromSession(false);
				}
			});
		}, "AnkiCombatRatingProbe").start();
	}

	private void recoverAnkiQuiz() {
		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.SUBMITTING) {
			long age = System.currentTimeMillis() - ankiSession.phaseStartedAt;
			if (age < 30000) {
				Toast.makeText(getContext(), "The rating is still being saved.", Toast.LENGTH_SHORT).show();
				return;
			}
			lastSubmissionProbeOperation = -1L;
			probeStalledSubmission();
			return;
		}

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.LOADING) {
			ankiSession.operationId++;
			ankiSession.errorMessage = "";
			ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
			loadAnkiCardForPlayerTurn();
			return;
		}

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.IDLE) {
			loadAnkiCardForPlayerTurn();
		} else {
			syncAnkiUiFromSession(false);
		}
	}

	private void runAnkiHealthCheck() {
		if (!isAttachedToWindow()) return;

		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) {
			return;
		}

		long age = System.currentTimeMillis() - ankiSession.phaseStartedAt;

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.IDLE) {
			if (!ankiSession.bypassForCurrentTurn) loadAnkiCardForPlayerTurn();
			return;
		}

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.LOADING && age > 12000) {
			// Card loading is read-only, so invalidating and retrying it is safe.
			ankiSession.operationId++;
			ankiSession.errorMessage = "Card loading stalled; retrying…";
			ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
			loadAnkiCardForPlayerTurn();
			return;
		}

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.SUBMITTING && age > 30000) {
			probeStalledSubmission();
		}

		// This also restores a panel that disappeared because of rotation or another UI action.
		syncAnkiUiFromSession(false);
	}

	private void fallbackToNormalCombat(String message) {
		controllers.combatController.resetAnkiQuizAndUnlockCombat();
		ankiSession.bypassForCurrentTurn = true;
		ankiQuiz.setVisibility(View.GONE);
		hideAnkiKeyboardAndClearFocus();
		actionBar.setVisibility(View.VISIBLE);
		setRatingButtonsEnabled(true);
		if (message != null && !message.isEmpty()) {
			Toast.makeText(getContext(), message + " Normal combat enabled.", Toast.LENGTH_LONG).show();
		}
	}

	private void toggleConditionsBarVisibility() {
		conditionsBarToggled = !conditionsBarToggled;
		if (conditionsBarToggled) showConditionsBar();
		else hideConditionsBar();
	}
	
	private void updateTurnInfo(Monster currentActiveMonster) {
		if (currentActiveMonster != null) {
			ankiQuiz.setVisibility(View.GONE);
			hideAnkiKeyboardAndClearFocus();
			actionBar.setVisibility(View.INVISIBLE);
			monsterActionText.setVisibility(View.VISIBLE);
			monsterActionText.setText(res.getString(R.string.combat_monsteraction, currentActiveMonster.getName()));
		} else {
			actionBar.setVisibility(
					ankiSession.phase == CombatController.AnkiCombatSession.Phase.IDLE
							? View.VISIBLE
							: View.GONE);
			monsterActionText.setVisibility(View.GONE);
		}
	}

	private void updateMonsterHealth(Monster m) {
		monsterHealth.update(m.getMaxHP(), m.getCurrentHP());
	}
	private void updatePlayerAP() {
		playerAPBar.update(player.getMaxAP(), player.getCurrentAP());
		updateAttackMoveButtonText();
	}
	private void updateSelectedMonster(Monster selectedMonster) {
		if (currentMonster != null && currentMonster == selectedMonster) return;

		attackMoveButton.setEnabled(true);
		monsterBar.setVisibility(View.INVISIBLE);
		currentMonster = null;
		if (selectedMonster != null) {
			monsterBar.setVisibility(View.VISIBLE);
			world.tileManager.setImageViewTile(res, monsterInfo, selectedMonster, world.model.currentMaps.tiles);
			updateMonsterHealth(selectedMonster);
			currentMonster = selectedMonster;
		}
		updateAttackMoveButtonText(selectedMonster != null);
		updateConditions();
	}

	private void updateConditions() {
		activeConditions.setTarget(currentMonster);
		if (currentMonster == null) {
			hideConditionsButton();
			return;
		}
		int condCount = currentMonster.conditions.size() + currentMonster.immunities.size();
		if(condCount > 0) {
			// If there are both conditions and immunities, prefer showing the condition type on the button.
			// Only treat the icon as an immunity when the displayed type actually comes from immunities.
			boolean showingImmunity = currentMonster.conditions.isEmpty();
			ActorConditionType condType = showingImmunity ? currentMonster.immunities.get(0).conditionType : currentMonster.conditions.get(0).conditionType;
			world.tileManager.setImageViewTile(getContext(), monsterConditionsButton, condType, showingImmunity, res.getString(R.string.monstercondition_icon_count, condCount), null);
			showConditionsButton();
			if (conditionsBarToggled) showConditionsBar();
		} else {
			hideConditionsButton();
			hideConditionsBar();
		}
	}
	
	private void showConditionsButton() {
		if (monsterConditionsButton.getVisibility() != View.VISIBLE) {
			monsterConditionsButton.setVisibility(View.VISIBLE);
			if (preferences.enableUiAnimations) {
				monsterConditionsButton.startAnimation(displayConditionsButtonAnimation);
			}
		}
	}

	private void hideConditionsButton() {
		if (monsterConditionsButton.getVisibility() == View.VISIBLE) {
			if (preferences.enableUiAnimations) {
				monsterConditionsButton.startAnimation(hideConditionsButtonAnimation);
			} else {
				monsterConditionsButton.setVisibility(View.GONE);
			}
		}
	}
	
	@SuppressLint("NewApi")
	private void showConditionsBar() {
		if (activeConditionsBar.getVisibility() != View.VISIBLE) {
			activeConditionsBar.setVisibility(View.VISIBLE);
			if (preferences.enableUiAnimations) {
				activeConditionsBar.startAnimation(displayConditionsBarAnimation);
			}
		}
	}

	
	@SuppressLint("NewApi")
	private void hideConditionsBar() {
		if (activeConditionsBar.getVisibility() == View.VISIBLE) {
			if (preferences.enableUiAnimations) {
				activeConditionsBar.startAnimation(hideConditionsBarAnimation);
			} else {
				activeConditionsBar.setVisibility(View.GONE);
			}
		}
	}
	
	private void updateAttackMoveButtonText() {
		updateAttackMoveButtonText(world.model.uiSelections.selectedMonster != null);
	}
	private void updateAttackMoveButtonText(boolean hasSelectedMonster) {
		if (hasSelectedMonster) {
			attackMoveButton.setText(res.getString(R.string.combat_attack, player.getAttackCost()));
		} else {
			attackMoveButton.setText(res.getString(R.string.combat_move, player.getMoveCost()));
		}
	}

	public void updateStatus() {
		updatePlayerAP();
		updateSelectedMonster(world.model.uiSelections.selectedMonster);
		syncAnkiUiFromSession(false);
	}

	private void show() {
		updateStatus();
		setVisibility(View.VISIBLE);
		bringToFront();
		if (preferences.enableUiAnimations) {
			startAnimation(displayAnimation);
		}
	}

	private void hide() {
		if (preferences.enableUiAnimations) {
			startAnimation(hideAnimation);
		} else {
			setVisibility(View.GONE);
		}
	}

	public void subscribe() {
		controllers.combatController.combatSelectionListeners.add(this);
		controllers.combatController.combatTurnListeners.add(this);
		controllers.actorStatsController.actorStatsListeners.add(this);
		controllers.actorStatsController.actorConditionListeners.add(this);
		activeConditions.subscribe();
		ankiMainHandler.removeCallbacks(ankiHealthCheck);
		ankiMainHandler.post(ankiHealthCheck);
		syncAnkiUiFromSession(true);
	}
	public void unsubscribe() {
		ankiMainHandler.removeCallbacks(ankiHealthCheck);
		controllers.actorStatsController.actorStatsListeners.remove(this);
		controllers.combatController.combatTurnListeners.remove(this);
		controllers.combatController.combatSelectionListeners.remove(this);
		controllers.actorStatsController.actorConditionListeners.remove(this);
		activeConditions.unsubscribe();
	}

	@Override
	public void onMonsterSelected(Monster m, Coord selectedPosition, Coord previousSelection) {
		updateSelectedMonster(m);
	}

	@Override
	public void onMovementDestinationSelected(Coord selectedPosition, Coord previousSelection) {
		updateSelectedMonster(null);
	}

	@Override
	public void onCombatSelectionCleared(Coord previousSelection) {
		updateSelectedMonster(null);
	}

	@Override
	public void onCombatStarted() {
		show();
		updateTurnInfo(null);
		syncAnkiUiFromSession(true);
	}

	@Override
	public void onCombatEnded() {
		ankiQuiz.setVisibility(View.GONE);
		hideAnkiKeyboardAndClearFocus();
		setRatingButtonsEnabled(true);
		hide();
	}

	@Override
	public void onNewPlayerTurn() {
		ankiSession.bypassForCurrentTurn = false;
		updateTurnInfo(null);
		loadAnkiCardForPlayerTurn();
	}

	@Override
	public void onMonsterIsAttacking(Monster m) {
		ankiQuiz.setVisibility(View.GONE);
		hideAnkiKeyboardAndClearFocus();
		updateTurnInfo(m);
	}

	@Override
	public void onActorHealthChanged(Actor actor) {
		if (actor == currentMonster) updateMonsterHealth(currentMonster);
	}

	@Override
	public void onActorAPChanged(Actor actor) {
		if (actor == player) updatePlayerAP();
	}

	@Override
	public void onActorAttackCostChanged(Actor actor, int newAttackCost) {
		if (actor == player) updateAttackMoveButtonText();
	}

	@Override
	public void onActorMoveCostChanged(Actor actor, int newMoveCost) {
		if (actor == player) updateAttackMoveButtonText();
	}

	@Override
	public void onPlayerReequipCostChanged(Player actor, int newAttackCost) {}

	@Override
	public void onPlayerUseCostChanged(Player actor, int newMoveCost) {}

	@Override
	public void onActorConditionAdded(Actor actor, ActorCondition condition) {
		if (actor == currentMonster) updateConditions();
	}

	@Override
	public void onActorConditionRemoved(Actor actor, ActorCondition condition) {
		if (actor == currentMonster) updateConditions();
	}

	@Override
	public void onActorConditionDurationChanged(Actor actor, ActorCondition condition) {
	}

	@Override
	public void onActorConditionMagnitudeChanged(Actor actor, ActorCondition condition) {
	}

	@Override
	public void onActorConditionRoundEffectApplied(Actor actor, ActorCondition condition) {
	}

	@Override
	public void onActorConditionImmunityAdded(Actor actor, ActorCondition condition) {
		if (actor == currentMonster) updateConditions();
	}

	@Override
	public void onActorConditionImmunityRemoved(Actor actor, ActorCondition condition) {
		if (actor == currentMonster) updateConditions();
	}

	@Override
	public void onActorConditionImmunityDurationChanged(Actor actor, ActorCondition condition) {
	}
}
