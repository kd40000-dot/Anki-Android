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

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tappable Vietnamese/English sentence text. Spans target Vietnamese lexical
 * words, not merely space-delimited syllables ("độc lập" stays together).
 * All translation is on-device after an initial model download.
 *
 * No card answer, rating, or game action is ever touched by this class.
 */
public final class AnkiWordTranslator {
    private static final Pattern SYLLABLE = Pattern.compile(
            "[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*");
    private static final int MAX_COMPOUND_SYLLABLES = 4;
    private static final long SLOW_DOWNLOAD_MS = 15000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 90000L;
    private static final long TRANSLATE_TIMEOUT_MS = 20000L;

    private static final class Token {
        final int start;
        final int end;
        Token(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Model {
        final Translator translator;
        Task<Void> download;
        boolean ready;
        boolean abandoned;
        Model(Translator translator) {
            this.translator = translator;
        }
    }

    private final LanguageIdentifier languageIdentifier = LanguageIdentification.getClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Model> models = new HashMap<>();
    private final Set<String> compounds = new HashSet<>();
    private boolean lexiconLoaded;
    private String visibleSentence;
    private long requestVersion;
    private boolean closed;

    /**
     * A clickable span covers the entire Vietnamese lexical unit. It preserves
     * the existing visual appearance and doesn't steal focus from the keyboard.
     */
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
        while (matcher.find()) {
            syllables.add(new Token(matcher.start(), matcher.end()));
        }

        boolean vietnamese = looksVietnamese(text);
        for (int i = 0; i < syllables.size();) {
            int size = vietnamese ? compoundSize(text, syllables, i) : 1;
            Token start = syllables.get(i);
            Token end = syllables.get(i + size - 1);
            final String chosen = text.substring(start.start, end.end);
            // No clickable span should be created for a raw cloze placeholder.
            if (!isClozePlaceholder(text, start.start, end.end)) {
                rich.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View view) {
                        lookup(resultView, chosen, text);
                    }
                    @Override
                    public void updateDrawState(TextPaint paint) {
                        paint.setUnderlineText(false);
                        paint.setColor(question.getCurrentTextColor());
                    }
                }, start.start, end.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i += size;
        }

        question.setText(rich, TextView.BufferType.SPANNABLE);
        question.setMovementMethod(LinkMovementMethod.getInstance());
        question.setHighlightColor(Color.TRANSPARENT);
        question.setFocusable(false);
        question.setFocusableInTouchMode(false);
    }

    private static boolean isClozePlaceholder(String sentence, int start, int end) {
        return start >= 2 && sentence.substring(start - 2, start).equals("{{")
                || end + 2 <= sentence.length() && sentence.substring(end, end + 2).equals("}}");
    }

    private static boolean looksVietnamese(String sentence) {
        // Covers the characteristic Vietnamese vowels, đ, and accented vowels.
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (c == 'đ' || c == 'Đ'
                    || (c >= '\u00c0' && c <= '\u1ef9')) {
                return true;
            }
        }
        return false;
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
                    compounds.add(line.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {
            // A missing word list must never prevent the card from being answered.
            // Individual-syllable taps remain functional.
        }
    }

    /** Longest lexicon match starting at i, never crossing punctuation. */
    private int compoundSize(String text, List<Token> words, int i) {
        int max = Math.min(MAX_COMPOUND_SYLLABLES, words.size() - i);
        for (int size = max; size > 1; size--) {
            boolean contiguous = true;
            for (int j = i + 1; j < i + size; j++) {
                String between = text.substring(words.get(j - 1).end, words.get(j).start);
                if (!between.matches("[ \\t]+")) {
                    contiguous = false;
                    break;
                }
            }
            if (!contiguous) continue;
            String candidate = text.substring(words.get(i).start,
                    words.get(i + size - 1).end)
                    .replaceAll("[ \\t]+", " ")
                    .toLowerCase(Locale.ROOT);
            if (compounds.contains(candidate)) return size;
        }
        return 1;
    }

    private void lookup(TextView result, String phrase, String sentence) {
        if (closed) return;
        long request = ++requestVersion;
        result.setVisibility(View.VISIBLE);

        // Skip language detection altogether for Vietnamese text: it's both
        // faster and more reliable on the short sentences used by cloze cards.
        if (looksVietnamese(sentence)) {
            translate(result, phrase, TranslateLanguage.VIETNAMESE, request);
            return;
        }

        result.setText(phrase + "  •  Detecting language…");
        final boolean[] resolved = {false};
        languageIdentifier.identifyLanguage(sentence)
                .addOnSuccessListener(code -> {
                    if (resolved[0] || !isCurrent(request)) return;
                    resolved[0] = true;
                    translate(result, phrase, code, request);
                })
                .addOnFailureListener(error -> {
                    if (resolved[0] || !isCurrent(request)) return;
                    resolved[0] = true;
                    translate(result, phrase, TranslateLanguage.ENGLISH, request);
                });
        handler.postDelayed(() -> {
            if (resolved[0] || !isCurrent(request)) return;
            resolved[0] = true;
            translate(result, phrase, TranslateLanguage.ENGLISH, request);
        }, 3000);
    }

    private void translate(TextView result, String phrase, String sourceCode, long request) {
        if (!isCurrent(request)) return;
        // Short English prompts are sometimes classified as "und".
        // In that case use English rather than breaking tap translation.
        String source = TranslateLanguage.fromLanguageTag(
                sourceCode == null ? "" : sourceCode.toLowerCase(Locale.ROOT));
        if (source == null) source = TranslateLanguage.ENGLISH;
        String target = source.equals(TranslateLanguage.ENGLISH)
                ? TranslateLanguage.VIETNAMESE : TranslateLanguage.ENGLISH;
        String direction = source + " → " + target;
        String key = source + "/" + target;

        Model model = models.get(key);
        if (model == null) {
            Translator translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(source).setTargetLanguage(target).build());
            model = new Model(translator);
            models.put(key, model);
        }
        final Model chosenModel = model;
        if (model.ready) {
            translateWithModel(result, phrase, direction, request, model);
            return;
        }

        result.setText(phrase + "  (" + direction + ")  •  Downloading offline model…");
        if (model.download == null) {
            // Crucially, do not require Wi-Fi: cellular, hotspot and existing
            // cached models should all be allowed. The model downloads only once.
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            model.download = model.translator.downloadModelIfNeeded(conditions);
            model.download.addOnCompleteListener(task -> {
                if (chosenModel.abandoned || closed) {
                    chosenModel.translator.close();
                    return;
                }
                if (task.isSuccessful()) {
                    chosenModel.ready = true;
                } else if (models.get(key) == chosenModel) {
                    models.remove(key);
                    chosenModel.translator.close();
                }
            });
        }
        final Task<Void> task = model.download;
        task.addOnSuccessListener(ignored -> {
            if (!isCurrent(request) || chosenModel.abandoned) return;
            chosenModel.ready = true;
            translateWithModel(result, phrase, direction, request, chosenModel);
        }).addOnFailureListener(error -> {
            if (!isCurrent(request)) return;
            String detail = error.getLocalizedMessage();
            if (detail == null || detail.trim().isEmpty()) detail = "Download failed";
            if (detail.length() > 100) detail = detail.substring(0, 100);
            result.setText(phrase + "  •  " + detail + ". Check Internet/Play Services; tap to retry.");
        });

        handler.postDelayed(() -> {
            if (!isCurrent(request) || task.isComplete()) return;
            result.setText(phrase + "  •  Still downloading the offline language model. "
                    + "First use may take a minute; Internet is required.");
        }, SLOW_DOWNLOAD_MS);
        handler.postDelayed(() -> {
            if (!isCurrent(request) || task.isComplete()) return;
            if (models.get(key) == chosenModel) {
                chosenModel.abandoned = true;
                models.remove(key); // New tap can start a fresh attempt.
                // The old translator is closed when its pending task completes.
            }
            result.setText(phrase + "  •  Model download timed out. "
                    + "Check Internet and Google Play Services, then tap again.");
        }, DOWNLOAD_TIMEOUT_MS);
    }

    private void translateWithModel(TextView result, String phrase, String direction,
            long request, Model model) {
        if (!isCurrent(request)) return;
        result.setText(phrase + "  •  Translating…");
        Task<String> task = model.translator.translate(phrase);
        task.addOnSuccessListener(translated -> {
            if (isCurrent(request)) {
                result.setText(phrase + "  →  " + translated + "    (" + direction + ")");
            }
        }).addOnFailureListener(error -> {
            if (isCurrent(request)) {
                result.setText(phrase + "  •  Translation failed. Tap to retry.");
            }
        });
        handler.postDelayed(() -> {
            if (isCurrent(request) && !task.isComplete()) {
                result.setText(phrase + "  •  Translation timed out. Tap to retry.");
            }
        }, TRANSLATE_TIMEOUT_MS);
    }

    private boolean isCurrent(long request) {
        return !closed && request == requestVersion;
    }

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
        languageIdentifier.close();
        for (Model model : models.values()) {
            if (model.download == null || model.download.isComplete()) {
                model.translator.close();
            } else {
                model.abandoned = true; // Completion callback closes it.
            }
        }
        models.clear();
        compounds.clear();
    }
}
