package com.tlean07.totvs.ui.components;

/** One labelled, coloured slice/bar in a chart. */
public class ChartEntry {
    public final String label;
    public final long value;
    public final int color;

    public ChartEntry(String label, long value, int color) {
        this.label = label;
        this.value = value;
        this.color = color;
    }
}
