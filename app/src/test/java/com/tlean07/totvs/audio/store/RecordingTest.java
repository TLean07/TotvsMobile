package com.tlean07.totvs.audio.store;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RecordingTest {

    private static Recording.Turno turno(int falante, long ini, long fim, String texto) {
        Recording.Turno t = new Recording.Turno();
        t.falante = falante;
        t.inicioMs = ini;
        t.fimMs = fim;
        t.texto = texto;
        return t;
    }

    @Test
    public void mergingSpeakersRenumbersAndJoinsAdjacentTurns() {
        Recording r = new Recording();
        r.falante(0).nome = "Ana";
        r.falante(1);
        r.falante(2).papel = "CLIENTE";
        r.turnos.add(turno(0, 0, 1_000, "oi"));
        r.turnos.add(turno(2, 1_100, 2_000, "bom dia"));
        r.turnos.add(turno(1, 2_100, 3_000, "tudo bem"));
        r.turnos.add(turno(2, 3_100, 4_000, "sim"));
        r.recalcularTemposDeFala();

        r.unirFalantes(2, 0);

        assertEquals(2, r.falantes.size());
        assertEquals("Ana", r.falantes.get(0).nome);
        assertEquals("CLIENTE", r.falantes.get(0).papel);
        assertEquals(3, r.turnos.size());
        assertEquals("oi bom dia", r.turnos.get(0).texto);
        assertEquals(0, r.turnos.get(0).falante);
        assertEquals(1, r.turnos.get(1).falante);
        assertEquals(0, r.turnos.get(2).falante);
        assertEquals("[LOCUTOR 1]: Oi bom dia.\n[LOCUTOR 2]: Tudo bem.\n[LOCUTOR 1]: Sim.", r.textoLeanIa());
        assertEquals("Locutor 1: Ana (cliente); Locutor 2: Pessoa 2", r.participantesTexto());
    }
}
