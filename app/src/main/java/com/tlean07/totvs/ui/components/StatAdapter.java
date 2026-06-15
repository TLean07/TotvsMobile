package com.tlean07.totvs.ui.components;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.databinding.ItemStatBinding;

import java.util.ArrayList;
import java.util.List;

/** Grid of metric cards on the dashboard. */
public class StatAdapter extends RecyclerView.Adapter<StatAdapter.VH> {

    private final List<StatItem> items = new ArrayList<>();

    public void submit(List<StatItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemStatBinding b = ItemStatBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        StatItem item = items.get(position);
        holder.b.statValue.setText(item.value);
        holder.b.statLabel.setText(item.label);
        holder.b.statAccent.setBackgroundColor(
                ContextCompat.getColor(holder.b.getRoot().getContext(), item.accentColor));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemStatBinding b;

        VH(ItemStatBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
