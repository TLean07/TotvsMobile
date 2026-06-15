package com.tlean07.totvs.ui.insights;

import com.tlean07.totvs.network.dto.OportunidadeConsolidadaDto;
import com.tlean07.totvs.network.dto.OportunidadeDto;
import com.tlean07.totvs.network.dto.RiscoConsolidadoDto;
import com.tlean07.totvs.network.dto.RiscoDto;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class InsightMapper {

    private InsightMapper() {
    }

    public static List<InsightCardItem> riscos(List<TranscricaoInsightsResponse> transcricoes) {
        List<InsightCardItem> result = new ArrayList<>();
        if (transcricoes == null) return result;

        for (TranscricaoInsightsResponse transcricao : transcricoes) {
            if (transcricao.riscosConsolidados != null && !transcricao.riscosConsolidados.isEmpty()) {
                for (int i = 0; i < transcricao.riscosConsolidados.size(); i++) {
                    RiscoConsolidadoDto risco = transcricao.riscosConsolidados.get(i);
                    result.add(new InsightCardItem(
                            transcricao.id + "-risco-" + i,
                            transcricao.id,
                            cliente(transcricao.cliente),
                            text(risco.categoria, "Risco"),
                            normalizarTipoRisco(risco.categoria),
                            text(risco.severidade, "MEDIO"),
                            Math.max(1, risco.ocorrencias),
                            evidencias(risco.evidencias),
                            text(risco.recomendacao, "Priorizar revisão comercial e acompanhar a evolução do cliente."),
                            false,
                            true));
                }
            } else if (transcricao.riscos != null) {
                for (int i = 0; i < transcricao.riscos.size(); i++) {
                    RiscoDto risco = transcricao.riscos.get(i);
                    result.add(new InsightCardItem(
                            transcricao.id + "-risco-legado-" + i,
                            transcricao.id,
                            cliente(transcricao.cliente),
                            text(risco.tipo, "INSATISFACAO"),
                            text(risco.tipo, "INSATISFACAO"),
                            text(risco.severidade, "MEDIO"),
                            1,
                            risco.descricao == null ? List.of() : List.of(risco.descricao),
                            risco.concorrenteMencionado != null
                                    ? "Comparar a proposta de " + risco.concorrenteMencionado + " e reforçar diferenciais TOTVS."
                                    : "Validar a causa raiz e criar um plano de acompanhamento.",
                            true,
                            true));
                }
            }
        }
        result.sort(Comparator.comparingInt(item -> severidade(item.badge)));
        return result;
    }

    public static List<InsightCardItem> oportunidades(List<TranscricaoInsightsResponse> transcricoes) {
        List<InsightCardItem> result = new ArrayList<>();
        if (transcricoes == null) return result;

        for (TranscricaoInsightsResponse transcricao : transcricoes) {
            if (transcricao.oportunidadesConsolidadas != null
                    && !transcricao.oportunidadesConsolidadas.isEmpty()) {
                for (int i = 0; i < transcricao.oportunidadesConsolidadas.size(); i++) {
                    OportunidadeConsolidadaDto oportunidade = transcricao.oportunidadesConsolidadas.get(i);
                    result.add(new InsightCardItem(
                            transcricao.id + "-oportunidade-" + i,
                            transcricao.id,
                            cliente(transcricao.cliente),
                            text(oportunidade.categoria, "Oportunidade"),
                            normalizarTipoOportunidade(oportunidade.categoria),
                            oportunidade.score == null ? "" : Math.round(oportunidade.score * 100) + "% confiança",
                            Math.max(1, oportunidade.ocorrencias),
                            evidencias(oportunidade.evidencias),
                            text(oportunidade.recomendacao,
                                    "Validar aderência e encaminhar a próxima ação comercial."),
                            false,
                            false));
                }
            } else if (transcricao.oportunidades != null) {
                for (int i = 0; i < transcricao.oportunidades.size(); i++) {
                    OportunidadeDto oportunidade = transcricao.oportunidades.get(i);
                    result.add(new InsightCardItem(
                            transcricao.id + "-oportunidade-legada-" + i,
                            transcricao.id,
                            cliente(transcricao.cliente),
                            text(oportunidade.tipo, "NOVO_PRODUTO"),
                            text(oportunidade.tipo, "NOVO_PRODUTO"),
                            oportunidade.score == null ? "" : Math.round(oportunidade.score * 100) + "% confiança",
                            1,
                            oportunidade.descricao == null ? List.of() : List.of(oportunidade.descricao),
                            oportunidade.produtoRelacionado != null
                                    ? "Validar interesse em " + oportunidade.produtoRelacionado + " e estimar proposta."
                                    : "Transformar o sinal em próxima ação com responsável definido.",
                            true,
                            false));
                }
            }
        }
        result.sort((left, right) -> Integer.compare(right.ocorrencias, left.ocorrencias));
        return result;
    }

    private static String cliente(String value) {
        return text(value, "Cliente não informado");
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> evidencias(List<String> values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
                if (result.size() == 5) break;
            }
        }
        return result;
    }

    private static int severidade(String value) {
        if ("ALTO".equals(value)) return 0;
        if ("MEDIO".equals(value)) return 1;
        return 2;
    }

    private static String normalizarTipoRisco(String categoria) {
        String value = normalizar(categoria);
        if (value.contains("CHURN") || value.contains("RETENCAO")) return "CHURN";
        if (value.contains("CONCORR")) return "CONCORRENCIA";
        if (value.contains("CONFIAN")) return "PERDA_CONFIANCA";
        return "INSATISFACAO";
    }

    private static String normalizarTipoOportunidade(String categoria) {
        String value = normalizar(categoria);
        if (value.contains("UPSELL") || value.contains("MODULO") || value.contains("LICENCA")) return "UPSELL";
        if (value.contains("CROSS")) return "CROSS_SELL";
        return "NOVO_PRODUTO";
    }

    private static String normalizar(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }
}
