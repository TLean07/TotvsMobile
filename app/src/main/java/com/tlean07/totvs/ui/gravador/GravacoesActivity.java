package com.tlean07.totvs.ui.gravador;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.databinding.ActivityGravacoesBinding;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.List;

/** Local meeting recordings (drafts and sent ones) with shortcuts to record or import. */
public class GravacoesActivity extends AppCompatActivity {

    public static final String[] AUDIO_MIME_TYPES = {"audio/*", "video/mp4", "application/octet-stream"};

    private ActivityGravacoesBinding binding;
    private RecordingStore store;
    private GravacaoAdapter adapter;

    private final ActivityResultLauncher<String[]> audioPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    startActivity(ImportarAudioActivity.intent(this, uri));
                }
            });

    public static Intent intent(Context context) {
        return new Intent(context, GravacoesActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGravacoesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SecurityManager.applySecureFlag(this, true, TotvsApp.get().session());
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.lstHeader);
        SafeAreaHelper.applyBottomInset(binding.lstRoot);

        store = new RecordingStore(this);
        adapter = new GravacaoAdapter(r -> startActivity(RevisaoActivity.intent(this, r.id)), this::confirmDelete);
        binding.lstRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.lstRecycler.setAdapter(adapter);
        binding.lstBack.setOnClickListener(v -> finish());
        binding.lstRecord.setOnClickListener(v -> startActivity(new Intent(this, GravadorActivity.class)));
        binding.lstImport.setOnClickListener(v -> audioPicker.launch(AUDIO_MIME_TYPES));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        AppExecutors.io(() -> {
            List<Recording> items = store.list();
            AppExecutors.main(() -> {
                if (binding == null) {
                    return;
                }
                adapter.submit(items);
                binding.lstEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                binding.lstSubtitle.setText(items.size() == 1
                        ? getString(R.string.lst_subtitle_one)
                        : getString(R.string.lst_subtitle_many, items.size()));
            });
        });
    }

    private void confirmDelete(Recording r) {
        new AlertDialog.Builder(this)
                .setTitle(r.tituloOuPadrao())
                .setMessage(R.string.rev_delete_confirm)
                .setPositiveButton(R.string.rev_delete, (d, w) -> AppExecutors.io(() -> {
                    store.delete(r.id);
                    AppExecutors.main(this::load);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
