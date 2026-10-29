package com.gpl.rpg.AndorsTrail.anki;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import java.util.concurrent.atomic.AtomicBoolean;

/** Completely offline Vietnamese recognition using the larger 70k-hour Vietnamese Zipformer.
 * One short utterance at a time, no recordings persisted. Avoids Google ASR.
 */
public final class OfflineVietnameseSpeech {
    public interface Result { void onResult(String text, String error); }
    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Thread worker;
    private AudioRecord recorder;
    private OfflineRecognizer recognizer;
    private java.util.List<String> selectedVocabulary = java.util.Collections.emptyList();
    private String vocabularyDeck = "";

    public OfflineVietnameseSpeech(Context context) { app = context.getApplicationContext(); }

    public synchronized void listen(Result callback) {
        // Refresh current deck for every utterance so deck changes and newly
        // added notes are reflected without reinstalling the game.
        try {
            selectedVocabulary = new AnkiCombatReviewClient()
                .getSelectedDeckVietnameseVocabulary(app.getContentResolver());
        } catch (Exception ignored) {
            selectedVocabulary = java.util.Collections.emptyList();
        }
        stop();
        cancelled.set(false);
        worker = new Thread(() -> {
            String result = "", error = null;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 23 &&
                    app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("Microphone permission required");
                if (recognizer == null) {
                    String dir = "anki_zipformer_vi";
                    OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
                    transducer.setEncoder(dir + "/encoder-epoch-12-avg-8.onnx");
                    transducer.setDecoder(dir + "/decoder-epoch-12-avg-8.onnx");
                    transducer.setJoiner(dir + "/joiner-epoch-12-avg-8.onnx");
                    OfflineModelConfig model = new OfflineModelConfig();
                    model.setTransducer(transducer);
                    model.setTokens(dir + "/tokens.txt");
                    model.setModelType("zipformer");
                    model.setNumThreads(2);
                    OfflineRecognizerConfig config = new OfflineRecognizerConfig();
                    config.setModelConfig(model);
                    recognizer = new OfflineRecognizer(app.getAssets(), config);
                }
                int rate = 16000;
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("Microphone input unavailable");
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("Microphone could not initialize");
                recorder.startRecording();
                short[] chunk = new short[1600];
                float[] all = new float[rate * 5];
                int total = 0;
                int silentChunks = 0;
                boolean started = false;
                long until = android.os.SystemClock.elapsedRealtime() + 5500;
                while (!cancelled.get() && android.os.SystemClock.elapsedRealtime() < until && total < all.length) {
                    int n = recorder.read(chunk, 0, chunk.length);
                    if (n <= 0) break;
                    double energy = 0;
                    for (int i=0; i<n; i++) {
                        float v = chunk[i] / 32768.0f;
                        all[total++] = v;
                        energy += v * v;
                    }
                    boolean voiced = Math.sqrt(energy / n) > 0.012;
                    if (voiced) { started = true; silentChunks = 0; }
                    else if (started) silentChunks++;
                    if (started && silentChunks >= 8 && total >= 8000) break;
                }
                if (!cancelled.get()) {
                    if (total < 1600) throw new IllegalStateException("No audio captured");
                    float[] audio = java.util.Arrays.copyOf(all, total);
                    OfflineStream stream = recognizer.createStream();
                    try {
                        stream.acceptWaveform(audio, rate);
                        recognizer.decode(stream);
                        result = recognizer.getResult(stream).getText();
                    } finally { stream.release(); }
                }
            } catch (Exception ex) { error = ex.getMessage(); }
            finally {
                AudioRecord r = recorder;
                recorder = null;
                if (r != null) { try { r.stop(); } catch(Exception ignored) {} r.release(); }
            }
            final String text = result, failure = error;
            if (!cancelled.get()) ui.post(() -> callback.onResult(text, failure));
        }, "AnkiOfflineVietnameseASR");
        worker.start();
    }

    /** Non-authoritative nearest vocabulary match for diagnostics only.
     * Never overwrite raw transcript or accept answers on similarity alone.
     */
    public String nearestDeckTerm(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String normalized = java.text.Normalizer.normalize(raw.toLowerCase(java.util.Locale.ROOT).trim(), java.text.Normalizer.Form.NFC);
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        for (String term : selectedVocabulary) {
            int d = distance(normalized, term);
            if (d < bestDistance) { bestDistance = d; best = term; }
        }
        return bestDistance <= Math.max(1, normalized.length()/4) ? best : "";
    }
    private static int distance(String a, String b) {
        int[] prev = new int[b.length()+1], cur = new int[b.length()+1];
        for(int j=0;j<=b.length();j++) prev[j]=j;
        for(int i=1;i<=a.length();i++) {
            cur[0]=i;
            for(int j=1;j<=b.length();j++)
                cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            int[] temp=prev;prev=cur;cur=temp;
        }
        return prev[b.length()];
    }

    public void stop() {
        cancelled.set(true);
        AudioRecord r = recorder;
        if (r != null) { try { r.stop(); } catch(Exception ignored) {} }
    }
    public void close() { stop(); if (recognizer != null) { recognizer.release(); recognizer = null; } }
}
