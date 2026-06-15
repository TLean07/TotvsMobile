package com.tlean07.totvs.ui.insights;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.R;
import com.tlean07.totvs.databinding.ItemInsightBinding;

import java.util.ArrayList;
import java.util.List;

public class InsightAdapter extends RecyclerView.Adapter<InsightAdapter.VH> {

    public interface OnClick {
        void onClick(InsightCardItem item);
    }

    private final List<InsightCardItem> items = new ArrayList<>();
    private final OnClick onClick;

    public InsightAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<InsightCardItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemInsightBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        InsightCardItem item = items.get(position);
        int accent = ContextCompat.getColor(holder.itemView.getContext(),
                item.risco ? R.color.totvs_danger : R.color.totvs_opportunity);
        holder.b.insightAccent.setBackgroundColor(accent);
        holder.b.insightCategory.setText(item.categoria);
        holder.b.insightCategory.setTextColor(accent);
        holder.b.insightBadge.setText(item.badge);
        holder.b.insightBadge.setVisibility(item.badge.isBlank() ? View.GONE : View.VISIBLE);
        holder.b.insightTitle.setText(item.risco ? "Risco consolidado" : "Oportunidade consolidada");
        holder.b.insightEvidence.setText(formatEvidence(item.evidencias));
        holder.b.insightEvidence.setVisibility(item.evidencias.isEmpty() ? View.GONE : View.VISIBLE);
        holder.b.insightOccurrences.setText("Cobertura interna: " + item.ocorrencias
                + (item.ocorrencias == 1 ? " ocorrência agrupada" : " ocorrências agrupadas"));
        holder.b.insightOccurrences.setVisibility(item.ocorrencias > 1 ? View.VISIBLE : View.GONE);
        holder.b.insightRecommendation.setText(item.recomendacao);
        holder.b.insightClient.setText(item.cliente);
        holder.b.insightMeta.setText(item.legado ? "Registro legado" : "Consolidado");
        holder.b.getRoot().setRippleColor(ColorStateList.valueOf(Color.argb(28,
                Color.red(accent), Color.green(accent), Color.blue(accent))));
        holder.b.getRoot().setOnClickListener(v -> onClick.onClick(item));
    }

    private String formatEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) return "";
        List<String> visible = evidence.subList(0, Math.min(3, evidence.size()));
        return "• " + TextUtils.join("\n• ", visible);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemInsightBinding b;

        VH(ItemInsightBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}
