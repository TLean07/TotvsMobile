package com.tlean07.totvs.ui.components;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.databinding.ItemAlertaBinding;
import com.tlean07.totvs.network.dto.AlertaResponse;

import java.util.ArrayList;
import java.util.List;

/** Recent alerts list. */
public class AlertaAdapter extends RecyclerView.Adapter<AlertaAdapter.VH> {

    public interface OnClick {
        void onClick(AlertaResponse alerta);
    }

    private final List<AlertaResponse> items = new ArrayList<>();
    private OnClick onClick;

    public void setOnClick(OnClick listener) {
        this.onClick = listener;
    }

    public void submit(List<AlertaResponse> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAlertaBinding b = ItemAlertaBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AlertaResponse a = items.get(position);
        holder.b.alertaTitulo.setText(a.titulo != null ? a.titulo : "Alerta");
        holder.b.alertaMensagem.setText(a.mensagem != null ? a.mensagem : "");
        StringBuilder meta = new StringBuilder();
        if (a.prioridade != null) {
            meta.append(a.prioridade);
        }
        if (a.ocorrencias != null) {
            if (meta.length() > 0) meta.append(" • ");
            meta.append(a.ocorrencias).append("x");
        }
        String nota = new com.tlean07.totvs.ui.alertas.AlertNotes(holder.b.getRoot().getContext()).get(a.id);
        if (nota != null) {
            if (meta.length() > 0) meta.append(" • ");
            meta.append(holder.b.getRoot().getContext().getString(com.tlean07.totvs.R.string.alert_treated, nota));
        }
        holder.b.alertaMeta.setText(meta.toString());
        holder.b.alertaSeverity.setBackgroundColor(severityColor(a.prioridade));
        holder.b.getRoot().setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(a);
            }
        });
    }

    private int severityColor(String prioridade) {
        if (prioridade == null) {
            return Color.parseColor("#94A3B8");
        }
        switch (prioridade.toUpperCase()) {
            case "ALTO":
            case "ALTA":
            case "CRITICA":
            case "CRITICAL":
            case "HIGH":
                return Color.parseColor("#E11D48");
            case "MEDIO":
            case "MEDIA":
            case "MEDIUM":
                return Color.parseColor("#D97706");
            default:
                return Color.parseColor("#2B6CB0");
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemAlertaBinding b;

        VH(ItemAlertaBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
