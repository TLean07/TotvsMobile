package com.tlean07.totvs.ui.gravador;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.tlean07.totvs.R;

/** Stable colour per detected speaker, shared by every recorder screen. */
public final class SpeakerColors {

    private static final int[] COLORS = {
            R.color.speaker_1, R.color.speaker_2, R.color.speaker_3, R.color.speaker_4,
            R.color.speaker_5, R.color.speaker_6, R.color.speaker_7, R.color.speaker_8
    };

    private SpeakerColors() {
    }

    public static int of(Context context, int speaker) {
        if (speaker < 0) {
            return ContextCompat.getColor(context, R.color.speaker_unknown);
        }
        return ContextCompat.getColor(context, COLORS[speaker % COLORS.length]);
    }

    /** Same colour at ~14 % alpha for chip / bubble backgrounds. */
    public static int tint(Context context, int speaker) {
        int c = of(context, speaker);
        return (c & 0x00FFFFFF) | 0x24000000;
    }
}
