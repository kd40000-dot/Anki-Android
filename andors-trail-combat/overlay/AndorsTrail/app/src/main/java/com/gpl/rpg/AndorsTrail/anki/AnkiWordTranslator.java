package com.gpl.rpg.AndorsTrail.anki;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tap-to-translate for words visible in the Anki question.
 *
 * Does not read the hidden answer or modify the Anki scheduling/session state.
 * The popup is an inline TextView so an open IME and typed text stay untouched.
 */
public final class AnkiWordTranslator {
    // Include Vietnamese combining marks, curly apostrophes and hyphenated words.
    private static final Pattern WORD = Pattern.compile(
            "[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*");

    private final LanguageIdentifier languageIdentifier = LanguageIdentification.getClient();
    private String visibleSentence = null;
    private long requestVersion = 0L;
    private boolean closed = false;

    /** Keep clickable spans installed even if the combat watchdog re-renders the same card. */
    public void renderQuestion(TextView question, TextView translated, String sentence) {
        if (closed) return;
        final String text = sentence == null ? "" : sentence;
        if (text.equals(visibleSentence) && question.getText() instanceof Spanned) return;

        visibleSentence = text;
        requestVersion++;
        translated.setVisibility(View.GONE);

        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Matcher words = WORD.matcher(text);
        while (words.find()) {
            final String word = words.group();
            // A cloze placeholder is not a visible word and must not be translated.
            if (word.matches("(?i)c[0-9]+")
                    && words.start() >= 2 && text.substring(words.start() - 2, words.start()).equals("{{")) {
                continue;
            }
            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    lookup(translated, word, text);
                }

                @Override
                public void updateDrawState(TextPaint paint) {
                    // Keep the original question typography unchanged.
                    paint.setUnderlineText(false);
                    paint.setColor(question.getCurrentTextColor());
                }
            }, words.start(), words.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        question.setText(builder, TextView.BufferType.SPANNABLE);
        question.setMovementMethod(LinkMovementMethod.getInstance());
        question.setHighlightColor(Color.TRANSPARENT);
        // Selecting the question or requesting focus would dismiss the IME.
        question.setFocusable(false);
        question.setFocusableInTouchMode(false);
    }

    private void lookup(TextView translated, String word, String sentence) {
        if (closed) return;
        long request = ++requestVersion;
        translated.setText(word + "  •  Detecting language…");
        translated.setVisibility(View.VISIBLE);

        languageIdentifier.identifyLanguage(sentence)
                .addOnSuccessListener(code -> {
                    if (!isCurrent(request)) return;
                    translate(translated, word, code, request);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(request)) return;
                    translate(translated, word, "und", request);
                });
    }

    private void translate(TextView translated, String word, String detected, long request) {
        if (!isCurrent(request)) return;
        String code = detected == null ? "" : detected.toLowerCase(java.util.Locale.ROOT);
        if (code.equals("und") || code.isEmpty()) {
            // Vietnamese-specific letters provide a useful fallback for short cards.
            code = visibleSentence != null
                    && visibleSentence.matches("(?s).*[ăâđêôơưĂÂĐÊÔƠƯ].*")
                    ? TranslateLanguage.VIETNAMESE : TranslateLanguage.ENGLISH;
        }

        final String from = TranslateLanguage.fromLanguageTag(code);
        if (from == null) {
            translated.setText(word + "  •  Language not supported (" + code + ")");
            return;
        }
        final String to = from.equals(TranslateLanguage.ENGLISH)
                ? TranslateLanguage.VIETNAMESE : TranslateLanguage.ENGLISH;
        final Translator translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(from).setTargetLanguage(to).build());

        final String direction = from + " → " + to;
        translated.setText(word + "  (" + direction + ")  •  Translating…");
        // Download once over Wi-Fi, then use the cached model without a network.
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(ignored -> {
                    if (!isCurrent(request)) {
                        translator.close();
                        return;
                    }
                    translator.translate(word)
                            .addOnSuccessListener(result -> {
                                if (isCurrent(request)) {
                                    translated.setText(word + "  →  " + result
                                            + "    (" + direction + ")");
                                }
                            })
                            .addOnFailureListener(e -> {
                                if (isCurrent(request)) {
                                    translated.setText(word + "  •  Translation unavailable. Tap again.");
                                }
                            })
                            .addOnCompleteListener(task -> translator.close());
                })
                .addOnFailureListener(e -> {
                    if (isCurrent(request)) {
                        translated.setText(word + "  •  Model needed. Connect to Wi-Fi and tap again.");
                    }
                    translator.close();
                });
    }

    private boolean isCurrent(long request) {
        return !closed && request == requestVersion;
    }

    /** Clear pending lookups when switching cards or leaving combat. */
    public void reset(TextView question, TextView translated) {
        requestVersion++;
        visibleSentence = null;
        translated.setVisibility(View.GONE);
        question.setText("");
    }

    public void close() {
        closed = true;
        requestVersion++;
        languageIdentifier.close();
    }
}
