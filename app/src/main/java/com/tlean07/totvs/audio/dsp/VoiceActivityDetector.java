package com.tlean07.totvs.audio.dsp;

/**
 * Energy-based voice activity detector with an adaptive noise floor.
 *
 * The floor follows the quietest recent frames (drops instantly, rises slowly) so
 * a noisy meeting room and a silent office both end up with a threshold a few dB
 * above their own background. Onsets need a few consecutive loud frames; offsets
 * only after a hangover so short pauses between words don't split a sentence.
 */
public final class VoiceActivityDetector {

    /** Frames above threshold before speech is declared (3 × 16 ms). */
    private static final int ONSET_FRAMES = 3;
    private static final float THRESHOLD_ABOVE_FLOOR_DB = 9f;
    /** Never treat anything quieter than this as speech, even in digital silence. */
    private static final float MIN_THRESHOLD_DB = -52f;
    /** Floor climbs at most this much per frame (≈ 3 dB/s) when the room gets louder. */
    private static final float FLOOR_RISE_DB_PER_FRAME = 0.05f;
    private static final float FLOOR_DROP_SMOOTHING = 0.35f;

    private final int hangoverFrames;
    private float smoothed = -90f;
    private float floor = Float.NaN;
    private int aboveCount;
    private int belowCount;
    private boolean speech;

    /** @param hangoverMs silence needed after speech before the detector goes quiet */
    public VoiceActivityDetector(int hangoverMs) {
        this.hangoverFrames = Math.max(1, hangoverMs / (FeatureExtractor.HOP * 1000 / FeatureExtractor.SAMPLE_RATE));
    }

    /** Feeds one frame; returns true while the detector considers speech active. */
    public boolean update(float energyDb) {
        smoothed = 0.6f * smoothed + 0.4f * energyDb;
        if (Float.isNaN(floor)) {
            floor = smoothed;
        } else if (smoothed < floor) {
            floor += (smoothed - floor) * FLOOR_DROP_SMOOTHING;
        } else if (!speech) {
            floor += FLOOR_RISE_DB_PER_FRAME;
        } else {
            floor += FLOOR_RISE_DB_PER_FRAME * 0.2f;
        }
        float threshold = Math.max(floor + THRESHOLD_ABOVE_FLOOR_DB, MIN_THRESHOLD_DB);
        boolean loud = energyDb > threshold;
        if (loud) {
            aboveCount++;
            belowCount = 0;
            if (!speech && aboveCount >= ONSET_FRAMES) {
                speech = true;
            }
        } else {
            belowCount++;
            aboveCount = 0;
            if (speech && belowCount >= hangoverFrames) {
                speech = false;
            }
        }
        return speech;
    }

    public boolean isSpeech() {
        return speech;
    }

    public float noiseFloorDb() {
        return Float.isNaN(floor) ? -90f : floor;
    }

    public float thresholdDb() {
        return Math.max(noiseFloorDb() + THRESHOLD_ABOVE_FLOOR_DB, MIN_THRESHOLD_DB);
    }

    /** Frames of silence accumulated since the last loud frame. */
    public int silentFrames() {
        return belowCount;
    }

    public void reset() {
        smoothed = -90f;
        floor = Float.NaN;
        aboveCount = 0;
        belowCount = 0;
        speech = false;
    }
}
