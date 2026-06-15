package com.tlean07.totvs.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Horizontal labelled bar chart (no third-party library) for risk/opportunity splits. */
public class BarChartView extends View {

    private final List<ChartEntry> entries = new ArrayList<>();
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private long max;

    private final float rowHeight;
    private final float barHeight;
    private final float labelHeight;

    public BarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        rowHeight = dp(44);
        barHeight = dp(12);
        labelHeight = dp(18);
        trackPaint.setColor(Color.parseColor("#EEF2F7"));
        labelPaint.setColor(Color.parseColor("#475569"));
        labelPaint.setTextSize(sp(13));
        valuePaint.setColor(Color.parseColor("#10294A"));
        valuePaint.setTextSize(sp(13));
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setData(List<ChartEntry> data) {
        entries.clear();
        max = 1;
        if (data != null) {
            for (ChartEntry e : data) {
                if (e.value > 0) {
                    entries.add(e);
                    max = Math.max(max, e.value);
                }
            }
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize((int) dp(280), widthMeasureSpec);
        int height = (int) (Math.max(entries.size(), 1) * rowHeight);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float radius = dp(6);

        for (int i = 0; i < entries.size(); i++) {
            ChartEntry e = entries.get(i);
            float top = i * rowHeight;
            float labelBaseline = top + labelHeight;

            canvas.drawText(e.label, left, labelBaseline, labelPaint);
            canvas.drawText(String.valueOf(e.value), right, labelBaseline, valuePaint);

            float barTop = labelBaseline + dp(8);
            rect.set(left, barTop, right, barTop + barHeight);
            canvas.drawRoundRect(rect, radius, radius, trackPaint);

            float fraction = (float) e.value / max;
            float fillRight = left + (right - left) * fraction;
            rect.set(left, barTop, Math.max(fillRight, left + barHeight), barTop + barHeight);
            barPaint.setColor(e.color);
            canvas.drawRoundRect(rect, radius, radius, barPaint);
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
