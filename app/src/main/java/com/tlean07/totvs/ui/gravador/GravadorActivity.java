package com.tlean07.totvs.ui.gravador;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.AudioPipeline;
import com.tlean07.totvs.audio.MeetingRecorderService;
import com.tlean07.totvs.audio.RecorderSession;
import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.asr.VoskModelManager;
import com.tlean07.totvs.audio.dsp.SpeakerDiarizer;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.databinding.ActivityGravadorBinding;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Live meeting recorder: clock, waveform coloured by speaker, "who is talking"
 * chips and a live transcript, driven by the {@link RecorderSession} owned by
 * the foreground service (so the screen can rotate or turn off).
 */
public class GravadorActivity extends AppCompatActivity implements RecorderSession.Listener, VoskModelManager.Listener {

    private ActivityGravadorBinding binding;
    private TurnoAdapter adapter;
    private VoskModelManager models;
    @Nullable
    private RecorderSession session;
    private int currentSpeaker = -1;
    private int speakerCount;
    private boolean speaking;
    private boolean navigatedToReview;

    private final ActivityResultLauncher<String[]> permissions =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean mic = result.get(Manifest.permission.RECORD_AUDIO);
                if (Boolean.TRUE.equals(mic)) {
                    startRecording();
                } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    showPermissionSettings();
                } else {
                    Toast.makeText(this, R.string.grav_permission_needed, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGravadorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SecurityManager.applySecureFlag(this, true, TotvsApp.get().session());
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.gravHeader);
        SafeAreaHelper.applyBottomInset(binding.gravControls);

        models = VoskModelManager.get(this);
        adapter = new TurnoAdapter(null);
        binding.gravTranscript.setLayoutManager(new LinearLayoutManager(this));
        binding.gravTranscript.setAdapter(adapter);
        binding.gravTranscript.setItemAnimator(null);

        binding.gravBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        binding.gravMain.setOnClickListener(v -> onMainButton());
        binding.gravStop.setOnClickListener(v -> confirmStop());
        binding.gravMarker.setOnClickListener(v -> addMarker());
        binding.gravAsrButton.setOnClickListener(v -> onAsrButton());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (session != null && session.isActive()) {
                    confirmDiscardOrKeep();
                } else {
                    finish();
                }
            }
        });
        renderIdle();
    }

    @Override
    protected void onStart() {
        super.onStart();
        models.addListener(this);
        attach(RecorderSession.current());
    }

    @Override
    protected void onStop() {
        super.onStop();
        models.removeListener(this);
        if (session != null) {
            session.removeListener(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        attach(RecorderSession.current());
    }

    private void attach(@Nullable RecorderSession s) {
        if (session != null && session != s) {
            session.removeListener(this);
        }
        session = s;
        if (s == null) {
            renderIdle();
            return;
        }
        binding.gravWave.setHistory(s.levelHistory());
        s.addListener(this);
    }

    // --- actions ---

    private void onMainButton() {
        if (session == null || !session.isActive()) {
            ensurePermissionsThenStart();
        } else if (session.state() == RecorderSession.State.RECORDING) {
            session.pause();
        } else if (session.state() == RecorderSession.State.PAUSED) {
            session.resume();
        }
    }

    private void ensurePermissionsThenStart() {
        List<String> wanted = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (wanted.isEmpty()) {
            startRecording();
        } else {
            permissions.launch(wanted.toArray(new String[0]));
        }
    }

    private void startRecording() {
        navigatedToReview = false;
        adapter.submit(new ArrayList<>());
        binding.gravWave.clear();
        MeetingRecorderService.start(this);
        // The service creates the session synchronously in onStartCommand; poll once it exists.
        binding.getRoot().postDelayed(this::attachWhenReady, 150);
    }

    private void attachWhenReady() {
        RecorderSession s = RecorderSession.current();
        if (s == null) {
            binding.getRoot().postDelayed(this::attachWhenReady, 150);
            return;
        }
        attach(s);
    }

    private void confirmStop() {
        if (session == null || !session.isActive()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.grav_confirm_stop_title)
                .setMessage(R.string.grav_confirm_stop_message)
                .setPositiveButton(R.string.grav_stop, (d, w) -> session.stop())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDiscardOrKeep() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.grav_confirm_discard_title)
                .setMessage(R.string.grav_confirm_discard_message)
                .setPositiveButton(R.string.grav_discard, (d, w) -> {
                    if (session != null) {
                        session.discard();
                    }
                })
                .setNeutralButton(R.string.grav_stop, (d, w) -> {
                    if (session != null) {
                        session.stop();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addMarker() {
        if (session == null || !session.isActive()) {
            return;
        }
        session.addMarker(null);
        Toast.makeText(this, getString(R.string.grav_marker_added,
                TranscriptFormatter.formatClock(session.positionMs())), Toast.LENGTH_SHORT).show();
    }

    private void onAsrButton() {
        switch (models.status()) {
            case ABSENT:
            case ERROR:
                models.download();
                break;
            case DOWNLOADING:
                models.cancelDownload();
                break;
            default:
                break;
        }
    }

    private void showPermissionSettings() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.grav_permission_settings)
                .setPositiveButton("Configurações", (d, w) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- session callbacks (main thread) ---

    @Override
    public void onState(RecorderSession.State state, @Nullable String message) {
        boolean active = state == RecorderSession.State.RECORDING || state == RecorderSession.State.PAUSED;
        getWindow().setFlags(active ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        binding.gravOverlay.setVisibility(state == RecorderSession.State.FINISHING ? View.VISIBLE : View.GONE);
        binding.gravRecBadge.setVisibility(state == RecorderSession.State.RECORDING ? View.VISIBLE : View.INVISIBLE);
        binding.gravWave.setPaused(state == RecorderSession.State.PAUSED);
        binding.gravStopWrap.setAlpha(active ? 1f : 0.35f);
        binding.gravMarkerWrap.setAlpha(active ? 1f : 0.35f);
        binding.gravStop.setEnabled(active);
        binding.gravMarker.setEnabled(active);
        binding.gravAsrCard.setVisibility(active || state == RecorderSession.State.FINISHING ? View.GONE : View.VISIBLE);

        switch (state) {
            case PREPARING:
                binding.gravSubtitle.setText(R.string.grav_subtitle_preparing);
                binding.gravMain.setEnabled(false);
                break;
            case RECORDING:
                binding.gravMain.setEnabled(true);
                binding.gravMain.setImageResource(R.drawable.ic_pause);
                binding.gravMain.setBackgroundResource(R.drawable.bg_record_button_active);
                binding.gravMainLabel.setText(R.string.grav_pause);
                if (session != null && !session.hasAsr()) {
                    binding.gravTranscriptEmpty.setText(R.string.grav_transcript_unavailable);
                }
                updateClock(session == null ? 0 : session.positionMs());
                break;
            case PAUSED:
                binding.gravMain.setEnabled(true);
                binding.gravMain.setImageResource(R.drawable.ic_mic);
                binding.gravMain.setBackgroundResource(R.drawable.bg_record_button);
                binding.gravMainLabel.setText(R.string.grav_resume);
                binding.gravSubtitle.setText(getString(R.string.grav_subtitle_paused,
                        TranscriptFormatter.formatClock(session == null ? 0 : session.positionMs())));
                if (session != null && session.wasPausedByCall()) {
                    Toast.makeText(this, R.string.grav_paused_by_call, Toast.LENGTH_LONG).show();
                }
                break;
            case FINISHING:
                binding.gravSubtitle.setText(R.string.grav_subtitle_finishing);
                binding.gravOverlayText.setText(getString(R.string.grav_finishing, 0));
                binding.gravMain.setEnabled(false);
                break;
            case DONE:
                Recording r = session == null ? null : session.result();
                if (r != null && !navigatedToReview) {
                    navigatedToReview = true;
                    startActivity(RevisaoActivity.intent(this, r.id));
                    finish();
                } else {
                    renderIdle();
                }
                break;
            case ERROR:
                renderIdle();
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onSnapshot(AudioPipeline.Snapshot snapshot) {
        currentSpeaker = snapshot.currentSpeaker;
        speaking = snapshot.speaking;
        speakerCount = snapshot.speakerCount;
        adapter.submit(snapshot.turns);
        if (!snapshot.turns.isEmpty()) {
            binding.gravTranscriptEmpty.setVisibility(View.GONE);
            binding.gravTranscript.scrollToPosition(snapshot.turns.size() - 1);
        } else {
            binding.gravTranscriptEmpty.setVisibility(View.VISIBLE);
        }
        renderSpeakers(snapshot);
        long lagSec = snapshot.asrBacklog * 40L / 1000;
        if (lagSec >= 3) {
            binding.gravAsrLag.setVisibility(View.VISIBLE);
            binding.gravAsrLag.setText(getString(R.string.grav_asr_behind, (int) lagSec));
        } else {
            binding.gravAsrLag.setVisibility(View.GONE);
        }
    }

    @Override
    public void onLevel(float dbfs, long positionMs) {
        binding.gravWave.push(dbfs, speaking ? Math.max(currentSpeaker, -1) : -1);
        if (session != null && session.state() == RecorderSession.State.RECORDING) {
            updateClock(positionMs);
        }
    }

    @Override
    public void onFinishProgress(int percent) {
        binding.gravOverlayText.setText(getString(R.string.grav_finishing, percent));
    }

    // --- model status ---

    @Override
    public void onStatus(VoskModelManager.Status status, int progress, @Nullable String message) {
        binding.gravAsrProgress.setVisibility(status == VoskModelManager.Status.DOWNLOADING ? View.VISIBLE : View.GONE);
        switch (status) {
            case READY:
                binding.gravAsrText.setText(R.string.grav_asr_ready);
                binding.gravAsrButton.setVisibility(View.GONE);
                break;
            case DOWNLOADING:
                binding.gravAsrText.setText(getString(R.string.grav_asr_downloading, Math.max(progress, 0)));
                binding.gravAsrProgress.setProgress(Math.max(progress, 0));
                binding.gravAsrButton.setVisibility(View.VISIBLE);
                binding.gravAsrButton.setText(R.string.cancel);
                break;
            case UNPACKING:
                binding.gravAsrText.setText(R.string.grav_asr_unpacking);
                binding.gravAsrButton.setVisibility(View.GONE);
                break;
            default:
                binding.gravAsrText.setText(message != null ? message
                        : getString(R.string.grav_asr_absent, VoskModelManager.MODEL_SIZE_MB));
                binding.gravAsrButton.setVisibility(View.VISIBLE);
                binding.gravAsrButton.setText(R.string.grav_asr_download);
                break;
        }
    }

    // --- rendering helpers ---

    private void renderIdle() {
        binding.gravSubtitle.setText(R.string.grav_subtitle_idle);
        binding.gravClock.setText("00:00");
        binding.gravMain.setEnabled(true);
        binding.gravMain.setImageResource(R.drawable.ic_mic);
        binding.gravMain.setBackgroundResource(R.drawable.bg_record_button);
        binding.gravMainLabel.setText(R.string.grav_start);
        binding.gravRecBadge.setVisibility(View.INVISIBLE);
        binding.gravOverlay.setVisibility(View.GONE);
        binding.gravStopWrap.setAlpha(0.35f);
        binding.gravMarkerWrap.setAlpha(0.35f);
        binding.gravStop.setEnabled(false);
        binding.gravMarker.setEnabled(false);
        binding.gravAsrCard.setVisibility(View.VISIBLE);
        binding.gravSpeaking.setText(R.string.grav_nobody);
        binding.gravSpeakingDot.getBackground().setTint(ContextCompat.getColor(this, R.color.speaker_unknown));
        binding.gravAsrLag.setVisibility(View.GONE);
        binding.gravTranscriptEmpty.setVisibility(View.VISIBLE);
        binding.gravTranscriptEmpty.setText(R.string.grav_transcript_empty);
        binding.gravSpeakers.removeAllViews();
        addSpeakerHint();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateClock(long positionMs) {
        String clock = TranscriptFormatter.formatClock(positionMs);
        binding.gravClock.setText(clock);
        if (session != null && session.state() == RecorderSession.State.RECORDING) {
            binding.gravSubtitle.setText(getString(R.string.grav_subtitle_recording, clock));
        }
    }

    private void renderSpeakers(AudioPipeline.Snapshot s) {
        if (!s.speaking) {
            binding.gravSpeaking.setText(R.string.grav_nobody);
            binding.gravSpeakingDot.getBackground().setTint(ContextCompat.getColor(this, R.color.speaker_unknown));
        } else if (s.currentSpeaker == SpeakerDiarizer.UNKNOWN || s.currentSpeaker < 0) {
            binding.gravSpeaking.setText(R.string.grav_someone);
            binding.gravSpeakingDot.getBackground().setTint(ContextCompat.getColor(this, R.color.speaker_unknown));
        } else {
            binding.gravSpeaking.setText(getString(R.string.grav_speaking_now, TranscriptFormatter.defaultName(s.currentSpeaker)));
            binding.gravSpeakingDot.getBackground().setTint(SpeakerColors.of(this, s.currentSpeaker));
        }
        binding.gravSpeakers.removeAllViews();
        if (s.speakerCount == 0) {
            addSpeakerHint();
            return;
        }
        long total = 0;
        for (long t : s.talkTimeMs) {
            total += t;
        }
        for (int i = 0; i < s.speakerCount; i++) {
            TextView chip = new TextView(this);
            int color = SpeakerColors.of(this, i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(999f);
            boolean active = s.speaking && s.currentSpeaker == i;
            bg.setColor(active ? color : SpeakerColors.tint(this, i));
            bg.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), color);
            chip.setBackground(bg);
            chip.setTextColor(active ? 0xFFFFFFFF : color);
            chip.setTextSize(12f);
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            int pad = (int) (10 * getResources().getDisplayMetrics().density);
            int padV = (int) (5 * getResources().getDisplayMetrics().density);
            chip.setPadding(pad, padV, pad, padV);
            long ms = i < s.talkTimeMs.length ? s.talkTimeMs[i] : 0;
            int pct = total == 0 ? 0 : (int) (ms * 100 / total);
            chip.setText(TranscriptFormatter.defaultName(i) + " · " + pct + "%");
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (6 * getResources().getDisplayMetrics().density));
            binding.gravSpeakers.addView(chip, lp);
        }
    }

    private void addSpeakerHint() {
        TextView hint = new TextView(this);
        hint.setText(R.string.grav_no_speakers);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_muted_light));
        hint.setTextSize(12f);
        binding.gravSpeakers.addView(hint);
    }
}
