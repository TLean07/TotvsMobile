package com.tlean07.totvs.ui.insights;

import com.tlean07.totvs.R;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;

import java.util.List;

public class OportunidadesActivity extends BaseInsightsActivity {

    private String tipo = "";

    @Override
    protected void configureScreen() {
        setHeader("Oportunidades", "Oportunidades consolidadas das reuniões");
        hideSecondaryFilters();
        addFilter(binding.insightPrimaryFilters, "Todas", "", true, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Upsell", "UPSELL", false, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Cross-sell", "CROSS_SELL", false, value -> tipo = value);
        addFilter(binding.insightPrimaryFilters, "Novo produto", "NOVO_PRODUTO", false, value -> tipo = value);
    }

    @Override
    protected List<InsightCardItem> map(List<TranscricaoInsightsResponse> source) {
        return InsightMapper.oportunidades(source);
    }

    @Override
    protected boolean matches(InsightCardItem item) {
        return tipo.isEmpty() || tipo.equals(item.tipo);
    }

    @Override
    protected String itemLabel(int count) {
        return count + (count == 1 ? " oportunidade consolidada" : " oportunidades consolidadas");
    }

    @Override
    protected String emptyMessage() {
        return "Nenhuma oportunidade encontrada para o filtro selecionado.";
    }

    @Override
    protected int accentColor() {
        return R.color.totvs_opportunity;
    }
}
