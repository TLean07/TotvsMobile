package com.tlean07.totvs.audio;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tlean07.totvs.audio.dsp.SpeakerProfile;

/**
 * Remembers the logged-in user's own voice between meetings. After a recording
 * the speaker closest to the stored print is labelled "Você" automatically;
 * marking a speaker as the vendedor on the review screen (re)teaches it.
 * Stored per user id in private preferences; never leaves the device.
 */
public final class VoicePrints {

    /** Distance below which a final speaker centroid is accepted as the user's voice. */
    public static final float MATCH_DISTANCE = 0.9f;
    private static final String PREFS = "voiceprints";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public VoicePrints(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public SpeakerProfile load(@Nullable String userId) {
        if (userId == null) {
            return null;
        }
        String json = prefs.getString(key(userId), null);
        if (json == null) {
            return null;
        }
        try {
            double[] data = gson.fromJson(json, double[].class);
            SpeakerProfile p = SpeakerProfile.fromExport(data);
            return p.isReliable() ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Blends a new sample into the stored print (or creates it). */
    public void learn(@Nullable String userId, @Nullable SpeakerProfile sample) {
        if (userId == null || sample == null || !sample.isReliable()) {
            return;
        }
        SpeakerProfile current = load(userId);
        if (current == null) {
            current = sample.copy();
        } else {
            current.blend(sample, 6_000);
        }
        prefs.edit().putString(key(userId), gson.toJson(current.export())).apply();
    }

    public void forget(@Nullable String userId) {
        if (userId != null) {
            prefs.edit().remove(key(userId)).apply();
        }
    }

    public boolean has(@Nullable String userId) {
        return load(userId) != null;
    }

    /** Index of the speaker whose profile matches the stored print, or -1. */
    public int match(@Nullable String userId, java.util.List<SpeakerProfile> speakers) {
        SpeakerProfile mine = load(userId);
        if (mine == null) {
            return -1;
        }
        int best = -1;
        float bestD = MATCH_DISTANCE;
        for (int i = 0; i < speakers.size(); i++) {
            SpeakerProfile s = speakers.get(i);
            if (!s.isReliable()) {
                continue;
            }
            float d = s.distanceTo(mine);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static String key(String userId) {
        return "print:" + userId;
    }
}
