package com.gpl.rpg.AndorsTrail.anki;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service-free offline dictionary for tappable Anki question words.
 *
 * Vietnamese word boundaries are lexical, including multi-syllable words such
 * as "độc lập". Word definitions come from bundled VNEDICT data (CC BY 3.0).
 * No internet, Play Services, Google libraries or runtime model downloads.
 *
 * This class never accesses hidden cloze answers or changes review scheduling.
 */
public final class AnkiWordTranslator {
    private static final Pattern SYLLABLE = Pattern.compile(
            "[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*");
    private static final int MAX_COMPOUND_SYLLABLES = 4;

    private static final class Token {
        final int start;
        final int end;

        Token(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService dictionaryExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> compounds = new HashSet<>();
    private final Map<String, String> vietnameseEnglish = new HashMap<>();
    private final Map<String, String> englishVietnamese = new HashMap<>();

    private boolean lexiconLoaded;
    private boolean dictionaryLoadAttempted;
    private String dictionaryLoadError;
    private String visibleSentence;
    private long requestVersion;
    private boolean closed;

    /** Install tap targets without changing the current question typography. */
    public void renderQuestion(TextView question, TextView resultView, String sentence) {
        if (closed) return;
        String text = sentence == null ? "" : sentence;
        if (text.equals(visibleSentence) && question.getText() instanceof Spanned) return;

        visibleSentence = text;
        requestVersion++;
        resultView.setVisibility(View.GONE);
        ensureLexiconLoaded(question.getContext());

        SpannableStringBuilder rich = new SpannableStringBuilder(text);
        List<Token> syllables = new ArrayList<>();
        Matcher matcher = SYLLABLE.matcher(text);
        while (matcher.find()) syllables.add(new Token(matcher.start(), matcher.end()));

        boolean vietnamese = looksVietnamese(text);
        for (int i = 0; i < syllables.size();) {
            int count = vietnamese ? compoundSize(text, syllables, i) : 1;
            Token start = syllables.get(i);
            Token end = syllables.get(i + count - 1);
            final String word = text.substring(start.start, end.end);
            if (!isClozePlaceholder(text, start.start, end.end)) {
                rich.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View view) {
                        lookup(resultView, word, text);
                    }

                    @Override
                    public void updateDrawState(TextPaint paint) {
                        paint.setUnderlineText(false);
                        paint.setColor(question.getCurrentTextColor());
                    }
                }, start.start, end.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i += count;
        }

        question.setText(rich, TextView.BufferType.SPANNABLE);
        question.setMovementMethod(LinkMovementMethod.getInstance());
        question.setHighlightColor(Color.TRANSPARENT);
        question.setFocusable(false);
        question.setFocusableInTouchMode(false);
    }

    private static boolean isClozePlaceholder(String text, int start, int end) {
        return (start >= 2 && text.substring(start - 2, start).equals("{{"))
                || (end + 2 <= text.length()
                    && text.substring(end, end + 2).equals("}}"));
    }

    private static boolean looksVietnamese(String text) {
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == 'đ' || c == 'Đ' || (c >= '\u00c0' && c <= '\u1ef9')) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[ \\t]+", " "), Normalizer.Form.NFC);
    }

    private void ensureLexiconLoaded(Context context) {
        if (lexiconLoaded) return;
        lexiconLoaded = true;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("anki_vietnamese_compounds.txt"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && line.indexOf(' ') >= 0) {
                    compounds.add(normalize(line));
                }
            }
        } catch (Exception ignored) {
            // Missing segmentation data should never stop gameplay.
        }
    }

    /** Longest dictionary phrase match; punctuation is always a boundary. */
    private int compoundSize(String text, List<Token> words, int start) {
        int max = Math.min(MAX_COMPOUND_SYLLABLES, words.size() - start);
        for (int size = max; size > 1; --size) {
            boolean adjacent = true;
            for (int i = start + 1; i < start + size; ++i) {
                String gap = text.substring(words.get(i - 1).end, words.get(i).start);
                if (!gap.matches("[ \\t]+")) {
                    adjacent = false;
                    break;
                }
            }
            if (!adjacent) continue;
            String phrase = normalize(text.substring(
                    words.get(start).start, words.get(start + size - 1).end));
            if (compounds.contains(phrase)) return size;
        }
        return 1;
    }

    /** Load the dictionary from APK assets once, on a background thread. */
    private void loadDictionary(Context context) {
        if (dictionaryLoadAttempted) return;
        dictionaryLoadAttempted = true;
        try {
            loadEntries(context, "anki_vi_en_dictionary.tsv", vietnameseEnglish);
            loadEntries(context, "anki_en_vi_dictionary.tsv", englishVietnamese);
            if (vietnameseEnglish.size() < 30000) {
                dictionaryLoadError = "Offline dictionary was not bundled correctly.";
            }
        } catch (Exception e) {
            dictionaryLoadError = "Offline dictionary could not be read: "
                    + e.getClass().getSimpleName();
        }
    }

    private static void loadEntries(Context context, String file, Map<String, String> target)
            throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) continue;
                String word = normalize(line.substring(0, tab));
                String definition = line.substring(tab + 1).trim();
                if (!word.isEmpty() && !definition.isEmpty()) {
                    target.put(word, definition);
                }
            }
        }
    }

    private void lookup(TextView result, String phrase, String sentence) {
        if (closed) return;
        final long request = ++requestVersion;
        final String key = normalize(phrase);
        final boolean vietnamese = looksVietnamese(sentence);
        final Context context = result.getContext().getApplicationContext();

        result.setVisibility(View.VISIBLE);
        result.setText(phrase + "  •  Looking up offline…");
        dictionaryExecutor.execute(() -> {
            loadDictionary(context);
            String definition = vietnamese
                    ? vietnameseEnglish.get(key) : englishVietnamese.get(key);
            String direction = vietnamese ? "vi → en" : "en → vi";

            if (definition == null) {
                definition = vietnamese
                        ? englishVietnamese.get(key) : vietnameseEnglish.get(key);
                if (definition != null) {
                    direction = vietnamese ? "en → vi" : "vi → en";
                }
            }
            final String answer = definition;
            final String directionLabel = direction;
            final String error = dictionaryLoadError;

            handler.post(() -> {
                if (!isCurrent(request)) return;
                if (error != null) {
                    result.setText(phrase + "  •  " + error);
                } else if (answer != null) {
                    result.setText(phrase + "  →  " + answer
                            + "    (" + directionLabel + ")");
                } else {
                    result.setText(phrase + "  •  No offline dictionary entry.");
                }
            });
        });
    }

    private boolean isCurrent(long request) {
        return !closed && requestVersion == request;
    }

    /** Never let the previous card's translation appear on a new card. */
    public void reset(TextView question, TextView result) {
        requestVersion++;
        visibleSentence = null;
        result.setVisibility(View.GONE);
        question.setText("");
    }

    public void close() {
        closed = true;
        requestVersion++;
        handler.removeCallbacksAndMessages(null);
        dictionaryExecutor.shutdownNow();
        compounds.clear();
        vietnameseEnglish.clear();
        englishVietnamese.clear();
    }
}
