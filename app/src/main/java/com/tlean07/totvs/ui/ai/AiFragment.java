package com.tlean07.totvs.ui.ai;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.DialogCreateTranscricaoBinding;
import com.tlean07.totvs.databinding.FragmentAiBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.ui.components.TranscricaoAdapter;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.List;

public class AiFragment extends Fragment {

    private FragmentAiBinding binding;
    private DataRepository data;
    private TranscricaoAdapter adapter;

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
        binding.aiRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.aiRecycler.setAdapter(adapter);

        binding.aiSwipe.setOnRefreshListener(this::load);
        binding.aiRetry.setOnClickListener(v -> load());
        binding.aiFab.setOnClickListener(v -> showCreateDialog());
        load();
    }

    private void load() {
        hideState();
        binding.aiSwipe.setRefreshing(true);
        data.transcricoes(result -> {
            binding.aiSwipe.setRefreshing(false);
            if (result.success) {
                List<TranscricaoResponse> list = result.data;
                adapter.submit(list);
                if (list == null || list.isEmpty()) {
                    showState("Nenhuma análise ainda.\nToque em + para enviar uma transcrição.", false);
                }
            } else {
                showState(result.errorMessage, true);
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
                .setMessage("Status: " + (t.status != null ? t.status : "—") + "\n\n" + resumo)
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void showCreateDialog() {
        DialogCreateTranscricaoBinding db = DialogCreateTranscricaoBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Nova análise")
                .setView(db.getRoot())
                .setPositiveButton("Analisar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String texto = db.dialogTexto.getText().toString().trim();
            if (TextUtils.isEmpty(texto)) {
                db.dialogTexto.setError("Informe a transcrição");
                return;
            }
            String cliente = db.dialogCliente.getText().toString().trim();
            String vendedor = db.dialogVendedor.getText().toString().trim();
            dialog.dismiss();
            submit(new TranscricaoRequest(texto, cliente, vendedor, null));
        }));
        dialog.show();
    }

    private void submit(TranscricaoRequest request) {
        binding.aiProgress.setVisibility(View.VISIBLE);
        data.criarTranscricao(request, result -> {
            binding.aiProgress.setVisibility(View.GONE);
            if (result.success) {
                Toast.makeText(requireContext(), "Análise enviada", Toast.LENGTH_SHORT).show();
                load();
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
