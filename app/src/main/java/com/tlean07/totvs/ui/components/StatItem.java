package com.tlean07.totvs.ui.components;

import androidx.annotation.ColorRes;

/** A single dashboard metric card. */
public class StatItem {
    public final String label;
    public final String value;
    @ColorRes
    public final int accentColor;

    public StatItem(String label, String value, @ColorRes int accentColor) {
        this.label = label;
        this.value = value;
        this.accentColor = accentColor;
    }
}
