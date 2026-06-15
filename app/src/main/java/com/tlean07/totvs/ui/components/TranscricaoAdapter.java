package com.tlean07.totvs.ui.components;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
        String resumo = t.resumoExecutivo != null && !t.resumoExecutivo.isBlank()
                ? t.resumoExecutivo
                : "Análise em processamento…";
        holder.b.transcricaoResumo.setText(resumo);
        holder.b.transcricaoStatus.setText(t.status != null ? t.status : "—");
        holder.b.getRoot().setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(t);
            }
        });
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
