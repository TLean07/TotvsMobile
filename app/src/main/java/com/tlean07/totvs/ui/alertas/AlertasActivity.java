package com.tlean07.totvs.ui.alertas;

import android.content.res.ColorStateList;
import android.os.Bundle;
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
import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.ui.components.AlertaAdapter;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Full alerts screen — same shell/UX as Riscos & Oportunidades, but backed by
 * the shared {@code GET /api/alertas} endpoint (the web "Alertas" page). Filters
 * by status and priority client-side; lets an alert be marked as seen.
 */
public class AlertasActivity extends AppCompatActivity {

    private ActivityInsightsBinding binding;
    private DataRepository data;
    private final AlertaAdapter adapter = new AlertaAdapter();
    private final List<AlertaResponse> all = new ArrayList<>();

    private String status = "";
    private String prioridade = "";

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

        binding.insightEyebrow.setText("MONITORAMENTO");
        binding.insightScreenTitle.setText("Alertas");

        adapter.setOnClick(this::showDetail);
        binding.insightRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.insightRecycler.setAdapter(adapter);
        binding.insightBack.setOnClickListener(v -> finish());
        binding.insightSwipe.setOnRefreshListener(() -> load(true));
        binding.insightRetry.setOnClickListener(v -> load());

        addFilter(binding.insightPrimaryFilters, "Todos", "", true, v -> status = v);
        addFilter(binding.insightPrimaryFilters, "Novos", "NOVO", false, v -> status = v);
        addFilter(binding.insightPrimaryFilters, "Vistos", "VISTO", false, v -> status = v);
        addFilter(binding.insightPrimaryFilters, "Resolvidos", "RESOLVIDO", false, v -> status = v);

        addFilter(binding.insightSecondaryFilters, "Todas prioridades", "", true, v -> prioridade = v);
        addFilter(binding.insightSecondaryFilters, "Alta", "ALTO", false, v -> prioridade = v);
        addFilter(binding.insightSecondaryFilters, "Média", "MEDIO", false, v -> prioridade = v);
        addFilter(binding.insightSecondaryFilters, "Baixa", "BAIXO", false, v -> prioridade = v);

        load();
    }

    private void load() {
        load(false);
    }

    private void load(boolean forceRefresh) {
        binding.insightState.setVisibility(View.GONE);
        if (all.isEmpty()) {
            binding.insightProgress.setVisibility(View.VISIBLE);
        }
        data.alertas(forceRefresh, result -> {
            if (binding == null) {
                return;
            }
            binding.insightProgress.setVisibility(View.GONE);
            binding.insightSwipe.setRefreshing(false);
            if (result.success) {
                all.clear();
                if (result.data != null) {
                    all.addAll(result.data);
                }
                renderFiltered();
            } else {
                adapter.submit(List.of());
                showState(result.errorMessage, true);
            }
        });
    }

    private void renderFiltered() {
        List<AlertaResponse> filtered = new ArrayList<>();
        int novos = 0;
        for (AlertaResponse a : all) {
            if ("NOVO".equalsIgnoreCase(a.status)) {
                novos++;
            }
            if (!status.isEmpty() && !status.equalsIgnoreCase(a.status)) {
                continue;
            }
            if (!prioridade.isEmpty() && !prioridade.equalsIgnoreCase(a.prioridade)) {
                continue;
            }
            filtered.add(a);
        }
        adapter.submit(filtered);
        binding.insightSubtitle.setText(filtered.size() == 1
                ? "1 alerta" : filtered.size() + " alertas");
        binding.insightStatTotal.setText(all.size() + "\nAlertas");
        binding.insightStatBases.setText(novos + "\nNovos");
        if (filtered.isEmpty()) {
            showState("Nenhum alerta para os filtros selecionados.", false);
        } else {
            binding.insightState.setVisibility(View.GONE);
        }
    }

    private void showDetail(AlertaResponse a) {
        StringBuilder msg = new StringBuilder();
        if (a.mensagem != null) {
            msg.append(a.mensagem);
        }
        if (a.recomendacao != null && !a.recomendacao.isBlank()) {
            msg.append("\n\nRecomendação:\n").append(a.recomendacao);
        }
        if (a.prioridade != null || a.status != null) {
            msg.append("\n\nPrioridade: ").append(a.prioridade)
                    .append("  •  Status: ").append(a.status);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(a.titulo != null ? a.titulo : "Alerta")
                .setMessage(msg.toString())
                .setPositiveButton("Fechar", null);

        String nota = new AlertNotes(this).get(a.id);
        if (nota != null) {
            b.setMessage(msg + "\n\n" + getString(R.string.alert_treated, nota));
        }
        b.setNeutralButton(R.string.alert_treat, (d, w) -> tratar(a));
        if ("NOVO".equalsIgnoreCase(a.status)) {
            b.setNegativeButton("Marcar como visto", (d, w) -> marcarVisto(a));
        }
        b.show();
    }

    /** "Tratado" note kept on the device (the backend only knows "visto"). */
    private void tratar(AlertaResponse a) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.alert_treat_hint);
        input.setMinLines(2);
        String atual = new AlertNotes(this).get(a.id);
        if (atual != null) {
            input.setText(atual);
        }
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.alert_treat)
                .setView(wrap)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String nota = input.getText().toString().trim();
                    if (nota.isEmpty()) {
                        return;
                    }
                    new AlertNotes(this).put(a.id, nota);
                    com.tlean07.totvs.security.AuditLog.get(this).record("alerta.tratado", a.id + " · " + nota);
                    android.widget.Toast.makeText(this, R.string.alert_treated_ok, android.widget.Toast.LENGTH_SHORT).show();
                    if ("NOVO".equalsIgnoreCase(a.status)) {
                        marcarVisto(a);
                    } else {
                        load();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void marcarVisto(AlertaResponse a) {
        data.marcarAlertaVisto(a.id, result -> {
            if (binding == null) {
                return;
            }
            if (result.success) {
                load();
            }
        });
    }

    private void showState(String message, boolean retry) {
        binding.insightStateText.setText(message != null ? message : getString(R.string.error_generic));
        binding.insightRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
        binding.insightState.setVisibility(View.VISIBLE);
    }

    // --- chip filters (same look as the insights screens) ---

    private void addFilter(ChipGroup group, String label, String value, boolean checked,
                           FilterSelection listener) {
        Chip chip = new Chip(this);
        chip.setId(View.generateViewId());
        chip.setTag(value);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setTextSize(13f);
        chip.setChipStrokeWidth(1f);
        chip.setChipBackgroundColor(stateColors(R.color.totvs_warning, R.color.surface_light));
        chip.setTextColor(stateColors(R.color.white, R.color.text_secondary_light));
        chip.setChipStrokeColor(stateColors(R.color.totvs_warning, R.color.border_light));
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

    @FunctionalInterface
    private interface FilterSelection {
        void onSelected(String value);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
