package com.tlean07.totvs.ui.dashboard;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.FragmentDashboardBinding;
import com.tlean07.totvs.databinding.ItemLegendBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.network.dto.OportunidadeDto;
import com.tlean07.totvs.network.dto.RiscoDto;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.ui.alertas.AlertasActivity;
import com.tlean07.totvs.ui.components.AlertaAdapter;
import com.tlean07.totvs.ui.components.BarChartView;
import com.tlean07.totvs.ui.components.ChartEntry;
import com.tlean07.totvs.ui.components.StatAdapter;
import com.tlean07.totvs.ui.components.StatItem;
import com.tlean07.totvs.ui.insights.OportunidadesActivity;
import com.tlean07.totvs.ui.insights.RiscosActivity;
import com.tlean07.totvs.util.SafeAreaHelper;
import com.tlean07.totvs.widget.ResumoWidget;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Executive dashboard. Server numbers by default; with a period / UF / segment
 * filter the numbers are recomputed on the device from the full meeting list
 * and the insights list (the backend has no filtered aggregation yet).
 */
public class DashboardFragment extends Fragment {

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    private FragmentDashboardBinding binding;
    private StatAdapter statAdapter;
    private final AlertaAdapter alertaAdapter = new AlertaAdapter();
    private DataRepository data;
    private DashboardResponse server;
    private List<TranscricaoResponse> meetings;
    private List<TranscricaoInsightsResponse> insights;

    // filters
    private int periodDays = 0;
    private String uf = "";
    private String segmento = "";

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

        binding.swipeRefresh.setOnRefreshListener(() -> load(true));
        binding.dashRetry.setOnClickListener(v -> load(true));
        binding.chartRiscoCard.setOnClickListener(v -> openRiscos());
        binding.chartOportunidadeCard.setOnClickListener(v -> openOportunidades());
        buildChips();
        load(false);
    }

    private void load(boolean forceRefresh) {
        binding.swipeRefresh.setRefreshing(true);
        hideState();
        if (forceRefresh) {
            meetings = null;
            insights = null;
        }
        data.dashboard(forceRefresh, result -> {
            if (binding == null) {
                return;
            }
            binding.swipeRefresh.setRefreshing(false);
            if (result.success && result.data != null) {
                server = result.data;
                ResumoWidget.publish(requireContext(), server);
                if (filtering()) {
                    renderFiltered();
                } else {
                    render(server);
                }
            } else {
                showError(result.errorMessage);
            }
        });
    }

    // ---------------------------------------------------------------- filters

    private boolean filtering() {
        return periodDays > 0 || !uf.isEmpty() || !segmento.isEmpty();
    }

    private void buildChips() {
        binding.dashChips.removeAllViews();
        addChip(periodDays == 0 ? getString(R.string.dash_filter_period)
                : (periodDays + " dias"), R.color.totvs_primary, periodDays > 0, v -> pickPeriod());
        addChip(uf.isEmpty() ? getString(R.string.dash_filter_uf) : uf, R.color.totvs_secondary, !uf.isEmpty(), v -> pickValue(true));
        addChip(segmento.isEmpty() ? getString(R.string.dash_filter_segment) : segmento, R.color.totvs_opportunity,
                !segmento.isEmpty(), v -> pickValue(false));
        if (filtering()) {
            addChip(getString(R.string.dash_filter_clear), R.color.totvs_danger, false, v -> {
                periodDays = 0;
                uf = "";
                segmento = "";
                buildChips();
                if (server != null) {
                    render(server);
                }
                binding.dashFilterInfo.setVisibility(View.GONE);
            });
        }
    }

    private void addChip(String label, @ColorRes int color, boolean active, View.OnClickListener click) {
        Chip chip = new Chip(requireContext());
        chip.setText(label);
        chip.setCheckable(false);
        chip.setTextSize(12f);
        chip.setChipStrokeWidth(1f);
        chip.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                active ? color : R.color.surface_light)));
        chip.setTextColor(ContextCompat.getColor(requireContext(), active ? R.color.white : R.color.text_secondary_light));
        chip.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), active ? color : R.color.border_light)));
        chip.setOnClickListener(click);
        binding.dashChips.addView(chip);
    }

    private void pickPeriod() {
        String[] labels = {getString(R.string.dash_filter_all), "7 dias", "30 dias", "90 dias"};
        int[] days = {0, 7, 30, 90};
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dash_filter_period)
                .setItems(labels, (d, w) -> {
                    periodDays = days[w];
                    onFilterChanged();
                })
                .show();
    }

    private void pickValue(boolean isUf) {
        ensureMeetings(() -> {
            TreeSet<String> values = new TreeSet<>();
            for (TranscricaoResponse t : meetings) {
                String v = isUf ? t.uf : t.nomeSegmento;
                if (v != null && !v.isBlank()) {
                    values.add(v.trim());
                }
            }
            List<String> labels = new ArrayList<>();
            labels.add(getString(R.string.dash_filter_all));
            labels.addAll(values);
            new AlertDialog.Builder(requireContext())
                    .setTitle(isUf ? R.string.dash_filter_uf : R.string.dash_filter_segment)
                    .setItems(labels.toArray(new String[0]), (d, w) -> {
                        String chosen = w == 0 ? "" : labels.get(w);
                        if (isUf) {
                            uf = chosen;
                        } else {
                            segmento = chosen;
                        }
                        onFilterChanged();
                    })
                    .show();
        });
    }

    private void onFilterChanged() {
        buildChips();
        if (filtering()) {
            renderFiltered();
        } else if (server != null) {
            render(server);
            binding.dashFilterInfo.setVisibility(View.GONE);
        }
    }

    private void ensureMeetings(Runnable then) {
        if (meetings != null) {
            then.run();
            return;
        }
        binding.dashFilterInfo.setVisibility(View.VISIBLE);
        binding.dashFilterInfo.setText(R.string.dash_filter_loading);
        data.transcricoesTodas(false, result -> {
            if (binding == null) {
                return;
            }
            if (result.success && result.data != null) {
                meetings = result.data;
                then.run();
            } else {
                binding.dashFilterInfo.setText(result.errorMessage);
            }
        });
    }

    private void renderFiltered() {
        ensureMeetings(() -> {
            if (insights == null) {
                data.insights(false, result -> {
                    if (binding == null) {
                        return;
                    }
                    insights = result.success && result.data != null ? result.data : new ArrayList<>();
                    renderFiltered();
                });
                return;
            }
            long since = periodDays > 0 ? System.currentTimeMillis() - periodDays * 24L * 3600_000L : 0;
            Map<String, TranscricaoInsightsResponse> byId = new HashMap<>();
            for (TranscricaoInsightsResponse i : insights) {
                byId.put(i.id, i);
            }
            DashboardResponse d = new DashboardResponse();
            d.sentimentoDistribuicao = new LinkedHashMap<>();
            d.riscoPorTipo = new LinkedHashMap<>();
            d.oportunidadePorTipo = new LinkedHashMap<>();
            d.alertasNovos = server == null ? 0 : server.alertasNovos;
            d.alertasRecentes = server == null ? null : server.alertasRecentes;
            for (TranscricaoResponse t : meetings) {
                if (since > 0 && parse(t.dataCriacao) < since) {
                    continue;
                }
                if (!uf.isEmpty() && !uf.equalsIgnoreCase(t.uf == null ? "" : t.uf.trim())) {
                    continue;
                }
                if (!segmento.isEmpty() && !segmento.equalsIgnoreCase(t.nomeSegmento == null ? "" : t.nomeSegmento.trim())) {
                    continue;
                }
                d.totalTranscricoes++;
                if (t.status != null && t.status.toUpperCase(Locale.ROOT).startsWith("PROCESSAD")) {
                    d.transcricoesProcessadas++;
                }
                if (t.analise != null && t.analise.sentimentoGeral != null) {
                    d.sentimentoDistribuicao.merge(t.analise.sentimentoGeral.toUpperCase(Locale.ROOT), 1L, Long::sum);
                }
                TranscricaoInsightsResponse ins = byId.get(t.id);
                if (ins != null) {
                    if (ins.riscos != null) {
                        for (RiscoDto r : ins.riscos) {
                            d.totalRiscos++;
                            if (r.tipo != null) {
                                d.riscoPorTipo.merge(r.tipo.toUpperCase(Locale.ROOT), 1L, Long::sum);
                            }
                        }
                    }
                    if (ins.oportunidades != null) {
                        for (OportunidadeDto o : ins.oportunidades) {
                            d.totalOportunidades++;
                            if (o.tipo != null) {
                                d.oportunidadePorTipo.merge(o.tipo.toUpperCase(Locale.ROOT), 1L, Long::sum);
                            }
                        }
                    }
                }
            }
            binding.dashFilterInfo.setVisibility(View.VISIBLE);
            binding.dashFilterInfo.setText(getString(R.string.dash_filter_info, (int) d.totalTranscricoes, meetings.size()));
            render(d);
        });
    }

    private static long parse(String iso) {
        if (iso == null || iso.length() < 16) {
            return 0;
        }
        try {
            return ISO.parse(iso.substring(0, 16)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- rendering

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
        stats.add(new StatItem("Churn", String.valueOf(churnCount(d)),
                R.color.totvs_danger, StatItem.ACTION_RISCOS));
        statAdapter.submit(stats);

        renderCharts(d);

        boolean hasAlerts = d.alertasRecentes != null && !d.alertasRecentes.isEmpty();
        binding.alertsTitle.setVisibility(hasAlerts ? View.VISIBLE : View.GONE);
        alertaAdapter.submit(d.alertasRecentes);
    }

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
