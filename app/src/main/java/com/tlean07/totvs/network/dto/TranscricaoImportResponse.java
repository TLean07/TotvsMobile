package com.tlean07.totvs.network.dto;

/** Result of POST /api/transcricoes/import/totvs (TOTVS JSON import). */
public class TranscricaoImportResponse {
    public boolean success;
    public String sourceFile;
    public int found;
    public int imported;
    public int skipped;
    public int failed;

    // Legacy fields kept by the backend for backward compatibility.
    public int totalRegistros;
    public int importados;
    public int ignorados;

    /** Best-effort count of imported records across old/new contract fields. */
    public int importedCount() {
        return Math.max(imported, importados);
    }

    public int foundCount() {
        return Math.max(found, totalRegistros);
    }
}
