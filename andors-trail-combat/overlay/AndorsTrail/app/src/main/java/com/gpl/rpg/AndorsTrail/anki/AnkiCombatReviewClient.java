package com.gpl.rpg.AndorsTrail.anki;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
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
        public final String audioFileName;
        public final String[] audioFileNamesByAlternative;
        public final String[] nextIntervals;
        public long shownAtMs;

        ReviewCard(
                long noteId,
                int cardOrd,
                int reps,
                String question,
                String answer,
                String audioFileName,
                String[] audioFileNamesByAlternative,
                String[] nextIntervals) {
            this.noteId = noteId;
            this.cardOrd = cardOrd;
            this.reps = reps;
            this.question = question;
            this.answer = answer;
            this.audioFileName = audioFileName;
            this.audioFileNamesByAlternative = audioFileNamesByAlternative;
            this.nextIntervals = nextIntervals;
            this.shownAtMs = System.currentTimeMillis();
        }
    }

    public ReviewCard getNextCard(ContentResolver resolver) {
        long noteId;
        int cardOrd;
        String[] intervals;
        String audioFileName = null;

        try (Cursor cur = resolver.query(
                SCHEDULE_URI,
                new String[]{"note_id", "ord", "button_count", "next_review_times", "media_files"},
                "limit=1",
                null,
                null)) {
            if (cur == null || !cur.moveToFirst()) return null;

            noteId = cur.getLong(cur.getColumnIndexOrThrow("note_id"));
            cardOrd = cur.getInt(cur.getColumnIndexOrThrow("ord"));
            int buttonCount = cur.getInt(cur.getColumnIndexOrThrow("button_count"));
            intervals = parseIntervals(cur.getString(cur.getColumnIndexOrThrow("next_review_times")), buttonCount);
            audioFileName = firstAudioFile(cur.getString(cur.getColumnIndexOrThrow("media_files")));
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
        String rawNotes = null;

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
                    int notes = findField(names, "Notes");

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

                    if (notes >= 0 && notes < fields.length) {
                        rawNotes = fields[notes];
                    }
                }
            }
        }

        String[] audioFileNamesByAlternative = parseAudioMap(rawNotes, answer);
        return new ReviewCard(
                noteId,
                cardOrd,
                reps,
                question,
                answer,
                audioFileName,
                audioFileNamesByAlternative,
                intervals);
    }

    public Uri getAudioUri(ReviewCard card, String typedAnswer) {
        if (card == null) return null;

        String filename = card.audioFileName;
        if (card.audioFileNamesByAlternative != null) {
            int matchedAlternative = matchingAlternativeIndex(typedAnswer, card.answer);
            if (matchedAlternative < 0
                    || matchedAlternative >= card.audioFileNamesByAlternative.length) {
                return null;
            }
            filename = card.audioFileNamesByAlternative[matchedAlternative];
        }

        if (filename == null || filename.trim().isEmpty()) return null;
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("media")
                .appendPath(filename)
                .build();
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
        return matchingAlternativeIndex(typed, expected) >= 0;
    }

    private static int matchingAlternativeIndex(String typed, String expected) {
        String normalizedTyped = normalize(typed);
        String[] alternatives = expected == null ? new String[]{""} : expected.split("\\|", -1);
        for (int i = 0; i < alternatives.length; i++) {
            if (normalizedTyped.equals(normalize(alternatives[i]))) return i;
        }
        return -1;
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

    private static String[] parseAudioMap(String rawNotes, String expected) {
        if (rawNotes == null || expected == null) return null;

        final String prefix = "<!--anki-audio-map:";
        int start = rawNotes.indexOf(prefix);
        if (start < 0) return null;
        int end = rawNotes.indexOf("-->", start + prefix.length());
        if (end < 0) return null;

        try {
            JSONObject mapping = new JSONObject(
                    rawNotes.substring(start + prefix.length(), end).trim());
            String[] alternatives = expected.split("\\|", -1);
            String[] files = new String[alternatives.length];

            for (Iterator<String> it = mapping.keys(); it.hasNext(); ) {
                String key = it.next();
                String normalizedKey = normalize(key);
                for (int i = 0; i < alternatives.length; i++) {
                    if (normalizedKey.equals(normalize(alternatives[i]))) {
                        String value = mapping.optString(key, "").trim();
                        files[i] = value.isEmpty() ? null : value;
                        break;
                    }
                }
            }
            return files;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstAudioFile(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String name = arr.optString(i, "").trim();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mp3")
                        || lower.endsWith(".ogg")
                        || lower.endsWith(".opus")
                        || lower.endsWith(".wav")
                        || lower.endsWith(".m4a")
                        || lower.endsWith(".aac")
                        || lower.endsWith(".flac")) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
