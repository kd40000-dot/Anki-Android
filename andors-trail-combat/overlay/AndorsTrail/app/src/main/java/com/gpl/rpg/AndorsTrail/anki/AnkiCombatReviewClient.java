package com.gpl.rpg.AndorsTrail.anki;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.text.Html;

import org.json.JSONArray;

import java.util.Locale;

/**
 * Small, dependency-free client for the custom AnkiDroid review ContentProvider.
 *
 * The custom AnkiDroid build is com.ichi2.anki.retry, so we intentionally target
 * that provider rather than stock AnkiDroid.
 */
public final class AnkiCombatReviewClient {
    public static final String AUTHORITY = "com.ichi2.anki.retry.flashcards";
    public static final String PERMISSION = "com.ichi2.anki.retry.permission.READ_WRITE_DATABASE";

    private static final Uri SCHEDULE_URI = Uri.parse("content://" + AUTHORITY + "/schedule/");

    public static final class ReviewCard {
        public final long noteId;
        public final int cardOrd;
        public final int reps;
        public final String question;
        public final String answer;
        public final String[] nextIntervals;
        public long shownAtMs;

        ReviewCard(long noteId, int cardOrd, int reps, String question, String answer, String[] nextIntervals) {
            this.noteId = noteId;
            this.cardOrd = cardOrd;
            this.reps = reps;
            this.question = question;
            this.answer = answer;
            this.nextIntervals = nextIntervals;
            this.shownAtMs = System.currentTimeMillis();
        }
    }

    public ReviewCard getNextCard(ContentResolver resolver) {
        long noteId;
        int cardOrd;
        String[] intervals;

        try (Cursor cur = resolver.query(
                SCHEDULE_URI,
                new String[]{"note_id", "ord", "button_count", "next_review_times"},
                "limit=1",
                null,
                null)) {
            if (cur == null || !cur.moveToFirst()) return null;

            noteId = cur.getLong(cur.getColumnIndexOrThrow("note_id"));
            cardOrd = cur.getInt(cur.getColumnIndexOrThrow("ord"));
            int buttonCount = cur.getInt(cur.getColumnIndexOrThrow("button_count"));
            intervals = parseIntervals(cur.getString(cur.getColumnIndexOrThrow("next_review_times")), buttonCount);
        }

        Uri cardUri = Uri.parse("content://" + AUTHORITY + "/notes/" + noteId + "/cards/" + cardOrd);
        String questionFallback = "";
        String answerFallback = "";
        int reps = 0;

        try (Cursor cur = resolver.query(
                cardUri,
                new String[]{"question_simple", "answer_pure", "reps"},
                null,
                null,
                null)) {
            if (cur == null || !cur.moveToFirst()) return null;
            questionFallback = htmlToText(cur.getString(cur.getColumnIndexOrThrow("question_simple")));
            answerFallback = htmlToText(cur.getString(cur.getColumnIndexOrThrow("answer_pure")));
            reps = cur.getInt(cur.getColumnIndexOrThrow("reps"));
        }

        String question = questionFallback;
        String answer = answerFallback;

        // Prefer raw Front/Back fields when the note type has them. This matches the user's
        // typed-answer note type and avoids treating back-template decorations as answer text.
        Uri noteUri = Uri.parse("content://" + AUTHORITY + "/notes/" + noteId);
        long modelId = -1L;
        String encodedFields = null;
        try (Cursor cur = resolver.query(noteUri, new String[]{"mid", "flds"}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                modelId = cur.getLong(cur.getColumnIndexOrThrow("mid"));
                encodedFields = cur.getString(cur.getColumnIndexOrThrow("flds"));
            }
        }

        if (modelId >= 0 && encodedFields != null) {
            Uri modelUri = Uri.parse("content://" + AUTHORITY + "/models/" + modelId);
            try (Cursor cur = resolver.query(modelUri, new String[]{"field_names"}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    String encodedNames = cur.getString(cur.getColumnIndexOrThrow("field_names"));
                    String[] names = encodedNames.split("\\u001f", -1);
                    String[] fields = encodedFields.split("\\u001f", -1);
                    int front = findField(names, "Front");
                    int back = findField(names, "Back");

                    if (front >= 0 && front < fields.length) {
                        question = htmlToText(fields[front]);
                    } else if (fields.length > 0) {
                        question = htmlToText(fields[0]);
                    }

                    if (back >= 0 && back < fields.length) {
                        answer = htmlToText(fields[back]);
                    } else if (fields.length > 1) {
                        answer = htmlToText(fields[1]);
                    }
                }
            }
        }

        return new ReviewCard(noteId, cardOrd, reps, question, answer, intervals);
    }

    public boolean answerCard(ContentResolver resolver, ReviewCard card, int ease) {
        ContentValues values = new ContentValues();
        values.put("note_id", card.noteId);
        values.put("ord", card.cardOrd);
        values.put("answer_ease", ease);
        values.put("time_taken", Math.max(0L, System.currentTimeMillis() - card.shownAtMs));
        return resolver.update(SCHEDULE_URI, values, null, null) > 0;
    }

    public int getCardReps(ContentResolver resolver, ReviewCard card) {
        Uri cardUri = Uri.parse("content://" + AUTHORITY + "/notes/" + card.noteId + "/cards/" + card.cardOrd);
        try (Cursor cur = resolver.query(cardUri, new String[]{"reps"}, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) return -1;
            return cur.getInt(cur.getColumnIndexOrThrow("reps"));
        }
    }

    /**
     * Mirrors the user's SmartTypeField defaults closely:
     * - trims whitespace
     * - ignores case
     * - keeps accents and punctuation significant
     * - accepts alternatives separated by '|'
     */
    public boolean isCorrect(String typed, String expected) {
        String normalizedTyped = normalize(typed);
        String[] alternatives = expected == null ? new String[]{""} : expected.split("\\|", -1);
        for (String alternative : alternatives) {
            if (normalizedTyped.equals(normalize(alternative))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int findField(String[] names, String target) {
        for (int i = 0; i < names.length; i++) {
            if (target.equalsIgnoreCase(names[i].trim())) return i;
        }
        return -1;
    }

    private static String[] parseIntervals(String json, int count) {
        String[] intervals = new String[Math.max(4, count)];
        for (int i = 0; i < intervals.length; i++) intervals[i] = "";
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && i < intervals.length; i++) {
                intervals[i] = arr.optString(i, "");
            }
        } catch (Exception ignored) {
        }
        return intervals;
    }

    @SuppressWarnings("deprecation")
    private static String htmlToText(String html) {
        if (html == null) return "";
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replace("[[type:Back]]", "")
                .replace("[[type:Front]]", "");
        return Html.fromHtml(cleaned).toString().trim();
    }
}
