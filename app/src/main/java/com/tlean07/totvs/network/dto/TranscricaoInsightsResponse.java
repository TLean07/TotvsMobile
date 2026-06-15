package com.tlean07.totvs.network.dto;

import java.util.List;

public class TranscricaoInsightsResponse {
    public String id;
    public String cliente;
    public List<RiscoConsolidadoDto> riscosConsolidados;
    public List<OportunidadeConsolidadaDto> oportunidadesConsolidadas;
    public List<RiscoDto> riscos;
    public List<OportunidadeDto> oportunidades;
}
