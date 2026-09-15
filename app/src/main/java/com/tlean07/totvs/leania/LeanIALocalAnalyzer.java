package com.tlean07.totvs.leania;


import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor local da LeanIA (mesmas regras do ai-service Python, em Java).
 *
 * E usado quando o microsservico de IA nao responde (Space do Hugging Face dormindo, sem rede,
 * texto acima do limite), para que o backend continue entregando o resultado esperado do
 * briefing do Challenge: produtos atual/oportunidade/concorrente, alerta de churn/concorrencia,
 * sinal de upsell, persona, budget e sentimento por area, mais insights e indicadores da conversa.
 *
 * Pipeline: locutores -> papeis (vendedor x cliente) -> sentencas e oracoes -> anotacao
 * (areas, produtos, concorrentes, sentimento com negacao) -> extracoes -> consolidacao.
 */
public class LeanIALocalAnalyzer {

    // ---------------------------------------------------------------- catalogos

    private static final Map<String, String> PRODUTOS_TOTVS = new LinkedHashMap<>();
    private static final Map<String, String> CONCORRENTES = new LinkedHashMap<>();
    private static final Set<String> CONCORRENTES_AMBIGUOS = Set.of("senior", "oracle", "sage", "mega sistemas", "dynamics");

    static {
        String[][] produtos = {
                {"protheus", "TOTVS Protheus"}, {"proteus", "TOTVS Protheus"}, {"protheos", "TOTVS Protheus"},
                {"proteos", "TOTVS Protheus"}, {"microsiga", "TOTVS Protheus"}, {"totvs rm", "TOTVS RM"}, {"rm", "TOTVS RM"},
                {"rm labore", "TOTVS RM"}, {"fluig", "TOTVS Fluig"}, {"datasul", "TOTVS Datasul"}, {"winthor", "TOTVS Winthor"},
                {"techfin", "TOTVS Techfin"}, {"carol", "TOTVS Carol"}, {"rd station", "RD Station"}, {"rdstation", "RD Station"},
                {"totvs moda", "TOTVS Moda"}, {"totvs saude", "TOTVS Saude"}, {"totvs logistica", "TOTVS Logistica"},
                {"totvs educacional", "TOTVS Educacional"}, {"totvs hotelaria", "TOTVS Hotelaria"},
                {"totvs construcao", "TOTVS Construcao"}, {"totvs juridico", "TOTVS Juridico"}, {"totvs agro", "TOTVS Agro"},
                {"totvs varejo", "TOTVS Varejo"}, {"totvs cloud", "TOTVS Cloud"}, {"smart link", "TOTVS Smart Link"},
                {"gestao de pessoas", "TOTVS RM"},
        };
        for (String[] p : produtos) {
            PRODUTOS_TOTVS.put(p[0], p[1]);
        }
        String[][] concorrentes = {
                {"senior sistemas", "Senior"}, {"senior", "Senior"}, {"sap business one", "SAP Business One"},
                {"sap b1", "SAP Business One"}, {"sap", "SAP"}, {"oracle netsuite", "Oracle NetSuite"},
                {"netsuite", "Oracle NetSuite"}, {"oracle erp", "Oracle"}, {"oracle", "Oracle"}, {"sankhya", "Sankhya"},
                {"linx", "Linx"}, {"omie", "Omie"}, {"bling", "Bling"}, {"conta azul", "Conta Azul"}, {"tiny erp", "Tiny ERP"},
                {"sage", "Sage"}, {"alterdata", "Alterdata"}, {"mega sistemas", "Mega Sistemas"}, {"nasajon", "Nasajon"},
                {"dynamics", "Microsoft Dynamics"}, {"workday", "Workday"}, {"gupy", "Gupy"}, {"sistema senior", "Senior"},
        };
        for (String[] c : concorrentes) {
            CONCORRENTES.put(c[0], c[1]);
        }
    }

    private static final List<String> CONTEXTO_SISTEMA = List.of(
            "demo", "demonstracao", "proposta", "cotacao", "orcamento", "sistema", "erp", "solucao", "software",
            "plataforma", "ferramenta", "modulo", "licenca", "migrar", "trocar", "avaliar", "avaliando", "gostou",
            "gostaram", "preferem", "comparar", "concorrente", "fornecedor", "contrato", "implantar", "folha", "rh",
            "financeiro", "fiscal", "estoque");

    /** aliases, rotulo da area, produto TOTVS que atende, modulo sugerido */
    private record Area(List<String> aliases, String rotulo, String produto, String modulo) {
    }

    private static final List<Area> AREAS = List.of(
            new Area(List.of("folha de pagamento", "folha", "holerite", "esocial", "e-social", "ponto eletronico", "beneficios",
                    "recrutamento", "departamento pessoal", "recursos humanos", "rh", "gestao de pessoas", "admissao", "ferias"),
                    "RH", "TOTVS RM", "Folha de Pagamento"),
            new Area(List.of("estoque", "inventario", "controle de estoque", "compras", "suprimentos", "almoxarifado"),
                    "Estoque", "TOTVS Protheus", "Estoque e Compras"),
            new Area(List.of("backoffice", "back office", "financeiro", "contabil", "contabilidade", "fiscal", "faturamento",
                    "tesouraria", "contas a pagar", "contas a receber", "sped", "nota fiscal", "nfe", "patrimonio", "ativo fixo",
                    "controladoria"), "Backoffice", "TOTVS Protheus", "Backoffice (Financeiro/Fiscal)"),
            new Area(List.of("workflow", "bpm", "ecm", "gestao de documentos", "aprovacoes", "processos internos",
                    "assinatura eletronica", "portal"), "Processos", "TOTVS Fluig", "Workflow e Documentos"),
            new Area(List.of("producao", "pcp", "chao de fabrica", "manufatura", "mrp", "industria", "fabrica"),
                    "Manufatura", "TOTVS Datasul", "Manufatura (PCP)"),
            new Area(List.of("distribuicao", "atacado", "wms", "logistica", "tms", "armazem", "frota", "entregas"),
                    "Logistica", "TOTVS Logistica", "Logistica e Distribuicao"),
            new Area(List.of("crm", "marketing", "leads", "automacao de marketing", "funil", "prospeccao"),
                    "CRM", "RD Station", "CRM e Marketing"),
            new Area(List.of("credito", "antecipacao", "antecipacao de recebiveis", "pagamentos", "cobranca", "boleto"),
                    "Servicos financeiros", "TOTVS Techfin", "Credito e Pagamentos"),
            new Area(List.of("bi", "dashboards", "analytics", "relatorios gerenciais", "indicadores", "inteligencia de dados"),
                    "Analytics", "TOTVS Carol", "Analytics"),
            new Area(List.of("saude", "prontuario", "hospital", "clinica", "operadora", "plano de saude"),
                    "Saude", "TOTVS Saude", "Gestao de Saude"),
            new Area(List.of("educacional", "escola", "matricula", "alunos", "faculdade", "secretaria academica"),
                    "Educacional", "TOTVS Educacional", "Gestao Educacional"),
            new Area(List.of("varejo", "pdv", "loja", "lojas", "frente de caixa", "e-commerce"),
                    "Varejo", "TOTVS Varejo", "Varejo e PDV"),
            new Area(List.of("integracao", "integrar", "api", "integrado", "conectar", "sincronizar"),
                    "Integracao", "TOTVS Fluig", "Integracao")
    );

    private static final Map<String, Double> POSITIVOS = new LinkedHashMap<>();
    private static final Map<String, Double> NEGATIVOS = new LinkedHashMap<>();

    static {
        Object[][] pos = {
                {"esta atendendo", 1.0}, {"atende bem", 1.2}, {"atendeu", 0.8}, {"atendendo", 0.6}, {"satisfeit", 1.3},
                {"content", 0.8}, {"excelente", 1.3}, {"otimo", 1.1}, {"otima", 1.1}, {"muito bom", 1.1}, {"muito boa", 1.1},
                {"funciona bem", 1.2}, {"gostamos", 1.0}, {"gostei", 1.0}, {"adoramos", 1.3}, {"perfeito", 1.1},
                {"eficiente", 0.9}, {"produtiv", 0.8}, {"recomendo", 1.2}, {"aprovamos", 1.0}, {"feliz", 1.0},
                {"superou", 1.2}, {"parabens", 1.2}, {"elogi", 1.0}, {"resolveu", 0.9}, {"melhorou", 0.9},
                {"confiamos", 1.0}, {"bem atendid", 1.2}, {"tranquilo", 0.5}, {"sem problema", 0.6}, {"deu certo", 0.9},
                {"funcionou", 0.7}, {"vale a pena", 0.9}, {"estamos gostando", 1.2}, {"esta funcionando", 0.8},
        };
        for (Object[] p : pos) {
            POSITIVOS.put((String) p[0], (Double) p[1]);
        }
        Object[][] neg = {
                {"sofrendo", 1.3}, {"frustrad", 1.3}, {"insatisf", 1.3}, {"decepcion", 1.3}, {"problema", 0.9},
                {"erro", 0.8}, {"bug", 0.8}, {"nao funciona", 1.3}, {"ninguem resolve", 1.4}, {"reclamando", 1.0},
                {"reclamacao", 1.0}, {"demora", 0.8}, {"lento", 0.8}, {"lentidao", 0.9}, {"trava", 0.9}, {"travando", 1.0},
                {"pior", 0.9}, {"horrivel", 1.4}, {"pessimo", 1.4}, {"nao atende", 1.1}, {"falhando", 1.0}, {"caindo", 0.8},
                {"instavel", 1.0}, {"dor", 0.7}, {"dificuldade", 0.8}, {"gargalo", 0.9}, {"retrabalho", 1.0},
                {"sem retorno", 1.1}, {"nao respondem", 1.1}, {"prejuizo", 1.2}, {"atraso", 0.8}, {"manual", 0.5},
                {"chamado", 0.4}, {"cancelar", 0.6}, {"trocar", 0.4}, {"perdendo", 0.9}, {"nao aguento", 1.3},
                {"insuportavel", 1.4}, {"vergonha", 1.2}, {"absurdo", 1.1}, {"caro", 0.6}, {"complicado", 0.7},
                {"perde muito tempo", 1.2}, {"perdemos muito tempo", 1.2}, {"perde tempo", 1.0}, {"perdemos tempo", 1.0},
                {"perde venda", 1.2}, {"perdemos venda", 1.2}, {"perde cliente", 1.2}, {"nao da conta", 1.1},
                {"sobrecarregad", 1.0}, {"inferno", 1.3}, {"um saco", 1.0}, {"terrivel", 1.3}, {"desastre", 1.3},
                {"nao funciona direito", 1.2},
        };
        for (Object[] n : neg) {
            NEGATIVOS.put((String) n[0], (Double) n[1]);
        }
    }

    private static final Set<String> NEGACOES = Set.of("nao", "sem", "nenhum", "nenhuma", "nunca", "jamais", "nem");
    private static final Set<String> INTENSIFICADORES = Set.of("muito", "muita", "extremamente", "bastante", "demais", "super", "totalmente");
    private static final Set<String> ATENUADORES = Set.of("pouco", "meio", "levemente");

    private static final List<String> CHURN_VERBOS = List.of("cancelar", "cancelamento", "encerrar", "rescindir", "descontinuar",
            "desistir", "nao renovar", "nao vamos renovar", "sair da", "migrar", "trocar", "substituir", "avaliar outras",
            "outras opcoes", "outro fornecedor", "outra solucao");
    private static final List<String> CHURN_OBJETOS = List.of("contrato", "sistema", "licenca", "licencas", "assinatura", "totvs",
            "servico", "plano", "fornecedor", "erp", "solucao", "ferramenta", "opcoes", "proposta", "modulo");
    private static final List<String> CHURN_EXPLICITO = List.of("churn", "nao renovar", "nao vamos renovar", "encerrar contrato",
            "cancelar o contrato", "cancelar contrato", "sair da totvs", "trocar de sistema", "trocar de fornecedor",
            "migrar para", "rescindir");
    private static final List<String> PERDA_CONFIANCA = List.of("perdi confianca", "perdemos confianca", "sem confianca",
            "nao confio", "desconfi", "prometeram e nao", "nao cumpriu", "nao cumpriram", "quebrou a confianca", "enganad",
            "mentiu", "mentira", "promessa vazia", "outro vendedor");
    private static final List<String> CONFIANCA_POSITIVA = List.of("confio", "confiamos", "confianca em voces", "parceiro",
            "parceria", "recomendo", "gosto de trabalhar", "sempre atende", "sempre ajudou", "conto com voce", "entre nos",
            "nao mencione", "confidencial", "pode confiar", "prefiro consolidar");
    private static final List<String> OPORTUNIDADE_DESEJO = List.of("prefiro", "preferimos", "queremos", "quero", "gostaria de",
            "gostariamos de", "precisamos de", "precisamos", "preciso de", "temos necessidade", "consolidar", "expandir",
            "ampliar", "implantar", "contratar", "adquirir", "vamos investir", "pretendemos", "planejamos", "unificar",
            "centralizar", "automatizar", "estamos avaliando", "temos interesse", "interesse em", "queremos contratar",
            "quero contratar", "vamos fechar", "queremos fechar");
    private static final List<String> OPORTUNIDADE_FORTE = List.of("aprovado", "aprovamos", "orcamento aprovado", "verba aprovada",
            "diretoria aprovou", "proximo trimestre", "o quanto antes", "urgente", "fechar", "assinar", "quando podemos comecar");
    private static final List<String> CONDICIONAIS = List.of("se o", "se a", "desde que", "caso", "contanto que", "depende");
    private static final List<String> PROXIMOS_PASSOS = List.of("vou enviar", "vamos enviar", "envio a proposta", "proposta formal",
            "vamos agendar", "vou agendar", "agendar", "amanha", "semana que vem", "proxima semana", "proximo passo", "retorno",
            "conversar com meu socio", "conversar com a diretoria", "aguardar aprovacao", "te ligo", "te mando", "vou conectar",
            "reuniao com", "follow up", "follow-up", "depois do", "ate o final do mes", "ate sexta", "segunda-feira");
    private static final List<String> DUVIDAS_JORNADA = List.of("quanto custa", "qual o preco", "qual e o preco", "quanto tempo",
            "prazo", "como funciona", "integra com", "e se nao der certo", "garantia", "suporte", "implantacao", "treinamento",
            "quem vai", "como fica", "e o que acontece", "posso", "consigo", "da para", "tem como");
    private static final List<String> CARGOS_EXECUTIVO = List.of("ceo", "presidente", "dono", "dona", "proprietario", "proprietaria",
            "socio", "socia", "fundador", "diretor geral", "diretora geral", "minha empresa", "meu negocio", "meu socio");
    private static final List<String> CARGOS_FINANCEIRO = List.of("cfo", "diretor financeiro", "diretora financeira", "controller",
            "financeiro da empresa", "gerente financeiro", "contador", "contadora", "tesouraria");
    private static final List<String> CARGOS_TI = List.of("cto", "cio", "diretor de ti", "gerente de ti", "analista de ti",
            "arquiteto", "desenvolvedor", "infra", "equipe de tecnologia", "time de ti", "nosso ti");
    private static final List<String> CARGOS_OPERACIONAL = List.of("gerente", "gestor", "gestora", "coordenador", "coordenadora",
            "supervisor", "supervisora", "lider", "head", "manager", "operacao", "nosso time", "time de", "minha equipe",
            "meu time", "backoffice", "processo", "rotina", "o pessoal", "a equipe");
    private static final List<String> TERCEIROS_DECISORES = List.of("meu cfo", "o cfo", "para o cfo", "meu diretor", "a diretoria",
            "meu chefe", "meu socio", "o presidente", "o dono", "conselho", "comite");
    private static final List<String> VENDEDOR_MARCAS = List.of("nossa solucao", "nosso produto", "nossa plataforma",
            "a gente oferece", "oferecemos", "nossa proposta", "vou enviar", "posso te mostrar", "demonstracao", "trial",
            "posso ajudar", "temos confianca no que fazemos", "nosso time tecnico", "trabalhamos com", "voces querem fazer",
            "me conta sobre", "qual e o principal desafio", "totvs oferece", "nosso modulo", "conectar voce", "voces querem",
            "voces precisam", "o que voces", "processo de voces", "me conta", "como funciona hoje", "voces usam", "voces tem",
            "posso apresentar", "vou apresentar", "nossa equipe vai");
    private static final List<String> CLIENTE_MARCAS = List.of("a gente usa", "usamos", "nosso time", "nossa empresa", "meu cfo",
            "meu socio", "a gente atua", "queremos", "precisamos", "estamos avaliando", "meu negocio", "nossas lojas",
            "nosso protheus", "nosso rm", "nosso sistema", "a gente perde", "a gente tem", "nosso contador", "esta atendendo",
            "sofrendo", "quanto custa", "qual o preco", "quanto tempo leva", "e se nao der certo");

    private static final Pattern REGEX_LOCUTOR = Pattern.compile("\\[LOCUTOR\\s*(\\d+)]\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGEX_SENTENCA = Pattern.compile("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÂÊÔÃÕÇ\\[\"'(])|\\n+");
    private static final Pattern REGEX_ORACAO = Pattern.compile(
            "\\s*[,;]?\\s+(?=(?:mas|por[eé]m|entretanto|no entanto|contudo|s[oó] que|todavia)\\s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGEX_VALOR = Pattern.compile(
            "(r\\$\\s*)?(\\d{1,3}(?:[.\\s]\\d{3})*(?:,\\d{1,2})?|\\d+(?:[.,]\\d+)?)\\s*"
                    + "(mil(?:hoes|hao)?|k|milhoes|milhao|bi(?:lhoes|lhao)?|reais)?(\\s*(?:/|por)\\s*(?:mes|ano))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REGEX_FAIXA = Pattern.compile(
            "(?:r\\$\\s*)?(\\d+(?:[.,]\\d+)?)\\s*(?:a|e|ate)\\s*(?:r\\$\\s*)?(\\d+(?:[.,]\\d+)?)\\s*"
                    + "(mil(?:hoes|hao)?|k|milhoes|milhao)(\\s*(?:/|por)\\s*(?:mes|ano))?");
    private static final Pattern REGEX_ANONIMO = Pattern.compile("\\[(PESSOA|EMPRESA|LOCAL|EMAIL|TELEFONE|CPF|CNPJ)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGEX_PALAVRA = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    // ---------------------------------------------------------------- estruturas

    private record Turno(String locutor, String texto) {
    }

    private static final class Sentenca {
        final String texto;
        final String norm;
        final String locutor;
        final String papel;
        final int indice;
        double pos;
        double neg;
        final List<String> areas = new ArrayList<>();
        final List<String> produtos = new ArrayList<>();
        final List<String> concorrentes = new ArrayList<>();

        Sentenca(String texto, String locutor, String papel, int indice) {
            this.texto = texto;
            this.norm = normalizar(texto);
            this.locutor = locutor;
            this.papel = papel;
            this.indice = indice;
        }

        double polaridade() {
            return pos - neg;
        }
    }

    private static final class Risco {
        final AiAnalysisDTO.RiscoDTO dto;
        final double forca;

        Risco(AiAnalysisDTO.RiscoDTO dto, double forca) {
            this.dto = dto;
            this.forca = forca;
        }
    }

    private static final class Bucket {
        final String area;
        String produto;
        final String modulo;
        double score;
        String dor;
        String desejo;
        boolean forte;
        final List<String> evidencias = new ArrayList<>();

        Bucket(String area, String produto, String modulo) {
            this.area = area;
            this.produto = produto;
            this.modulo = modulo;
        }
    }

    // ---------------------------------------------------------------- API

    public AiAnalysisDTO analisar(String textoBruto, LeanIAContextDTO contexto) {
        String texto = textoBruto == null ? "" : textoBruto;
        AiAnalysisDTO.MetricasAgregadasDTO metricasLegado = extrairMetricasConsolidadas(texto);
        boolean consolidado = metricasLegado.getPartesAnalisadas() != null && metricasLegado.getPartesAnalisadas() > 0;

        List<Turno> turnos = segmentarTurnos(texto);
        Map<String, String> papeis = inferirPapeis(turnos);
        List<Sentenca> sentencas = segmentarSentencas(turnos, papeis);
        for (Sentenca s : sentencas) {
            anotar(s, contexto);
        }

        List<AiAnalysisDTO.ProdutoDTO> produtos = extrairProdutos(sentencas);
        List<String> concorrentes = sentencas.stream().flatMap(s -> s.concorrentes.stream()).distinct().sorted().toList();
        String[] sentimento = agregarSentimento(sentencas);
        double scoreSentimento = Double.parseDouble(sentimento[2]);

        String[] budget = extrairBudget(sentencas);
        List<Risco> riscosInternos = extrairRiscos(sentencas);
        List<AiAnalysisDTO.RiscoDTO> riscos = riscosInternos.stream().map(r -> r.dto).toList();
        List<AiAnalysisDTO.OportunidadeDTO> oportunidades = extrairOportunidades(sentencas, produtos, budget[0], contexto);
        String[] persona = identificarPersona(sentencas);
        double confianca = calcularConfianca(sentencas, riscos);
        AiAnalysisDTO.InsightsDTO insights = insightsAdicionais(sentencas, budget[0], budget[1], persona[1]);
        Map<String, Object> indicadores = indicadoresQualidade(texto, turnos, papeis, sentencas);

        AiAnalysisDTO.MetricasAgregadasDTO metricas = consolidado ? metricasLegado
                : metricasPorTurnos(turnos, sentencas, riscos, oportunidades);
        List<AiAnalysisDTO.RiscoConsolidadoDTO> riscosConsolidados = consolidarRiscos(riscos, metricas);
        List<AiAnalysisDTO.OportunidadeConsolidadaDTO> oportunidadesConsolidadas = consolidarOportunidades(oportunidades, metricas);
        List<AiAnalysisDTO.AlertaConsolidadoDTO> alertas = gerarAlertasConsolidados(metricas, riscos, consolidado);
        metricas.setAlertasConsolidados(alertas.size());
        String proximaAcao = sugerirProximaAcao(sentimento[0], oportunidades, riscos, concorrentes, insights);
        String resumo = gerarResumo(sentimento[1], produtos, oportunidades, riscos, persona[0], budget[0], contexto, proximaAcao);

        return AiAnalysisDTO.builder()
                .sentimentoGeral(sentimento[0])
                .resumo(resumo)
                .personaIdentificada(persona[0])
                .budgetIdentificado(budget[0])
                .scoreConfiancaVendedor(confianca)
                .scoreSentimento(scoreSentimento)
                .metricasAgregadas(metricas)
                .produtos(produtos)
                .oportunidades(oportunidades)
                .riscos(riscos)
                .riscosConsolidados(riscosConsolidados)
                .oportunidadesConsolidadas(oportunidadesConsolidadas)
                .alertasConsolidados(alertas)
                .itensCriticos(extrairItensCriticos(sentencas))
                .proximaAcao(proximaAcao)
                .sentimentoDetalhe(sentimento[1])
                .insightsAdicionais(insights)
                .indicadoresQualidade(indicadores)
                .build();
    }

    // ---------------------------------------------------------------- utilitarios

    static String normalizar(String texto) {
        String semAcento = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[ \\t]+", " ").trim();
    }

    private static boolean contem(String norm, List<String> termos) {
        for (String t : termos) {
            if (norm.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contemPalavra(String norm, String termo) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(termo) + "(?![a-z0-9])").matcher(norm).find();
    }

    private static String recortar(String texto, int limite) {
        String t = (texto == null ? "" : texto).replaceAll("\\s+", " ").trim();
        return t.length() <= limite ? t : t.substring(0, limite - 3).stripTrailing() + "...";
    }

    private static String formatarBrl(double valor) {
        long centavosTotal = Math.round(valor * 100);
        long inteiro = centavosTotal / 100;
        long centavos = centavosTotal % 100;
        String milhares = String.format(Locale.US, "%,d", inteiro).replace(',', '.');
        return "R$ " + milhares + "," + String.format(Locale.ROOT, "%02d", centavos);
    }

    private static List<String> contextList(LeanIAContextDTO contexto, List<String> values) {
        if (contexto == null || values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).limit(8).toList();
    }

    // ---------------------------------------------------------------- segmentacao

    private List<Turno> segmentarTurnos(String texto) {
        List<Turno> turnos = new ArrayList<>();
        Matcher m = REGEX_LOCUTOR.matcher(texto);
        int last = 0;
        String locutor = null;
        boolean found = false;
        while (m.find()) {
            String fala = texto.substring(last, m.start()).trim();
            if (!fala.isEmpty()) {
                turnos.add(new Turno(locutor, fala));
            }
            locutor = m.group(1);
            last = m.end();
            found = true;
        }
        String resto = texto.substring(last).trim();
        if (!resto.isEmpty()) {
            turnos.add(new Turno(found ? locutor : null, resto));
        }
        return turnos;
    }

    private Map<String, String> inferirPapeis(List<Turno> turnos) {
        Map<String, Double> pontos = new LinkedHashMap<>();
        Map<String, Integer> chars = new LinkedHashMap<>();
        for (Turno t : turnos) {
            String n = normalizar(t.texto());
            long v = VENDEDOR_MARCAS.stream().filter(n::contains).count();
            long c = CLIENTE_MARCAS.stream().filter(n::contains).count();
            pontos.merge(t.locutor(), (double) (v - c), Double::sum);
            chars.merge(t.locutor(), t.texto().length(), Integer::sum);
        }
        Map<String, String> papeis = new LinkedHashMap<>();
        if (turnos.isEmpty()) {
            return papeis;
        }
        if (chars.size() == 1) {
            String locutor = chars.keySet().iterator().next();
            papeis.put(locutor, pontos.getOrDefault(locutor, 0.0) > 0 ? "vendedor" : "cliente");
            return papeis;
        }
        List<Map.Entry<String, Double>> ordenados = new ArrayList<>(pontos.entrySet());
        ordenados.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        String vendedor = ordenados.get(0).getValue() > 0 ? ordenados.get(0).getKey() : null;
        for (String locutor : chars.keySet()) {
            double p = pontos.getOrDefault(locutor, 0.0);
            if (Objects.equals(locutor, vendedor) || p > 0) {
                papeis.put(locutor, "vendedor");
            } else if (p < 0) {
                papeis.put(locutor, "cliente");
            } else {
                papeis.put(locutor, vendedor != null && chars.size() == 2 ? "cliente" : "desconhecido");
            }
        }
        if (vendedor != null && !papeis.containsValue("cliente")) {
            papeis.put(ordenados.get(ordenados.size() - 1).getKey(), "cliente");
        }
        if (vendedor == null) {
            Map<String, Long> perguntas = new LinkedHashMap<>();
            for (Turno t : turnos) {
                perguntas.merge(t.locutor(), t.texto().chars().filter(ch -> ch == '?').count(), Long::sum);
            }
            String candidato = perguntas.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (candidato != null && perguntas.get(candidato) > 0) {
                papeis.put(candidato, "vendedor");
            }
        }
        return papeis;
    }

    private List<Sentenca> segmentarSentencas(List<Turno> turnos, Map<String, String> papeis) {
        List<Sentenca> sentencas = new ArrayList<>();
        for (Turno t : turnos) {
            for (String frase : REGEX_SENTENCA.split(t.texto())) {
                for (String pedaco : REGEX_ORACAO.split(frase)) {
                    String p = pedaco.trim();
                    if (p.length() < 3) {
                        continue;
                    }
                    sentencas.add(new Sentenca(p, t.locutor(), papeis.getOrDefault(t.locutor(), "desconhecido"), sentencas.size()));
                }
            }
        }
        return sentencas;
    }

    // ---------------------------------------------------------------- anotacao

    private void anotar(Sentenca s, LeanIAContextDTO contexto) {
        String n = s.norm;
        for (Area area : AREAS) {
            if (area.aliases().stream().anyMatch(a -> contemPalavra(n, a)) && !s.areas.contains(area.rotulo())) {
                s.areas.add(area.rotulo());
            }
        }
        for (Map.Entry<String, String> e : PRODUTOS_TOTVS.entrySet()) {
            if (contemPalavra(n, e.getKey()) && !s.produtos.contains(e.getValue())) {
                s.produtos.add(e.getValue());
            }
        }
        for (String nome : contextList(contexto, contexto == null ? null : contexto.getProdutosFrequentes())) {
            if (contemPalavra(n, normalizar(nome)) && !s.produtos.contains(nome)) {
                s.produtos.add(nome);
            }
        }
        for (Map.Entry<String, String> e : CONCORRENTES.entrySet()) {
            if (!contemPalavra(n, e.getKey()) || s.concorrentes.contains(e.getValue())) {
                continue;
            }
            if (CONCORRENTES_AMBIGUOS.contains(e.getKey()) && !contem(n, CONTEXTO_SISTEMA)) {
                continue;
            }
            s.concorrentes.add(e.getValue());
        }
        for (String nome : contextList(contexto, contexto == null ? null : contexto.getConcorrentesFrequentes())) {
            if (contemPalavra(n, normalizar(nome)) && !s.concorrentes.contains(nome)) {
                s.concorrentes.add(nome);
            }
        }
        pontuarSentimento(s);
    }

    private void pontuarSentimento(Sentenca s) {
        String n = s.norm;
        double pos = 0, neg = 0;
        for (Map.Entry<String, Double> e : POSITIVOS.entrySet()) {
            int idx = n.indexOf(e.getKey());
            while (idx >= 0) {
                if (negado(n, idx)) {
                    neg += e.getValue() * 0.8;
                } else {
                    pos += e.getValue() * intensidade(n, idx);
                }
                idx = n.indexOf(e.getKey(), idx + 1);
            }
        }
        for (Map.Entry<String, Double> e : NEGATIVOS.entrySet()) {
            int idx = n.indexOf(e.getKey());
            while (idx >= 0) {
                if (negado(n, idx)) {
                    pos += e.getValue() * 0.3;
                } else {
                    neg += e.getValue() * intensidade(n, idx);
                }
                idx = n.indexOf(e.getKey(), idx + 1);
            }
        }
        if (n.contains("manual") && !contem(n, List.of("sofrendo", "dificuldade", "retrabalho", "perde", "perdemos", "demora"))) {
            neg = Math.max(0, neg - NEGATIVOS.get("manual"));
        }
        if (!s.concorrentes.isEmpty() && pos > 0) {
            pos = 0; // elogio ao concorrente vira risco, nao satisfacao com a TOTVS
        }
        if (n.split(" ").length <= 2) {
            pos *= 0.5;
            neg *= 0.5;
        }
        s.pos = Math.round(pos * 100) / 100.0;
        s.neg = Math.round(neg * 100) / 100.0;
    }

    private static boolean negado(String n, int idx) {
        String[] prefixo = n.substring(0, idx).trim().split(" ");
        int from = Math.max(0, prefixo.length - 3);
        for (int i = from; i < prefixo.length; i++) {
            if (NEGACOES.contains(prefixo[i].replaceAll("[,;]", ""))) {
                return true;
            }
        }
        return false;
    }

    private static double intensidade(String n, int idx) {
        String[] prefixo = n.substring(0, idx).trim().split(" ");
        int from = Math.max(0, prefixo.length - 2);
        for (int i = from; i < prefixo.length; i++) {
            if (INTENSIFICADORES.contains(prefixo[i])) {
                return 1.5;
            }
            if (ATENUADORES.contains(prefixo[i])) {
                return 0.6;
            }
        }
        return 1.0;
    }

    // ---------------------------------------------------------------- sentimento

    /** [rotulo, detalhe, score] */
    private String[] agregarSentimento(List<Sentenca> sentencas) {
        List<Sentenca> relevantes = sentencas.stream().filter(s -> !"vendedor".equals(s.papel)).toList();
        if (relevantes.isEmpty()) {
            relevantes = sentencas;
        }
        double pos = relevantes.stream().mapToDouble(s -> s.pos).sum();
        double neg = relevantes.stream().mapToDouble(s -> s.neg).sum();
        List<String> posAreas = areasPorSinal(relevantes, true);
        List<String> negAreas = areasPorSinal(relevantes, false);
        double total = pos + neg;
        double score = total == 0 ? 0 : Math.round((pos - neg) / total * 100) / 100.0;

        String rotulo;
        if (pos >= 0.8 && neg >= 0.8 && Math.min(pos, neg) / Math.max(pos, neg) >= 0.3) {
            rotulo = "MISTO";
        } else if (neg > pos && neg >= 0.8) {
            rotulo = "NEGATIVO";
        } else if (pos > neg && pos >= 0.8) {
            rotulo = "POSITIVO";
        } else {
            rotulo = "NEUTRO";
        }

        String detalhe;
        String posArea = posAreas.stream().filter(a -> !a.equals("a solucao atual")).findFirst().orElse(null);
        String negArea = negAreas.stream().filter(a -> !a.equals("a solucao atual")).findFirst().orElse(null);
        switch (rotulo) {
            case "MISTO" -> {
                if (posArea != null && negArea != null && !posArea.equals(negArea)) {
                    detalhe = "Misto (Satisfeito com " + posArea + " / Frustrado com " + negArea + ")";
                } else if (negArea != null) {
                    detalhe = "Misto (elogios pontuais, mas frustracao com " + negArea + ")";
                } else if (posArea != null) {
                    detalhe = "Misto (satisfeito com " + posArea + ", com ressalvas)";
                } else {
                    detalhe = "Misto (sinais positivos e negativos ao longo da conversa)";
                }
            }
            case "NEGATIVO" -> detalhe = "Negativo" + (negArea != null ? " (insatisfacao com " + negArea + ")" : " (insatisfacao recorrente)");
            case "POSITIVO" -> detalhe = "Positivo" + (posArea != null ? " (satisfeito com " + posArea + ")" : " (satisfacao geral)");
            default -> detalhe = "Neutro (conversa informativa, sem sinais fortes)";
        }
        return new String[]{rotulo, detalhe, String.valueOf(score)};
    }

    private List<String> areasPorSinal(List<Sentenca> sentencas, boolean positivo) {
        Map<String, Double> pesos = new LinkedHashMap<>();
        for (Sentenca s : sentencas) {
            double valor = positivo ? s.pos : s.neg;
            if (valor <= 0) {
                continue;
            }
            List<String> areas = s.areas.isEmpty() ? List.of("a solucao atual") : s.areas;
            for (String area : areas) {
                if (!area.equals("Integracao")) {
                    pesos.merge(area, valor, Double::sum);
                }
            }
        }
        return pesos.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    // ---------------------------------------------------------------- produtos

    private List<AiAnalysisDTO.ProdutoDTO> extrairProdutos(List<Sentenca> sentencas) {
        Map<String, AiAnalysisDTO.ProdutoDTO> resultado = new LinkedHashMap<>();
        for (Sentenca s : sentencas) {
            for (String nome : s.produtos) {
                String tipo = classificarProduto(s.norm, nome);
                AiAnalysisDTO.ProdutoDTO atual = resultado.get(nome);
                if (atual == null) {
                    resultado.put(nome, AiAnalysisDTO.ProdutoDTO.builder().nomeProduto(nome).tipo(tipo).contexto(recortar(s.texto, 240)).build());
                } else if ("OPORTUNIDADE".equals(atual.getTipo()) && "ATUAL".equals(tipo)) {
                    atual.setTipo("ATUAL");
                    atual.setContexto(recortar(s.texto, 240));
                }
            }
            for (String nome : s.concorrentes) {
                resultado.putIfAbsent(nome, AiAnalysisDTO.ProdutoDTO.builder().nomeProduto(nome).tipo("CONCORRENTE").contexto(recortar(s.texto, 240)).build());
            }
        }
        Map<String, Integer> ordem = Map.of("ATUAL", 0, "OPORTUNIDADE", 1, "CONCORRENTE", 2);
        return resultado.values().stream().sorted(Comparator.comparing(p -> ordem.getOrDefault(p.getTipo(), 3))).toList();
    }

    private String classificarProduto(String n, String nome) {
        String alias = normalizar(nome.replace("TOTVS ", "")).trim();
        int pos = n.indexOf(alias);
        String antes = pos >= 0 ? n.substring(Math.max(0, pos - 40), pos) : "";
        String depois = pos >= 0 ? n.substring(pos, Math.min(n.length(), pos + 60)) : n;
        boolean condicional = contem(antes, CONDICIONAIS);
        boolean posse = contem(antes, List.of("nosso ", "nossa ", "o nosso", "a nossa", "usamos", "a gente usa", "temos o",
                "temos a", "ja temos", "hoje o", "hoje a", "atualmente", "a gente tem", "utilizamos", "rodamos", "com o", "com a"));
        boolean atualDepois = contem(depois, List.of("esta atendendo", "atende", "que usamos", "que a gente usa", "ja esta",
                "esta rodando", "implantado", "em producao"));
        boolean desejo = contem(n, OPORTUNIDADE_DESEJO) || contem(depois, List.of("for integrado", "for realmente", "fosse", "seria", "poderia"));
        if (condicional || (desejo && !posse)) {
            return "OPORTUNIDADE";
        }
        if (posse || atualDepois) {
            return "ATUAL";
        }
        return desejo ? "OPORTUNIDADE" : "ATUAL";
    }

    // ---------------------------------------------------------------- budget

    /** [rotulo, fraseDeOrigem] */
    private String[] extrairBudget(List<Sentenca> sentencas) {
        record Candidato(double valor, String rotulo, String frase, boolean contexto) {
        }
        List<Candidato> candidatos = new ArrayList<>();
        for (Sentenca s : sentencas) {
            Matcher faixa = REGEX_FAIXA.matcher(s.norm);
            if (faixa.find()) {
                Double v1 = parseValor(faixa.group(1), faixa.group(3));
                Double v2 = parseValor(faixa.group(2), faixa.group(3));
                if (v1 != null && v2 != null && v1 < v2) {
                    String periodo = faixa.group(4) == null ? "" : faixa.group(4);
                    String sufixo = periodo.contains("mes") ? " por mes" : periodo.contains("ano") ? " por ano" : "";
                    candidatos.add(new Candidato(v2, formatarBrl(v1) + " a " + formatarBrl(v2) + sufixo, s.texto, true));
                    continue;
                }
            }
            Matcher m = REGEX_VALOR.matcher(s.norm);
            while (m.find()) {
                if (m.group(1) == null && m.group(3) == null) {
                    continue;
                }
                Double valor = parseValor(m.group(2), m.group(3));
                if (valor == null || valor < 100) {
                    continue;
                }
                String periodo = m.group(4) == null ? "" : m.group(4);
                boolean contexto = contem(s.norm, List.of("orcamento", "budget", "verba", "valor", "investir", "investimento",
                        "aprovado", "conversamos", "pagar", "custa", "por mes", "mensal", "anual", "contrato", "proposta"));
                String rotulo = formatarBrl(valor) + (periodo.contains("mes") ? " por mes" : periodo.contains("ano") ? " por ano" : "");
                candidatos.add(new Candidato(valor, rotulo, s.texto, contexto));
            }
        }
        if (candidatos.isEmpty()) {
            return new String[]{"Nao identificado", ""};
        }
        candidatos.sort(Comparator.comparing((Candidato c) -> !c.contexto()).thenComparing(c -> -c.valor()));
        return new String[]{candidatos.get(0).rotulo(), candidatos.get(0).frase()};
    }

    private static Double parseValor(String num, String escala) {
        String bruto = num.replace(" ", "");
        double valor;
        try {
            if (bruto.contains(",") && bruto.contains(".")) {
                valor = Double.parseDouble(bruto.replace(".", "").replace(',', '.'));
            } else if (bruto.contains(",")) {
                valor = Double.parseDouble(bruto.replace(',', '.'));
            } else if (bruto.chars().filter(c -> c == '.').count() == 1 && bruto.substring(bruto.indexOf('.') + 1).length() != 3) {
                valor = Double.parseDouble(bruto);
            } else {
                valor = Double.parseDouble(bruto.replace(".", ""));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        String esc = normalizar(escala == null ? "" : escala);
        if ((esc.startsWith("mil") && !esc.contains("milh")) || esc.equals("k")) {
            valor *= 1_000;
        } else if (esc.contains("milh")) {
            valor *= 1_000_000;
        } else if (esc.startsWith("bi")) {
            valor *= 1_000_000_000;
        }
        return valor;
    }

    // ---------------------------------------------------------------- riscos

    private List<Risco> extrairRiscos(List<Sentenca> sentencas) {
        List<Risco> riscos = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        for (Sentenca s : sentencas) {
            String n = s.norm;
            String area = areaContexto(sentencas, s);
            if ("vendedor".equals(s.papel) && s.concorrentes.isEmpty()) {
                continue;
            }
            for (String conc : s.concorrentes) {
                boolean avaliando = contem(n, List.of("demo", "demonstracao", "proposta", "cotacao", "avaliando", "avaliar", "gostou",
                        "gostaram", "preferem", "olhando", "conversando", "reuniao", "migrar", "trocar", "comparar", "apresentou", "orcamento"));
                String desc = "Cliente avaliando solucao da " + conc + (area != null ? " para " + area : "");
                if (avaliando && contem(n, List.of("gostou", "gostaram", "preferem"))) {
                    desc += " e demonstrou interesse";
                }
                add(riscos, vistos, "CONCORRENCIA", avaliando ? "ALTO" : "MEDIO",
                        desc + ". Trecho: \"" + recortar(s.texto, 160) + "\"", conc, 5.0);
            }
            boolean churn = contem(n, CHURN_EXPLICITO) || (contem(n, CHURN_VERBOS) && contem(n, CHURN_OBJETOS));
            if (churn) {
                add(riscos, vistos, "CHURN", "ALTO", "Sinal de cancelamento ou troca de fornecedor"
                                + (area != null ? " em " + area : "") + ". Trecho: \"" + recortar(s.texto, 160) + "\"",
                        s.concorrentes.isEmpty() ? null : s.concorrentes.get(0), 5.0);
            }
            if (contem(n, PERDA_CONFIANCA)) {
                add(riscos, vistos, "PERDA_CONFIANCA", "ALTO",
                        "Cliente sinaliza perda de confianca na relacao. Trecho: \"" + recortar(s.texto, 160) + "\"", null, 4.0);
            }
            if (s.neg >= 1.2 && n.split(" ").length >= 6 && s.concorrentes.isEmpty() && !churn) {
                add(riscos, vistos, "INSATISFACAO", s.neg >= 2.0 ? "ALTO" : "MEDIO",
                        "Insatisfacao" + (area != null ? " com " + area : "") + ": \"" + recortar(s.texto, 160) + "\"", null, s.neg);
            }
        }
        Map<String, Integer> ordem = Map.of("ALTO", 0, "MEDIO", 1, "BAIXO", 2);
        riscos.sort(Comparator.comparing((Risco r) -> ordem.getOrDefault(r.dto.getSeveridade(), 3)).thenComparing(r -> -r.forca));
        return riscos.size() > 12 ? new ArrayList<>(riscos.subList(0, 12)) : riscos;
    }

    private static void add(List<Risco> riscos, Set<String> vistos, String tipo, String severidade, String descricao,
                            String concorrente, double forca) {
        String chave = tipo + "|" + descricao.substring(0, Math.min(80, descricao.length()));
        if (!vistos.add(chave)) {
            return;
        }
        riscos.add(new Risco(AiAnalysisDTO.RiscoDTO.builder().tipo(tipo).descricao(descricao).severidade(severidade)
                .concorrenteMencionado(concorrente).build(), forca));
    }

    private String areaContexto(List<Sentenca> sentencas, Sentenca s) {
        for (String a : s.areas) {
            if (!a.equals("Integracao")) {
                return a;
            }
        }
        if (s.indice > 0) {
            for (String a : sentencas.get(s.indice - 1).areas) {
                if (!a.equals("Integracao")) {
                    return a;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- oportunidades

    private List<AiAnalysisDTO.OportunidadeDTO> extrairOportunidades(List<Sentenca> sentencas,
                                                                     List<AiAnalysisDTO.ProdutoDTO> produtos,
                                                                     String budget, LeanIAContextDTO contexto) {
        List<String> atuais = produtos.stream().filter(p -> "ATUAL".equals(p.getTipo())).map(AiAnalysisDTO.ProdutoDTO::getNomeProduto).toList();
        List<String> desejados = produtos.stream().filter(p -> "OPORTUNIDADE".equals(p.getTipo())).map(AiAnalysisDTO.ProdutoDTO::getNomeProduto).toList();
        boolean clienteTotvs = !atuais.isEmpty();
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        String termos = OPORTUNIDADE_DESEJO.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElse("");
        Pattern negadoRx = Pattern.compile("\\b(nao|nem|nunca)\\s+(\\w+\\s+){0,2}(" + termos + ")");
        Pattern segundaPessoaRx = Pattern.compile("\\bvoces?\\s+(\\w+\\s+){0,2}(" + termos + ")");

        for (Sentenca s : sentencas) {
            if ("vendedor".equals(s.papel)) {
                continue;
            }
            String n = s.norm;
            List<String> areas = s.areas.stream().filter(a -> !a.equals("Integracao")).toList();
            if (areas.isEmpty() && s.indice > 0) {
                areas = sentencas.get(s.indice - 1).areas.stream().filter(a -> !a.equals("Integracao")).toList();
            }
            boolean passo = contem(n, PROXIMOS_PASSOS) || contem(n, List.of("conversar com", "falar com", "pensar", "avisar"));
            boolean negado = negadoRx.matcher(n).find();
            boolean segundaPessoa = segundaPessoaRx.matcher(n).find();
            boolean desejo = contem(n, OPORTUNIDADE_DESEJO) && !passo && !negado && !segundaPessoa;
            boolean forte = contem(n, OPORTUNIDADE_FORTE) && !passo && !negado;
            boolean dor = s.neg >= 1.2 && s.concorrentes.isEmpty() && n.split(" ").length >= 6;
            String produtoDesejado = s.produtos.stream().filter(desejados::contains).findFirst().orElse(null);
            if (!(desejo || forte || dor || produtoDesejado != null)) {
                continue;
            }

            Area alvo = null;
            for (Area area : AREAS) {
                if (areas.contains(area.rotulo()) && !area.rotulo().equals("Integracao")) {
                    alvo = area;
                    break;
                }
            }
            String areaRotulo;
            String produto;
            String modulo;
            if (produtoDesejado != null) {
                Area info = AREAS.stream().filter(a -> a.produto().equals(produtoDesejado)).findFirst().orElse(null);
                Area base = alvo != null ? alvo : info;
                areaRotulo = base != null ? base.rotulo() : produtoDesejado;
                modulo = base != null ? base.modulo() : produtoDesejado;
                produto = produtoDesejado;
            } else if (alvo != null) {
                areaRotulo = alvo.rotulo();
                produto = alvo.produto();
                modulo = alvo.modulo();
            } else {
                areaRotulo = "Geral";
                produto = "A definir";
                modulo = "solucao TOTVS a definir";
            }

            Bucket b = buckets.computeIfAbsent(areaRotulo, k -> new Bucket(areaRotulo, produto, modulo));
            if (dor && b.dor == null) {
                b.dor = recortar(s.texto, 160);
                b.score = Math.max(b.score, 0.55);
            }
            if (desejo || produtoDesejado != null) {
                if (b.desejo == null) {
                    b.desejo = recortar(s.texto, 160);
                }
                b.score = Math.max(b.score, 0.7);
            }
            if (forte) {
                b.forte = true;
                b.score = Math.max(b.score, 0.85);
            }
            if (b.evidencias.size() < 4) {
                b.evidencias.add(recortar(s.texto, 200));
            }
        }

        List<AiAnalysisDTO.OportunidadeDTO> oportunidades = new ArrayList<>();
        List<String> frequentes = contextList(contexto, contexto == null ? null : contexto.getOportunidadesFrequentes());
        for (Bucket b : buckets.values()) {
            boolean generico = "A definir".equals(b.produto);
            if (generico && b.desejo == null) {
                continue;
            }
            double score = b.score;
            if (b.dor != null && b.desejo != null && !generico) {
                score = Math.max(score, 0.85);
            }
            if (generico) {
                score = Math.min(score, 0.5);
            }
            if (!"Nao identificado".equals(budget)) {
                score = Math.min(1.0, score + 0.05);
            }
            String areaLower = b.area.toLowerCase(Locale.ROOT);
            if (frequentes.stream().anyMatch(o -> areaLower.contains(o.toLowerCase(Locale.ROOT)))) {
                score = Math.min(1.0, score + 0.05);
            }
            score = Math.round(Math.min(score, 0.98) * 100) / 100.0;
            String grau = score >= 0.8 ? "Alta probabilidade" : score >= 0.6 ? "Boa probabilidade" : "Possibilidade";
            StringBuilder desc = new StringBuilder(generico
                    ? grau + " de venda (" + b.modulo + ")"
                    : grau + " de venda do modulo de " + b.modulo);
            if (!generico && !b.modulo.contains(b.produto)) {
                desc.append(" (").append(b.produto).append(")");
            }
            if (b.desejo != null) {
                desc.append("; cliente sinaliza intencao: \"").append(b.desejo).append("\"");
            }
            if (b.dor != null) {
                desc.append("; dor relatada: \"").append(b.dor).append("\"");
            }
            desc.append(".");
            oportunidades.add(AiAnalysisDTO.OportunidadeDTO.builder()
                    .tipo(tipoOportunidade(b.produto, clienteTotvs))
                    .descricao(desc.toString())
                    .produtoRelacionado(b.produto)
                    .score(score)
                    .valorEstimado("Nao identificado".equals(budget) ? "Nao estimado" : budget)
                    .build());
        }
        oportunidades.sort(Comparator.comparing(AiAnalysisDTO.OportunidadeDTO::getScore).reversed());
        return oportunidades.size() > 6 ? new ArrayList<>(oportunidades.subList(0, 6)) : oportunidades;
    }

    private static String tipoOportunidade(String produto, boolean clienteTotvs) {
        if (!clienteTotvs) {
            return "NOVO_PRODUTO";
        }
        if (produto.equals("RD Station") || produto.equals("TOTVS Techfin") || produto.equals("TOTVS Carol")) {
            return "CROSS_SELL";
        }
        return "UPSELL";
    }

    // ---------------------------------------------------------------- persona e confianca

    /** [persona, observacao (pode ser vazia)] */
    private String[] identificarPersona(List<Sentenca> sentencas) {
        List<Sentenca> cliente = sentencas.stream().filter(s -> !"vendedor".equals(s.papel)).toList();
        if (cliente.isEmpty()) {
            cliente = sentencas;
        }
        String texto = cliente.stream().map(s -> s.norm).reduce((a, b) -> a + " " + b).orElse("");
        Map<String, Integer> pontos = new LinkedHashMap<>(Map.of("executivo", 0, "financeiro", 0, "ti", 0, "operacional", 0));
        String terceiro = TERCEIROS_DECISORES.stream().filter(texto::contains).findFirst().orElse(null);
        for (String cargo : CARGOS_EXECUTIVO) {
            if (contemPalavra(texto, cargo)) {
                pontos.merge("executivo", 2, Integer::sum);
            }
        }
        for (String cargo : CARGOS_FINANCEIRO) {
            if (contemPalavra(texto, cargo) && !(terceiro != null && terceiro.contains(cargo))) {
                pontos.merge("financeiro", 2, Integer::sum);
            }
        }
        for (String cargo : CARGOS_TI) {
            if (texto.contains(cargo)) {
                pontos.merge("ti", 1, Integer::sum);
            }
        }
        for (String cargo : CARGOS_OPERACIONAL) {
            if (texto.contains(cargo)) {
                pontos.merge("operacional", 1, Integer::sum);
            }
        }
        if (contem(texto, List.of("roi", "payback", "custo", "investimento", "orcamento aprovado")) && pontos.get("financeiro") == 0 && terceiro == null) {
            pontos.merge("financeiro", 1, Integer::sum);
        }
        String obs = "";
        if (terceiro != null) {
            String quem = terceiro.contains("cfo") ? "CFO" : terceiro.contains("diretoria") ? "diretoria"
                    : terceiro.replace("meu ", "").replace("o ", "");
            obs = "Menciona " + quem + " como decisor financeiro/final: o interlocutor influencia, mas nao fecha sozinho.";
        }
        String melhor = pontos.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("operacional");
        if (pontos.get(melhor) == 0) {
            return new String[]{"Interlocutor nao identificado (sem cargo ou funcao explicita)", obs};
        }
        Map<String, String> rotulos = Map.of(
                "executivo", "Socio/Executivo (decisor final)",
                "financeiro", "Decisor financeiro (CFO/Controller)",
                "ti", "Gestor de TI (influenciador tecnico)",
                "operacional", "Gestor Operacional (decisor tecnico/influenciador)");
        return new String[]{rotulos.get(melhor), obs};
    }

    private double calcularConfianca(List<Sentenca> sentencas, List<AiAnalysisDTO.RiscoDTO> riscos) {
        List<Sentenca> cliente = sentencas.stream().filter(s -> !"vendedor".equals(s.papel)).toList();
        if (cliente.isEmpty()) {
            cliente = sentencas;
        }
        String texto = cliente.stream().map(s -> s.norm).reduce((a, b) -> a + " " + b).orElse("");
        double score = 0.5;
        score += 0.12 * CONFIANCA_POSITIVA.stream().filter(texto::contains).count();
        score -= 0.2 * PERDA_CONFIANCA.stream().filter(texto::contains).count();
        if (riscos.stream().anyMatch(r -> "PERDA_CONFIANCA".equals(r.getTipo()))) {
            score -= 0.15;
        }
        if (riscos.stream().anyMatch(r -> "CHURN".equals(r.getTipo()))) {
            score -= 0.1;
        }
        return Math.max(0.0, Math.min(1.0, Math.round(score * 100) / 100.0));
    }

    // ---------------------------------------------------------------- insights e indicadores

    private AiAnalysisDTO.InsightsDTO insightsAdicionais(List<Sentenca> sentencas, String budget, String budgetFrase, String personaObs) {
        AiAnalysisDTO.InsightsDTO in = AiAnalysisDTO.InsightsDTO.builder().build();
        if (personaObs != null && !personaObs.isEmpty()) {
            in.getObservacoes().add(personaObs);
        }
        for (Sentenca s : sentencas) {
            String n = s.norm;
            if (!"vendedor".equals(s.papel)) {
                if (s.pos >= 0.8 && s.concorrentes.isEmpty() && in.getElogios().size() < 5) {
                    in.getElogios().add(recortar(s.texto, 200));
                }
                if (s.neg >= 0.9 && in.getReclamacoes().size() < 5) {
                    in.getReclamacoes().add(recortar(s.texto, 200));
                }
                if (contem(n, DUVIDAS_JORNADA) && in.getDuvidasJornada().size() < 5) {
                    in.getDuvidasJornada().add(recortar(s.texto, 200));
                }
            }
            if (contem(n, PROXIMOS_PASSOS) && in.getProximosPassos().size() < 5) {
                in.getProximosPassos().add(recortar(s.texto, 200));
            }
            if (contem(n, List.of("nao mencione", "nao comente", "entre nos", "confidencial", "sigilo", "nao fale"))) {
                String alvo = n.contains("cfo") ? "ao CFO" : "a terceiros";
                String sobre = !"Nao identificado".equals(budget) && s.texto.equals(budgetFrase) ? " sobre o valor " + budget : "";
                in.getObservacoes().add("Cliente pediu discricao" + sobre + " junto " + alvo + ": tratar a informacao como confidencial.");
            }
            if (contem(n, List.of("roi do semestre", "roi do trimestre", "fechamento do trimestre", "fim do ano fiscal"))) {
                in.getObservacoes().add("Decisor financeiro focado no ciclo de ROI: alinhar proposta ao calendario orcamentario.");
            }
        }
        if (in.getObservacoes().size() > 6) {
            in.setObservacoes(new ArrayList<>(in.getObservacoes().subList(0, 6)));
        }
        return in;
    }

    private Map<String, Object> indicadoresQualidade(String texto, List<Turno> turnos, Map<String, String> papeis, List<Sentenca> sentencas) {
        Map<String, Integer> chars = new LinkedHashMap<>(Map.of("vendedor", 0, "cliente", 0, "desconhecido", 0));
        Map<String, Integer> perguntas = new LinkedHashMap<>(Map.of("vendedor", 0, "cliente", 0));
        for (Turno t : turnos) {
            String papel = papeis.getOrDefault(t.locutor(), "desconhecido");
            chars.merge(papel, t.texto().length(), Integer::sum);
            if (perguntas.containsKey(papel)) {
                perguntas.merge(papel, (int) t.texto().chars().filter(c -> c == '?').count(), Integer::sum);
            }
        }
        int total = Math.max(1, chars.values().stream().mapToInt(Integer::intValue).sum());
        int vendedorPct = (int) Math.round(100.0 * chars.get("vendedor") / total);
        int clientePct = (int) Math.round(100.0 * chars.get("cliente") / total);
        long locutores = turnos.stream().map(Turno::locutor).filter(Objects::nonNull).distinct().count();
        int palavras = 0;
        Matcher pm = REGEX_PALAVRA.matcher(texto);
        while (pm.find()) {
            palavras++;
        }
        int anonimos = 0;
        Matcher am = REGEX_ANONIMO.matcher(texto);
        while (am.find()) {
            anonimos++;
        }
        String escuta;
        if (vendedorPct == 0 && clientePct == 0) {
            escuta = "Nao mensuravel (sem identificacao de locutores)";
        } else if (vendedorPct <= 45) {
            escuta = "Vendedor ouviu mais do que falou";
        } else if (vendedorPct <= 60) {
            escuta = "Equilibrado";
        } else {
            escuta = "Vendedor falou mais do que ouviu";
        }
        String qualidade = palavras >= 150 && turnos.size() >= 2 ? "alta" : palavras >= 40 ? "media" : "baixa";
        Map<String, Object> ind = new LinkedHashMap<>();
        ind.put("locutores", (int) locutores);
        ind.put("turnos", turnos.size());
        ind.put("sentencas", sentencas.size());
        ind.put("palavras", palavras);
        ind.put("participacaoVendedorPct", vendedorPct);
        ind.put("participacaoClientePct", clientePct);
        ind.put("perguntasVendedor", perguntas.get("vendedor"));
        ind.put("perguntasCliente", perguntas.get("cliente"));
        ind.put("escutaAtiva", escuta);
        ind.put("tokensAnonimizados", anonimos);
        ind.put("qualidadeTranscricao", qualidade);
        return ind;
    }

    // ---------------------------------------------------------------- consolidacao

    private AiAnalysisDTO.MetricasAgregadasDTO extrairMetricasConsolidadas(String texto) {
        Matcher partes = Pattern.compile("SENTIMENTO_PARTES:\\s*positivas=(\\d+),\\s*neutras=(\\d+),\\s*negativas=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(texto);
        int positivas = 0, neutras = 0, negativas = 0;
        if (partes.find()) {
            positivas = Integer.parseInt(partes.group(1));
            neutras = Integer.parseInt(partes.group(2));
            negativas = Integer.parseInt(partes.group(3));
        }
        int total = number(texto, "TOTAL_PARTES_VALIDAS");
        if (total == 0) {
            total = positivas + neutras + negativas;
        }
        return AiAnalysisDTO.MetricasAgregadasDTO.builder()
                .totalPartes(total).partesAnalisadas(total)
                .partesNegativas(negativas).partesNeutras(neutras).partesPositivas(positivas)
                .riscosDetectados(number(texto, "RISCOS_DETECTADOS"))
                .oportunidadesDetectadas(number(texto, "OPORTUNIDADES_DETECTADAS"))
                .churnsPotenciais(number(texto, "CHURNS_POTENCIAIS"))
                .mencoesCriticas(number(texto, "MENCOES_CRITICAS"))
                .alertasConsolidados(0)
                .chunksProcessados(total > 0 ? Math.max(1, (total + 49) / 50) : 1)
                .build();
    }

    private static int number(String texto, String label) {
        Matcher m = Pattern.compile(label + ":\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(texto);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private AiAnalysisDTO.MetricasAgregadasDTO metricasPorTurnos(List<Turno> turnos, List<Sentenca> sentencas,
                                                                List<AiAnalysisDTO.RiscoDTO> riscos,
                                                                List<AiAnalysisDTO.OportunidadeDTO> oportunidades) {
        int pos = 0, neg = 0, neu = 0;
        for (Sentenca s : sentencas) {
            if (s.polaridade() >= 0.8) {
                pos++;
            } else if (s.polaridade() <= -0.8) {
                neg++;
            } else {
                neu++;
            }
        }
        int total = sentencas.size();
        return AiAnalysisDTO.MetricasAgregadasDTO.builder()
                .totalPartes(total).partesAnalisadas(total)
                .partesNegativas(neg).partesNeutras(neu).partesPositivas(pos)
                .riscosDetectados(riscos.size())
                .oportunidadesDetectadas(oportunidades.size())
                .churnsPotenciais((int) riscos.stream().filter(r -> "CHURN".equals(r.getTipo())).count())
                .mencoesCriticas((int) riscos.stream().filter(r -> "ALTO".equals(r.getSeveridade())).count())
                .alertasConsolidados(0)
                .chunksProcessados(Math.max(1, (turnos.size() + 49) / 50))
                .build();
    }

    private List<AiAnalysisDTO.RiscoConsolidadoDTO> consolidarRiscos(List<AiAnalysisDTO.RiscoDTO> riscos,
                                                                     AiAnalysisDTO.MetricasAgregadasDTO metricas) {
        Map<String, String> rotulos = Map.of("CHURN", "Churn", "CONCORRENCIA", "Concorrencia", "INSATISFACAO", "Insatisfacao",
                "PERDA_CONFIANCA", "Perda de confianca");
        Map<String, String> recomendacoes = Map.of(
                "CHURN", "Acionar plano de retencao com causa raiz, responsavel e prazo; envolver lideranca comercial.",
                "CONCORRENCIA", "Preparar comparativo objetivo contra o concorrente e reforcar diferenciais de integracao TOTVS.",
                "INSATISFACAO", "Registrar as dores por area, abrir plano de acao com suporte/produto e dar retorno com data.",
                "PERDA_CONFIANCA", "Escalar para gestao da conta, reconhecer o problema e combinar entregas verificaveis.");
        Map<String, AiAnalysisDTO.RiscoConsolidadoDTO> buckets = new LinkedHashMap<>();
        for (AiAnalysisDTO.RiscoDTO r : riscos) {
            AiAnalysisDTO.RiscoConsolidadoDTO b = buckets.computeIfAbsent(r.getTipo(), t -> AiAnalysisDTO.RiscoConsolidadoDTO.builder()
                    .categoria(rotulos.getOrDefault(t, t)).severidade(r.getSeveridade()).ocorrencias(0)
                    .recomendacao(recomendacoes.getOrDefault(t, "Agrupar evidencias e definir plano de acao.")).build());
            b.setOcorrencias(b.getOcorrencias() + 1);
            if ("ALTO".equals(r.getSeveridade())) {
                b.setSeveridade("ALTO");
            }
            if (b.getEvidencias().size() < 5) {
                b.getEvidencias().add(recortar(r.getDescricao(), 240));
            }
        }
        Integer churns = metricas.getChurnsPotenciais();
        if (churns != null && churns > 0 && !buckets.containsKey("CHURN")) {
            buckets.put("CHURN", AiAnalysisDTO.RiscoConsolidadoDTO.builder().categoria("Churn").severidade("ALTO")
                    .ocorrencias(churns).recomendacao(recomendacoes.get("CHURN")).build());
        }
        return new ArrayList<>(buckets.values());
    }

    private List<AiAnalysisDTO.OportunidadeConsolidadaDTO> consolidarOportunidades(List<AiAnalysisDTO.OportunidadeDTO> oportunidades,
                                                                                   AiAnalysisDTO.MetricasAgregadasDTO metricas) {
        Map<String, AiAnalysisDTO.OportunidadeConsolidadaDTO> buckets = new LinkedHashMap<>();
        for (AiAnalysisDTO.OportunidadeDTO o : oportunidades) {
            boolean generico = "A definir".equals(o.getProdutoRelacionado());
            String chave = generico ? o.getTipo() : o.getProdutoRelacionado();
            AiAnalysisDTO.OportunidadeConsolidadaDTO b = buckets.computeIfAbsent(chave, k -> AiAnalysisDTO.OportunidadeConsolidadaDTO.builder()
                    .categoria(generico ? titulo(k) : k).score(0.0).ocorrencias(0)
                    .recomendacao("Validar aderencia, estimar valor e encaminhar proposta com o modulo relacionado.").build());
            b.setOcorrencias(b.getOcorrencias() + 1);
            b.setScore(Math.max(b.getScore(), o.getScore()));
            if (b.getEvidencias().size() < 5) {
                b.getEvidencias().add(recortar(o.getDescricao(), 240));
            }
        }
        Integer detectadas = metricas.getOportunidadesDetectadas();
        if (detectadas != null && detectadas > 0 && buckets.isEmpty()) {
            buckets.put("geral", AiAnalysisDTO.OportunidadeConsolidadaDTO.builder().categoria("Melhoria no atendimento").score(0.72)
                    .ocorrencias(detectadas).recomendacao("Transformar recorrencias em backlog comercial e de produto.").build());
        }
        return new ArrayList<>(buckets.values());
    }

    private static String titulo(String tipo) {
        String[] partes = tipo.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : partes) {
            if (!p.isEmpty()) {
                sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private List<AiAnalysisDTO.AlertaConsolidadoDTO> gerarAlertasConsolidados(AiAnalysisDTO.MetricasAgregadasDTO metricas,
                                                                              List<AiAnalysisDTO.RiscoDTO> riscos, boolean consolidado) {
        List<AiAnalysisDTO.AlertaConsolidadoDTO> alertas = new ArrayList<>();
        if (consolidado) {
            int total = valor(metricas.getPartesAnalisadas());
            int negativas = valor(metricas.getPartesNegativas());
            double taxa = total == 0 ? 0 : (double) negativas / total;
            if (negativas >= 10 || taxa >= 0.15) {
                alertas.add(alerta("Alta recorrencia de sentimento negativo", "Sentimento negativo", taxa >= 0.3 ? "ALTO" : "MEDIO", negativas,
                        "Volume relevante de partes negativas na transcricao consolidada.", "Priorizar revisao das evidencias e contato de recuperacao."));
            }
            if (valor(metricas.getChurnsPotenciais()) > 0) {
                alertas.add(alerta("Indicadores de churn na transcricao", "Churn", "ALTO", metricas.getChurnsPotenciais(),
                        "Foram encontrados sinais de cancelamento, troca ou nao renovacao.", "Abrir plano de retencao com causa raiz e responsaveis."));
            }
            if (valor(metricas.getMencoesCriticas()) >= 5) {
                alertas.add(alerta("Muitas mencoes criticas operacionais", "Criticidade operacional", metricas.getMencoesCriticas() >= 15 ? "ALTO" : "MEDIO",
                        metricas.getMencoesCriticas(), "Recorrencia de mencoes criticas no material analisado.",
                        "Consolidar causas tecnicas e criar plano de acao com suporte e produto."));
            }
            return alertas;
        }
        Map<String, Integer> porConc = new LinkedHashMap<>();
        for (AiAnalysisDTO.RiscoDTO r : riscos) {
            if ("CONCORRENCIA".equals(r.getTipo()) && r.getConcorrenteMencionado() != null) {
                porConc.merge(r.getConcorrenteMencionado(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : porConc.entrySet()) {
            alertas.add(alerta("Cliente avaliando concorrente: " + e.getKey(), "Concorrencia", "ALTO", e.getValue(),
                    "A " + e.getKey() + " foi citada em contexto de avaliacao/demonstracao.",
                    "Antecipar proposta e destacar integracao nativa entre os modulos TOTVS."));
        }
        long churns = riscos.stream().filter(r -> "CHURN".equals(r.getTipo())).count();
        if (churns > 0) {
            alertas.add(alerta("Risco de churn", "Churn", "ALTO", (int) churns, "Cliente verbalizou cancelamento, troca ou nao renovacao.",
                    "Acionar plano de retencao imediato com lideranca comercial."));
        }
        long insat = riscos.stream().filter(r -> "INSATISFACAO".equals(r.getTipo()) && "ALTO".equals(r.getSeveridade())).count();
        if (insat >= 2) {
            alertas.add(alerta("Insatisfacao recorrente", "Sentimento negativo", "MEDIO", (int) insat,
                    "Mais de uma dor forte relatada na mesma conversa.", "Consolidar as dores e retornar com plano de acao datado."));
        }
        return alertas;
    }

    private static int valor(Integer v) {
        return v == null ? 0 : v;
    }

    private static AiAnalysisDTO.AlertaConsolidadoDTO alerta(String titulo, String categoria, String severidade, Integer ocorrencias,
                                                             String motivo, String recomendacao) {
        return AiAnalysisDTO.AlertaConsolidadoDTO.builder().titulo(titulo).categoria(categoria).severidade(severidade)
                .ocorrencias(ocorrencias).motivo(motivo).recomendacao(recomendacao).build();
    }

    private List<AiAnalysisDTO.ItemCriticoDTO> extrairItensCriticos(List<Sentenca> sentencas) {
        List<AiAnalysisDTO.ItemCriticoDTO> itens = new ArrayList<>();
        for (Sentenca s : sentencas) {
            String n = s.norm;
            if (!s.concorrentes.isEmpty()) {
                itens.add(item("Concorrencia", s.texto, "ALTO"));
            } else if (contem(n, CHURN_EXPLICITO) || (contem(n, CHURN_VERBOS) && contem(n, CHURN_OBJETOS))) {
                itens.add(item("Churn", s.texto, "ALTO"));
            } else if (s.neg >= 1.2 && !"vendedor".equals(s.papel)) {
                itens.add(item("Criticidade operacional", s.texto, "MEDIO"));
            } else if (contem(n, List.of("urgente", "critico", "parado", "parada", "nao consigo emitir", "producao parada"))) {
                itens.add(item("Criticidade operacional", s.texto, "ALTO"));
            }
            if (itens.size() >= 20) {
                break;
            }
        }
        return itens;
    }

    private static AiAnalysisDTO.ItemCriticoDTO item(String categoria, String trecho, String severidade) {
        return AiAnalysisDTO.ItemCriticoDTO.builder().tipo("risco").categoria(categoria).trecho(recortar(trecho, 300)).severidade(severidade).build();
    }

    // ---------------------------------------------------------------- resumo

    private String sugerirProximaAcao(String sentimento, List<AiAnalysisDTO.OportunidadeDTO> oportunidades,
                                      List<AiAnalysisDTO.RiscoDTO> riscos, List<String> concorrentes, AiAnalysisDTO.InsightsDTO insights) {
        if (riscos.stream().anyMatch(r -> "CHURN".equals(r.getTipo()))) {
            return "Acionar plano de retencao com a lideranca comercial antes do proximo contato.";
        }
        if (!concorrentes.isEmpty() && !oportunidades.isEmpty()) {
            return "Antecipar proposta do modulo de " + oportunidades.get(0).getProdutoRelacionado() + " com comparativo contra "
                    + String.join(", ", concorrentes) + ", destacando integracao nativa.";
        }
        if (!concorrentes.isEmpty()) {
            return "Preparar comparativo objetivo contra " + String.join(", ", concorrentes) + " e agendar demonstracao.";
        }
        if (!oportunidades.isEmpty() && oportunidades.get(0).getScore() >= 0.75) {
            return "Enviar proposta ou agendar demonstracao com os decisores nos proximos dias.";
        }
        if (!oportunidades.isEmpty()) {
            return "Qualificar necessidade, budget e prazo antes de enviar proposta.";
        }
        if (riscos.stream().anyMatch(r -> "ALTO".equals(r.getSeveridade()))) {
            return "Priorizar contato de recuperacao com plano de acao objetivo e data de retorno.";
        }
        if (!insights.getDuvidasJornada().isEmpty()) {
            return "Responder as duvidas levantadas sobre preco, prazo e integracao com material objetivo.";
        }
        if ("POSITIVO".equals(sentimento)) {
            return "Explorar expansao de uso e pedir indicacoes internas.";
        }
        return "Coletar mais contexto sobre dor, budget e decisores.";
    }

    private String gerarResumo(String detalhe, List<AiAnalysisDTO.ProdutoDTO> produtos, List<AiAnalysisDTO.OportunidadeDTO> oportunidades,
                               List<AiAnalysisDTO.RiscoDTO> riscos, String persona, String budget, LeanIAContextDTO contexto, String proximaAcao) {
        List<String> atuais = produtos.stream().filter(p -> "ATUAL".equals(p.getTipo())).map(AiAnalysisDTO.ProdutoDTO::getNomeProduto).toList();
        List<String> desejados = produtos.stream().filter(p -> "OPORTUNIDADE".equals(p.getTipo())).map(AiAnalysisDTO.ProdutoDTO::getNomeProduto).toList();
        List<String> concorrentes = produtos.stream().filter(p -> "CONCORRENTE".equals(p.getTipo())).map(AiAnalysisDTO.ProdutoDTO::getNomeProduto).toList();
        List<String> partes = new ArrayList<>();
        if (!atuais.isEmpty()) {
            partes.add("Cliente utiliza " + String.join(", ", atuais) + (desejados.isEmpty() ? "" : " e avalia " + String.join(", ", desejados)));
        } else if (!desejados.isEmpty()) {
            partes.add("Cliente avalia " + String.join(", ", desejados));
        }
        partes.add("Sentimento " + detalhe.toLowerCase(Locale.ROOT));
        long altos = riscos.stream().filter(r -> "ALTO".equals(r.getSeveridade())).count();
        if (!concorrentes.isEmpty()) {
            partes.add("ATENCAO: concorrente em avaliacao (" + String.join(", ", concorrentes) + ")");
        } else if (altos > 0) {
            partes.add("ATENCAO: " + altos + " risco(s) de alta severidade");
        }
        if (!oportunidades.isEmpty()) {
            partes.add(oportunidades.get(0).getDescricao().split(";")[0]);
        }
        if (persona != null && !persona.startsWith("Interlocutor nao")) {
            partes.add("Interlocutor: " + persona);
        }
        if (!"Nao identificado".equals(budget)) {
            partes.add("Budget mencionado: " + budget);
        }
        List<String> ajustes = contextList(contexto, contexto == null ? null : contexto.getAjustesRecentes());
        if (!ajustes.isEmpty()) {
            partes.add("Calibrado com feedback recente: " + String.join("; ", ajustes.subList(0, Math.min(2, ajustes.size()))));
        }
        partes.add("Proxima acao sugerida: " + proximaAcao.replaceAll("\\.$", ""));
        return partes.stream().filter(p -> p != null && !p.isEmpty()).map(p -> p.replaceAll("\\.$", ""))
                .reduce((a, b) -> a + ". " + b).orElse("") + ".";
    }

    @SuppressWarnings("unused")
    private static List<String> lista(String... itens) {
        return Arrays.asList(itens);
    }
}
