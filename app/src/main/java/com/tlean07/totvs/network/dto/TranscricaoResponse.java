package com.tlean07.totvs.network.dto;

import java.util.List;
import java.util.Map;

/**
 * Mirror of the backend {@code TranscricaoResponse}. Lists come slim (no text, summary only);
 * {@code GET /transcricoes/{id}} fills everything the analysis detail screen shows.
 */
public class TranscricaoResponse {
    public String id;
    public String cliente;
    public String vendedor;
    public String participantes;
    public String origemTranscricao;
    public String status;
    public String dataReuniao;
    public String dataCriacao;
    public String dataProcessamento;
    public Integer duracaoMinutos;
    public String textoOriginal;
    public String transcriptionModel;
    public Double audioDurationSeconds;
    public String uf;
    public String nomeSegmento;
    public String resumoExecutivo;
    public Metricas metricasAgregadas;
    public List<RiscoConsolidadoDto> riscosConsolidados;
    public List<OportunidadeConsolidadaDto> oportunidadesConsolidadas;
    public List<AlertaConsolidado> alertasConsolidados;
    public List<ItemCritico> itensCriticos;
    /** The AI result, nested under "analise" (slim in lists, full in the detail). */
    public Analise analise;

    public static class Analise {
        public String id;
        public String sentimentoGeral;
        public String resumo;
        public String resumoExecutivo;
        public String personaIdentificada;
        public String budgetIdentificado;
        public Double scoreConfiancaVendedor;
        public Double scoreSentimento;
        public String proximaAcao;
        public String sentimentoDetalhe;
        public String dataAnalise;
        public List<Produto> produtos;
        public List<OportunidadeDto> oportunidades;
        public List<RiscoDto> riscos;
        public List<AlertaResponse> alertas;
        public List<RiscoConsolidadoDto> riscosConsolidados;
        public List<OportunidadeConsolidadaDto> oportunidadesConsolidadas;
        public List<AlertaConsolidado> alertasConsolidados;
        public List<ItemCritico> itensCriticos;
        public Metricas metricasAgregadas;
        public Map<String, Object> insightsAdicionais;
        public Map<String, Object> indicadoresQualidade;
    }

    public static class Produto {
        public String nomeProduto;
        /** ATUAL, OPORTUNIDADE or CONCORRENTE. */
        public String tipo;
        public String contexto;
    }

    public static class AlertaConsolidado {
        public String titulo;
        public String categoria;
        public String severidade;
        public int ocorrencias;
        public String motivo;
        public String recomendacao;
    }

    public static class ItemCritico {
        public String tipo;
        public String categoria;
        public String trecho;
        public String severidade;
    }

    public static class Metricas {
        public int totalPartes;
        public int partesAnalisadas;
        public int partesNegativas;
        public int partesNeutras;
        public int partesPositivas;
        public int riscosDetectados;
        public int oportunidadesDetectadas;
        public int churnsPotenciais;
        public int mencoesCriticas;
        public int alertasConsolidados;
    }

    /** Executive summary when present, otherwise the plain summary, otherwise null. */
    public String resumoTexto() {
        if (resumoExecutivo != null && !resumoExecutivo.isBlank()) {
            return resumoExecutivo;
        }
        if (analise == null) {
            return null;
        }
        if (analise.resumoExecutivo != null && !analise.resumoExecutivo.isBlank()) {
            return analise.resumoExecutivo;
        }
        return analise.resumo != null && !analise.resumo.isBlank() ? analise.resumo : null;
    }

    public List<RiscoConsolidadoDto> riscosConsolidadosOuVazio() {
        if (analise != null && analise.riscosConsolidados != null && !analise.riscosConsolidados.isEmpty()) {
            return analise.riscosConsolidados;
        }
        return riscosConsolidados == null ? List.of() : riscosConsolidados;
    }

    public List<OportunidadeConsolidadaDto> oportunidadesConsolidadasOuVazio() {
        if (analise != null && analise.oportunidadesConsolidadas != null && !analise.oportunidadesConsolidadas.isEmpty()) {
            return analise.oportunidadesConsolidadas;
        }
        return oportunidadesConsolidadas == null ? List.of() : oportunidadesConsolidadas;
    }

    public List<AlertaConsolidado> alertasConsolidadosOuVazio() {
        if (analise != null && analise.alertasConsolidados != null && !analise.alertasConsolidados.isEmpty()) {
            return analise.alertasConsolidados;
        }
        return alertasConsolidados == null ? List.of() : alertasConsolidados;
    }

    public List<ItemCritico> itensCriticosOuVazio() {
        if (analise != null && analise.itensCriticos != null && !analise.itensCriticos.isEmpty()) {
            return analise.itensCriticos;
        }
        return itensCriticos == null ? List.of() : itensCriticos;
    }
}
