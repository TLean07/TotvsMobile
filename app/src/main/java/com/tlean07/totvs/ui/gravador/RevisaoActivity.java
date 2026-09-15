package com.tlean07.totvs.ui.gravador;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.databinding.ActivityRevisaoBinding;
import com.tlean07.totvs.databinding.DialogEditarFalanteBinding;
import com.tlean07.totvs.databinding.DialogEditarTurnoBinding;
import com.tlean07.totvs.databinding.ItemFalanteBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Review a finished recording before it goes to the AI: listen back with a
 * speaker-coloured timeline, name the people and set their roles, fix the
 * text, then send the transcript (or the audio) for analysis.
 */
public class RevisaoActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "id";
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    private ActivityRevisaoBinding binding;
    private RecordingStore store;
    private DataRepository data;
    private Recording rec;
    private TurnoAdapter adapter;
    @Nullable
    private MediaPlayer player;
    private boolean playerReady;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && playerReady && player.isPlaying()) {
                long pos = player.getCurrentPosition();
                binding.revTimeline.setPosition(pos);
                binding.revPosition.setText(TranscriptFormatter.formatClock(pos));
                handler.postDelayed(this, 200);
            }
        }
    };

    public static Intent intent(Context context, String recordingId) {
        return new Intent(context, RevisaoActivity.class).putExtra(EXTRA_ID, recordingId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRevisaoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SecurityManager.applySecureFlag(this, true, TotvsApp.get().session());
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.revHeader);
        SafeAreaHelper.applyBottomInset(binding.revActions);
        SafeAreaHelper.applyImeInset(binding.revScroll);

        store = new RecordingStore(this);
        data = TotvsApp.get().dataRepository();
        adapter = new TurnoAdapter(this::editTurn);
        binding.revTranscript.setLayoutManager(new LinearLayoutManager(this));
        binding.revTranscript.setAdapter(adapter);

        binding.revBack.setOnClickListener(v -> finish());
        binding.revDelete.setOnClickListener(v -> confirmDelete());
        binding.revMore.setOnClickListener(this::showMoreMenu);
        binding.revPlay.setOnClickListener(v -> togglePlay());
        binding.revTimeline.setOnSeek(this::seekTo);
        binding.revSendText.setOnClickListener(v -> sendText());
        binding.revSendAudio.setOnClickListener(v -> sendAudio());
        binding.revTranscribe.setOnClickListener(v -> transcribeNow());

        String id = getIntent().getStringExtra(EXTRA_ID);
        AppExecutors.io(() -> {
            Recording r = id == null ? null : store.load(id);
            AppExecutors.main(() -> {
                if (r == null) {
                    Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                rec = r;
                render();
                preparePlayer();
                binding.revScroll.post(() -> binding.revScroll.scrollTo(0, 0));
            });
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && playerReady && player.isPlaying()) {
            player.pause();
            binding.revPlay.setImageResource(R.drawable.ic_play);
        }
        if (rec != null) {
            readForm();
            persist();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.release();
            player = null;
        }
        if (rec != null) {
            store.releaseAudio(rec);
        }
        super.onDestroy();
        dismissProgress();
        if (modelListener != null) {
            com.tlean07.totvs.audio.asr.VoskModelManager.get(this).removeListener(modelListener);
            modelListener = null;
        }
    }

    // --- rendering ---

    private void render() {
        binding.revTitle.setText(rec.tituloOuPadrao());
        binding.revMeta.setText(getString(R.string.rev_meta, DATE.format(new Date(rec.criadoEm)),
                rec.falantes.size(), TranscriptFormatter.formatClock(rec.duracaoMs)));
        boolean sent = Recording.STATUS_ENVIADA.equals(rec.status);
        binding.revBadge.setText(sent ? R.string.rev_sent_badge
                : Recording.STATUS_FILA.equals(rec.status) ? R.string.rev_queued_badge : R.string.rev_draft_badge);
        binding.revDuration.setText(TranscriptFormatter.formatClock(rec.duracaoMs));
        binding.revTimeline.bind(rec);

        if (binding.revCliente.getText() == null || binding.revCliente.getText().length() == 0) {
            binding.revCliente.setText(rec.cliente);
        }
        if (binding.revVendedor.getText() == null || binding.revVendedor.getText().length() == 0) {
            binding.revVendedor.setText(rec.vendedor != null ? rec.vendedor : vendedorFromSpeakers());
        }
        if (binding.revParticipantes.getText() == null || binding.revParticipantes.getText().length() == 0) {
            binding.revParticipantes.setText(rec.participantes != null ? rec.participantes : rec.participantesTexto());
        }

        renderSpeakers();
        renderMarkers();
        renderPreview();
        adapter.setNames(rec.nomes());
        adapter.submit(rec.turns());
        boolean hasText = rec.temTexto();
        binding.revTranscript.setVisibility(hasText ? View.VISIBLE : View.GONE);
        if (hasText) {
            binding.revTranscriptHint.setText(R.string.rev_transcript_hint);
        } else if (rec.modeloAsr == null) {
            binding.revTranscriptHint.setText(getString(R.string.rev_transcript_none_model,
                    com.tlean07.totvs.audio.asr.VoskModelManager.MODEL_SIZE_MB));
        } else {
            binding.revTranscriptHint.setText(R.string.rev_transcript_none_empty);
        }
        binding.revTranscribe.setVisibility(hasText ? View.GONE : View.VISIBLE);
        binding.revSendText.setEnabled(hasText);
        binding.revSendText.setAlpha(hasText ? 1f : 0.5f);
    }

    // --- transcribe again (voice model missing at recording time, or no text captured) ---

    @Nullable
    private com.tlean07.totvs.audio.asr.VoskModelManager.Listener modelListener;
    @Nullable
    private AlertDialog progressDialog;

    private void transcribeNow() {
        if (rec == null || transcribing) {
            return;
        }
        com.tlean07.totvs.audio.asr.VoskModelManager models = com.tlean07.totvs.audio.asr.VoskModelManager.get(this);
        if (models.isReady()) {
            runRetranscribe();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.rev_model_prompt_title)
                .setMessage(getString(R.string.rev_model_prompt_message, com.tlean07.totvs.audio.asr.VoskModelManager.MODEL_SIZE_MB))
                .setPositiveButton(R.string.rev_model_prompt_download, (d, w) -> downloadThenRetranscribe(models))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean transcribing;

    private void downloadThenRetranscribe(com.tlean07.totvs.audio.asr.VoskModelManager models) {
        showProgress(getString(R.string.rev_transcribe_download, 0));
        modelListener = (status, progress, message) -> {
            switch (status) {
                case DOWNLOADING:
                    updateProgress(getString(R.string.rev_transcribe_download, Math.max(0, progress)));
                    break;
                case UNPACKING:
                    updateProgress(getString(R.string.grav_asr_unpacking));
                    break;
                case READY:
                    detachModelListener(models);
                    runRetranscribe();
                    break;
                case ERROR:
                    detachModelListener(models);
                    dismissProgress();
                    Toast.makeText(this, getString(R.string.rev_transcribe_failed,
                            message != null ? message : getString(R.string.error_generic)), Toast.LENGTH_LONG).show();
                    break;
                default:
                    break;
            }
        };
        models.addListener(modelListener);
        if (models.status() != com.tlean07.totvs.audio.asr.VoskModelManager.Status.DOWNLOADING
                && models.status() != com.tlean07.totvs.audio.asr.VoskModelManager.Status.UNPACKING) {
            models.download();
        }
    }

    private void detachModelListener(com.tlean07.totvs.audio.asr.VoskModelManager models) {
        if (modelListener != null) {
            models.removeListener(modelListener);
            modelListener = null;
        }
    }

    private void runRetranscribe() {
        if (rec == null || transcribing) {
            return;
        }
        transcribing = true;
        readForm();
        showProgress(getString(R.string.rev_transcribing, 0));
        com.tlean07.totvs.audio.FileTranscriber.retranscribe(this, rec, new com.tlean07.totvs.audio.FileTranscriber.Listener() {
            @Override
            public void onProgress(int phase, int percent) {
                updateProgress(getString(R.string.rev_transcribing, phase == 0 ? percent / 2 : 50 + percent / 2));
            }

            @Override
            public void onDone(Recording recording) {
                transcribing = false;
                dismissProgress();
                if (binding == null) {
                    return;
                }
                rec = recording;
                render();
                Toast.makeText(RevisaoActivity.this, R.string.rev_transcribe_done, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                transcribing = false;
                dismissProgress();
                if (binding != null) {
                    Toast.makeText(RevisaoActivity.this, getString(R.string.rev_transcribe_failed, message), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showProgress(String text) {
        dismissProgress();
        progressDialog = new AlertDialog.Builder(this)
                .setMessage(text)
                .setCancelable(false)
                .show();
    }

    private void updateProgress(String text) {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.setMessage(text);
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void renderSpeakers() {
        binding.revSpeakers.removeAllViews();
        long total = 0;
        for (Recording.Falante f : rec.falantes) {
            total += f.tempoFalaMs;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Recording.Falante f : rec.falantes) {
            ItemFalanteBinding row = ItemFalanteBinding.inflate(inflater, binding.revSpeakers, false);
            int color = SpeakerColors.of(this, f.indice);
            row.falanteAvatar.setText(String.valueOf(f.indice + 1));
            row.falanteAvatar.getBackground().setTint(color);
            row.falanteNome.setText(f.rotulo());
            row.falanteNome.setTextColor(color);
            int pct = total == 0 ? 0 : (int) (f.tempoFalaMs * 100 / total);
            row.falanteMeta.setText(getString(R.string.rev_talk_share, pct));
            row.falantePapel.setText(roleLabel(f.papel));
            row.falantePapel.setVisibility("OUTRO".equals(f.papel) || f.papel == null ? View.GONE : View.VISIBLE);
            row.getRoot().setOnClickListener(v -> editSpeaker(f));
            binding.revSpeakers.addView(row.getRoot());
        }
    }

    private void renderMarkers() {
        binding.revMarkers.removeAllViews();
        if (rec.marcadores.isEmpty()) {
            binding.revMarkersCard.setVisibility(View.GONE);
            return;
        }
        binding.revMarkersCard.setVisibility(View.VISIBLE);
        for (Recording.Marcador m : rec.marcadores) {
            TextView tv = new TextView(this);
            tv.setText(TranscriptFormatter.formatClock(m.tMs) + "  ·  " + m.rotulo);
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light));
            tv.setTextSize(14f);
            int pad = (int) (6 * getResources().getDisplayMetrics().density);
            tv.setPadding(0, pad, 0, pad);
            tv.setOnClickListener(v -> seekTo(m.tMs));
            binding.revMarkers.addView(tv);
        }
    }

    private String roleLabel(String papel) {
        if ("VENDEDOR".equals(papel)) {
            return getString(R.string.rev_role_vendedor);
        }
        if ("CLIENTE".equals(papel)) {
            return getString(R.string.rev_role_cliente);
        }
        return getString(R.string.rev_role_outro);
    }

    @Nullable
    private String vendedorFromSpeakers() {
        for (Recording.Falante f : rec.falantes) {
            if ("VENDEDOR".equals(f.papel) && f.nome != null && !f.nome.isBlank()) {
                return f.nome;
            }
        }
        return null;
    }

    // --- player ---

    private void preparePlayer() {
        File audio = store.audioFile(rec);
        if (!audio.exists()) {
            binding.revPlay.setEnabled(false);
            binding.revPlay.setAlpha(0.4f);
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(audio.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                playerReady = true;
                if (mp.getDuration() > 0) {
                    binding.revDuration.setText(TranscriptFormatter.formatClock(mp.getDuration()));
                }
            });
            player.setOnCompletionListener(mp -> {
                binding.revPlay.setImageResource(R.drawable.ic_play);
                binding.revTimeline.setPosition(0);
                binding.revPosition.setText(TranscriptFormatter.formatClock(0));
            });
            player.prepareAsync();
        } catch (Exception e) {
            player = null;
            binding.revPlay.setEnabled(false);
            binding.revPlay.setAlpha(0.4f);
        }
    }

    private void togglePlay() {
        if (player == null || !playerReady) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            binding.revPlay.setImageResource(R.drawable.ic_play);
        } else {
            player.start();
            binding.revPlay.setImageResource(R.drawable.ic_pause);
            handler.post(ticker);
        }
    }

    private void seekTo(long ms) {
        binding.revTimeline.setPosition(ms);
        binding.revPosition.setText(TranscriptFormatter.formatClock(ms));
        if (player != null && playerReady) {
            player.seekTo((int) ms);
            if (!player.isPlaying()) {
                player.start();
                binding.revPlay.setImageResource(R.drawable.ic_pause);
                handler.post(ticker);
            }
        }
    }

    // --- editing ---

    private void editSpeaker(Recording.Falante f) {
        DialogEditarFalanteBinding dlg = DialogEditarFalanteBinding.inflate(getLayoutInflater());
        dlg.dlgNome.setText(f.nome);
        if ("VENDEDOR".equals(f.papel)) {
            dlg.dlgPapelVendedor.setChecked(true);
        } else if ("CLIENTE".equals(f.papel)) {
            dlg.dlgPapelCliente.setChecked(true);
        } else {
            dlg.dlgPapelOutro.setChecked(true);
        }
        dlg.dlgUnir.setVisibility(rec.falantes.size() > 1 ? View.VISIBLE : View.GONE);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.rev_edit_speaker_title) + " · " + TranscriptFormatter.defaultName(f.indice))
                .setView(dlg.getRoot())
                .setPositiveButton(R.string.save, (d, w) -> {
                    String nome = dlg.dlgNome.getText() == null ? "" : dlg.dlgNome.getText().toString().trim();
                    f.nome = nome.isEmpty() ? null : nome;
                    if (dlg.dlgPapelVendedor.isChecked()) {
                        f.papel = "VENDEDOR";
                        learnVoice(f);
                        if (f.nome != null && (binding.revVendedor.getText() == null || binding.revVendedor.getText().length() == 0)) {
                            binding.revVendedor.setText(f.nome);
                        }
                    } else if (dlg.dlgPapelCliente.isChecked()) {
                        f.papel = "CLIENTE";
                        if (f.nome != null && (binding.revCliente.getText() == null || binding.revCliente.getText().length() == 0)) {
                            binding.revCliente.setText(f.nome);
                        }
                    } else {
                        f.papel = "OUTRO";
                    }
                    binding.revParticipantes.setText(rec.participantesTexto());
                    afterEdit();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.dlgUnir.setOnClickListener(v -> {
            dialog.dismiss();
            mergeSpeaker(f);
        });
        dialog.show();
    }

    private void mergeSpeaker(Recording.Falante from) {
        List<Recording.Falante> others = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Recording.Falante f : rec.falantes) {
            if (f.indice != from.indice) {
                others.add(f);
                labels.add(f.rotulo());
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.rev_merge_title, from.rotulo()))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    rec.unirFalantes(from.indice, others.get(which).indice);
                    binding.revParticipantes.setText(rec.participantesTexto());
                    afterEdit();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editTurn(int position, TranscriptTurn turn) {
        if (position < 0 || position >= rec.turnos.size()) {
            return;
        }
        Recording.Turno t = rec.turnos.get(position);
        DialogEditarTurnoBinding dlg = DialogEditarTurnoBinding.inflate(getLayoutInflater());
        dlg.dlgTexto.setText(t.texto);
        for (Recording.Falante f : rec.falantes) {
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setTag(f.indice);
            chip.setText(f.rotulo());
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setChipStrokeWidth(2f);
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(SpeakerColors.of(this, f.indice)));
            chip.setChipBackgroundColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{SpeakerColors.of(this, f.indice), SpeakerColors.tint(this, f.indice)}));
            chip.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{0xFFFFFFFF, SpeakerColors.of(this, f.indice)}));
            chip.setChecked(f.indice == t.falante);
            dlg.dlgFalantes.addView(chip);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.rev_edit_turn_title) + " · " + TranscriptFormatter.formatClock(t.inicioMs))
                .setView(dlg.getRoot())
                .setPositiveButton(R.string.save, (d, w) -> {
                    t.texto = dlg.dlgTexto.getText() == null ? "" : dlg.dlgTexto.getText().toString().trim();
                    int checked = dlg.dlgFalantes.getCheckedChipId();
                    if (checked != View.NO_ID) {
                        Chip c = dlg.dlgFalantes.findViewById(checked);
                        if (c != null && c.getTag() instanceof Integer) {
                            t.falante = (Integer) c.getTag();
                        }
                    }
                    rec.recalcularTemposDeFala();
                    afterEdit();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void afterEdit() {
        readForm();
        render();
        persist();
    }

    private void readForm() {
        rec.cliente = text(binding.revCliente);
        rec.vendedor = text(binding.revVendedor);
        rec.participantes = text(binding.revParticipantes);
    }

    @Nullable
    private static String text(com.google.android.material.textfield.TextInputEditText e) {
        if (e.getText() == null) {
            return null;
        }
        String s = e.getText().toString().trim();
        return s.isEmpty() ? null : s;
    }

    private void persist() {
        Recording snapshot = rec;
        AppExecutors.io(() -> {
            try {
                store.save(snapshot);
            } catch (Exception ignored) {
                // the next edit retries
            }
        });
    }

    // --- sending ---

    private void sendText() {
        readForm();
        if (rec.cliente == null) {
            binding.revCliente.setError(getString(R.string.rev_cliente_required));
            binding.revCliente.requestFocus();
            return;
        }
        if (Recording.STATUS_ENVIADA.equals(rec.status)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.rev_already_sent)
                    .setPositiveButton(R.string.rev_send_again, (d, w) -> doSendText())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        doSendText();
    }

    private void doSendText() {
        overlay(true);
        TranscricaoRequest req = new TranscricaoRequest(rec.textoLeanIa(), rec.cliente, rec.vendedor, rec.participantes);
        data.criarTranscricao(req, result -> {
            overlay(false);
            if (result.success) {
                markSent(result.data == null ? null : result.data.id);
            } else if (result.networkError) {
                com.tlean07.totvs.network.PendingQueue.get(this).enqueue(req, rec.id);
                rec.status = Recording.STATUS_FILA;
                persist();
                Toast.makeText(this, R.string.queue_offline, Toast.LENGTH_LONG).show();
                finish();
            } else {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void sendAudio() {
        readForm();
        File audio = store.audioFile(rec);
        if (!audio.exists()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.rev_send_audio)
                .setMessage(R.string.rev_send_audio_hint)
                .setPositiveButton(R.string.rev_send_audio, (d, w) -> {
                    overlay(true);
                    data.importarAudio(audio, rec.cliente, rec.vendedor, rec.participantes, result -> {
                        overlay(false);
                        if (result.success) {
                            markSent(result.data == null ? null : result.data.id);
                        } else {
                            Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void markSent(@Nullable String transcricaoId) {
        rec.status = Recording.STATUS_ENVIADA;
        rec.transcricaoId = transcricaoId;
        persist();
        com.tlean07.totvs.security.AuditLog.get(this).record("gravacao.enviada",
                rec.id + (transcricaoId != null ? " → análise " + transcricaoId : ""));
        Toast.makeText(this, R.string.rev_sent, Toast.LENGTH_SHORT).show();
        finish();
    }

    // --- more menu: export / share ---

    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.rev_export_txt);
        menu.getMenu().add(0, 2, 1, R.string.rev_export_pdf);
        menu.getMenu().add(0, 3, 2, R.string.rev_share_summary);
        menu.setOnMenuItemClickListener(item -> {
            readForm();
            switch (item.getItemId()) {
                case 1:
                    export(false);
                    return true;
                case 2:
                    export(true);
                    return true;
                case 3:
                    shareSummary();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void export(boolean pdf) {
        Recording snapshot = rec;
        AppExecutors.io(() -> {
            try {
                File f = pdf ? com.tlean07.totvs.audio.TranscriptExporter.toPdf(this, snapshot)
                        : com.tlean07.totvs.audio.TranscriptExporter.toTxt(this, snapshot);
                com.tlean07.totvs.security.AuditLog.get(this).record("gravacao.exportada", snapshot.id + " · " + f.getName());
                AppExecutors.main(() -> com.tlean07.totvs.audio.TranscriptExporter.share(this, f, pdf ? "application/pdf" : "text/plain"));
            } catch (Exception e) {
                AppExecutors.main(() -> Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** One-tap CRM hand-off: a ready-to-paste summary through the system share sheet. */
    private void shareSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Reunião: ").append(rec.tituloOuPadrao()).append('\n');
        sb.append("Data: ").append(DATE.format(new Date(rec.criadoEm))).append(" · ")
                .append(TranscriptFormatter.formatClock(rec.duracaoMs)).append('\n');
        if (rec.vendedor != null) {
            sb.append("Vendedor: ").append(rec.vendedor).append('\n');
        }
        sb.append("Participantes: ").append(rec.participantes != null ? rec.participantes : rec.participantesTexto()).append('\n');
        Recording.AnaliseLocal a = rec.analiseLocal;
        if (a != null) {
            sb.append("\nSentimento: ").append(GravadorActivity.sentimentoLabel(a.sentimentoGeral)).append('\n');
            if (a.persona != null) {
                sb.append("Interlocutor: ").append(a.persona).append('\n');
            }
            if (a.budget != null) {
                sb.append("Budget: ").append(a.budget).append('\n');
            }
            if (!a.riscos.isEmpty()) {
                sb.append("\nRiscos:\n");
                for (Recording.Sinal s : a.riscos) {
                    sb.append("- ").append(s.texto).append('\n');
                }
            }
            if (!a.oportunidades.isEmpty()) {
                sb.append("\nOportunidades:\n");
                for (Recording.Sinal s : a.oportunidades) {
                    sb.append("- ").append(s.texto).append('\n');
                }
            }
            if (a.proximaAcao != null) {
                sb.append("\nPróxima ação: ").append(a.proximaAcao).append('\n');
            }
        }
        if (!rec.marcadores.isEmpty()) {
            sb.append("\nMomentos marcados:\n");
            for (Recording.Marcador m : rec.marcadores) {
                sb.append("- ").append(TranscriptFormatter.formatClock(m.tMs)).append(' ').append(m.rotulo).append('\n');
            }
        }
        sb.append("\nGerado pelo TOTVS Conversational Intelligence");
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Resumo da reunião: " + rec.tituloOuPadrao())
                .putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, getString(R.string.rev_share_summary)));
        com.tlean07.totvs.security.AuditLog.get(this).record("gravacao.compartilhada", rec.id);
    }

    /** The user said "this is me": remember this voice for the next recordings. */
    private void learnVoice(Recording.Falante f) {
        com.tlean07.totvs.network.dto.AuthUser user = TotvsApp.get().session().getCurrentUser();
        if (user == null || f.voz == null) {
            return;
        }
        com.tlean07.totvs.audio.VoicePrints prints = new com.tlean07.totvs.audio.VoicePrints(this);
        boolean knew = prints.has(user.id);
        prints.learn(user.id, com.tlean07.totvs.audio.dsp.SpeakerProfile.fromExport(f.voz));
        if (!knew && prints.has(user.id)) {
            Toast.makeText(this, R.string.rev_voice_learned, Toast.LENGTH_LONG).show();
            com.tlean07.totvs.security.AuditLog.get(this).record("voz.aprendida", user.id);
        }
    }

    /** What the on-device LeanIA found, before/without the server analysis. */
    private void renderPreview() {
        Recording.AnaliseLocal a = rec.analiseLocal;
        binding.revPreview.removeAllViews();
        if (a == null || (a.riscos.isEmpty() && a.oportunidades.isEmpty() && a.resumo == null)) {
            binding.revPreviewCard.setVisibility(View.GONE);
            return;
        }
        binding.revPreviewCard.setVisibility(View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;
        addText(binding.revPreview, getString(R.string.rev_preview_title), 15, R.color.text_primary_light, true, 0);
        addText(binding.revPreview, getString(R.string.rev_preview_hint), 12, R.color.text_muted_light, false, 2);
        addText(binding.revPreview, GravadorActivity.sentimentoLabel(a.sentimentoGeral)
                + (a.persona != null ? " · " + a.persona : "") + (a.budget != null ? " · " + a.budget : ""), 13,
                GravadorActivity.sentimentoColor(a.sentimentoGeral), true, 10);
        if (a.resumo != null) {
            addText(binding.revPreview, a.resumo, 13, R.color.text_secondary_light, false, 6);
        }
        for (Recording.Sinal s : a.riscos) {
            addText(binding.revPreview, "▲ " + s.texto, 13, R.color.totvs_danger, false, 6);
        }
        for (Recording.Sinal s : a.oportunidades) {
            addText(binding.revPreview, "● " + s.texto, 13, R.color.totvs_accent, false, 6);
        }
        if (!a.concorrentes.isEmpty()) {
            addText(binding.revPreview, getString(R.string.live_concorrente, String.join(", ", a.concorrentes)), 13,
                    R.color.totvs_warning, false, 6);
        }
        if (a.proximaAcao != null) {
            addText(binding.revPreview, getString(R.string.live_acao, a.proximaAcao), 13, R.color.speaker_1, true, 8);
        }
    }

    private void addText(android.widget.LinearLayout parent, String text, int sp, int color, boolean bold, int topDp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) {
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        tv.setPadding(0, (int) (topDp * getResources().getDisplayMetrics().density), 0, 0);
        parent.addView(tv);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.rev_delete)
                .setMessage(R.string.rev_delete_confirm)
                .setPositiveButton(R.string.rev_delete, (d, w) -> {
                    String id = rec.id;
                    rec = null;
                    AppExecutors.io(() -> store.delete(id));
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void overlay(boolean show) {
        binding.revOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
