package com.tlean07.totvs.ui.ai;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.databinding.FragmentAiBinding;
import com.tlean07.totvs.databinding.SheetCreateTranscricaoBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.PendingQueue;
import com.tlean07.totvs.network.dto.PageResponse;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.ui.analise.AnaliseDetalheActivity;
import com.tlean07.totvs.ui.components.TranscricaoAdapter;
import com.tlean07.totvs.ui.gravador.GravacoesActivity;
import com.tlean07.totvs.ui.gravador.GravadorActivity;
import com.tlean07.totvs.ui.gravador.ImportarAudioActivity;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Analyses list. Paged while browsing; as soon as a search or filter is
 * active the full (slim) list is fetched once, cached and filtered locally.
 */
public class AiFragment extends Fragment {

    private static final int PAGE_SIZE = 20;
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    private FragmentAiBinding binding;
    private DataRepository data;
    private TranscricaoAdapter adapter;
    private int nextPage;
    private boolean loadingPage;
    private boolean lastPage;
    private long totalElements;

    private BottomSheetDialog createSheet;
    private boolean resumedBefore;

    // filters
    private String search = "";
    private String status = "";
    private String sentimento = "";
    private int periodDays = 0;
    private List<TranscricaoResponse> fullList;
    private boolean loadingFull;

    private final ActivityResultLauncher<String[]> jsonPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onJsonPicked);

    private final ActivityResultLauncher<String[]> audioPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    if (createSheet != null && createSheet.isShowing()) {
                        createSheet.dismiss();
                    }
                    startActivity(ImportarAudioActivity.intent(requireContext(), uri));
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAiBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SafeAreaHelper.applyTopInset(binding.aiHeader);
        data = TotvsApp.get().dataRepository();

        adapter = new TranscricaoAdapter(this::showDetail);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        binding.aiRecycler.setLayoutManager(layoutManager);
        binding.aiRecycler.setAdapter(adapter);
        binding.aiRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loadingPage || lastPage || filtering()) {
                    return;
                }
                int visible = layoutManager.getChildCount();
                int total = layoutManager.getItemCount();
                int first = layoutManager.findFirstVisibleItemPosition();
                if (visible + first >= total - 4) {
                    loadPage(false);
                }
            }
        });

        binding.aiSwipe.setOnRefreshListener(() -> refresh(true));
        binding.aiRetry.setOnClickListener(v -> refresh(false));
        binding.aiFab.setOnClickListener(v -> showCreateDialog());
        binding.aiSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                search = s == null ? "" : s.toString().trim();
                applyFilters();
            }
        });
        buildChips();
        refresh(false);
    }

    // ---------------------------------------------------------------- filters

    private boolean filtering() {
        return !search.isEmpty() || !status.isEmpty() || !sentimento.isEmpty() || periodDays > 0;
    }

    private void buildChips() {
        binding.aiChips.removeAllViews();
        addChip(getString(R.string.ai_filter_processed), R.color.totvs_accent, () -> status = toggle(status, "PROCESSADA"), () -> "PROCESSADA".equals(status));
        addChip(getString(R.string.ai_filter_processing), R.color.totvs_secondary, () -> status = toggle(status, "PROCESSANDO"), () -> "PROCESSANDO".equals(status));
        addChip(getString(R.string.ai_filter_error), R.color.totvs_danger, () -> status = toggle(status, "ERRO"), () -> "ERRO".equals(status));
        addChip(getString(R.string.ai_filter_sent_negative), R.color.totvs_danger, () -> sentimento = toggle(sentimento, "NEGATIVO"), () -> "NEGATIVO".equals(sentimento));
        addChip(getString(R.string.ai_filter_sent_mixed), R.color.totvs_warning, () -> sentimento = toggle(sentimento, "MISTO"), () -> "MISTO".equals(sentimento));
        addChip(getString(R.string.ai_filter_sent_positive), R.color.totvs_accent, () -> sentimento = toggle(sentimento, "POSITIVO"), () -> "POSITIVO".equals(sentimento));
        addChip(getString(R.string.ai_filter_7d), R.color.totvs_primary, () -> periodDays = periodDays == 7 ? 0 : 7, () -> periodDays == 7);
        addChip(getString(R.string.ai_filter_30d), R.color.totvs_primary, () -> periodDays = periodDays == 30 ? 0 : 30, () -> periodDays == 30);
    }

    private static String toggle(String current, String value) {
        return value.equals(current) ? "" : value;
    }

    private interface Toggle {
        void run();
    }

    private interface Checked {
        boolean is();
    }

    private void addChip(String label, @ColorRes int color, Toggle toggle, Checked checked) {
        Chip chip = new Chip(requireContext());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setTextSize(12f);
        chip.setChipStrokeWidth(1f);
        chip.setChipBackgroundColor(states(color, R.color.surface_light));
        chip.setTextColor(states(R.color.white, R.color.text_secondary_light));
        chip.setChipStrokeColor(states(color, R.color.border_light));
        chip.setChecked(checked.is());
        chip.setOnClickListener(v -> {
            toggle.run();
            syncChips();
            applyFilters();
        });
        chip.setTag(checked);
        binding.aiChips.addView(chip);
    }

    private void syncChips() {
        for (int i = 0; i < binding.aiChips.getChildCount(); i++) {
            Chip c = (Chip) binding.aiChips.getChildAt(i);
            c.setChecked(((Checked) c.getTag()).is());
        }
    }

    private ColorStateList states(@ColorRes int checkedColor, @ColorRes int defaultColor) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ContextCompat.getColor(requireContext(), checkedColor),
                        ContextCompat.getColor(requireContext(), defaultColor)});
    }

    private void applyFilters() {
        if (binding == null) {
            return;
        }
        if (!filtering()) {
            loadFirstPage(false);
            return;
        }
        if (fullList == null) {
            if (!loadingFull) {
                loadingFull = true;
                binding.aiSwipe.setRefreshing(true);
                data.transcricoesTodas(false, result -> {
                    loadingFull = false;
                    if (binding == null) {
                        return;
                    }
                    binding.aiSwipe.setRefreshing(false);
                    if (result.success && result.data != null) {
                        fullList = result.data;
                        applyFilters();
                    } else {
                        adapter.clear();
                        showState(result.errorMessage, true);
                    }
                });
            }
            return;
        }
        List<TranscricaoResponse> out = new ArrayList<>();
        long since = periodDays > 0 ? System.currentTimeMillis() - periodDays * 24L * 3600_000L : 0;
        String q = search.toLowerCase(Locale.ROOT);
        for (TranscricaoResponse t : fullList) {
            if (!q.isEmpty() && (t.cliente == null || !t.cliente.toLowerCase(Locale.ROOT).contains(q))
                    && (t.vendedor == null || !t.vendedor.toLowerCase(Locale.ROOT).contains(q))) {
                continue;
            }
            if (!status.isEmpty() && (t.status == null || !t.status.toUpperCase(Locale.ROOT).startsWith(status))) {
                continue;
            }
            if (!sentimento.isEmpty() && (t.analise == null || !sentimento.equalsIgnoreCase(t.analise.sentimentoGeral))) {
                continue;
            }
            if (since > 0 && parse(t.dataCriacao) < since) {
                continue;
            }
            out.add(t);
        }
        hideState();
        adapter.submit(out);
        binding.aiSubtitle.setText(getString(R.string.ai_filter_result, out.size(), fullList.size()));
        if (out.isEmpty()) {
            adapter.clear();
            showState(getString(R.string.ai_filter_empty), false);
        }
    }

    private static long parse(String iso) {
        if (iso == null || iso.length() < 16) {
            return 0;
        }
        try {
            return ISO.parse(iso.substring(0, 16)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- loading

    private void refresh(boolean force) {
        if (force) {
            fullList = null;
        }
        if (filtering()) {
            applyFilters();
        } else {
            loadFirstPage(force);
        }
    }

    private void loadFirstPage() {
        loadFirstPage(false);
    }

    private void loadFirstPage(boolean forceRefresh) {
        if (loadingPage) {
            binding.aiSwipe.setRefreshing(false);
            return;
        }
        nextPage = 0;
        lastPage = false;
        adapter.clear();
        loadPage(true, forceRefresh);
    }

    private void loadPage(boolean firstPage) {
        loadPage(firstPage, false);
    }

    private void loadPage(boolean firstPage, boolean forceRefresh) {
        if (loadingPage || lastPage) {
            return;
        }
        loadingPage = true;
        hideState();
        binding.aiSwipe.setRefreshing(firstPage);
        binding.aiLoadMore.setVisibility(firstPage ? View.GONE : View.VISIBLE);

        data.transcricoesPaginadas(nextPage, PAGE_SIZE, forceRefresh, result -> {
            if (binding == null) {
                return;
            }
            loadingPage = false;
            binding.aiSwipe.setRefreshing(false);
            binding.aiLoadMore.setVisibility(View.GONE);
            if (filtering()) {
                return;
            }
            if (result.success && result.data != null) {
                PageResponse<TranscricaoResponse> page = result.data;
                adapter.append(page.content);
                nextPage = page.page + 1;
                lastPage = page.last;
                totalElements = page.totalElements;
                binding.aiSubtitle.setText(page.totalElements + " análises disponíveis");
                if (page.totalElements == 0) {
                    showState("Nenhuma análise ainda.\nToque em + para enviar uma transcrição.", false);
                }
            } else if (firstPage) {
                showState(result.errorMessage, true);
            } else {
                Toast.makeText(requireContext(), result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showDetail(TranscricaoResponse t) {
        startActivity(AnaliseDetalheActivity.intent(requireContext(), t.id));
    }

    // ---------------------------------------------------------------- create sheet

    private void showCreateDialog() {
        SheetCreateTranscricaoBinding sb =
                SheetCreateTranscricaoBinding.inflate(getLayoutInflater());
        createSheet = new BottomSheetDialog(requireContext(), R.style.Theme_Totvs_BottomSheet);
        createSheet.setContentView(sb.getRoot());
        SafeAreaHelper.keyboardAwareSheet(createSheet, sb.getRoot());

        sb.sheetImportJson.setOnClickListener(v ->
                jsonPicker.launch(new String[]{"application/json", "application/octet-stream", "text/plain"}));
        sb.sheetRecord.setOnClickListener(v -> {
            createSheet.dismiss();
            startActivity(new Intent(requireContext(), GravadorActivity.class));
        });
        sb.sheetImportAudio.setOnClickListener(v -> audioPicker.launch(GravacoesActivity.AUDIO_MIME_TYPES));
        int gravacoes = new RecordingStore(requireContext()).count();
        sb.sheetRecordings.setText(gravacoes > 0 ? getString(R.string.sheet_recordings, gravacoes) : getString(R.string.sheet_recordings_none));
        sb.sheetRecordings.setOnClickListener(v -> {
            createSheet.dismiss();
            startActivity(GravacoesActivity.intent(requireContext()));
        });

        sb.buttonAnalisar.setOnClickListener(v -> {
            String texto = sb.inputTexto.getText() == null ? "" : sb.inputTexto.getText().toString().trim();
            if (TextUtils.isEmpty(texto)) {
                sb.inputTexto.setError("Informe a transcrição");
                return;
            }
            String cliente = sb.inputCliente.getText() == null ? "" : sb.inputCliente.getText().toString().trim();
            String vendedor = sb.inputVendedor.getText() == null ? "" : sb.inputVendedor.getText().toString().trim();
            createSheet.dismiss();
            submit(new TranscricaoRequest(texto, cliente, vendedor, null));
        });

        createSheet.show();
    }

    private void onJsonPicked(@Nullable Uri uri) {
        if (uri == null || binding == null) {
            return;
        }
        binding.aiProgress.setVisibility(View.VISIBLE);
        AppExecutors.io(() -> {
            try {
                ContentResolver resolver = requireContext().getContentResolver();
                String name = queryDisplayName(resolver, uri);
                byte[] bytes = readAllBytes(resolver, uri);
                AppExecutors.main(() -> uploadJson(bytes, name));
            } catch (Exception e) {
                AppExecutors.main(() -> {
                    if (binding != null) {
                        binding.aiProgress.setVisibility(View.GONE);
                    }
                    Toast.makeText(requireContext(),
                            "Não foi possível ler o arquivo.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void uploadJson(byte[] bytes, String name) {
        if (binding == null) {
            return;
        }
        data.importTotvsJson(bytes, name, result -> {
            if (binding == null) {
                return;
            }
            binding.aiProgress.setVisibility(View.GONE);
            if (result.success && result.data != null) {
                int n = result.data.importedCount();
                Toast.makeText(requireContext(),
                        "Importação concluída: " + n + (n == 1 ? " análise" : " análises"),
                        Toast.LENGTH_LONG).show();
                if (createSheet != null && createSheet.isShowing()) {
                    createSheet.dismiss();
                }
                refresh(true);
            } else {
                Toast.makeText(requireContext(),
                        result.errorMessage != null ? result.errorMessage
                                : "Falha ao importar o JSON.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "totvs.json";
    }

    private byte[] readAllBytes(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new IllegalStateException("stream null");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void submit(TranscricaoRequest request) {
        binding.aiProgress.setVisibility(View.VISIBLE);
        data.criarTranscricao(request, result -> {
            if (binding == null) {
                return;
            }
            binding.aiProgress.setVisibility(View.GONE);
            if (result.success) {
                Toast.makeText(requireContext(), "Análise enviada", Toast.LENGTH_SHORT).show();
                refresh(true);
            } else if (result.networkError) {
                PendingQueue.get(requireContext()).enqueue(request, null);
                Toast.makeText(requireContext(), R.string.queue_offline, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showState(String message, boolean retry) {
        binding.aiState.setVisibility(View.VISIBLE);
        binding.aiStateText.setText(message != null ? message : getString(R.string.error_generic));
        binding.aiRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private void hideState() {
        binding.aiState.setVisibility(View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        PendingQueue queue = PendingQueue.get(requireContext());
        if (queue.size() > 0) {
            queue.flush((sent, remaining) -> {
                if (binding != null && sent > 0) {
                    Toast.makeText(requireContext(), getString(R.string.queue_sent, sent), Toast.LENGTH_SHORT).show();
                    refresh(true);
                }
            });
        }
        if (resumedBefore && !loadingPage) {
            refresh(false);
        }
        resumedBefore = true;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
