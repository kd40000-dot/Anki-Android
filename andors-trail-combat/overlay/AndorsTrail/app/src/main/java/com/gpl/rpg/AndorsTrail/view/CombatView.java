package com.gpl.rpg.AndorsTrail.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
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
	public static final int REQUEST_ANKI_PERMISSION = 4817;

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
				ankiMainHandler.postDelayed(this, 600);
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
		ankiRecover = (Button) findViewById(R.id.combatview_anki_recover);

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

	private boolean sameCard(AnkiCombatReviewClient.ReviewCard a, AnkiCombatReviewClient.ReviewCard b) {
		return a != null && b != null && a.noteId == b.noteId && a.cardOrd == b.cardOrd;
	}

	private void beginAnkiQuestion(AnkiCombatReviewClient.ReviewCard card) {
		ankiSession.card = card;
		ankiSession.resetAttempt();
		card.shownAtMs = System.currentTimeMillis();
		ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.QUESTION);
		controllers.combatController.setAnkiQuizGateActive(true);
		syncAnkiUiFromSession(false);
	}

	private void loadAnkiCardForPlayerTurn() {
		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;

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
					if (error instanceof SecurityException && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
						try {
							((Activity) getContext()).requestPermissions(
									new String[]{AnkiCombatReviewClient.PERMISSION},
									REQUEST_ANKI_PERMISSION);
						} catch (Exception ignored) {
						}
					}
					fallbackToNormalCombat("Could not load Anki card: " + error.getMessage());
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

	private void prepareQuizFrame() {
		controllers.combatController.setAnkiQuizGateActive(true);
		actionBar.setVisibility(View.GONE);
		monsterActionText.setVisibility(View.GONE);
		ankiQuiz.setVisibility(View.VISIBLE);
		ankiRecover.setVisibility(View.GONE);
	}

	private void renderLoadingState() {
		prepareQuizFrame();
		ankiStatus.setText(ankiSession.errorMessage.isEmpty() ? "Loading Anki card…" : ankiSession.errorMessage);
		ankiQuestion.setText("");
		ankiInput.setVisibility(View.GONE);
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
		ankiStatus.setText(ankiSession.retryActive && sameCard(ankiSession.retryCard, card)
				? "Retry: get it right to attack. Any rating after a correct retry records Again."
				: "Anki combat");
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

		if (!ankiSession.errorMessage.isEmpty()) {
			ankiStatus.setText(ankiSession.errorMessage);
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
		ankiInput.setEnabled(false);
		ankiInput.setVisibility(View.VISIBLE);
		ankiReveal.setVisibility(View.GONE);

		if (ankiSession.neutralFirstExposure) {
			ankiFeedback.setText("First exposure — blank answer is not marked wrong. No attack this turn.");
		} else if (ankiSession.typedCorrect) {
			ankiFeedback.setText("ANKI_CORRECT — choose a rating to attack.");
		} else {
			ankiFeedback.setText("ANKI_WRONG — choose a rating. Your turn will be skipped.");
		}

		ankiFeedback.setVisibility(View.VISIBLE);
		ankiAnswer.setText("Answer: " + card.answer);
		ankiAnswer.setVisibility(View.VISIBLE);
		ankiRatings.setVisibility(View.VISIBLE);

		setRatingLabel(ankiAgain, "Again", card.nextIntervals, 0);
		setRatingLabel(ankiHard, "Hard", card.nextIntervals, 1);
		setRatingLabel(ankiGood, "Good", card.nextIntervals, 2);
		setRatingLabel(ankiEasy, "Easy", card.nextIntervals, 3);

		setRatingButtonsEnabled(!submitting);

		if (submitting) {
			String tapped = ratingName(ankiSession.pendingTappedEase);
			String effective = ratingName(ankiSession.pendingEffectiveEase);
			ankiStatus.setText(ankiSession.pendingTappedEase != ankiSession.pendingEffectiveEase
					? "Saving " + effective + " (you tapped " + tapped + ")…"
					: "Saving " + effective + "…");
			if (System.currentTimeMillis() - ankiSession.phaseStartedAt > 8000) {
				ankiRecover.setVisibility(View.VISIBLE);
			}
		} else if (!ankiSession.errorMessage.isEmpty()) {
			ankiStatus.setText(ankiSession.errorMessage);
			ankiRecover.setVisibility(View.VISIBLE);
		} else if (ankiSession.retryActive && sameCard(ankiSession.retryCard, card)) {
			ankiStatus.setText(ankiSession.typedCorrect
					? "Correct retry: every button will be recorded as Again."
					: "Retry attempt");
		} else {
			ankiStatus.setText("Anki combat");
		}
	}

	private void syncAnkiUiFromSession(boolean allowLoad) {
		if (!isAttachedToWindow()) return;

		if (!world.model.uiSelections.isInCombat) {
			ankiQuiz.setVisibility(View.GONE);
			return;
		}

		if (!world.model.uiSelections.isPlayersCombatTurn) {
			ankiQuiz.setVisibility(View.GONE);
			return;
		}

		switch (ankiSession.phase) {
			case IDLE:
				controllers.combatController.setAnkiQuizGateActive(false);
				ankiQuiz.setVisibility(View.GONE);
				actionBar.setVisibility(View.VISIBLE);
				if (allowLoad) loadAnkiCardForPlayerTurn();
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
		button.setText(interval == null || interval.isEmpty() ? name : name + "\n" + interval);
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
		ankiSession.resetAll();

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
			loadAnkiCardForPlayerTurn();
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

		if (ankiSession.phase == CombatController.AnkiCombatSession.Phase.SUBMITTING && age > 15000) {
			probeStalledSubmission();
		}

		// This also restores a panel that disappeared because of rotation or another UI action.
		syncAnkiUiFromSession(false);
	}

	private void fallbackToNormalCombat(String message) {
		controllers.combatController.resetAnkiQuizAndUnlockCombat();
		ankiQuiz.setVisibility(View.GONE);
		actionBar.setVisibility(View.VISIBLE);
		setRatingButtonsEnabled(true);
		if (message != null && !message.isEmpty()) {
			Toast.makeText(getContext(), message + " Normal combat enabled.", Toast.LENGTH_LONG).show();
		}
	}

	public void onAnkiPermissionResult(boolean granted) {
		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;
		// The custom AnkiDroid build also has a package allowlist, so try the provider again
		// even if Android's custom-permission dialog reports an unexpected result.
		ankiSession.operationId++;
		ankiSession.setPhase(CombatController.AnkiCombatSession.Phase.IDLE);
		loadAnkiCardForPlayerTurn();
	}

	private void toggleConditionsBarVisibility() {
		conditionsBarToggled = !conditionsBarToggled;
		if (conditionsBarToggled) showConditionsBar();
		else hideConditionsBar();
	}
	
	private void updateTurnInfo(Monster currentActiveMonster) {
		if (currentActiveMonster != null) {
			ankiQuiz.setVisibility(View.GONE);
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
		setRatingButtonsEnabled(true);
		hide();
	}

	@Override
	public void onNewPlayerTurn() {
		updateTurnInfo(null);
		loadAnkiCardForPlayerTurn();
	}

	@Override
	public void onMonsterIsAttacking(Monster m) {
		ankiQuiz.setVisibility(View.GONE);
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
