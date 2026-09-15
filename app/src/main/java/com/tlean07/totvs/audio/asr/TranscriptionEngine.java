package com.tlean07.totvs.audio.asr;

import java.util.List;

/**
 * Speech-to-text engine fed with the same 16 kHz mono PCM16 stream the diarizer
 * sees, so word timestamps and speaker segments share one clock.
 */
public interface TranscriptionEngine {

    interface Listener {
        /** Text of the utterance being spoken right now (replaces the previous partial). */
        void onPartial(String text);

        /** An utterance was finalised into timed words (may be empty for silence). */
        void onWords(List<TranscriptWord> words);
    }

    /** Human readable model name for the review screen / audit trail. */
    String modelName();

    void accept(short[] pcm, int length);

    /** Forces the pending utterance to be finalised (pause / stop). */
    void flush();

    void close();
}
