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

/** Lightweight donut chart (no third-party library) used for the sentiment split. */
public class DonutChartView extends View {

    private final List<ChartEntry> entries = new ArrayList<>();
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float stroke;
    private long total;

    public DonutChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        stroke = dp(22);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(Color.parseColor("#EEF2F7"));
        valuePaint.setColor(Color.parseColor("#10294A"));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextSize(sp(26));
        labelPaint.setColor(Color.parseColor("#94A3B8"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(12));
    }

    public void setData(List<ChartEntry> data) {
        entries.clear();
        total = 0;
        if (data != null) {
            for (ChartEntry e : data) {
                if (e.value > 0) {
                    entries.add(e);
                    total += e.value;
                }
            }
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) dp(180);
        int w = resolveSize(size, widthMeasureSpec);
        int h = resolveSize(size, heightMeasureSpec);
        int side = Math.min(w, h);
        setMeasuredDimension(w, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = stroke / 2 + dp(4);
        float side = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = side / 2f;
        float radius = side / 2f - pad;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawArc(oval, 0, 360, false, trackPaint);

        if (total > 0) {
            float start = -90f;
            for (ChartEntry e : entries) {
                float sweep = 360f * e.value / total;
                arcPaint.setColor(e.color);
                canvas.drawArc(oval, start, sweep - 1.5f, false, arcPaint);
                start += sweep;
            }
        }

        String totalText = String.valueOf(total);
        canvas.drawText(totalText, cx, cy + sp(8), valuePaint);
        canvas.drawText("análises", cx, cy + sp(26), labelPaint);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
