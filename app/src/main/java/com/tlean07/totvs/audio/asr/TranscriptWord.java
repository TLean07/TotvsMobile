package com.tlean07.totvs.audio.asr;

/** One recognised word with its position in the recording. */
public final class TranscriptWord {

    public final String text;
    public final long startMs;
    public final long endMs;
    public final float confidence;

    public TranscriptWord(String text, long startMs, long endMs, float confidence) {
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
        this.confidence = confidence;
    }

    public long midMs() {
        return (startMs + endMs) / 2;
    }

    @Override
    public String toString() {
        return text + "@" + startMs;
    }
}
