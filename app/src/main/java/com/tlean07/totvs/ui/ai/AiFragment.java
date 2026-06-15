package com.tlean07.totvs.ui.ai;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.FragmentAiBinding;
import com.tlean07.totvs.databinding.SheetCreateTranscricaoBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.PageResponse;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.ui.components.TranscricaoAdapter;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

public class AiFragment extends Fragment {

    private static final int PAGE_SIZE = 20;

    private FragmentAiBinding binding;
    private DataRepository data;
    private TranscricaoAdapter adapter;
    private int nextPage;
    private boolean loadingPage;
    private boolean lastPage;

    private BottomSheetDialog createSheet;

    // File picker for the TOTVS JSON import (same feature as the web app).
    private final ActivityResultLauncher<String[]> jsonPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onJsonPicked);

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
                if (dy <= 0 || loadingPage || lastPage) {
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

        binding.aiSwipe.setOnRefreshListener(this::loadFirstPage);
        binding.aiRetry.setOnClickListener(v -> loadFirstPage());
        binding.aiFab.setOnClickListener(v -> showCreateDialog());
        loadFirstPage();
    }

    private void loadFirstPage() {
        if (loadingPage) {
            binding.aiSwipe.setRefreshing(false);
            return;
        }
        nextPage = 0;
        lastPage = false;
        adapter.clear();
        loadPage(true);
    }

    private void loadPage(boolean firstPage) {
        if (loadingPage || lastPage) {
            return;
        }
        loadingPage = true;
        hideState();
        binding.aiSwipe.setRefreshing(firstPage);
        binding.aiLoadMore.setVisibility(firstPage ? View.GONE : View.VISIBLE);

        data.transcricoesPaginadas(nextPage, PAGE_SIZE, result -> {
            if (binding == null) {
                return;
            }
            loadingPage = false;
            binding.aiSwipe.setRefreshing(false);
            binding.aiLoadMore.setVisibility(View.GONE);

            if (result.success && result.data != null) {
                PageResponse<TranscricaoResponse> page = result.data;
                List<TranscricaoResponse> items = page.content;
                adapter.append(items);
                nextPage = page.page + 1;
                lastPage = page.last;
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
        String resumo = t.resumoExecutivo != null && !t.resumoExecutivo.isBlank()
                ? t.resumoExecutivo
                : "A análise ainda está sendo processada pela IA.";
        String title = t.cliente != null && !t.cliente.isBlank() ? t.cliente : "Análise";
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage("Status: " + (t.status != null ? t.status : "-") + "\n\n" + resumo)
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void showCreateDialog() {
        SheetCreateTranscricaoBinding sb =
                SheetCreateTranscricaoBinding.inflate(getLayoutInflater());
        createSheet = new BottomSheetDialog(requireContext(), R.style.Theme_Totvs_BottomSheet);
        createSheet.setContentView(sb.getRoot());

        sb.sheetImportJson.setOnClickListener(v ->
                jsonPicker.launch(new String[]{"application/json", "application/octet-stream", "text/plain"}));

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
                loadFirstPage();
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
                loadFirstPage();
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
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
