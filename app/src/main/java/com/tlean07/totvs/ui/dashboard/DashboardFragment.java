package com.tlean07.totvs.ui.dashboard;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.FragmentDashboardBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.databinding.ItemLegendBinding;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.ui.components.AlertaAdapter;
import com.tlean07.totvs.ui.components.BarChartView;
import com.tlean07.totvs.ui.components.ChartEntry;
import com.tlean07.totvs.ui.components.DonutChartView;
import com.tlean07.totvs.ui.components.StatAdapter;
import com.tlean07.totvs.ui.components.StatItem;
import com.tlean07.totvs.ui.alertas.AlertasActivity;
import com.tlean07.totvs.ui.insights.OportunidadesActivity;
import com.tlean07.totvs.ui.insights.RiscosActivity;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;
    private StatAdapter statAdapter;
    private final AlertaAdapter alertaAdapter = new AlertaAdapter();
    private DataRepository data;

    // Mesma identidade visual de cores/rótulos da versão web (dashboard).
    private static final Map<String, String[]> SENTIMENTO = map(new String[][]{
            {"POSITIVO", "Positivo", "#10B981"},
            {"NEGATIVO", "Negativo", "#F43F5E"},
            {"NEUTRO", "Neutro", "#94A3B8"},
            {"MISTO", "Misto", "#F59E0B"},
    });
    private static final Map<String, String[]> RISCO = map(new String[][]{
            {"CHURN", "Churn", "#E11D48"},
            {"CONCORRENCIA", "Concorrência", "#F97316"},
            {"INSATISFACAO", "Insatisfação", "#EAB308"},
            {"PERDA_CONFIANCA", "Confiança", "#7C3AED"},
    });
    private static final Map<String, String[]> OPORTUNIDADE = map(new String[][]{
            {"UPSELL", "Upsell", "#7C3AED"},
            {"CROSS_SELL", "Cross-sell", "#2563EB"},
            {"NOVO_PRODUTO", "Novo produto", "#059669"},
    });

    private static Map<String, String[]> map(String[][] defs) {
        LinkedHashMap<String, String[]> m = new LinkedHashMap<>();
        for (String[] d : defs) {
            m.put(d[0], new String[]{d[1], d[2]});
        }
        return m;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SafeAreaHelper.applyTopInset(binding.dashHeader);
        data = TotvsApp.get().dataRepository();

        statAdapter = new StatAdapter(this::openStat);
        binding.statsRecycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.statsRecycler.setAdapter(statAdapter);
        binding.alertsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.alertsRecycler.setAdapter(alertaAdapter);
        alertaAdapter.setOnClick(a -> openAlertas());

        AuthUser user = TotvsApp.get().session().getCurrentUser();
        if (user != null && user.name != null) {
            binding.dashGreeting.setText("Olá, " + firstName(user.name));
        }

        binding.swipeRefresh.setOnRefreshListener(this::load);
        binding.dashRetry.setOnClickListener(v -> load());
        binding.chartRiscoCard.setOnClickListener(v -> openRiscos());
        binding.chartOportunidadeCard.setOnClickListener(v -> openOportunidades());
        load();
    }

    private void load() {
        binding.swipeRefresh.setRefreshing(true);
        hideState();
        data.dashboard(result -> {
            // The view may have been destroyed while the request was in flight
            // (tab switch, lock/unlock) — drop the result if so.
            if (binding == null) {
                return;
            }
            binding.swipeRefresh.setRefreshing(false);
            if (result.success && result.data != null) {
                render(result.data);
            } else {
                showError(result.errorMessage);
            }
        });
    }

    private void render(DashboardResponse d) {
        List<StatItem> stats = new ArrayList<>();
        stats.add(new StatItem("Transcrições", String.valueOf(d.totalTranscricoes), R.color.totvs_secondary));
        stats.add(new StatItem("Processadas", String.valueOf(d.transcricoesProcessadas), R.color.totvs_accent));
        stats.add(new StatItem("Oportunidades", String.valueOf(d.totalOportunidades),
                R.color.totvs_opportunity, StatItem.ACTION_OPORTUNIDADES));
        stats.add(new StatItem("Riscos", String.valueOf(d.totalRiscos),
                R.color.totvs_danger, StatItem.ACTION_RISCOS));
        stats.add(new StatItem("Alertas novos", String.valueOf(d.alertasNovos),
                R.color.totvs_warning, StatItem.ACTION_ALERTAS));
        // "Churn" = riscos do tipo CHURN (mesmo dado do gráfico/web); o campo
        // churnsPotenciais do backend não é populado e vinha sempre zerado.
        stats.add(new StatItem("Churn", String.valueOf(churnCount(d)),
                R.color.totvs_danger, StatItem.ACTION_RISCOS));
        statAdapter.submit(stats);

        renderCharts(d);

        boolean hasAlerts = d.alertasRecentes != null && !d.alertasRecentes.isEmpty();
        binding.alertsTitle.setVisibility(hasAlerts ? View.VISIBLE : View.GONE);
        alertaAdapter.submit(d.alertasRecentes);
    }

    /** Churn count derived from the consolidated risk distribution (same as the chart). */
    private long churnCount(DashboardResponse d) {
        if (d.riscoPorTipo == null) {
            return 0;
        }
        Long v = d.riscoPorTipo.get("CHURN");
        return v == null ? 0 : v;
    }

    private void renderCharts(DashboardResponse d) {
        List<ChartEntry> sentimento = buildEntries(d.sentimentoDistribuicao, SENTIMENTO);
        if (sentimento.isEmpty()) {
            binding.chartSentimentoCard.setVisibility(View.GONE);
        } else {
            binding.chartSentimentoCard.setVisibility(View.VISIBLE);
            binding.donutSentimento.setData(sentimento);
            renderLegend(sentimento);
        }

        bindBar(buildEntries(d.riscoPorTipo, RISCO), binding.chartRiscoCard, binding.barRisco);
        bindBar(buildEntries(d.oportunidadePorTipo, OPORTUNIDADE), binding.chartOportunidadeCard, binding.barOportunidade);
    }

    private void bindBar(List<ChartEntry> entries, View card, BarChartView bar) {
        if (entries.isEmpty()) {
            card.setVisibility(View.GONE);
        } else {
            card.setVisibility(View.VISIBLE);
            bar.setData(entries);
        }
    }

    private List<ChartEntry> buildEntries(Map<String, Long> source, Map<String, String[]> meta) {
        List<ChartEntry> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        // Preserve the canonical order, then append any unexpected keys.
        for (Map.Entry<String, String[]> m : meta.entrySet()) {
            Long value = source.get(m.getKey());
            if (value != null && value > 0) {
                out.add(new ChartEntry(m.getValue()[0], value, Color.parseColor(m.getValue()[1])));
            }
        }
        for (Map.Entry<String, Long> s : source.entrySet()) {
            if (!meta.containsKey(s.getKey()) && s.getValue() != null && s.getValue() > 0) {
                out.add(new ChartEntry(s.getKey(), s.getValue(), Color.parseColor("#64748B")));
            }
        }
        return out;
    }

    private void renderLegend(List<ChartEntry> entries) {
        binding.legendSentimento.removeAllViews();
        for (ChartEntry e : entries) {
            ItemLegendBinding lb = ItemLegendBinding.inflate(
                    getLayoutInflater(), binding.legendSentimento, false);
            lb.legendDot.setBackgroundColor(e.color);
            lb.legendLabel.setText(e.label);
            lb.legendValue.setText(String.valueOf(e.value));
            binding.legendSentimento.addView(lb.getRoot());
        }
    }

    private void showError(String message) {
        binding.dashState.setText(message != null ? message : getString(R.string.error_generic));
        binding.dashState.setVisibility(View.VISIBLE);
        binding.dashRetry.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        binding.dashState.setVisibility(View.GONE);
        binding.dashRetry.setVisibility(View.GONE);
    }

    private String firstName(String name) {
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    private void openStat(StatItem item) {
        if (StatItem.ACTION_RISCOS.equals(item.action)) {
            openRiscos();
        } else if (StatItem.ACTION_OPORTUNIDADES.equals(item.action)) {
            openOportunidades();
        } else if (StatItem.ACTION_ALERTAS.equals(item.action)) {
            openAlertas();
        }
    }

    private void openRiscos() {
        startActivity(new Intent(requireContext(), RiscosActivity.class));
    }

    private void openOportunidades() {
        startActivity(new Intent(requireContext(), OportunidadesActivity.class));
    }

    private void openAlertas() {
        startActivity(new Intent(requireContext(), AlertasActivity.class));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
