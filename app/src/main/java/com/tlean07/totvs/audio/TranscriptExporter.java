package com.tlean07.totvs.audio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.core.content.FileProvider;

import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.store.Recording;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes a recording's transcript as TXT or PDF into the cache and opens the system share sheet. */
public final class TranscriptExporter {

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    private TranscriptExporter() {
    }

    public static File toTxt(Context context, Recording r) throws IOException {
        File f = new File(exportDir(context), safeName(r) + ".txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(header(r));
            w.write("\n\n");
            for (Recording.Turno t : r.turnos) {
                w.write("[" + TranscriptFormatter.formatClock(t.inicioMs) + "] " + r.falante(t.falante).rotulo() + ": "
                        + (t.texto == null ? "" : t.texto) + "\n");
            }
            if (!r.marcadores.isEmpty()) {
                w.write("\nMomentos marcados\n");
                for (Recording.Marcador m : r.marcadores) {
                    w.write("- " + TranscriptFormatter.formatClock(m.tMs) + " " + m.rotulo + "\n");
                }
            }
        }
        return f;
    }

    public static File toPdf(Context context, Recording r) throws IOException {
        File f = new File(exportDir(context), safeName(r) + ".pdf");
        PdfDocument doc = new PdfDocument();
        int pageW = 595;
        int pageH = 842;
        int margin = 40;
        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setTextSize(10.5f);
        TextPaint bold = new TextPaint(body);
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        TextPaint title = new TextPaint(bold);
        title.setTextSize(16f);

        StringBuilder sb = new StringBuilder();
        for (Recording.Turno t : r.turnos) {
            sb.append('[').append(TranscriptFormatter.formatClock(t.inicioMs)).append("] ")
                    .append(r.falante(t.falante).rotulo()).append(": ").append(t.texto == null ? "" : t.texto).append("\n\n");
        }
        if (!r.marcadores.isEmpty()) {
            sb.append("Momentos marcados\n");
            for (Recording.Marcador m : r.marcadores) {
                sb.append("- ").append(TranscriptFormatter.formatClock(m.tMs)).append(' ').append(m.rotulo).append('\n');
            }
        }
        StaticLayout layout = StaticLayout.Builder.obtain(sb, 0, sb.length(), body, pageW - 2 * margin)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build();
        StaticLayout head = StaticLayout.Builder.obtain(header(r), 0, header(r).length(), body, pageW - 2 * margin).build();

        int lineCount = layout.getLineCount();
        int line = 0;
        int page = 1;
        while (line < lineCount || page == 1) {
            PdfDocument.Page p = doc.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, page).create());
            android.graphics.Canvas c = p.getCanvas();
            int y = margin;
            if (page == 1) {
                c.drawText(r.tituloOuPadrao(), margin, y + 14, title);
                y += 26;
                c.save();
                c.translate(margin, y);
                head.draw(c);
                c.restore();
                y += head.getHeight() + 14;
            }
            int top = layout.getLineTop(line);
            int available = pageH - margin - y;
            int end = line;
            while (end < lineCount && layout.getLineBottom(end) - top <= available) {
                end++;
            }
            c.save();
            c.clipRect(margin, y, pageW - margin, pageH - margin);
            c.translate(margin, y - top);
            layout.draw(c);
            c.restore();
            c.drawText(String.valueOf(page), pageW - margin - 10, pageH - 20, body);
            doc.finishPage(p);
            if (end == line) {
                break;
            }
            line = end;
            page++;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            doc.writeTo(out);
        } finally {
            doc.close();
        }
        return f;
    }

    public static void share(Context context, File file, String mime) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(send, file.getName()));
    }

    private static String header(Recording r) {
        StringBuilder h = new StringBuilder();
        h.append("Reunião: ").append(r.tituloOuPadrao()).append('\n');
        h.append("Data: ").append(DATE.format(new Date(r.criadoEm))).append("   Duração: ")
                .append(TranscriptFormatter.formatClock(r.duracaoMs)).append('\n');
        if (r.vendedor != null) {
            h.append("Vendedor: ").append(r.vendedor).append('\n');
        }
        h.append("Participantes: ").append(r.participantes != null ? r.participantes : r.participantesTexto()).append('\n');
        h.append("Transcrição feita no celular (").append(r.modeloAsr != null ? r.modeloAsr : "sem modelo").append(")");
        return h.toString();
    }

    private static File exportDir(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "export");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cache indisponível");
        }
        return dir;
    }

    private static String safeName(Recording r) {
        String base = r.tituloOuPadrao().replaceAll("[^A-Za-z0-9À-ÿ ]", "").trim().replace(' ', '_');
        return (base.isEmpty() ? "reuniao" : base) + "_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date(r.criadoEm));
    }
}
