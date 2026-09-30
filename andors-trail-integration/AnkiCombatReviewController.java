package com.gpl.rpg.AndorsTrail.view;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gpl.rpg.AndorsTrail.R;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.controller.CombatController;

import org.json.JSONArray;

import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges Andor's Trail combat to the review provider exposed by the custom AnkiDroid Retry build.
 *
 * Combat rules:
 * - Correct first try: chosen Anki ease is recorded, then the player's attack executes.
 * - Wrong + Hard/Good/Easy: chosen ease is recorded and the player's whole turn is skipped.
 * - Wrong + Again: nothing is recorded yet, the whole turn is skipped, and the same card returns
 *   on the next player turn.
 * - Correct after a retry: every ease button records Again, then the player's attack executes.
 *
 * This deliberately defers recording a wrong+Again retry until the card is finally resolved, so
 * each combat card leaves at most one retained Anki review-log entry.
 */
public final class AnkiCombatReviewController {
    private static final String AUTHORITY = "com.ichi2.anki.retry.flashcards";
    private static final String READ_WRITE_PERMISSION = "com.ichi2.anki.retry.permission.READ_WRITE_DATABASE";
    private static final Uri SCHEDULE_URI = Uri.parse("content://" + AUTHORITY + "/schedule/");

    private static final int PERMISSION_REQUEST_CODE = 7319;

    // Match the default behavior of the user's SmarterTypeField setup.
    private static final boolean IGNORE_CASE = true;
    private static final boolean IGNORE_ACCENTS = false;
    private static final boolean IGNORE_PUNCTUATION = false;
    private static final boolean IGNORE_EXTRA_WORDS = false;

    private enum Result {
        CORRECT,
        WRONG,
        NEW_BLANK
    }

    private static final class ReviewCard {
        long noteId;
        int ord;
        int reps;
        String question;
        String answer;
        String[] nextReviewTimes = new String[4];
    }

    private final Activity activity;
    private final CombatController combat;
    private final WorldContext world;
    private final ContentResolver resolver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final LinearLayout panel;
    private final TextView question;
    private final EditText typedAnswer;
    private final Button showAnswer;
    private final TextView answer;
    private final TextView marker;
    private final LinearLayout ratingRow;
    private final Button again;
    private final Button hard;
    private final Button good;
    private final Button easy;

    private ReviewCard card;
    private boolean loading;
    private boolean retryActive;
    private Result result;
    private long attemptStartedAt;

    public AnkiCombatReviewController(Activity activity, CombatController combat, WorldContext world) {
        this.activity = activity;
        this.combat = combat;
        this.world = world;
        this.resolver = activity.getContentResolver();

        panel = activity.findViewById(R.id.combatview_anki_panel);
        question = activity.findViewById(R.id.combatview_anki_question);
        typedAnswer = activity.findViewById(R.id.combatview_anki_input);
        showAnswer = activity.findViewById(R.id.combatview_anki_showanswer);
        answer = activity.findViewById(R.id.combatview_anki_answer);
        marker = activity.findViewById(R.id.combatview_anki_marker);
        ratingRow = activity.findViewById(R.id.combatview_anki_ratingrow);
        again = activity.findViewById(R.id.combatview_anki_again);
        hard = activity.findViewById(R.id.combatview_anki_hard);
        good = activity.findViewById(R.id.combatview_anki_good);
        easy = activity.findViewById(R.id.combatview_anki_easy);

        showAnswer.setOnClickListener(v -> revealAnswer());
        typedAnswer.setOnEditorActionListener((v, actionId, event) -> {
            revealAnswer();
            return true;
        });

        again.setOnClickListener(v -> chooseEase(1));
        hard.setOnClickListener(v -> chooseEase(2));
        good.setOnClickListener(v -> chooseEase(3));
        easy.setOnClickListener(v -> chooseEase(4));

        hidePanel();
    }

    /**
     * @return true when the attack has been intercepted by the Anki combat flow.
     */
    public boolean interceptAttack() {
        if (world.model.uiSelections.selectedMonster == null) return false;
        if (!world.model.uiSelections.isPlayersCombatTurn) return true;

        if (!ensurePermission()) return true;

        if (card != null) {
            if (panel.getVisibility() != View.VISIBLE) showQuestion();
            focusInput();
            return true;
        }

        if (loading) return true;
        loading = true;
        panel.setVisibility(View.VISIBLE);
        question.setText("Loading Anki card…");
        typedAnswer.setVisibility(View.GONE);
        showAnswer.setVisibility(View.GONE);
        answer.setVisibility(View.GONE);
        marker.setVisibility(View.GONE);
        ratingRow.setVisibility(View.GONE);

        executor.execute(() -> {
            ReviewCard loaded = null;
            Exception failure = null;
            try {
                loaded = loadNextCard();
            } catch (Exception e) {
                failure = e;
            }
            final ReviewCard loadedCard = loaded;
            final Exception error = failure;
            activity.runOnUiThread(() -> {
                loading = false;
                if (!world.model.uiSelections.isInCombat) {
                    hidePanel();
                    return;
                }

                if (error != null) {
                    hidePanel();
                    Toast.makeText(activity, "Anki access failed; using normal combat.", Toast.LENGTH_LONG).show();
                    executeFallbackAttack();
                    return;
                }

                if (loadedCard == null) {
                    hidePanel();
                    Toast.makeText(activity, "No Anki cards are due; using normal combat.", Toast.LENGTH_SHORT).show();
                    executeFallbackAttack();
                    return;
                }

                card = loadedCard;
                showQuestion();
            });
        });

        return true;
    }

    public void onCombatStarted() {
        // A fresh combat should never inherit an unresolved card from a previous fight.
        clearCard();
        hidePanel();
    }

    public void onCombatEnded() {
        clearCard();
        hidePanel();
    }

    public void onMonsterTurn() {
        hideKeyboard();
        hidePanel();
    }

    public void onNewPlayerTurn() {
        if (retryActive && card != null && world.model.uiSelections.selectedMonster != null) {
            showQuestion();
        }
    }

    public void onTargetChanged(boolean hasTarget) {
        if (!hasTarget) {
            hideKeyboard();
            hidePanel();
        } else if (retryActive && card != null && world.model.uiSelections.isPlayersCombatTurn) {
            showQuestion();
        }
    }

    private boolean ensurePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (activity.checkSelfPermission(READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true;

        activity.requestPermissions(new String[] { READ_WRITE_PERMISSION }, PERMISSION_REQUEST_CODE);
        Toast.makeText(activity, "Grant AnkiDroid Retry access, then tap Attack again.", Toast.LENGTH_LONG).show();
        return false;
    }

    private void executeFallbackAttack() {
        if (!world.model.uiSelections.isInCombat) return;
        if (!world.model.uiSelections.isPlayersCombatTurn) return;
        if (world.model.uiSelections.selectedMonster == null) return;
        combat.executeMoveAttack(0, 0);
    }

    private void showQuestion() {
        if (card == null) return;

        result = null;
        attemptStartedAt = System.currentTimeMillis();

        panel.setVisibility(View.VISIBLE);
        setHtml(question, card.question);
        typedAnswer.setText("");
        typedAnswer.setVisibility(View.VISIBLE);
        typedAnswer.setEnabled(true);

        showAnswer.setVisibility(View.VISIBLE);
        answer.setText("");
        answer.setVisibility(View.GONE);
        marker.setText("");
        marker.setVisibility(View.GONE);
        ratingRow.setVisibility(View.GONE);

        updateEaseButtonLabels();
        focusInput();
    }

    private void focusInput() {
        typedAnswer.requestFocus();
        typedAnswer.post(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(typedAnswer, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(typedAnswer.getWindowToken(), 0);
    }

    private void revealAnswer() {
        if (card == null || result != null) return;

        String typed = typedAnswer.getText().toString().trim();
        boolean blank = typed.isEmpty();
        boolean correct = !blank && isAccepted(typed, card.answer);

        if (blank && card.reps == 0 && !retryActive) {
            result = Result.NEW_BLANK;
            marker.setText("");
            marker.setVisibility(View.GONE);
        } else if (correct) {
            result = Result.CORRECT;
            marker.setText("ANKI_CORRECT");
            marker.setVisibility(View.VISIBLE);
        } else {
            result = Result.WRONG;
            marker.setText("ANKI_WRONG");
            marker.setVisibility(View.VISIBLE);
        }

        setHtml(answer, card.answer);
        answer.setVisibility(View.VISIBLE);
        typedAnswer.setEnabled(false);
        showAnswer.setVisibility(View.GONE);
        ratingRow.setVisibility(View.VISIBLE);
        hideKeyboard();
    }

    private void chooseEase(int pressedEase) {
        if (card == null || result == null) return;

        if (result == Result.WRONG && pressedEase == 1) {
            // Do not write a scheduler entry yet. Keep the same card for the next player turn.
            retryActive = true;
            result = null;
            hideKeyboard();
            hidePanel();
            combat.endPlayerTurn();
            return;
        }

        final int effectiveEase =
                result == Result.CORRECT && retryActive ? 1 : pressedEase;
        final boolean shouldAttack = result == Result.CORRECT;
        final ReviewCard resolvedCard = card;
        final long timeTaken = Math.max(0L, System.currentTimeMillis() - attemptStartedAt);

        disableRatingButtons();

        executor.execute(() -> {
            boolean success;
            try {
                success = submitAnswer(resolvedCard, effectiveEase, timeTaken);
            } catch (Exception e) {
                success = false;
            }

            final boolean submitted = success;
            activity.runOnUiThread(() -> {
                if (!submitted) {
                    enableRatingButtons();
                    Toast.makeText(activity, "Could not save the Anki review.", Toast.LENGTH_LONG).show();
                    return;
                }

                clearCard();
                hidePanel();

                if (!world.model.uiSelections.isInCombat) return;
                if (shouldAttack) {
                    if (world.model.uiSelections.isPlayersCombatTurn &&
                            world.model.uiSelections.selectedMonster != null) {
                        combat.executeMoveAttack(0, 0);
                    }
                } else if (world.model.uiSelections.isPlayersCombatTurn) {
                    combat.endPlayerTurn();
                }
            });
        });
    }

    private void disableRatingButtons() {
        again.setEnabled(false);
        hard.setEnabled(false);
        good.setEnabled(false);
        easy.setEnabled(false);
    }

    private void enableRatingButtons() {
        again.setEnabled(true);
        hard.setEnabled(true);
        good.setEnabled(true);
        easy.setEnabled(true);
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
    }

    private void clearCard() {
        card = null;
        retryActive = false;
        result = null;
        enableRatingButtons();
    }

    private ReviewCard loadNextCard() throws Exception {
        String[] scheduleProjection = {
                "note_id",
                "ord",
                "button_count",
                "next_review_times"
        };

        long noteId;
        int ord;
        String nextReviewTimes;

        try (Cursor cursor = resolver.query(
                SCHEDULE_URI,
                scheduleProjection,
                "limit=1",
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            noteId = cursor.getLong(cursor.getColumnIndexOrThrow("note_id"));
            ord = cursor.getInt(cursor.getColumnIndexOrThrow("ord"));
            nextReviewTimes = cursor.getString(cursor.getColumnIndexOrThrow("next_review_times"));
        }

        ReviewCard loaded = new ReviewCard();
        loaded.noteId = noteId;
        loaded.ord = ord;

        Uri cardUri = Uri.parse("content://" + AUTHORITY + "/notes/" + noteId + "/cards/" + ord);
        String fallbackAnswer;

        try (Cursor cursor = resolver.query(
                cardUri,
                new String[] { "question_simple", "answer_pure", "reps" },
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            loaded.question = cursor.getString(cursor.getColumnIndexOrThrow("question_simple"));
            fallbackAnswer = cursor.getString(cursor.getColumnIndexOrThrow("answer_pure"));
            loaded.reps = cursor.getInt(cursor.getColumnIndexOrThrow("reps"));
        }

        loaded.answer = loadBackField(noteId, fallbackAnswer);

        try {
            JSONArray arr = new JSONArray(nextReviewTimes);
            for (int i = 0; i < loaded.nextReviewTimes.length; i++) {
                loaded.nextReviewTimes[i] = arr.optString(i, "");
            }
        } catch (Exception ignored) {
            // Labels fall back to just Again/Hard/Good/Easy.
        }

        return loaded;
    }

    private String loadBackField(long noteId, String fallback) {
        try {
            Uri noteUri = Uri.parse("content://" + AUTHORITY + "/notes/" + noteId);
            long modelId;
            String fieldData;
            try (Cursor note = resolver.query(
                    noteUri,
                    new String[] { "mid", "flds" },
                    null,
                    null,
                    null
            )) {
                if (note == null || !note.moveToFirst()) return fallback;
                modelId = note.getLong(note.getColumnIndexOrThrow("mid"));
                fieldData = note.getString(note.getColumnIndexOrThrow("flds"));
            }

            Uri modelUri = Uri.parse("content://" + AUTHORITY + "/models/" + modelId);
            String fieldNames;
            try (Cursor model = resolver.query(
                    modelUri,
                    new String[] { "field_names" },
                    null,
                    null,
                    null
            )) {
                if (model == null || !model.moveToFirst()) return fallback;
                fieldNames = model.getString(model.getColumnIndexOrThrow("field_names"));
            }

            String[] names = fieldNames.split("\\u001f", -1);
            String[] fields = fieldData.split("\\u001f", -1);
            int count = Math.min(names.length, fields.length);
            for (int i = 0; i < count; i++) {
                if ("Back".equalsIgnoreCase(names[i].trim())) return fields[i];
            }
        } catch (Exception ignored) {
            // Fall through to provider-rendered answer.
        }
        return fallback == null ? "" : fallback;
    }

    private boolean submitAnswer(ReviewCard reviewCard, int ease, long timeTaken) {
        ContentValues values = new ContentValues();
        values.put("note_id", reviewCard.noteId);
        values.put("ord", reviewCard.ord);
        values.put("answer_ease", ease);
        values.put("time_taken", timeTaken);
        return resolver.update(SCHEDULE_URI, values, null, null) > 0;
    }

    private void updateEaseButtonLabels() {
        setEaseLabel(again, "Again", 0);
        setEaseLabel(hard, "Hard", 1);
        setEaseLabel(good, "Good", 2);
        setEaseLabel(easy, "Easy", 3);
    }

    private void setEaseLabel(Button button, String label, int index) {
        String interval = card == null ? "" : card.nextReviewTimes[index];
        if (interval == null || interval.isEmpty()) button.setText(label);
        else button.setText(label + "\n" + interval);
    }

    private boolean isAccepted(String typed, String rawAnswer) {
        if (rawAnswer == null) return false;

        String[] alternatives = rawAnswer.split("\\|");
        String normalizedTyped = normalize(typed);

        for (String alternative : alternatives) {
            String normalizedAnswer = normalize(stripHtml(alternative));
            if (normalizedTyped.equals(normalizedAnswer)) return true;
            if (IGNORE_EXTRA_WORDS && !normalizedAnswer.isEmpty() && normalizedTyped.contains(normalizedAnswer)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String input) {
        String value = input == null ? "" : input.trim();

        if (IGNORE_ACCENTS) {
            value = Normalizer.normalize(value, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
        }

        if (IGNORE_CASE) value = value.toLowerCase(Locale.ROOT);

        if (IGNORE_PUNCTUATION) {
            value = value.replaceAll("[\\p{Punct}\\p{S}]", "");
        }

        return value.trim();
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        //noinspection deprecation
        return Html.fromHtml(html).toString();
    }

    private void setHtml(TextView view, String html) {
        if (html == null) {
            view.setText("");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            view.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
        } else {
            //noinspection deprecation
            view.setText(Html.fromHtml(html));
        }
    }
}
