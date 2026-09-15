package com.tlean07.totvs.ui.components;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.R;
import com.tlean07.totvs.databinding.ItemTranscricaoBinding;
import com.tlean07.totvs.network.dto.TranscricaoResponse;

import java.util.ArrayList;
import java.util.List;

/** Transcriptions (AI analyses) list with a row click callback. */
public class TranscricaoAdapter extends RecyclerView.Adapter<TranscricaoAdapter.VH> {

    public interface OnClick {
        void onClick(TranscricaoResponse item);
    }

    private final List<TranscricaoResponse> items = new ArrayList<>();
    private final OnClick onClick;

    public TranscricaoAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<TranscricaoResponse> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        int count = items.size();
        items.clear();
        if (count > 0) {
            notifyItemRangeRemoved(0, count);
        }
    }

    public void append(List<TranscricaoResponse> newItems) {
        if (newItems == null || newItems.isEmpty()) {
            return;
        }
        int start = items.size();
        items.addAll(newItems);
        notifyItemRangeInserted(start, newItems.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTranscricaoBinding b = ItemTranscricaoBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TranscricaoResponse t = items.get(position);
        String cliente = t.cliente != null && !t.cliente.isBlank() ? t.cliente : "Reunião sem cliente";
        holder.b.transcricaoCliente.setText(cliente);
        String resumo = t.resumoTexto();
        if (resumo == null) {
            resumo = "ERRO".equalsIgnoreCase(t.status) ? "A análise falhou. Abra para reprocessar."
                    : "Análise em processamento…";
        }
        holder.b.transcricaoResumo.setText(resumo);
        applyStatus(holder.b.transcricaoStatus, t.status);
        holder.b.getRoot().setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(t);
            }
        });
    }

    /** Colours the status pill by state, using theme-aware (light/dark) chips. */
    private void applyStatus(TextView view, String status) {
        view.setText(status != null ? status : "—");
        int bg;
        int fg;
        String s = status == null ? "" : status.toUpperCase();
        if (s.startsWith("PROCESSAD") || s.equals("CONCLUIDA") || s.equals("OK")) {
            bg = R.drawable.bg_pill_success;
            fg = R.color.chip_success_fg;
        } else if (s.startsWith("PROCESSAND") || s.equals("ANALISANDO")) {
            bg = R.drawable.bg_pill_role;
            fg = R.color.chip_role_fg;
        } else if (s.startsWith("PENDENTE") || s.startsWith("AGUARD")) {
            bg = R.drawable.bg_pill_warning;
            fg = R.color.chip_warning_fg;
        } else if (s.startsWith("ERRO") || s.startsWith("FALH")) {
            bg = R.drawable.bg_pill_danger;
            fg = R.color.chip_danger_fg;
        } else {
            bg = R.drawable.bg_pill_muted;
            fg = R.color.chip_muted_fg;
        }
        view.setBackgroundResource(bg);
        view.setTextColor(ContextCompat.getColor(view.getContext(), fg));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemTranscricaoBinding b;

        VH(ItemTranscricaoBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
