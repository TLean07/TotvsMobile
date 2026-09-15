package com.tlean07.totvs.audio.dsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Online, unsupervised speaker clustering: every finished utterance is compared
 * with the centroid of each known speaker and either joins the closest one or
 * founds a new speaker when it is far from all of them. Short utterances are
 * held to a stricter standard before they may create a speaker, because a
 * one-word "sim" carries little voice information.
 */
public final class SpeakerClusterer {

    public static final int DEFAULT_MAX_SPEAKERS = 8;
    /** Distance above which an utterance is "someone else" (tuned on synthetic + TTS dialogues). */
    public static final float DEFAULT_NEW_SPEAKER_DISTANCE = 1.05f;
    /** Voiced frames at which the threshold stops being inflated for short utterances. */
    private static final int CONFIDENT_FRAMES = 120;
    /** Voiced frames below which an utterance can never spawn a speaker. */
    private static final int MIN_FRAMES_FOR_NEW = 40;
    /** Memory of a centroid, in voiced frames (~25 s), so it keeps adapting. */
    private static final int CENTROID_MEMORY_FRAMES = 1_500;

    public static final class Decision {
        public final int speaker;
        public final boolean created;
        public final float distance;

        Decision(int speaker, boolean created, float distance) {
            this.speaker = speaker;
            this.created = created;
            this.distance = distance;
        }
    }

    private final List<SpeakerProfile> centroids = new ArrayList<>();
    private final int maxSpeakers;
    private final float newSpeakerDistance;

    public SpeakerClusterer() {
        this(DEFAULT_MAX_SPEAKERS, DEFAULT_NEW_SPEAKER_DISTANCE);
    }

    public SpeakerClusterer(int maxSpeakers, float newSpeakerDistance) {
        this.maxSpeakers = maxSpeakers;
        this.newSpeakerDistance = newSpeakerDistance;
    }

    public int speakerCount() {
        return centroids.size();
    }

    public SpeakerProfile centroid(int speaker) {
        return centroids.get(speaker);
    }

    /** Assigns without updating any centroid – used for the live "who is talking" guess. */
    public Decision peek(SpeakerProfile utterance) {
        return decide(utterance, false);
    }

    /** Assigns and folds the utterance into the chosen (or new) centroid. */
    public Decision assign(SpeakerProfile utterance) {
        return decide(utterance, true);
    }

    private Decision decide(SpeakerProfile utterance, boolean commit) {
        if (centroids.isEmpty()) {
            if (commit) {
                centroids.add(utterance.copy());
            }
            return new Decision(0, true, 0f);
        }
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < centroids.size(); i++) {
            float d = utterance.distanceTo(centroids.get(i));
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        float threshold = thresholdFor(utterance.voicedFrames());
        boolean create = bestDistance > threshold
                && utterance.voicedFrames() >= MIN_FRAMES_FOR_NEW
                && centroids.size() < maxSpeakers;
        if (create) {
            if (commit) {
                centroids.add(utterance.copy());
            }
            return new Decision(centroids.size() - (commit ? 1 : 0), true, bestDistance);
        }
        // A forced match (too short to found a speaker, or the speaker cap) must not
        // drag the centroid towards a voice that may not be this speaker's at all.
        if (commit && bestDistance <= threshold) {
            centroids.get(best).blend(utterance, CENTROID_MEMORY_FRAMES);
        }
        return new Decision(best, false, bestDistance);
    }

    /** Threshold grows for short utterances: fewer frames → less evidence for a new voice. */
    public float thresholdFor(int voicedFrames) {
        float shortage = Math.max(0f, 1f - voicedFrames / (float) CONFIDENT_FRAMES);
        return newSpeakerDistance * (1f + 0.8f * shortage);
    }

    public void reset() {
        centroids.clear();
    }
}
