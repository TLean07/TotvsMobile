package com.tlean07.totvs.ui.gravador;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.R;
import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.databinding.ItemGravacaoBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GravacaoAdapter extends RecyclerView.Adapter<GravacaoAdapter.VH> {

    public interface OnItem {
        void on(Recording r);
    }

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

    private final List<Recording> items = new ArrayList<>();
    private final OnItem onClick;
    private final OnItem onLongClick;

    public GravacaoAdapter(OnItem onClick, OnItem onLongClick) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
    }

    public void submit(List<Recording> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemGravacaoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Recording r = items.get(position);
        ItemGravacaoBinding b = h.b;
        b.gravacaoTitulo.setText(r.tituloOuPadrao());
        b.gravacaoMeta.setText(b.getRoot().getContext().getString(R.string.lst_item_meta,
                DATE.format(new Date(r.criadoEm)), TranscriptFormatter.formatClock(r.duracaoMs), r.falantes.size()));
        String preview = null;
        for (Recording.Turno t : r.turnos) {
            if (t.texto != null && !t.texto.isBlank()) {
                preview = t.texto;
                break;
            }
        }
        b.gravacaoPreview.setText(preview != null ? preview : b.getRoot().getContext().getString(R.string.rev_transcript_none));
        b.gravacaoIcon.setImageResource("arquivo".equals(r.origem) ? R.drawable.ic_audio_file : R.drawable.ic_mic);
        boolean sent = Recording.STATUS_ENVIADA.equals(r.status);
        b.gravacaoStatus.setText(sent ? R.string.rev_sent_badge : R.string.rev_draft_badge);
        b.gravacaoStatus.setBackgroundResource(sent ? R.drawable.bg_pill_success : R.drawable.bg_pill_warning);
        b.gravacaoStatus.setTextColor(ContextCompat.getColor(b.getRoot().getContext(),
                sent ? R.color.chip_success_fg : R.color.chip_warning_fg));
        b.getRoot().setOnClickListener(v -> onClick.on(r));
        b.getRoot().setOnLongClickListener(v -> {
            onLongClick.on(r);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemGravacaoBinding b;

        VH(ItemGravacaoBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
