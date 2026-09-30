package com.gpl.rpg.AndorsTrail.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
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
	private final AnkiCombatReviewClient ankiClient = new AnkiCombatReviewClient();

	private AnkiCombatReviewClient.ReviewCard currentAnkiCard;
	private AnkiCombatReviewClient.ReviewCard retryAnkiCard;
	private boolean ankiRetryActive = false;
	private boolean typedCorrect = false;
	private boolean neutralFirstExposure = false;
	private boolean awaitingRating = false;
	private boolean loadingAnkiCard = false;

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

		ankiReveal.setOnClickListener((View v) -> revealAnkiAnswer());
		ankiAgain.setOnClickListener((View v) -> chooseAnkiRating(1));
		ankiHard.setOnClickListener((View v) -> chooseAnkiRating(2));
		ankiGood.setOnClickListener((View v) -> chooseAnkiRating(3));
		ankiEasy.setOnClickListener((View v) -> chooseAnkiRating(4));

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

	private boolean hasAnkiPermission() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
		return getContext().checkSelfPermission(AnkiCombatReviewClient.PERMISSION) == PackageManager.PERMISSION_GRANTED;
	}

	private void loadAnkiCardForPlayerTurn() {
		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;

		controllers.combatController.setAnkiQuizGateActive(true);
		actionBar.setVisibility(View.GONE);
		monsterActionText.setVisibility(View.GONE);
		ankiQuiz.setVisibility(View.VISIBLE);

		if (ankiRetryActive && retryAnkiCard != null) {
			showAnkiCard(retryAnkiCard, true);
			return;
		}

		if (!hasAnkiPermission()) {
			ankiStatus.setText("Allow Andor's Trail to access AnkiDroid cards.");
			ankiQuestion.setText("");
			ankiInput.setVisibility(View.GONE);
			ankiReveal.setVisibility(View.GONE);
			ankiRatings.setVisibility(View.GONE);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				((Activity) getContext()).requestPermissions(
						new String[]{AnkiCombatReviewClient.PERMISSION},
						REQUEST_ANKI_PERMISSION);
			}
			return;
		}

		if (loadingAnkiCard) return;
		loadingAnkiCard = true;
		ankiStatus.setText("Loading Anki card...");
		ankiQuestion.setText("");
		ankiInput.setVisibility(View.GONE);
		ankiReveal.setVisibility(View.GONE);
		ankiFeedback.setVisibility(View.GONE);
		ankiAnswer.setVisibility(View.GONE);
		ankiRatings.setVisibility(View.GONE);

		new Thread(() -> {
			try {
				AnkiCombatReviewClient.ReviewCard card =
						ankiClient.getNextCard(getContext().getContentResolver());
				post(() -> {
					loadingAnkiCard = false;
					if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;
					if (card == null) {
						fallbackToNormalCombat("No Anki cards are due.");
					} else {
						showAnkiCard(card, false);
					}
				});
			} catch (Exception e) {
				post(() -> {
					loadingAnkiCard = false;
					fallbackToNormalCombat("Could not load Anki card: " + e.getMessage());
				});
			}
		}).start();
	}

	private void showAnkiCard(AnkiCombatReviewClient.ReviewCard card, boolean retry) {
		currentAnkiCard = card;
		card.shownAtMs = System.currentTimeMillis();
		typedCorrect = false;
		neutralFirstExposure = false;
		awaitingRating = false;

		controllers.combatController.setAnkiQuizGateActive(true);
		actionBar.setVisibility(View.GONE);
		monsterActionText.setVisibility(View.GONE);
		ankiQuiz.setVisibility(View.VISIBLE);
		ankiStatus.setText(retry ? "Retry: get it right to attack. Any rating will record Again." : "Anki combat");
		ankiQuestion.setText(card.question);
		ankiInput.setText("");
		ankiInput.setEnabled(true);
		ankiInput.setVisibility(View.VISIBLE);
		ankiReveal.setEnabled(true);
		ankiReveal.setVisibility(View.VISIBLE);
		ankiFeedback.setVisibility(View.GONE);
		ankiAnswer.setVisibility(View.GONE);
		ankiRatings.setVisibility(View.GONE);

		setRatingLabel(ankiAgain, "Again", card.nextIntervals, 0);
		setRatingLabel(ankiHard, "Hard", card.nextIntervals, 1);
		setRatingLabel(ankiGood, "Good", card.nextIntervals, 2);
		setRatingLabel(ankiEasy, "Easy", card.nextIntervals, 3);
	}

	private void setRatingLabel(Button button, String name, String[] intervals, int index) {
		String interval = intervals != null && index < intervals.length ? intervals[index] : "";
		button.setText(interval == null || interval.isEmpty() ? name : name + "\n" + interval);
	}

	private void revealAnkiAnswer() {
		if (currentAnkiCard == null || awaitingRating) return;

		String typed = ankiInput.getText().toString();
		boolean retry = ankiRetryActive && retryAnkiCard == currentAnkiCard;

		// Preserve the user's "blank is not ANKI_WRONG on first-ever exposure" rule.
		neutralFirstExposure = !retry && currentAnkiCard.reps == 0 && typed.trim().isEmpty();
		typedCorrect = !neutralFirstExposure && ankiClient.isCorrect(typed, currentAnkiCard.answer);
		awaitingRating = true;

		if (neutralFirstExposure) {
			ankiFeedback.setText("First exposure — blank answer is not marked wrong. No attack this turn.");
		} else if (typedCorrect) {
			ankiFeedback.setText("Correct — choose a rating to attack.");
		} else {
			ankiFeedback.setText("Incorrect — choose a rating. Your turn will be skipped.");
		}

		ankiAnswer.setText("Answer: " + currentAnkiCard.answer);
		ankiInput.setEnabled(false);
		ankiReveal.setVisibility(View.GONE);
		ankiFeedback.setVisibility(View.VISIBLE);
		ankiAnswer.setVisibility(View.VISIBLE);
		ankiRatings.setVisibility(View.VISIBLE);
	}

	private void setRatingButtonsEnabled(boolean enabled) {
		ankiAgain.setEnabled(enabled);
		ankiHard.setEnabled(enabled);
		ankiGood.setEnabled(enabled);
		ankiEasy.setEnabled(enabled);
	}

	private void chooseAnkiRating(int tappedEase) {
		if (!awaitingRating || currentAnkiCard == null) return;

		final AnkiCombatReviewClient.ReviewCard card = currentAnkiCard;
		final boolean retry = ankiRetryActive && retryAnkiCard == card;
		final boolean wasCorrect = typedCorrect;
		final boolean wasNeutral = neutralFirstExposure;

		// Wrong + Again means "retry next player turn". Do not write anything to Anki yet,
		// which guarantees the eventual card review produces only one scheduler entry.
		if (!wasNeutral && !wasCorrect && tappedEase == 1) {
			ankiRetryActive = true;
			retryAnkiCard = card;
			awaitingRating = false;
			currentAnkiCard = null;
			ankiStatus.setText("Again selected — retry this card next turn.");
			controllers.combatController.skipAnkiQuizTurn();
			return;
		}

		final int effectiveEase = (wasCorrect && retry) ? 1 : tappedEase;
		setRatingButtonsEnabled(false);
		ankiStatus.setText(effectiveEase != tappedEase
				? "Recording " + ratingName(effectiveEase) + " (you tapped " + ratingName(tappedEase) + ")..."
				: "Recording " + ratingName(effectiveEase) + "...");

		new Thread(() -> {
			try {
				boolean saved = ankiClient.answerCard(getContext().getContentResolver(), card, effectiveEase);
				post(() -> {
					if (!saved) {
						setRatingButtonsEnabled(true);
						ankiStatus.setText("AnkiDroid did not accept the rating. Try again.");
						return;
					}

					if (retry || (!wasCorrect && tappedEase != 1)) {
						ankiRetryActive = false;
						retryAnkiCard = null;
					}
					currentAnkiCard = null;
					awaitingRating = false;

					if (wasCorrect) {
						controllers.combatController.executeAnkiQuizAttack();
					} else {
						controllers.combatController.skipAnkiQuizTurn();
					}
				});
			} catch (Exception e) {
				post(() -> {
					setRatingButtonsEnabled(true);
					ankiStatus.setText("Could not save rating: " + e.getMessage());
				});
			}
		}).start();
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

	private void fallbackToNormalCombat(String message) {
		currentAnkiCard = null;
		awaitingRating = false;
		controllers.combatController.setAnkiQuizGateActive(false);
		ankiQuiz.setVisibility(View.GONE);
		actionBar.setVisibility(View.VISIBLE);
		if (message != null && !message.isEmpty()) {
			Toast.makeText(getContext(), message + " Normal combat enabled.", Toast.LENGTH_LONG).show();
		}
	}

	public void onAnkiPermissionResult(boolean granted) {
		if (!world.model.uiSelections.isInCombat || !world.model.uiSelections.isPlayersCombatTurn) return;
		if (granted) {
			loadAnkiCardForPlayerTurn();
		} else {
			fallbackToNormalCombat("AnkiDroid permission was denied.");
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
			actionBar.setVisibility(View.INVISIBLE);
			monsterActionText.setVisibility(View.VISIBLE);
			monsterActionText.setText(res.getString(R.string.combat_monsteraction, currentActiveMonster.getName()));
		} else {
			actionBar.setVisibility(View.VISIBLE);
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
	}
	public void unsubscribe() {
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
	}

	@Override
	public void onCombatEnded() {
		controllers.combatController.setAnkiQuizGateActive(false);
		ankiQuiz.setVisibility(View.GONE);
		currentAnkiCard = null;
		retryAnkiCard = null;
		ankiRetryActive = false;
		awaitingRating = false;
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
