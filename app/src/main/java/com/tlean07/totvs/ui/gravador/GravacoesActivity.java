package com.tlean07.totvs.ui.gravador;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.RecorderSettings;
import com.tlean07.totvs.audio.VoicePrints;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.auth.biometric.BiometricHelper;
import com.tlean07.totvs.databinding.ActivityGravacoesBinding;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.security.AuditLog;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.List;

/** Local meeting recordings (drafts and sent ones) with shortcuts to record or import, plus recorder settings. */
public class GravacoesActivity extends AppCompatActivity {

    public static final String[] AUDIO_MIME_TYPES = {"audio/*", "video/mp4", "application/octet-stream"};

    private ActivityGravacoesBinding binding;
    private RecordingStore store;
    private RecorderSettings settings;
    private GravacaoAdapter adapter;
    private boolean unlocked;

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
        settings = new RecorderSettings(this);
        adapter = new GravacaoAdapter(r -> startActivity(RevisaoActivity.intent(this, r.id)), this::confirmDelete);
        binding.lstRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.lstRecycler.setAdapter(adapter);
        binding.lstBack.setOnClickListener(v -> finish());
        binding.lstRecord.setOnClickListener(v -> startActivity(new Intent(this, GravadorActivity.class)));
        binding.lstImport.setOnClickListener(v -> audioPicker.launch(AUDIO_MIME_TYPES));
        binding.lstSettings.setOnClickListener(v -> showSettings());
        unlocked = !settings.biometricForRecordings() || !BiometricHelper.isAvailable(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (unlocked) {
            load();
        } else {
            BiometricHelper.prompt(this, getString(R.string.bio_recordings_title),
                    getString(R.string.bio_recordings_subtitle), getString(R.string.cancel),
                    new BiometricHelper.PromptCallback() {
                        @Override
                        public void onSuccess() {
                            unlocked = true;
                            AuditLog.get(GravacoesActivity.this).record("gravacoes.desbloqueio", "biometria");
                            load();
                        }

                        @Override
                        public void onCancel() {
                            finish();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(GravacoesActivity.this, message, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
        }
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
                    AuditLog.get(this).record("gravacao.excluida", r.id);
                    AppExecutors.main(this::load);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showSettings() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (22 * d), (int) (8 * d), (int) (22 * d), 0);

        box.addView(label(R.string.cfg_quality));
        Spinner quality = new Spinner(this);
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RecorderSettings.BITRATE_LABELS));
        quality.setSelection(settings.bitrateIndex());
        box.addView(quality);

        box.addView(label(R.string.cfg_retention));
        Spinner retention = new Spinner(this);
        retention.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RecorderSettings.RETENTION_LABELS));
        retention.setSelection(settings.retentionIndex());
        box.addView(retention);

        CheckBox encrypt = new CheckBox(this);
        encrypt.setText(R.string.cfg_encrypt);
        encrypt.setChecked(settings.encryptAtRest());
        encrypt.setPadding(0, (int) (10 * d), 0, 0);
        box.addView(encrypt);

        CheckBox bio = new CheckBox(this);
        bio.setText(R.string.cfg_biometric);
        bio.setChecked(settings.biometricForRecordings());
        bio.setEnabled(BiometricHelper.isAvailable(this));
        box.addView(bio);

        AuthUser user = TotvsApp.get().session().getCurrentUser();
        VoicePrints prints = new VoicePrints(this);
        boolean known = user != null && prints.has(user.id);
        TextView voice = new TextView(this);
        voice.setText(known ? R.string.cfg_voice_known : R.string.cfg_voice_unknown);
        voice.setTextSize(12f);
        voice.setTextColor(ContextCompat.getColor(this, R.color.text_muted_light));
        voice.setPadding(0, (int) (10 * d), 0, 0);
        box.addView(voice);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.cfg_title)
                .setView(box)
                .setPositiveButton(R.string.save, (dlg, w) -> {
                    settings.setBitrate(RecorderSettings.BITRATES[quality.getSelectedItemPosition()]);
                    settings.setRetentionDays(RecorderSettings.RETENTION_DAYS[retention.getSelectedItemPosition()]);
                    settings.setEncryptAtRest(encrypt.isChecked());
                    settings.setBiometricForRecordings(bio.isChecked());
                    Toast.makeText(this, R.string.cfg_saved, Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton(R.string.cancel, null);
        if (known) {
            b.setNeutralButton(R.string.cfg_forget_voice, (dlg, w) -> {
                prints.forget(user.id);
                AuditLog.get(this).record("voz.esquecida", user.id);
            });
        }
        b.show();
    }

    private TextView label(int res) {
        TextView tv = new TextView(this);
        tv.setText(res);
        tv.setTextSize(12f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_light));
        tv.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        return tv;
    }
}
