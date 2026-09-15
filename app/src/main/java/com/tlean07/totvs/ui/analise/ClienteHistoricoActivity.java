package com.tlean07.totvs.ui.analise;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.ActivityClienteBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.ui.components.TranscricaoAdapter;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Every analysed meeting of one client, with the sentiment trend across them. */
public class ClienteHistoricoActivity extends AppCompatActivity {

    private static final String EXTRA_CLIENTE = "cliente";
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
    private static final SimpleDateFormat SHORT = new SimpleDateFormat("dd/MM", Locale.getDefault());

    private ActivityClienteBinding binding;
    private DataRepository data;
    private TranscricaoAdapter adapter;
    private String cliente;

    public static Intent intent(Context context, String cliente) {
        return new Intent(context, ClienteHistoricoActivity.class).putExtra(EXTRA_CLIENTE, cliente);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityClienteBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SecurityManager.applySecureFlag(this, true, TotvsApp.get().session());
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.cliHeader);
        SafeAreaHelper.applyBottomInset(binding.cliRoot);
        data = TotvsApp.get().dataRepository();
        cliente = getIntent().getStringExtra(EXTRA_CLIENTE);
        binding.cliTitle.setText(cliente);
        binding.cliBack.setOnClickListener(v -> finish());
        adapter = new TranscricaoAdapter(t -> startActivity(AnaliseDetalheActivity.intent(this, t.id)));
        binding.cliRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.cliRecycler.setAdapter(adapter);
        load();
    }

    private void load() {
        binding.cliProgress.setVisibility(View.VISIBLE);
        data.transcricoesPorCliente(cliente, result -> {
            if (binding == null) {
                return;
            }
            binding.cliProgress.setVisibility(View.GONE);
            if (!result.success || result.data == null) {
                binding.cliState.setText(result.errorMessage != null ? result.errorMessage : getString(R.string.error_generic));
                binding.cliState.setVisibility(View.VISIBLE);
                return;
            }
            render(result.data);
        });
    }

    private void render(List<TranscricaoResponse> list) {
        List<TranscricaoResponse> ordered = new ArrayList<>(list);
        Collections.sort(ordered, (a, b) -> String.valueOf(a.dataCriacao).compareTo(String.valueOf(b.dataCriacao)));
        binding.cliSubtitle.setText(getString(R.string.cli_subtitle, ordered.size()));

        List<SentimentTimelineView.Point> pts = new ArrayList<>();
        int riscos = 0;
        int oportunidades = 0;
        for (TranscricaoResponse t : ordered) {
            if (t.analise == null) {
                continue;
            }
            double score = t.analise.scoreSentimento != null ? t.analise.scoreSentimento : scoreOf(t.analise.sentimentoGeral);
            pts.add(new SentimentTimelineView.Point(score, t.analise.sentimentoGeral, shortDate(t.dataCriacao)));
            riscos += t.riscosConsolidadosOuVazio().size();
            oportunidades += t.oportunidadesConsolidadasOuVazio().size();
        }
        binding.cliTimeline.setPoints(pts);
        binding.cliChartHint.setText(R.string.cli_chart_hint);

        String trend = getString(R.string.cli_trend_flat);
        int trendColor = R.color.speaker_unknown;
        if (pts.size() >= 2) {
            int half = pts.size() / 2;
            double first = 0;
            double last = 0;
            for (int i = 0; i < half; i++) {
                first += pts.get(i).score;
            }
            for (int i = pts.size() - half; i < pts.size(); i++) {
                last += pts.get(i).score;
            }
            double delta = last / half - first / half;
            if (delta > 0.15) {
                trend = getString(R.string.cli_trend_up);
                trendColor = R.color.totvs_accent;
            } else if (delta < -0.15) {
                trend = getString(R.string.cli_trend_down);
                trendColor = R.color.totvs_danger;
            }
        }
        binding.cliStats.removeAllViews();
        stat(getString(R.string.cli_stat_reunioes), String.valueOf(ordered.size()), R.color.speaker_1);
        stat(getString(R.string.cli_stat_riscos), String.valueOf(riscos), R.color.totvs_danger);
        stat(getString(R.string.cli_stat_oportunidades), String.valueOf(oportunidades), R.color.totvs_accent);
        stat(getString(R.string.cli_stat_tendencia), trend, trendColor);

        Collections.reverse(ordered);
        adapter.submit(ordered);
    }

    private void stat(String label, String value, int color) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * d);
        int c = ContextCompat.getColor(this, color);
        bg.setColor((c & 0x00FFFFFF) | 0x14000000);
        box.setBackground(bg);
        box.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(binding.cliStats.getChildCount() == 3 ? 0 : (int) (6 * d));
        box.setLayoutParams(lp);
        TextView l = new TextView(this);
        l.setText(label.toUpperCase(Locale.getDefault()));
        l.setTextSize(9f);
        l.setTextColor(c);
        l.setTypeface(null, Typeface.BOLD);
        box.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13f);
        v.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light));
        v.setTypeface(null, Typeface.BOLD);
        box.addView(v);
        binding.cliStats.addView(box);
    }

    private static double scoreOf(String s) {
        if (s == null) {
            return 0;
        }
        switch (s.toUpperCase(Locale.ROOT)) {
            case "POSITIVO":
                return 0.6;
            case "NEGATIVO":
                return -0.6;
            case "MISTO":
                return -0.1;
            default:
                return 0;
        }
    }

    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 16) {
            return null;
        }
        try {
            return SHORT.format(ISO.parse(iso.substring(0, 16)));
        } catch (Exception e) {
            return null;
        }
    }
}
