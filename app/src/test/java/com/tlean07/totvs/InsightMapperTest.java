package com.tlean07.totvs;

import com.tlean07.totvs.network.dto.OportunidadeConsolidadaDto;
import com.tlean07.totvs.network.dto.RiscoConsolidadoDto;
import com.tlean07.totvs.network.dto.RiscoDto;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;
import com.tlean07.totvs.ui.insights.InsightCardItem;
import com.tlean07.totvs.ui.insights.InsightMapper;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InsightMapperTest {

    @Test
    public void mapsAllConsolidatedRisksAndNormalizesTypes() {
        RiscoConsolidadoDto churn = new RiscoConsolidadoDto();
        churn.categoria = "Risco de churn";
        churn.severidade = "ALTO";
        churn.ocorrencias = 4;
        churn.evidencias = List.of("Cliente indicou possível saída");

        RiscoConsolidadoDto concorrencia = new RiscoConsolidadoDto();
        concorrencia.categoria = "Concorrência";
        concorrencia.severidade = "MEDIO";
        concorrencia.ocorrencias = 2;

        TranscricaoInsightsResponse source = new TranscricaoInsightsResponse();
        source.id = "t-1";
        source.cliente = "Cliente A";
        source.riscosConsolidados = List.of(churn, concorrencia);

        List<InsightCardItem> result = InsightMapper.riscos(List.of(source));

        assertEquals(2, result.size());
        assertEquals("CHURN", result.get(0).tipo);
        assertEquals("CONCORRENCIA", result.get(1).tipo);
        assertEquals(4, result.get(0).ocorrencias);
        assertFalse(result.get(0).legado);
    }

    @Test
    public void fallsBackToLegacyRiskWhenThereIsNoConsolidatedData() {
        RiscoDto legacy = new RiscoDto();
        legacy.tipo = "INSATISFACAO";
        legacy.severidade = "BAIXO";
        legacy.descricao = "Atendimento abaixo da expectativa";

        TranscricaoInsightsResponse source = new TranscricaoInsightsResponse();
        source.id = "t-2";
        source.riscos = List.of(legacy);

        List<InsightCardItem> result = InsightMapper.riscos(List.of(source));

        assertEquals(1, result.size());
        assertTrue(result.get(0).legado);
        assertEquals("INSATISFACAO", result.get(0).tipo);
    }

    @Test
    public void mapsOpportunitiesAndKeepsEveryMeeting() {
        OportunidadeConsolidadaDto first = new OportunidadeConsolidadaDto();
        first.categoria = "Upsell de módulo";
        first.ocorrencias = 3;
        first.score = 0.85;

        OportunidadeConsolidadaDto second = new OportunidadeConsolidadaDto();
        second.categoria = "Cross-sell";
        second.ocorrencias = 1;

        TranscricaoInsightsResponse one = new TranscricaoInsightsResponse();
        one.id = "t-1";
        one.oportunidadesConsolidadas = List.of(first);
        TranscricaoInsightsResponse two = new TranscricaoInsightsResponse();
        two.id = "t-2";
        two.oportunidadesConsolidadas = List.of(second);

        List<InsightCardItem> result = InsightMapper.oportunidades(List.of(one, two));

        assertEquals(2, result.size());
        assertEquals("UPSELL", result.get(0).tipo);
        assertEquals("CROSS_SELL", result.get(1).tipo);
    }
}
