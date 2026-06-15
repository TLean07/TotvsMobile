package com.tlean07.totvs.ui.insights;

import com.tlean07.totvs.R;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;

import java.util.List;

public class RiscosActivity extends BaseInsightsActivity {

    private String tipo = "";
    private String severidade = "";

    @Override
    protected void configureScreen() {
        setHeader("Riscos", "Riscos consolidados das reuniões");
        addFilter(binding.insightPrimaryFilters, "Todos os tipos", "", true, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Churn", "CHURN", false, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Concorrência", "CONCORRENCIA", false, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Insatisfação", "INSATISFACAO", false, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Perda de confiança", "PERDA_CONFIANCA", false, value -> tipo = value);

        addFilter(binding.insightSecondaryFilters, "Todas severidades", "", true,
                value -> severidade = value);
        addFilter(binding.insightSecondaryFilters, "Alto", "ALTO", false,
                value -> severidade = value);
        addFilter(binding.insightSecondaryFilters, "Médio", "MEDIO", false,
                value -> severidade = value);
        addFilter(binding.insightSecondaryFilters, "Baixo", "BAIXO", false,
                value -> severidade = value);
    }

    @Override
    protected List<InsightCardItem> map(List<TranscricaoInsightsResponse> source) {
        return InsightMapper.riscos(source);
    }

    @Override
    protected boolean matches(InsightCardItem item) {
        return (tipo.isEmpty() || tipo.equals(item.tipo))
                && (severidade.isEmpty() || severidade.equals(item.badge));
    }

    @Override
    protected String itemLabel(int count) {
        return count + (count == 1 ? " risco consolidado" : " riscos consolidados");
    }

    @Override
    protected String emptyMessage() {
        return "Nenhum risco encontrado para os filtros selecionados.";
    }

    @Override
    protected int accentColor() {
        return R.color.totvs_danger;
    }
}
