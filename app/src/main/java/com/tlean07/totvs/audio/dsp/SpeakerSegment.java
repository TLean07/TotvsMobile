package com.tlean07.totvs.audio.dsp;

/** A stretch of speech attributed to one speaker (index 0 = "Pessoa 1"). */
public final class SpeakerSegment {

    public final long startMs;
    public final long endMs;
    public final int speaker;
    /** True while the segment is still open and the label may still change. */
    public final boolean provisional;
    /** Distance to the chosen speaker's voice model (0 = identical; ~1 = borderline). */
    public final float distance;

    public SpeakerSegment(long startMs, long endMs, int speaker, boolean provisional) {
        this(startMs, endMs, speaker, provisional, 0f);
    }

    public SpeakerSegment(long startMs, long endMs, int speaker, boolean provisional, float distance) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.speaker = speaker;
        this.provisional = provisional;
        this.distance = distance;
    }

    public long durationMs() {
        return endMs - startMs;
    }

    public boolean contains(long tMs) {
        return tMs >= startMs && tMs < endMs;
    }

    @Override
    public String toString() {
        return "[" + startMs + "-" + endMs + " P" + (speaker + 1) + (provisional ? "?" : "")
                + String.format(java.util.Locale.ROOT, " d=%.2f", distance) + "]";
    }
}
