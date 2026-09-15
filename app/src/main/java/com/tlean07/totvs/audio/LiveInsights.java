package com.tlean07.totvs.audio;

import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.leania.AiAnalysisDTO;
import com.tlean07.totvs.leania.LeanIALocalAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the LeanIA analyser (the same Java port the backend uses) over the live
 * transcript while the meeting is still going, so the recorder can flag
 * churn signals, competitors, budget and buying intent in real time – no
 * server involved. Throttled: at most one run every {@value #MIN_INTERVAL_MS}
 * ms and only when the text grew meaningfully.
 */
public final class LiveInsights {

    public interface Listener {
        /** Background thread. */
        void onInsights(Recording.AnaliseLocal resumo, AiAnalysisDTO completo);
    }

    private static final long MIN_INTERVAL_MS = 8_000;
    private static final int MIN_NEW_CHARS = 40;

    private final LeanIALocalAnalyzer analyzer = new LeanIALocalAnalyzer();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leania-live");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String pending;
    private volatile long lastRunMs;
    private volatile int lastLength;
    private volatile Recording.AnaliseLocal latest;
    private volatile boolean closed;

    public LiveInsights(Listener listener) {
        this.listener = listener;
    }

    @Nullable
    public Recording.AnaliseLocal latest() {
        return latest;
    }

    /** Offers the current turns; runs the analyser when it is worth it. */
    public void submit(List<TranscriptTurn> turns) {
        if (closed) {
            return;
        }
        String texto = TranscriptFormatter.toLeanIa(turns, null);
        if (texto.length() < 60) {
            return;
        }
        pending = texto;
        long now = System.currentTimeMillis();
        if (now - lastRunMs < MIN_INTERVAL_MS || texto.length() - lastLength < MIN_NEW_CHARS) {
            return;
        }
        if (running.compareAndSet(false, true)) {
            lastRunMs = now;
            lastLength = texto.length();
            worker.execute(this::run);
        }
    }

    /** Synchronous final pass over the complete transcript (called once when the recording ends). */
    @Nullable
    public Recording.AnaliseLocal analyseNow(List<TranscriptTurn> turns) {
        String texto = TranscriptFormatter.toLeanIa(turns, null);
        if (texto.length() < 20) {
            return latest;
        }
        try {
            AiAnalysisDTO dto = analyzer.analisar(texto, null);
            latest = resumo(dto);
            return latest;
        } catch (RuntimeException e) {
            return latest;
        }
    }

    private void run() {
        try {
            String texto = pending;
            if (texto == null) {
                return;
            }
            AiAnalysisDTO dto = analyzer.analisar(texto, null);
            Recording.AnaliseLocal r = resumo(dto);
            latest = r;
            listener.onInsights(r, dto);
        } catch (RuntimeException ignored) {
            // the live view simply keeps the previous result
        } finally {
            running.set(false);
        }
    }

    public void close() {
        closed = true;
        worker.shutdownNow();
    }

    /** Boils the full DTO down to what the live card and the review preview show. */
    public static Recording.AnaliseLocal resumo(AiAnalysisDTO dto) {
        Recording.AnaliseLocal r = new Recording.AnaliseLocal();
        r.sentimentoGeral = dto.getSentimentoGeral();
        r.scoreSentimento = dto.getScoreSentimento();
        r.resumo = dto.getResumo();
        r.proximaAcao = dto.getProximaAcao();
        r.persona = dto.getPersonaIdentificada();
        r.budget = dto.getBudgetIdentificado();
        r.confiancaVendedor = dto.getScoreConfiancaVendedor();
        r.riscos = new ArrayList<>();
        if (dto.getRiscos() != null) {
            for (AiAnalysisDTO.RiscoDTO x : dto.getRiscos()) {
                Recording.Sinal s = new Recording.Sinal();
                s.tipo = x.getTipo();
                s.severidade = x.getSeveridade();
                s.texto = x.getDescricao();
                s.extra = x.getConcorrenteMencionado();
                r.riscos.add(s);
            }
        }
        r.oportunidades = new ArrayList<>();
        if (dto.getOportunidades() != null) {
            for (AiAnalysisDTO.OportunidadeDTO x : dto.getOportunidades()) {
                Recording.Sinal s = new Recording.Sinal();
                s.tipo = x.getTipo();
                s.texto = x.getDescricao();
                s.extra = x.getProdutoRelacionado();
                s.score = x.getScore();
                r.oportunidades.add(s);
            }
        }
        r.concorrentes = new ArrayList<>();
        r.produtos = new ArrayList<>();
        if (dto.getProdutos() != null) {
            for (AiAnalysisDTO.ProdutoDTO p : dto.getProdutos()) {
                if ("CONCORRENTE".equalsIgnoreCase(p.getTipo())) {
                    if (p.getNomeProduto() != null && !r.concorrentes.contains(p.getNomeProduto())) {
                        r.concorrentes.add(p.getNomeProduto());
                    }
                } else if (p.getNomeProduto() != null && !r.produtos.contains(p.getNomeProduto())) {
                    r.produtos.add(p.getNomeProduto());
                }
            }
        }
        r.alertas = new ArrayList<>();
        if (dto.getAlertasConsolidados() != null) {
            for (AiAnalysisDTO.AlertaConsolidadoDTO a : dto.getAlertasConsolidados()) {
                Recording.Sinal s = new Recording.Sinal();
                s.tipo = a.getCategoria();
                s.severidade = a.getSeveridade();
                s.texto = a.getTitulo();
                s.extra = a.getRecomendacao();
                r.alertas.add(s);
            }
        }
        r.atualizadoEm = System.currentTimeMillis();
        return r;
    }
}
