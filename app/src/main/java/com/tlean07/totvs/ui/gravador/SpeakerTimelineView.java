package com.tlean07.totvs.ui.gravador;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tlean07.totvs.R;
import com.tlean07.totvs.audio.store.Recording;

import java.util.ArrayList;
import java.util.List;

/**
 * Seek bar for the review player whose track is painted with who was talking
 * when – the whole meeting at a glance – plus tick marks for the moments the
 * user flagged while recording.
 */
public class SpeakerTimelineView extends View {

    public interface OnSeek {
        void onSeek(long positionMs);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<Recording.Turno> turnos = new ArrayList<>();
    private final List<Long> marcadores = new ArrayList<>();
    private long durationMs = 1;
    private long positionMs;
    private int trackColor;
    private int knobColor;
    private int markerColor;
    private OnSeek onSeek;

    public SpeakerTimelineView(Context context) {
        this(context, null);
    }

    public SpeakerTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        trackColor = ContextCompat.getColor(context, R.color.chart_track);
        knobColor = ContextCompat.getColor(context, R.color.text_primary_light);
        markerColor = ContextCompat.getColor(context, R.color.totvs_warning);
    }

    public void bind(Recording r) {
        turnos.clear();
        turnos.addAll(r.turnos);
        marcadores.clear();
        for (Recording.Marcador m : r.marcadores) {
            marcadores.add(m.tMs);
        }
        durationMs = Math.max(1, r.duracaoMs);
        invalidate();
    }

    public void setPosition(long ms) {
        positionMs = Math.max(0, Math.min(durationMs, ms));
        invalidate();
    }

    public void setOnSeek(OnSeek l) {
        onSeek = l;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (onSeek == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float w = getWidth() - getPaddingLeft() - getPaddingRight();
                float frac = Math.max(0f, Math.min(1f, (event.getX() - getPaddingLeft()) / w));
                positionMs = (long) (frac * durationMs);
                invalidate();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    onSeek.onSeek(positionMs);
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft();
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float trackH = Math.min(h, 14f * getResources().getDisplayMetrics().density);
        float top = getPaddingTop() + (h - trackH) / 2f;
        float radius = trackH / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        rect.set(left, top, left + w, top + trackH);
        canvas.drawRoundRect(rect, radius, radius, paint);

        for (Recording.Turno t : turnos) {
            float x0 = left + w * t.inicioMs / durationMs;
            float x1 = left + w * Math.max(t.fimMs, t.inicioMs + 200) / durationMs;
            paint.setColor(SpeakerColors.of(getContext(), t.falante));
            rect.set(x0, top, Math.min(left + w, x1), top + trackH);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        paint.setColor(markerColor);
        for (long m : marcadores) {
            float x = left + w * m / durationMs;
            rect.set(x - 2f, top - 4f, x + 2f, top + trackH + 4f);
            canvas.drawRoundRect(rect, 2f, 2f, paint);
        }

        float kx = left + w * positionMs / durationMs;
        paint.setColor(knobColor);
        canvas.drawCircle(kx, top + trackH / 2f, radius + 4f, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(kx, top + trackH / 2f, radius, paint);
    }
}
