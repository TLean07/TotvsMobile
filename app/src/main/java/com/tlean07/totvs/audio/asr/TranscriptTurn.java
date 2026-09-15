package com.tlean07.totvs.audio.asr;

/** A run of consecutive words from one speaker – what the transcript screen shows as one bubble. */
public final class TranscriptTurn {

    public final int speaker;
    public final long startMs;
    public final long endMs;
    public final String text;
    /** True while the words are still a partial hypothesis or the speaker label may change. */
    public final boolean provisional;

    public TranscriptTurn(int speaker, long startMs, long endMs, String text, boolean provisional) {
        this.speaker = speaker;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.provisional = provisional;
    }

    @Override
    public String toString() {
        return "P" + (speaker + 1) + "[" + startMs + "-" + endMs + "]" + (provisional ? "? " : " ") + text;
    }
}
