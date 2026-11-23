package com.neofastftl.midivideogen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Parser tests: timing, running status, truncation and garbage input. */
public class MidiFileTest {

    private static final int DIVISION = 480;

    // ---------- tiny MIDI builder ----------

    private static byte[] varLen(int value) {
        int v = value & 0x0FFFFFFF;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((v >> 21) & 0x7F);
        out.write((v >> 14) & 0x7F);
        out.write((v >> 7) & 0x7F);
        out.write(v & 0x7F);
        byte[] raw = out.toByteArray();
        // Drop leading zero groups (but keep at least one byte), then set the
        // continuation bit on every byte except the last.
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) {
            start++;
        }
        int n = raw.length - start;
        byte[] outBytes = new byte[n];
        for (int i = 0; i < n; i++) {
            outBytes[i] = raw[start + i];
            if (i < n - 1) {
                outBytes[i] |= (byte) 0x80;
            }
        }
        return outBytes;
    }

    private static byte[] header(int format, int tracks, int division) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MThd".getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(6);
        out.write((format >> 8) & 0xFF);
        out.write(format & 0xFF);
        out.write((tracks >> 8) & 0xFF);
        out.write(tracks & 0xFF);
        out.write((division >> 8) & 0xFF);
        out.write(division & 0xFF);
        return out.toByteArray();
    }

    private static byte[] chunk(String id, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        int len = payload.length;
        out.write((len >> 24) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] endOfTrack() {
        return new byte[]{0x00, (byte) 0xFF, 0x2F, 0x00};
    }

    private static byte[] parse(byte[]... parts) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            all.writeBytes(p);
        }
        return all.toByteArray();
    }

    private static MidiFile load(byte[] data) throws IOException {
        return MidiFile.parse(new ByteArrayInputStream(data));
    }

    // ---------- tests ----------

    @Test
    public void parsesNoteWithDefaultTempo() throws Exception {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        track.write(varLen(0));
        track.write(new byte[]{(byte) 0x90, 60, 100});      // note on C4
        track.write(varLen(DIVISION));
        track.write(new byte[]{(byte) 0x80, 60, 0});        // note off
        track.write(endOfTrack());

        MidiFile midi = load(parse(header(0, 1, DIVISION), chunk("MTrk", track.toByteArray())));
        List<MidiFile.Track> tracks = midi.getTracks();
        assertEquals(1, tracks.size());
        assertEquals(1, tracks.get(0).notes.size());
        MidiFile.Note note = tracks.get(0).notes.get(0);
        assertEquals(60, note.pitch);
        assertEquals(100, note.velocity);
        assertEquals(0.0, note.start, 1e-9);
        // 480 ticks at 120 BPM (500000 us per quarter) = 0.5 s
        assertEquals(0.5, note.end, 1e-9);
    }

    @Test
    public void honoursTempoChange() throws Exception {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        track.write(varLen(0));
        // tempo meta: 1_000_000 us per quarter = 60 BPM
        track.write(new byte[]{(byte) 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40});
        track.write(varLen(0));
        track.write(new byte[]{(byte) 0x90, 64, 90});
        track.write(varLen(DIVISION));
        track.write(new byte[]{(byte) 0x80, 64, 0});
        track.write(endOfTrack());

        MidiFile midi = load(parse(header(0, 1, DIVISION), chunk("MTrk", track.toByteArray())));
        MidiFile.Note note = midi.getTracks().get(0).notes.get(0);
        assertEquals(1.0, note.end, 1e-9);
    }

    /** Running status is everywhere in real files; it must not truncate. */
    @Test
    public void supportsRunningStatus() throws Exception {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        track.write(varLen(0));
        track.write(new byte[]{(byte) 0x90, 60, 100});  // explicit status
        track.write(varLen(240));
        track.write(new byte[]{60, 0});                 // running status, vel 0 = off
        track.write(varLen(0));
        track.write(new byte[]{62, 100});               // running status note on
        track.write(varLen(240));
        track.write(new byte[]{62, 0});                 // running status note off
        track.write(endOfTrack());

        MidiFile midi = load(parse(header(0, 1, DIVISION), chunk("MTrk", track.toByteArray())));
        List<MidiFile.Note> notes = midi.getTracks().get(0).notes;
        assertEquals(2, notes.size());
        assertEquals(60, notes.get(0).pitch);
        assertEquals(0.25, notes.get(0).end, 1e-9);
        assertEquals(62, notes.get(1).pitch);
        assertEquals(0.25, notes.get(1).start, 1e-9);
        assertEquals(0.5, notes.get(1).end, 1e-9);
    }

    /** Tempo lives in track 0 of format-1 files and applies to every track. */
    @Test
    public void mergesTempoAcrossTracks() throws Exception {
        ByteArrayOutputStream conductor = new ByteArrayOutputStream();
        conductor.write(varLen(0));
        conductor.write(new byte[]{(byte) 0xFF, 0x51, 0x03, 0x09, 0x27, (byte) 0xC0}); // 600000
        conductor.write(endOfTrack());

        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        notes.write(varLen(0));
        notes.write(new byte[]{(byte) 0x91, 72, 100});
        notes.write(varLen(DIVISION));
        notes.write(new byte[]{(byte) 0x81, 72, 0});
        notes.write(endOfTrack());

        MidiFile midi = load(parse(header(1, 2, DIVISION),
                chunk("MTrk", conductor.toByteArray()),
                chunk("MTrk", notes.toByteArray())));
        // The conductor track has no notes, so only the note track survives.
        assertEquals(1, midi.getTracks().size());
        assertEquals(0.6, midi.getTracks().get(0).notes.get(0).end, 1e-9);
    }

    @Test
    public void toleratesTruncatedFile() throws Exception {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        track.write(varLen(0));
        track.write(new byte[]{(byte) 0x90, 60, 100});
        track.write(varLen(DIVISION));
        track.write(new byte[]{(byte) 0x80, 60, 0});
        // No end-of-track and no padding: the chunk body is all we have.
        MidiFile midi = load(parse(header(0, 2, DIVISION),
                chunk("MTrk", track.toByteArray())));
        assertEquals(1, midi.getTracks().size());
        assertEquals(1, midi.getTracks().get(0).notes.size());
    }

    @Test
    public void rejectsGarbage() {
        try {
            load("this is definitely not a midi file".getBytes(StandardCharsets.US_ASCII));
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("midi"));
        }
    }

    @Test
    public void rejectsEmptyTrackList() {
        try {
            load(parse(header(0, 1, DIVISION),
                    chunk("MTrk", new byte[]{0x00, (byte) 0xFF, 0x2F, 0x00})));
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("no usable"));
        }
    }

    @Test
    public void keepsNotesWithoutMatchingOff() throws Exception {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        track.write(varLen(0));
        track.write(new byte[]{(byte) 0x90, 60, 100});
        track.write(varLen(DIVISION));
        track.write(endOfTrack());

        MidiFile midi = load(parse(header(0, 1, DIVISION), chunk("MTrk", track.toByteArray())));
        MidiFile.Note note = midi.getTracks().get(0).notes.get(0);
        // Closed at end of track, never shorter than one tick.
        assertTrue(note.end > note.start);
    }
}
