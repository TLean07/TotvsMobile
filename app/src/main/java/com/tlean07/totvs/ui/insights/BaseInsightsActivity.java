package com.tlean07.totvs.ui.insights;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.ActivityInsightsBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class BaseInsightsActivity extends AppCompatActivity {

    protected ActivityInsightsBinding binding;
    protected final List<InsightCardItem> allItems = new ArrayList<>();

    private DataRepository data;
    private InsightAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityInsightsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        data = TotvsApp.get().dataRepository();
        SessionManager session = TotvsApp.get().session();
        SecurityManager.applySecureFlag(this, true, session);
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.insightHeader);
        SafeAreaHelper.applyBottomInset(binding.insightRoot);

        adapter = new InsightAdapter(this::showDetail);
        binding.insightRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.insightRecycler.setAdapter(adapter);
        binding.insightBack.setOnClickListener(v -> finish());
        binding.insightSwipe.setOnRefreshListener(() -> load(true));
        binding.insightRetry.setOnClickListener(v -> load(true));

        configureScreen();
        load(false);
    }

    protected abstract void configureScreen();

    protected abstract List<InsightCardItem> map(List<TranscricaoInsightsResponse> source);

    protected abstract boolean matches(InsightCardItem item);

    protected abstract String itemLabel(int count);

    protected abstract String emptyMessage();

    protected abstract @ColorRes int accentColor();

    protected void setHeader(String title, String subtitle) {
        binding.insightScreenTitle.setText(title);
        binding.insightSubtitle.setText(subtitle);
    }

    protected void hideSecondaryFilters() {
        binding.insightSecondaryFilterContainer.setVisibility(View.GONE);
    }

    protected void addFilter(ChipGroup group, String label, String value, boolean checked,
                             FilterSelection listener) {
        Chip chip = new Chip(this);
        chip.setId(View.generateViewId());
        chip.setTag(value);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setTextSize(13f);
        chip.setChipStrokeWidth(1f);
        chip.setChipBackgroundColor(stateColors(accentColor(), R.color.surface_light));
        chip.setTextColor(stateColors(R.color.white, R.color.text_secondary_light));
        chip.setChipStrokeColor(stateColors(accentColor(), R.color.border_light));
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked) {
                listener.onSelected((String) button.getTag());
                renderFiltered();
            }
        });
        group.addView(chip);
        chip.setChecked(checked);
    }

    private ColorStateList stateColors(@ColorRes int checkedColor, @ColorRes int defaultColor) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ContextCompat.getColor(this, checkedColor),
                        ContextCompat.getColor(this, defaultColor)});
    }

    private void load(boolean forceRefresh) {
        binding.insightState.setVisibility(View.GONE);
        if (allItems.isEmpty()) {
            binding.insightProgress.setVisibility(View.VISIBLE);
        }
        data.insights(forceRefresh, result -> {
            binding.insightProgress.setVisibility(View.GONE);
            binding.insightSwipe.setRefreshing(false);
            if (result.success && result.data != null) {
                allItems.clear();
                allItems.addAll(map(result.data));
                renderFiltered();
            } else {
                adapter.submit(List.of());
                showState(result.errorMessage, true);
            }
        });
    }

    protected void renderFiltered() {
        List<InsightCardItem> filtered = new ArrayList<>();
        Set<String> bases = new HashSet<>();
        for (InsightCardItem item : allItems) {
            if (matches(item)) {
                filtered.add(item);
                bases.add(item.transcricaoId);
            }
        }
        adapter.submit(filtered);
        binding.insightSubtitle.setText(itemLabel(filtered.size()));
        binding.insightStatTotal.setText(filtered.size() + "\nConsolidados");
        binding.insightStatBases.setText(bases.size() + "\nReuniões");
        if (filtered.isEmpty()) {
            showState(emptyMessage(), false);
        } else {
            binding.insightState.setVisibility(View.GONE);
        }
    }

    private void showState(String message, boolean retry) {
        binding.insightStateText.setText(message != null ? message : getString(R.string.error_generic));
        binding.insightRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
        binding.insightState.setVisibility(View.VISIBLE);
    }

    private void showDetail(InsightCardItem item) {
        String evidence = item.evidencias == null || item.evidencias.isEmpty()
                ? "Sem evidências adicionais."
                : "• " + TextUtils.join("\n• ", item.evidencias.subList(0,
                Math.min(5, item.evidencias.size())));
        new AlertDialog.Builder(this)
                .setTitle(item.categoria)
                .setMessage(item.cliente + "\n\n" + evidence + "\n\nRecomendação:\n" + item.recomendacao)
                .setPositiveButton("Fechar", null)
                .show();
    }

    @FunctionalInterface
    protected interface FilterSelection {
        void onSelected(String value);
    }
}
