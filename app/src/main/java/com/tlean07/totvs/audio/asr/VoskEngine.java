package com.tlean07.totvs.audio.asr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Offline Portuguese speech recognition with Vosk (Kaldi), fed from our own PCM
 * stream so it never competes with the recorder for the microphone. Decoding
 * runs on a dedicated thread behind a queue, which keeps the capture thread
 * real-time even when the phone is slow.
 */
public final class VoskEngine implements TranscriptionEngine {

    private static final short[] FLUSH = new short[0];
    private static final short[] CLOSE = new short[1];

    private final Model model;
    private final Recognizer recognizer;
    private final Listener listener;
    private final String modelName;
    private final LinkedBlockingQueue<short[]> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean closed;
    private String lastPartial = "";

    public VoskEngine(Model model, String modelName, Listener listener) throws java.io.IOException {
        this.model = model;
        this.modelName = modelName;
        this.listener = listener;
        this.recognizer = new Recognizer(model, 16_000f);
        this.recognizer.setWords(true);
        this.worker = new Thread(this::loop, "vosk-asr");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public void accept(short[] pcm, int length) {
        if (closed || length <= 0) {
            return;
        }
        short[] copy = new short[length];
        System.arraycopy(pcm, 0, copy, 0, length);
        queue.add(copy);
    }

    @Override
    public void flush() {
        if (!closed) {
            queue.add(FLUSH);
        }
    }

    /** Blocks until every queued chunk has been decoded (used when processing files). */
    public void drain() throws InterruptedException {
        while (!closed && !queue.isEmpty()) {
            Thread.sleep(20);
        }
    }

    /** Chunks waiting to be decoded – lets the UI show "transcrição atrasada" on slow phones. */
    public int backlog() {
        return queue.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.add(CLOSE);
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        try {
            while (true) {
                short[] chunk = queue.take();
                if (chunk == CLOSE) {
                    break;
                }
                if (chunk == FLUSH) {
                    emitWords(recognizer.getFinalResult());
                    continue;
                }
                if (recognizer.acceptWaveForm(chunk, chunk.length)) {
                    emitWords(recognizer.getResult());
                } else {
                    String partial = parsePartial(recognizer.getPartialResult());
                    if (!partial.equals(lastPartial)) {
                        lastPartial = partial;
                        listener.onPartial(partial);
                    }
                }
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (RuntimeException e) {
            listener.onPartial("");
        } finally {
            try {
                recognizer.close();
            } catch (RuntimeException ignored) {
                // native handle already gone
            }
            try {
                model.close();
            } catch (RuntimeException ignored) {
                // idem
            }
        }
    }

    private void emitWords(String json) {
        lastPartial = "";
        List<TranscriptWord> words = parseWords(json);
        listener.onWords(words);
    }

    static String parsePartial(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonElement p = o.get("partial");
            return p == null || p.isJsonNull() ? "" : p.getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static List<TranscriptWord> parseWords(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonElement arr = o.get("result");
            if (arr == null || !arr.isJsonArray()) {
                return Collections.emptyList();
            }
            JsonArray items = arr.getAsJsonArray();
            List<TranscriptWord> words = new ArrayList<>(items.size());
            for (JsonElement e : items) {
                JsonObject w = e.getAsJsonObject();
                String text = w.get("word").getAsString();
                long start = Math.round(w.get("start").getAsDouble() * 1000);
                long end = Math.round(w.get("end").getAsDouble() * 1000);
                float conf = w.has("conf") ? w.get("conf").getAsFloat() : 1f;
                words.add(new TranscriptWord(text, start, end, conf));
            }
            return words;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
