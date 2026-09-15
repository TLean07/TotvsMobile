package com.tlean07.totvs.ui.analise;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tlean07.totvs.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentiment of one client over time: one dot per analysed meeting (oldest on
 * the left), joined by a line, coloured by the sentiment label. The vertical
 * axis is the sentiment score (-1 … +1).
 */
public class SentimentTimelineView extends View {

    public static final class Point {
        public final double score;
        public final String sentimento;
        public final String label;

        public Point(double score, String sentimento, String label) {
            this.score = score;
            this.sentimento = sentimento;
            this.label = label;
        }
    }

    private final List<Point> points = new ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public SentimentTimelineView(Context context) {
        this(context, null);
    }

    public SentimentTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setColor(ContextCompat.getColor(context, R.color.totvs_secondary));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(ContextCompat.getColor(context, R.color.border_light));
        text.setTextSize(10f * d);
        text.setColor(ContextCompat.getColor(context, R.color.text_muted_light));
    }

    public void setPoints(List<Point> pts) {
        points.clear();
        points.addAll(pts);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float left = 28 * d;
        float right = getWidth() - 8 * d;
        float top = 8 * d;
        float bottom = getHeight() - 18 * d;
        float mid = (top + bottom) / 2f;
        canvas.drawLine(left, top, right, top, grid);
        canvas.drawLine(left, mid, right, mid, grid);
        canvas.drawLine(left, bottom, right, bottom, grid);
        canvas.drawText("+1", 4 * d, top + 4 * d, text);
        canvas.drawText("0", 8 * d, mid + 4 * d, text);
        canvas.drawText("-1", 4 * d, bottom + 4 * d, text);
        if (points.isEmpty()) {
            return;
        }
        int n = points.size();
        path.reset();
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (left + right) / 2f : left + (right - left) * i / (n - 1);
            float y = (float) (mid - Math.max(-1, Math.min(1, points.get(i).score)) * (bottom - top) / 2f);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, line);
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            float x = n == 1 ? (left + right) / 2f : left + (right - left) * i / (n - 1);
            float y = (float) (mid - Math.max(-1, Math.min(1, p.score)) * (bottom - top) / 2f);
            dot.setColor(ContextCompat.getColor(getContext(), color(p.sentimento)));
            canvas.drawCircle(x, y, 5.5f * d, dot);
            dot.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, y, 2.5f * d, dot);
            if (p.label != null && (n <= 6 || i == 0 || i == n - 1 || i % Math.max(1, n / 5) == 0)) {
                float w = text.measureText(p.label);
                canvas.drawText(p.label, Math.max(left, Math.min(right - w, x - w / 2f)), getHeight() - 4 * d, text);
            }
        }
    }

    private static int color(String s) {
        if (s == null) {
            return R.color.speaker_unknown;
        }
        switch (s.toUpperCase()) {
            case "POSITIVO":
                return R.color.totvs_accent;
            case "NEGATIVO":
                return R.color.totvs_danger;
            case "MISTO":
                return R.color.totvs_warning;
            default:
                return R.color.speaker_unknown;
        }
    }
}
