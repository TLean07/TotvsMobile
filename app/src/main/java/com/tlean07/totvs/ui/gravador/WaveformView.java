package com.tlean07.totvs.ui.gravador;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tlean07.totvs.R;

/**
 * Scrolling level meter: one bar per audio chunk (40 ms), newest on the right,
 * coloured by the speaker talking at that moment. Idle bars are drawn muted so
 * silence reads as a flat line rather than noise.
 */
public class WaveformView extends View {

    private static final int BARS = 96;
    private static final float MIN_DB = -60f;
    private static final float MAX_DB = -6f;

    private final float[] levels = new float[BARS];
    private final int[] speakers = new int[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int head;
    private int idleColor;
    private boolean paused;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        idleColor = ContextCompat.getColor(context, R.color.border_light);
        java.util.Arrays.fill(levels, 0f);
        java.util.Arrays.fill(speakers, -1);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Feeds a level in dBFS for the speaker index currently detected (-1 = nobody). */
    public void push(float dbfs, int speaker) {
        float norm = (dbfs - MIN_DB) / (MAX_DB - MIN_DB);
        levels[head] = Math.max(0f, Math.min(1f, norm));
        speakers[head] = speaker;
        head = (head + 1) % BARS;
        invalidate();
    }

    /** Replays a history of levels (after re-attaching to a running session). */
    public void setHistory(float[] history) {
        java.util.Arrays.fill(levels, 0f);
        java.util.Arrays.fill(speakers, -1);
        head = 0;
        int start = Math.max(0, history.length - BARS);
        for (int i = start; i < history.length; i++) {
            push(history[i], -1);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        invalidate();
    }

    public void clear() {
        java.util.Arrays.fill(levels, 0f);
        java.util.Arrays.fill(speakers, -1);
        head = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) {
            return;
        }
        float slot = w / BARS;
        float barW = Math.max(2f, slot * 0.62f);
        float radius = barW / 2f;
        float mid = getPaddingTop() + h / 2f;
        for (int i = 0; i < BARS; i++) {
            int idx = (head + i) % BARS;
            float level = levels[idx];
            float barH = Math.max(3f, level * h);
            float x = getPaddingLeft() + i * slot + (slot - barW) / 2f;
            int color = speakers[idx] >= 0 && level > 0.08f ? SpeakerColors.of(getContext(), speakers[idx]) : idleColor;
            if (paused) {
                color = (color & 0x00FFFFFF) | 0x66000000;
            }
            paint.setColor(color);
            rect.set(x, mid - barH / 2f, x + barW, mid + barH / 2f);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
