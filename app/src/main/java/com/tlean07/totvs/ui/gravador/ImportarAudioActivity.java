package com.tlean07.totvs.ui.gravador;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.FileTranscriber;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.databinding.ActivityImportarBinding;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.util.SafeAreaHelper;

/** Progress screen for running an existing audio file through the on-device pipeline. */
public class ImportarAudioActivity extends AppCompatActivity {

    private static final String EXTRA_URI = "uri";

    private ActivityImportarBinding binding;
    private boolean running;

    public static Intent intent(Context context, Uri uri) {
        return new Intent(context, ImportarAudioActivity.class).putExtra(EXTRA_URI, uri.toString());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityImportarBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SecurityManager.applySecureFlag(this, true, TotvsApp.get().session());
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.impHeader);
        SafeAreaHelper.applyBottomInset(binding.impRoot);
        binding.impBack.setOnClickListener(v -> finish());
        binding.impRetry.setOnClickListener(v -> start());
        start();
    }

    private void start() {
        if (running) {
            return;
        }
        String raw = getIntent().getStringExtra(EXTRA_URI);
        if (raw == null) {
            finish();
            return;
        }
        Uri uri = Uri.parse(raw);
        String name = displayName(uri);
        binding.impFile.setText(name);
        binding.impRetry.setVisibility(View.GONE);
        binding.impProgress.setProgress(0);
        binding.impStatus.setText(getString(R.string.imp_phase_decode, 0));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running = true;
        FileTranscriber.run(this, uri, name, new FileTranscriber.Listener() {
            @Override
            public void onProgress(int phase, int percent) {
                if (binding == null) {
                    return;
                }
                binding.impProgress.setProgress(percent);
                binding.impStatus.setText(phase == 0
                        ? getString(R.string.imp_phase_decode, percent)
                        : getString(R.string.imp_phase_finish, percent));
            }

            @Override
            public void onDone(Recording recording) {
                running = false;
                if (isFinishing()) {
                    return;
                }
                startActivity(RevisaoActivity.intent(ImportarAudioActivity.this, recording.id));
                finish();
            }

            @Override
            public void onError(String message) {
                running = false;
                if (binding == null) {
                    return;
                }
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                binding.impStatus.setText(getString(R.string.imp_failed) + "\n" + message);
                binding.impRetry.setVisibility(View.VISIBLE);
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isBlank()) {
                    return n;
                }
            }
        } catch (Exception ignored) {
            // fall back to the path
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "audio";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
