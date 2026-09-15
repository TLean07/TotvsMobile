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
        binding.revPlay.setOnClickListener(v -> togglePlay());
        binding.revTimeline.setOnSeek(this::seekTo);
        binding.revSendText.setOnClickListener(v -> sendText());
        binding.revSendAudio.setOnClickListener(v -> sendAudio());

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
        super.onDestroy();
    }

    // --- rendering ---

    private void render() {
        binding.revTitle.setText(rec.tituloOuPadrao());
        binding.revMeta.setText(getString(R.string.rev_meta, DATE.format(new Date(rec.criadoEm)),
                rec.falantes.size(), TranscriptFormatter.formatClock(rec.duracaoMs)));
        boolean sent = Recording.STATUS_ENVIADA.equals(rec.status);
        binding.revBadge.setText(sent ? R.string.rev_sent_badge : R.string.rev_draft_badge);
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
        adapter.setNames(rec.nomes());
        adapter.submit(rec.turns());
        boolean hasText = rec.temTexto();
        binding.revTranscript.setVisibility(hasText ? View.VISIBLE : View.GONE);
        binding.revTranscriptHint.setText(hasText ? R.string.rev_transcript_hint : R.string.rev_transcript_none);
        binding.revSendText.setEnabled(hasText);
        binding.revSendText.setAlpha(hasText ? 1f : 0.5f);
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
        Toast.makeText(this, R.string.rev_sent, Toast.LENGTH_SHORT).show();
        finish();
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
