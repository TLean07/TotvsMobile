package com.tlean07.totvs.ui.components;

import androidx.annotation.ColorRes;

/** A single dashboard metric card. */
public class StatItem {
    public static final String ACTION_RISCOS = "riscos";
    public static final String ACTION_OPORTUNIDADES = "oportunidades";

    public final String label;
    public final String value;
    public final String action;
    @ColorRes
    public final int accentColor;

    public StatItem(String label, String value, @ColorRes int accentColor) {
        this(label, value, accentColor, null);
    }

    public StatItem(String label, String value, @ColorRes int accentColor, String action) {
        this.label = label;
        this.value = value;
        this.accentColor = accentColor;
        this.action = action;
    }
}
