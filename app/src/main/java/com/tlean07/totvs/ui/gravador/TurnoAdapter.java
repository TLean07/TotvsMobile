package com.tlean07.totvs.ui.gravador;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.databinding.ItemTurnoBinding;

import java.util.ArrayList;
import java.util.List;

/** Speaker turns as chat-like bubbles; used live (provisional turns in italics) and in the review. */
public class TurnoAdapter extends RecyclerView.Adapter<TurnoAdapter.VH> {

    public interface OnClick {
        void onClick(int position, TranscriptTurn turn);
    }

    private final List<TranscriptTurn> items = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    @Nullable
    private final OnClick onClick;

    public TurnoAdapter(@Nullable OnClick onClick) {
        this.onClick = onClick;
    }

    public void setNames(List<String> newNames) {
        names.clear();
        if (newNames != null) {
            names.addAll(newNames);
        }
        notifyDataSetChanged();
    }

    /** Replaces the whole list; cheap enough for a meeting's worth of turns. */
    public void submit(List<TranscriptTurn> turns) {
        items.clear();
        if (turns != null) {
            items.addAll(turns);
        }
        notifyDataSetChanged();
    }

    public List<TranscriptTurn> items() {
        return items;
    }

    public String nameOf(int speaker) {
        if (speaker >= 0 && speaker < names.size() && names.get(speaker) != null && !names.get(speaker).isBlank()) {
            return names.get(speaker);
        }
        return speaker < 0 ? "…" : TranscriptFormatter.defaultName(speaker);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemTurnoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TranscriptTurn t = items.get(position);
        ItemTurnoBinding b = holder.b;
        int color = SpeakerColors.of(b.getRoot().getContext(), t.speaker);
        b.turnoBar.setBackgroundColor(color);
        b.turnoFalante.setTextColor(color);
        b.turnoFalante.setText(nameOf(t.speaker));
        b.turnoTempo.setText(TranscriptFormatter.formatClock(t.startMs));
        b.turnoTexto.setText(t.text);
        b.turnoTexto.setTypeface(null, t.provisional ? Typeface.ITALIC : Typeface.NORMAL);
        b.turnoTexto.setAlpha(t.provisional ? 0.7f : 1f);
        b.getRoot().setOnClickListener(onClick == null ? null : v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                onClick.onClick(pos, items.get(pos));
            }
        });
        b.getRoot().setClickable(onClick != null);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemTurnoBinding b;

        VH(ItemTurnoBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
