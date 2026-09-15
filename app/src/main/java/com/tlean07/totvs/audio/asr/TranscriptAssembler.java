package com.tlean07.totvs.audio.asr;

import com.tlean07.totvs.audio.dsp.SpeakerSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Joins the two streams – timed words from the {@link TranscriptionEngine} and
 * speaker segments from the diarizer – into speaker turns. Words are attributed
 * to the segment that covers their midpoint (or the nearest one within a small
 * tolerance), then consecutive words of the same speaker are merged.
 *
 * Not thread-safe: the pipeline calls it from one worker thread and publishes
 * snapshots of {@link #turns()} to the UI.
 */
public final class TranscriptAssembler {

    /** Words further than this from any segment fall back to the previous word's speaker. */
    private static final long TOLERANCE_MS = 400;

    private final List<TranscriptWord> words = new ArrayList<>();
    private final List<SpeakerSegment> segments = new ArrayList<>();
    private SpeakerSegment openSegment;
    private String partial = "";
    private long partialStartMs = -1;

    public void addWords(List<TranscriptWord> newWords) {
        words.addAll(newWords);
        partial = "";
        partialStartMs = -1;
    }

    public void setPartial(String text, long nowMs) {
        partial = text == null ? "" : text.trim();
        if (partial.isEmpty()) {
            partialStartMs = -1;
        } else if (partialStartMs < 0) {
            partialStartMs = nowMs;
        }
    }

    public void addSegment(SpeakerSegment segment) {
        segments.add(segment);
        if (openSegment != null && openSegment.startMs <= segment.startMs) {
            openSegment = null;
        }
    }

    public void setOpenSegment(SpeakerSegment segment) {
        openSegment = segment;
    }

    public void clearOpenSegment() {
        openSegment = null;
    }

    public List<TranscriptWord> words() {
        return words;
    }

    public List<SpeakerSegment> segments() {
        return segments;
    }

    /** Speaker index attributed to a word at {@code tMs}; -1 when nothing is known. */
    public int speakerAt(long tMs) {
        SpeakerSegment best = null;
        long bestGap = Long.MAX_VALUE;
        for (SpeakerSegment s : segments) {
            if (s.contains(tMs)) {
                return s.speaker;
            }
            long gap = tMs < s.startMs ? s.startMs - tMs : tMs - s.endMs;
            if (gap < bestGap) {
                bestGap = gap;
                best = s;
            }
        }
        if (openSegment != null && openSegment.speaker >= 0) {
            if (openSegment.contains(tMs) || tMs >= openSegment.startMs) {
                return openSegment.speaker;
            }
            long gap = openSegment.startMs - tMs;
            if (gap < bestGap) {
                bestGap = gap;
                best = openSegment;
            }
        }
        return best != null && bestGap <= TOLERANCE_MS ? best.speaker : -1;
    }

    /** Rebuilds the turn list from scratch (cheap: a few thousand words at most). */
    public List<TranscriptTurn> turns() {
        List<TranscriptTurn> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int speaker = -1;
        long start = 0;
        long end = 0;
        int lastKnown = 0;
        for (TranscriptWord w : words) {
            int s = speakerAt(w.midMs());
            if (s < 0) {
                s = lastKnown;
            }
            lastKnown = s;
            if (speaker != -1 && s != speaker) {
                out.add(new TranscriptTurn(speaker, start, end, text.toString(), false));
                text.setLength(0);
            }
            if (text.length() == 0) {
                speaker = s;
                start = w.startMs;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(w.text);
            end = w.endMs;
        }
        if (text.length() > 0) {
            out.add(new TranscriptTurn(speaker, start, end, text.toString(), false));
        }
        if (!partial.isEmpty()) {
            int s = openSegment != null && openSegment.speaker >= 0
                    ? openSegment.speaker
                    : (partialStartMs >= 0 ? speakerAt(partialStartMs) : -1);
            if (s < 0) {
                s = lastKnown;
            }
            long ps = partialStartMs >= 0 ? partialStartMs : end;
            out.add(new TranscriptTurn(s, ps, ps, partial, true));
        }
        return out;
    }

    /** Re-labels every segment of {@code from} as {@code to} (user merged two detected voices). */
    public void mergeSpeakers(int from, int to) {
        for (int i = 0; i < segments.size(); i++) {
            SpeakerSegment s = segments.get(i);
            if (s.speaker == from) {
                segments.set(i, new SpeakerSegment(s.startMs, s.endMs, to, s.provisional));
            }
        }
    }

    public void reset() {
        words.clear();
        segments.clear();
        openSegment = null;
        partial = "";
        partialStartMs = -1;
    }
}
