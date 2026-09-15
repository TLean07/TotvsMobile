package com.tlean07.totvs.ui.analise;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.ActivityAnaliseBinding;
import com.tlean07.totvs.databinding.ItemConsolidadoBinding;
import com.tlean07.totvs.databinding.ViewFeedbackBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.network.dto.AnaliseFeedbackResponse;
import com.tlean07.totvs.network.dto.OportunidadeConsolidadaDto;
import com.tlean07.totvs.network.dto.OportunidadeDto;
import com.tlean07.totvs.network.dto.RiscoConsolidadoDto;
import com.tlean07.totvs.network.dto.RiscoDto;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full analysis of one meeting – the mobile counterpart of the web
 * {@code /transcricoes/[id]} page: summary, persona/budget/confidence, next
 * action, "beyond the obvious" insights, conversation quality, products,
 * opportunities, risks, alerts, feedback to LeanIA and the original transcript.
 */
public class AnaliseDetalheActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "id";
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
    private static final SimpleDateFormat SHOWN = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    private ActivityAnaliseBinding binding;
    private DataRepository data;
    private String id;
    private TranscricaoResponse item;
    private List<AnaliseFeedbackResponse> feedbacks = new ArrayList<>();
    private ViewFeedbackBinding feedbackView;
    private float density;

    public static Intent intent(Context context, String transcricaoId) {
        return new Intent(context, AnaliseDetalheActivity.class).putExtra(EXTRA_ID, transcricaoId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAnaliseBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SecurityManager.applySecureFlag(this, true, TotvsApp.get().session());
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.anaHeader);
        SafeAreaHelper.applyBottomInset(binding.anaRoot);
        SafeAreaHelper.applyImeInset(binding.anaScroll);
        density = getResources().getDisplayMetrics().density;

        data = TotvsApp.get().dataRepository();
        id = getIntent().getStringExtra(EXTRA_ID);
        binding.anaBack.setOnClickListener(v -> finish());
        binding.anaRetry.setOnClickListener(v -> load(true));
        binding.anaSwipe.setOnRefreshListener(() -> load(true));
        if (id == null) {
            finish();
            return;
        }
        load(false);
    }

    private void load(boolean force) {
        binding.anaState.setVisibility(View.GONE);
        if (item == null) {
            binding.anaSkeleton.getRoot().setVisibility(View.VISIBLE);
        }
        data.transcricao(id, force, result -> {
            if (binding == null) {
                return;
            }
            binding.anaSkeleton.getRoot().setVisibility(View.GONE);
            binding.anaSwipe.setRefreshing(false);
            if (!result.success || result.data == null) {
                if (item == null) {
                    binding.anaStateText.setText(result.errorMessage != null ? result.errorMessage : getString(R.string.error_generic));
                    binding.anaState.setVisibility(View.VISIBLE);
                } else {
                    Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
                }
                return;
            }
            item = result.data;
            render();
            loadFeedbacks();
        });
    }

    private void loadFeedbacks() {
        data.feedbacks(id, result -> {
            if (binding == null) {
                return;
            }
            feedbacks = result.success && result.data != null ? result.data : new ArrayList<>();
            renderFeedbackHistory();
        });
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        TranscricaoResponse t = item;
        TranscricaoResponse.Analise a = t.analise;
        binding.anaTitle.setText(!TextUtils.isEmpty(t.cliente) ? t.cliente : getString(R.string.ana_title_default));

        List<String> sub = new ArrayList<>();
        if (!TextUtils.isEmpty(t.vendedor)) {
            sub.add("Vendedor: " + t.vendedor);
        }
        if (t.duracaoMinutos != null && t.duracaoMinutos > 0) {
            sub.add(getString(R.string.ana_minutos, t.duracaoMinutos));
        }
        if (t.status != null) {
            sub.add(t.status);
        }
        binding.anaSubtitle.setText(TextUtils.join(" · ", sub));

        String sentimento = a == null ? null : a.sentimentoGeral;
        if (sentimento != null) {
            binding.anaSentimento.setVisibility(View.VISIBLE);
            binding.anaSentimento.setText(sentimentoLabel(sentimento));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(999f);
            bg.setColor(ContextCompat.getColor(this, sentimentoColor(sentimento)));
            binding.anaSentimento.setBackground(bg);
        } else {
            binding.anaSentimento.setVisibility(View.GONE);
        }

        binding.anaChips.removeAllViews();
        addHeaderChip(origemLabel(t.origemTranscricao));
        if (!TextUtils.isEmpty(t.transcriptionModel)) {
            addHeaderChip(getString(R.string.ana_modelo, t.transcriptionModel));
        }
        if (t.audioDurationSeconds != null && t.audioDurationSeconds > 0) {
            addHeaderChip(getString(R.string.ana_audio_seg, Math.round(t.audioDurationSeconds)));
        }
        String quando = formatDate(t.dataProcessamento != null ? t.dataProcessamento : t.dataCriacao);
        if (quando != null) {
            addHeaderChip(quando);
        }
        if (!TextUtils.isEmpty(t.uf)) {
            addHeaderChip(t.uf);
        }
        if (!TextUtils.isEmpty(t.nomeSegmento)) {
            addHeaderChip(t.nomeSegmento);
        }

        LinearLayout content = binding.anaContent;
        content.removeAllViews();

        // Quick actions: client history + one-tap CRM hand-off.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = dp(12);
        actions.setLayoutParams(alp);
        if (!TextUtils.isEmpty(t.cliente)) {
            MaterialButton hist = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            hist.setText(R.string.cli_open);
            hist.setTextSize(12f);
            hist.setIconResource(R.drawable.ic_schedule);
            hist.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            hist.setOnClickListener(v -> startActivity(ClienteHistoricoActivity.intent(this, t.cliente)));
            actions.addView(hist);
        }
        MaterialButton share = new MaterialButton(this);
        share.setText(R.string.rev_share_summary);
        share.setTextSize(12f);
        share.setIconResource(R.drawable.ic_share);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.setMarginStart(actions.getChildCount() > 0 ? dp(8) : 0);
        share.setLayoutParams(slp);
        share.setOnClickListener(v -> shareSummary());
        actions.addView(share);
        content.addView(actions);

        if ("ERRO".equalsIgnoreCase(t.status)) {
            LinearLayout box = section(getString(R.string.ana_erro), null);
            MaterialButton btn = new MaterialButton(this);
            btn.setText(R.string.ana_reprocessar);
            btn.setIconResource(R.drawable.ic_refresh);
            btn.setOnClickListener(v -> reprocessar());
            box.addView(btn);
        } else if (a == null) {
            section(getString(R.string.ana_processando), null);
        }

        if (a != null) {
            renderResumo(a);
            renderProximaAcao(a);
            renderAlemDoObvio(a.insightsAdicionais);
            renderQualidade(a.indicadoresQualidade);
            renderMetricas(a.metricasAgregadas != null ? a.metricasAgregadas : t.metricasAgregadas);
            renderProdutos(a.produtos);
            renderOportunidades(t, a);
            renderRiscos(t, a);
            renderAlertas(t, a);
            renderCriticos(t);
            renderFeedback();
        }
        renderTranscricao(t.textoOriginal, t.participantes);
    }

    private void renderResumo(TranscricaoResponse.Analise a) {
        LinearLayout card = section(getString(R.string.ana_resumo_title), null);
        String exec = a.resumoExecutivo;
        if (!TextUtils.isEmpty(exec)) {
            card.addView(text(exec, 14, R.color.text_primary_light, false));
        }
        if (!TextUtils.isEmpty(a.resumo) && !a.resumo.equals(exec)) {
            TextView r = text(a.resumo, 13, R.color.text_secondary_light, false);
            r.setPadding(0, dp(6), 0, 0);
            card.addView(r);
        }
        LinearLayout grid = grid();
        tile(grid, getString(R.string.ana_persona), or(a.personaIdentificada), R.color.speaker_1);
        tile(grid, getString(R.string.ana_budget), or(a.budgetIdentificado), R.color.speaker_3);
        tile(grid, getString(R.string.ana_confianca), a.scoreConfiancaVendedor == null ? getString(R.string.ana_na)
                : Math.round(a.scoreConfiancaVendedor * 100) + "%", R.color.speaker_2);
        tile(grid, getString(R.string.ana_sentimento_score), a.scoreSentimento == null ? sentimentoLabel(a.sentimentoGeral)
                : String.format(Locale.ROOT, "%+.2f", a.scoreSentimento), sentimentoColor(a.sentimentoGeral));
        card.addView(grid);
    }

    private void renderProximaAcao(TranscricaoResponse.Analise a) {
        if (TextUtils.isEmpty(a.proximaAcao) && TextUtils.isEmpty(a.sentimentoDetalhe)) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_proxima_acao), null);
        if (!TextUtils.isEmpty(a.proximaAcao)) {
            TextView box = text(a.proximaAcao, 14, R.color.chip_role_fg, true);
            box.setBackgroundResource(R.drawable.bg_pill_role);
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(ContextCompat.getColor(this, R.color.chip_role_bg));
            box.setBackground(bg);
            card.addView(box);
        }
        if (!TextUtils.isEmpty(a.sentimentoDetalhe)) {
            TextView s = text(getString(R.string.ana_sentimento_area), 11, R.color.text_muted_light, true);
            s.setPadding(0, dp(10), 0, 0);
            card.addView(s);
            com.google.android.material.chip.ChipGroup group = new com.google.android.material.chip.ChipGroup(this);
            group.setChipSpacingHorizontal(dp(6));
            for (String parte : a.sentimentoDetalhe.split("\\s*[;|]\\s*|\\s*,\\s*(?=[A-ZÀ-Ú])")) {
                if (parte.isBlank()) {
                    continue;
                }
                com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
                chip.setText(parte.trim());
                chip.setTextSize(12f);
                chip.setClickable(false);
                int c = ContextCompat.getColor(this, sentimentoColor(guessSentimento(parte)));
                chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf((c & 0x00FFFFFF) | 0x1F000000));
                chip.setTextColor(c);
                group.addView(chip);
            }
            card.addView(group);
        }
    }

    private static String guessSentimento(String texto) {
        String t = texto.toLowerCase(Locale.ROOT);
        if (t.contains("negativ") || t.contains("insatisf") || t.contains("frustra")) {
            return "NEGATIVO";
        }
        if (t.contains("positiv") || t.contains("elogio") || t.contains("satisf")) {
            return "POSITIVO";
        }
        if (t.contains("mist")) {
            return "MISTO";
        }
        return "NEUTRO";
    }

    /** One-tap CRM hand-off: the analysis as plain text through the system share sheet. */
    private void shareSummary() {
        TranscricaoResponse t = item;
        TranscricaoResponse.Analise a = t.analise;
        StringBuilder sb = new StringBuilder();
        sb.append("Reunião: ").append(t.cliente != null ? t.cliente : "-").append('\n');
        if (t.vendedor != null) {
            sb.append("Vendedor: ").append(t.vendedor).append('\n');
        }
        String quando = formatDate(t.dataProcessamento != null ? t.dataProcessamento : t.dataCriacao);
        if (quando != null) {
            sb.append("Data: ").append(quando).append('\n');
        }
        if (a != null) {
            sb.append("Sentimento: ").append(sentimentoLabel(a.sentimentoGeral)).append('\n');
            if (a.personaIdentificada != null) {
                sb.append("Interlocutor: ").append(a.personaIdentificada).append('\n');
            }
            if (a.budgetIdentificado != null) {
                sb.append("Budget: ").append(a.budgetIdentificado).append('\n');
            }
            String resumo = t.resumoTexto();
            if (resumo != null) {
                sb.append("\nResumo: ").append(resumo).append('\n');
            }
            if (a.proximaAcao != null) {
                sb.append("\nPróxima ação: ").append(a.proximaAcao).append('\n');
            }
            List<OportunidadeConsolidadaDto> ops = firstNonEmpty(a.oportunidadesConsolidadas, t.oportunidadesConsolidadas);
            if (!ops.isEmpty()) {
                sb.append("\nOportunidades:\n");
                for (OportunidadeConsolidadaDto o : ops) {
                    sb.append("- ").append(o.categoria).append(o.recomendacao != null ? ": " + o.recomendacao : "").append('\n');
                }
            }
            List<RiscoConsolidadoDto> rs = firstNonEmpty(a.riscosConsolidados, t.riscosConsolidados);
            if (!rs.isEmpty()) {
                sb.append("\nRiscos:\n");
                for (RiscoConsolidadoDto r : rs) {
                    sb.append("- ").append(r.categoria).append(" (").append(severidadeLabel(r.severidade)).append(")")
                            .append(r.recomendacao != null ? ": " + r.recomendacao : "").append('\n');
                }
            }
        }
        sb.append("\nGerado pelo TOTVS Conversational Intelligence");
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Resumo da reunião: " + (t.cliente != null ? t.cliente : ""))
                .putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, getString(R.string.rev_share_summary)));
        com.tlean07.totvs.security.AuditLog.get(this).record("analise.compartilhada", t.id);
    }

    private void renderAlemDoObvio(@Nullable Map<String, Object> ins) {
        if (ins == null || ins.isEmpty()) {
            return;
        }
        String[][] blocos = {
                {"proximosPassos", getString(R.string.ana_alem_passos)},
                {"duvidasJornada", getString(R.string.ana_alem_duvidas)},
                {"reclamacoes", getString(R.string.ana_alem_reclamacoes)},
                {"elogios", getString(R.string.ana_alem_elogios)},
                {"observacoes", getString(R.string.ana_alem_observacoes)},
        };
        int[] cores = {R.color.speaker_1, R.color.speaker_2, R.color.totvs_danger, R.color.speaker_3, R.color.totvs_warning};
        LinearLayout card = null;
        for (int i = 0; i < blocos.length; i++) {
            List<String> itens = stringList(ins.get(blocos[i][0]));
            if (itens.isEmpty()) {
                continue;
            }
            if (card == null) {
                card = section(getString(R.string.ana_alem_title), getString(R.string.ana_alem_hint));
            }
            LinearLayout bloco = subBox();
            TextView titulo = text(blocos[i][1].toUpperCase(Locale.getDefault()), 11, cores[i], true);
            titulo.setLetterSpacing(0.06f);
            bloco.addView(titulo);
            for (String s : itens) {
                TextView li = text("•  " + s, 13, R.color.text_primary_light, false);
                li.setPadding(0, dp(4), 0, 0);
                bloco.addView(li);
            }
            card.addView(bloco);
        }
    }

    private void renderQualidade(@Nullable Map<String, Object> q) {
        if (q == null || q.get("turnos") == null) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_qualidade_title), getString(R.string.ana_qualidade_hint));
        LinearLayout grid = grid();
        tile(grid, getString(R.string.ana_q_escuta), str(q.get("escutaAtiva"), "-"), R.color.speaker_1);
        tile(grid, getString(R.string.ana_q_vendedor), num(q.get("participacaoVendedorPct")) + "%", R.color.speaker_1);
        tile(grid, getString(R.string.ana_q_cliente), num(q.get("participacaoClientePct")) + "%", R.color.speaker_2);
        tile(grid, getString(R.string.ana_q_perg_vendedor), num(q.get("perguntasVendedor")), R.color.speaker_1);
        tile(grid, getString(R.string.ana_q_perg_cliente), num(q.get("perguntasCliente")), R.color.speaker_2);
        tile(grid, getString(R.string.ana_q_locutores), num(q.get("locutores")), R.color.speaker_3);
        tile(grid, getString(R.string.ana_q_turnos), num(q.get("turnos")), R.color.speaker_3);
        tile(grid, getString(R.string.ana_q_palavras), num(q.get("palavras")), R.color.speaker_3);
        card.addView(grid);
    }

    private void renderMetricas(@Nullable TranscricaoResponse.Metricas m) {
        if (m == null || m.partesAnalisadas == 0) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_metricas_title), null);
        LinearLayout grid = grid();
        tile(grid, getString(R.string.ana_m_partes), String.valueOf(m.partesAnalisadas), R.color.speaker_1);
        tile(grid, getString(R.string.ana_m_positivas), String.valueOf(m.partesPositivas), R.color.speaker_3);
        tile(grid, getString(R.string.ana_m_neutras), String.valueOf(m.partesNeutras), R.color.speaker_unknown);
        tile(grid, getString(R.string.ana_m_negativas), String.valueOf(m.partesNegativas), R.color.totvs_danger);
        tile(grid, getString(R.string.ana_m_riscos), String.valueOf(m.riscosDetectados), R.color.totvs_danger);
        tile(grid, getString(R.string.ana_m_oportunidades), String.valueOf(m.oportunidadesDetectadas), R.color.speaker_3);
        tile(grid, getString(R.string.ana_m_churn), String.valueOf(m.churnsPotenciais), R.color.totvs_warning);
        tile(grid, getString(R.string.ana_m_alertas), String.valueOf(m.alertasConsolidados), R.color.speaker_2);
        card.addView(grid);
    }

    private void renderProdutos(@Nullable List<TranscricaoResponse.Produto> produtos) {
        if (produtos == null || produtos.isEmpty()) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_produtos_title), null);
        for (TranscricaoResponse.Produto p : produtos) {
            LinearLayout box = subBox();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView nome = text(or(p.nomeProduto), 14, R.color.text_primary_light, true);
            nome.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nome);
            row.addView(pill(produtoLabel(p.tipo), produtoColor(p.tipo)));
            box.addView(row);
            if (!TextUtils.isEmpty(p.contexto)) {
                TextView ctx = text(p.contexto, 13, R.color.text_secondary_light, false);
                ctx.setPadding(0, dp(4), 0, 0);
                box.addView(ctx);
            }
            card.addView(box);
        }
    }

    private void renderOportunidades(TranscricaoResponse t, TranscricaoResponse.Analise a) {
        List<OportunidadeConsolidadaDto> cons = firstNonEmpty(a.oportunidadesConsolidadas, t.oportunidadesConsolidadas);
        List<OportunidadeDto> brutas = a.oportunidades == null ? Collections.emptyList() : a.oportunidades;
        if (cons.isEmpty() && brutas.isEmpty()) {
            return;
        }
        int internas = 0;
        for (OportunidadeConsolidadaDto o : cons) {
            internas += o.ocorrencias;
        }
        String hint = cons.isEmpty() ? null : getString(R.string.ana_consolidados, cons.size(), Math.max(internas, brutas.size()));
        LinearLayout card = section(getString(R.string.ana_oportunidades_title), hint);
        if (!cons.isEmpty()) {
            for (OportunidadeConsolidadaDto o : cons) {
                String badge = o.score == null ? null : getString(R.string.ana_score, Math.round(o.score * 100));
                card.addView(consolidado(or(o.categoria), badge, R.color.chip_muted_fg, R.drawable.bg_pill_muted,
                        getString(R.string.ana_ocorrencias, o.ocorrencias), o.evidencias, o.recomendacao, R.color.speaker_3));
            }
        } else {
            for (OportunidadeDto o : brutas) {
                String badge = o.score == null ? null : getString(R.string.ana_score, Math.round(o.score * 100));
                String meta = TextUtils.isEmpty(o.produtoRelacionado) ? or(o.tipo) : or(o.tipo) + " · " + o.produtoRelacionado;
                card.addView(consolidado(or(o.descricao), badge, R.color.chip_muted_fg, R.drawable.bg_pill_muted, meta,
                        null, o.valorEstimado, R.color.speaker_3));
            }
        }
    }

    private void renderRiscos(TranscricaoResponse t, TranscricaoResponse.Analise a) {
        List<RiscoConsolidadoDto> cons = firstNonEmpty(a.riscosConsolidados, t.riscosConsolidados);
        List<RiscoDto> brutos = a.riscos == null ? Collections.emptyList() : a.riscos;
        if (cons.isEmpty() && brutos.isEmpty()) {
            return;
        }
        int internas = 0;
        for (RiscoConsolidadoDto r : cons) {
            internas += r.ocorrencias;
        }
        String hint = cons.isEmpty() ? null : getString(R.string.ana_consolidados, cons.size(), Math.max(internas, brutos.size()));
        LinearLayout card = section(getString(R.string.ana_riscos_title), hint);
        if (!cons.isEmpty()) {
            for (RiscoConsolidadoDto r : cons) {
                card.addView(consolidado(or(r.categoria), severidadeLabel(r.severidade), severidadeFg(r.severidade),
                        severidadeBg(r.severidade), getString(R.string.ana_ocorrencias, r.ocorrencias), r.evidencias,
                        r.recomendacao, R.color.totvs_danger));
            }
        } else {
            for (RiscoDto r : brutos) {
                String meta = TextUtils.isEmpty(r.concorrenteMencionado) ? or(r.tipo) : or(r.tipo) + " · " + r.concorrenteMencionado;
                card.addView(consolidado(or(r.descricao), severidadeLabel(r.severidade), severidadeFg(r.severidade),
                        severidadeBg(r.severidade), meta, null, null, R.color.totvs_danger));
            }
        }
    }

    private void renderAlertas(TranscricaoResponse t, TranscricaoResponse.Analise a) {
        List<AlertaResponse> alertas = a.alertas == null ? Collections.emptyList() : a.alertas;
        List<TranscricaoResponse.AlertaConsolidado> cons = firstNonEmpty(a.alertasConsolidados, t.alertasConsolidados);
        if (alertas.isEmpty() && cons.isEmpty()) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_alertas_title), null);
        if (!alertas.isEmpty()) {
            for (AlertaResponse al : alertas) {
                List<String> ev = TextUtils.isEmpty(al.mensagem) ? null : Collections.singletonList(al.mensagem);
                String meta = TextUtils.isEmpty(al.categoria) ? or(al.tipo) : al.categoria;
                if (al.ocorrencias != null && al.ocorrencias > 1) {
                    meta += " · " + al.ocorrencias + "x";
                }
                card.addView(consolidado(or(al.titulo), severidadeLabel(al.prioridade), severidadeFg(al.prioridade),
                        severidadeBg(al.prioridade), meta, ev, al.recomendacao, R.color.totvs_warning));
            }
        } else {
            for (TranscricaoResponse.AlertaConsolidado al : cons) {
                List<String> ev = TextUtils.isEmpty(al.motivo) ? null : Collections.singletonList(al.motivo);
                card.addView(consolidado(or(al.titulo), severidadeLabel(al.severidade), severidadeFg(al.severidade),
                        severidadeBg(al.severidade), or(al.categoria) + " · " + al.ocorrencias + "x", ev, al.recomendacao,
                        R.color.totvs_warning));
            }
        }
    }

    private void renderCriticos(TranscricaoResponse t) {
        List<TranscricaoResponse.ItemCritico> itens = firstNonEmpty(t.analise == null ? null : t.analise.itensCriticos, t.itensCriticos);
        if (itens.isEmpty()) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_criticos_title), null);
        int shown = 0;
        for (TranscricaoResponse.ItemCritico i : itens) {
            if (shown++ >= 8) {
                break;
            }
            card.addView(consolidado(or(i.categoria), severidadeLabel(i.severidade), severidadeFg(i.severidade),
                    severidadeBg(i.severidade), or(i.tipo), TextUtils.isEmpty(i.trecho) ? null : Collections.singletonList(i.trecho),
                    null, R.color.totvs_danger));
        }
    }

    private void renderFeedback() {
        LinearLayout card = section(getString(R.string.ana_fb_title), getString(R.string.ana_fb_hint_title));
        feedbackView = ViewFeedbackBinding.inflate(LayoutInflater.from(this), card, false);
        feedbackView.fbUtil.setOnClickListener(v -> enviarFeedback(true));
        feedbackView.fbAjuste.setOnClickListener(v -> enviarFeedback(false));
        card.addView(feedbackView.getRoot());
        renderFeedbackHistory();
    }

    private void renderFeedbackHistory() {
        if (feedbackView == null) {
            return;
        }
        LinearLayout h = feedbackView.fbHistorico;
        h.removeAllViews();
        if (feedbacks.isEmpty()) {
            h.addView(text(getString(R.string.ana_fb_nenhum), 13, R.color.text_muted_light, false));
            return;
        }
        for (AnaliseFeedbackResponse f : feedbacks) {
            LinearLayout box = subBox();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tag = text(getString(f.util ? R.string.ana_fb_marcado_util : R.string.ana_fb_marcado_ajuste), 12,
                    f.util ? R.color.chip_success_fg : R.color.chip_warning_fg, true);
            tag.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tag);
            String when = formatDate(f.createdAt);
            if (when != null) {
                row.addView(text(when, 11, R.color.text_muted_light, false));
            }
            box.addView(row);
            if (!TextUtils.isEmpty(f.ajuste)) {
                TextView aj = text(f.ajuste, 13, R.color.text_primary_light, false);
                aj.setPadding(0, dp(4), 0, 0);
                box.addView(aj);
            }
            h.addView(box);
        }
    }

    private void renderTranscricao(@Nullable String texto, @Nullable String participantes) {
        if (TextUtils.isEmpty(texto) && TextUtils.isEmpty(participantes)) {
            return;
        }
        LinearLayout card = section(getString(R.string.ana_transcricao_title),
                TextUtils.isEmpty(participantes) ? null : getString(R.string.ana_participantes, participantes));
        if (TextUtils.isEmpty(texto)) {
            return;
        }
        TextView tv = text(texto, 13, R.color.text_secondary_light, false);
        tv.setMaxLines(8);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(tv);
        MaterialButton more = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        more.setText(R.string.ana_ver_mais);
        more.setTextColor(ContextCompat.getColor(this, R.color.totvs_secondary));
        more.setOnClickListener(v -> {
            boolean expanded = tv.getMaxLines() == Integer.MAX_VALUE;
            tv.setMaxLines(expanded ? 8 : Integer.MAX_VALUE);
            more.setText(expanded ? R.string.ana_ver_mais : R.string.ana_ver_menos);
        });
        card.addView(more);
    }

    // ------------------------------------------------------------------ actions

    private void enviarFeedbackAudit(boolean util) {
        com.tlean07.totvs.security.AuditLog.get(this).record("feedback.enviado", id + " · " + (util ? "útil" : "ajuste"));
    }

    private void enviarFeedback(boolean util) {
        String texto = feedbackView.fbTexto.getText() == null ? "" : feedbackView.fbTexto.getText().toString().trim();
        feedbackView.fbUtil.setEnabled(false);
        feedbackView.fbAjuste.setEnabled(false);
        data.registrarFeedback(id, util, texto.isEmpty() ? null : texto, result -> {
            if (binding == null || feedbackView == null) {
                return;
            }
            feedbackView.fbUtil.setEnabled(true);
            feedbackView.fbAjuste.setEnabled(true);
            if (result.success) {
                feedbackView.fbTexto.setText("");
                enviarFeedbackAudit(util);
                Toast.makeText(this, R.string.ana_fb_enviado, Toast.LENGTH_SHORT).show();
                loadFeedbacks();
            } else {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void reprocessar() {
        binding.anaProgress.setVisibility(View.VISIBLE);
        data.reprocessar(id, result -> {
            if (binding == null) {
                return;
            }
            binding.anaProgress.setVisibility(View.GONE);
            if (result.success) {
                Toast.makeText(this, R.string.ana_reprocessando, Toast.LENGTH_SHORT).show();
                load(true);
            } else {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------ view builders

    /** A white card with a bold title (and optional hint); returns its content column. */
    private LinearLayout section(String title, @Nullable String hint) {
        CardView card = new CardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(dp(1));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_light));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(14), dp(14), dp(14), dp(14));
        column.addView(text(title, 15, R.color.text_primary_light, true));
        if (hint != null) {
            TextView h = text(hint, 12, R.color.text_muted_light, false);
            h.setPadding(0, dp(2), 0, 0);
            column.addView(h);
        }
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dp(8)));
        column.addView(spacer);
        card.addView(column);
        binding.anaContent.addView(card);
        return column;
    }

    private LinearLayout subBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card_outline);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    /** Two-column grid of tiles (rows are created on demand by {@link #tile}). */
    private LinearLayout grid() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return g;
    }

    private void tile(LinearLayout grid, String label, String value, @ColorRes int accent) {
        LinearLayout row;
        int rows = grid.getChildCount();
        if (rows == 0 || ((LinearLayout) grid.getChildAt(rows - 1)).getChildCount() >= 2) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            row.setLayoutParams(lp);
            grid.addView(row);
        } else {
            row = (LinearLayout) grid.getChildAt(rows - 1);
        }
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        int c = ContextCompat.getColor(this, accent);
        bg.setColor((c & 0x00FFFFFF) | 0x14000000);
        bg.setStroke(dp(1), (c & 0x00FFFFFF) | 0x33000000);
        t.setBackground(bg);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginStart(row.getChildCount() == 0 ? 0 : dp(8));
        t.setLayoutParams(lp);
        TextView l = text(label.toUpperCase(Locale.getDefault()), 10, accent, true);
        l.setLetterSpacing(0.06f);
        t.addView(l);
        TextView v = text(value, 14, R.color.text_primary_light, true);
        v.setPadding(0, dp(3), 0, 0);
        t.addView(v);
        row.addView(t);
    }

    private View consolidado(String titulo, @Nullable String badge, @ColorRes int badgeFg, int badgeBg, String meta,
                             @Nullable List<String> evidencias, @Nullable String recomendacao, @ColorRes int accent) {
        ItemConsolidadoBinding b = ItemConsolidadoBinding.inflate(LayoutInflater.from(this), binding.anaContent, false);
        b.consAccent.setBackgroundColor(ContextCompat.getColor(this, accent));
        b.consCategoria.setText(titulo);
        if (badge == null) {
            b.consBadge.setVisibility(View.GONE);
        } else {
            b.consBadge.setText(badge);
            b.consBadge.setBackgroundResource(badgeBg);
            b.consBadge.setTextColor(ContextCompat.getColor(this, badgeFg));
        }
        b.consMeta.setText(meta);
        b.consMeta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);
        if (evidencias != null) {
            int n = 0;
            for (String e : evidencias) {
                if (TextUtils.isEmpty(e) || n++ >= 3) {
                    continue;
                }
                TextView tv = text("“" + e.trim() + "”", 12, R.color.text_secondary_light, false);
                tv.setBackgroundResource(R.drawable.bg_card_outline);
                tv.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                tv.setLayoutParams(lp);
                b.consEvidencias.addView(tv);
            }
        }
        if (TextUtils.isEmpty(recomendacao)) {
            b.consRecomendacao.setVisibility(View.GONE);
        } else {
            b.consRecomendacao.setText(recomendacao);
        }
        return b.getRoot();
    }

    private TextView pill(String label, @ColorRes int color) {
        TextView p = text(label, 11, color, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999f);
        int c = ContextCompat.getColor(this, color);
        bg.setColor((c & 0x00FFFFFF) | 0x1F000000);
        p.setBackground(bg);
        p.setPadding(dp(9), dp(4), dp(9), dp(4));
        return p;
    }

    private void addHeaderChip(String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(11f);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setTextColor(0xFFFFFFFF);
        chip.setBackgroundResource(R.drawable.bg_chip_glass);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        binding.anaChips.addView(chip, lp);
    }

    private TextView text(String s, int sp, @ColorRes int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        tv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ labels

    private String or(@Nullable String s) {
        return TextUtils.isEmpty(s) ? getString(R.string.ana_na) : s;
    }

    private String origemLabel(@Nullable String origem) {
        if (origem == null) {
            return getString(R.string.ana_origem_manual);
        }
        switch (origem.toLowerCase(Locale.ROOT)) {
            case "parakeet":
            case "audio":
                return getString(R.string.ana_origem_audio);
            case "json":
            case "totvs":
            case "csv":
                return getString(R.string.ana_origem_json);
            default:
                return getString(R.string.ana_origem_manual);
        }
    }

    private String sentimentoLabel(@Nullable String s) {
        if (s == null) {
            return getString(R.string.ana_na);
        }
        switch (s.toUpperCase(Locale.ROOT)) {
            case "POSITIVO":
                return getString(R.string.ana_sent_positivo);
            case "NEGATIVO":
                return getString(R.string.ana_sent_negativo);
            case "MISTO":
                return getString(R.string.ana_sent_misto);
            case "NEUTRO":
                return getString(R.string.ana_sent_neutro);
            default:
                return s;
        }
    }

    @ColorRes
    private int sentimentoColor(@Nullable String s) {
        if (s == null) {
            return R.color.speaker_unknown;
        }
        switch (s.toUpperCase(Locale.ROOT)) {
            case "POSITIVO":
                return R.color.totvs_accent;
            case "NEGATIVO":
                return R.color.totvs_danger;
            case "MISTO":
                return R.color.totvs_warning;
            default:
                return R.color.speaker_unknown;
        }
    }

    private String severidadeLabel(@Nullable String s) {
        if (s == null) {
            return null;
        }
        switch (s.toUpperCase(Locale.ROOT)) {
            case "CRITICA":
            case "CRITICO":
                return getString(R.string.ana_sev_critica);
            case "ALTA":
            case "ALTO":
                return getString(R.string.ana_sev_alta);
            case "MEDIA":
            case "MEDIO":
                return getString(R.string.ana_sev_media);
            case "BAIXA":
            case "BAIXO":
                return getString(R.string.ana_sev_baixa);
            default:
                return s;
        }
    }

    @ColorRes
    private int severidadeFg(@Nullable String s) {
        String u = s == null ? "" : s.toUpperCase(Locale.ROOT);
        if (u.startsWith("CRIT") || u.startsWith("ALT")) {
            return R.color.chip_danger_fg;
        }
        if (u.startsWith("MED")) {
            return R.color.chip_warning_fg;
        }
        return R.color.chip_muted_fg;
    }

    private int severidadeBg(@Nullable String s) {
        String u = s == null ? "" : s.toUpperCase(Locale.ROOT);
        if (u.startsWith("CRIT") || u.startsWith("ALT")) {
            return R.drawable.bg_pill_danger;
        }
        if (u.startsWith("MED")) {
            return R.drawable.bg_pill_warning;
        }
        return R.drawable.bg_pill_muted;
    }

    private String produtoLabel(@Nullable String tipo) {
        if ("OPORTUNIDADE".equalsIgnoreCase(tipo)) {
            return getString(R.string.ana_prod_oportunidade);
        }
        if ("CONCORRENTE".equalsIgnoreCase(tipo)) {
            return getString(R.string.ana_prod_concorrente);
        }
        return getString(R.string.ana_prod_atual);
    }

    @ColorRes
    private int produtoColor(@Nullable String tipo) {
        if ("OPORTUNIDADE".equalsIgnoreCase(tipo)) {
            return R.color.totvs_accent;
        }
        if ("CONCORRENTE".equalsIgnoreCase(tipo)) {
            return R.color.totvs_danger;
        }
        return R.color.totvs_secondary;
    }

    // ------------------------------------------------------------------ small helpers

    private static <T> List<T> firstNonEmpty(@Nullable List<T> a, @Nullable List<T> b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b == null ? Collections.emptyList() : b;
    }

    private static List<String> stringList(@Nullable Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object e : (List<?>) o) {
                if (e != null && !e.toString().isBlank()) {
                    out.add(e.toString());
                }
            }
        } else if (o instanceof String && !((String) o).isBlank()) {
            out.addAll(Arrays.asList(((String) o).split("\\s*;\\s*")));
        }
        return out;
    }

    private static String num(@Nullable Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
        }
        return o == null ? "0" : o.toString();
    }

    private static String str(@Nullable Object o, String fallback) {
        return o == null || o.toString().isBlank() ? fallback : (o instanceof Number ? num(o) : o.toString());
    }

    @Nullable
    private static String formatDate(@Nullable String iso) {
        if (iso == null || iso.length() < 16) {
            return null;
        }
        try {
            return SHOWN.format(ISO.parse(iso.substring(0, 16)));
        } catch (Exception e) {
            return iso.substring(0, Math.min(iso.length(), 16)).replace('T', ' ');
        }
    }
}
