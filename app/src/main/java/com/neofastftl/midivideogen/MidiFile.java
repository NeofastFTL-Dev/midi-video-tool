package com.neofastftl.midivideogen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal Standard MIDI File (SMF) reader.
 *
 * <p>Parses the header chunk and every track chunk, collects note events and
 * tempo changes, then converts all note times to seconds using a global tempo
 * map (tempo events from any track, which is how format-1 files encode them).
 * Everything else (controllers, program changes, text, ...) is ignored: this
 * app renders one video segment per note.
 *
 * <p>The reader is defensive on purpose — files from the wild are often
 * truncated, carry junk chunks, or rely on running status — and a malformed
 * file must produce a readable message instead of a crash.
 */
public final class MidiFile {

    /** Largest file we are willing to load into memory. */
    static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    /** Largest individual chunk we accept. */
    private static final int MAX_CHUNK_BYTES = 32 * 1024 * 1024;
    /** Guard against pathological files that would exhaust the heap. */
    private static final int MAX_NOTES = 200_000;

    /** A parsed note with start/end times in seconds. */
    public static final class Note {
        public final double start;
        public final double end;
        public final int pitch;
        public final int velocity;

        Note(double start, double end, int pitch, int velocity) {
            this.start = start;
            this.end = end;
            this.pitch = pitch;
            this.velocity = velocity;
        }
    }

    /** One MIDI track (instrument) with its notes. */
    public static final class Track {
        public String name;
        public final List<Note> notes = new ArrayList<>();
        final List<RawNote> rawNotes = new ArrayList<>();

        Track(String name) {
            this.name = name == null ? "" : name;
        }
    }

    private static final class RawNote {
        final int pitch;
        final int channel;
        final long startTick;
        long endTick;
        final int velocity;

        RawNote(int pitch, int channel, long startTick, int velocity) {
            this.pitch = pitch;
            this.channel = channel;
            this.startTick = startTick;
            this.endTick = startTick;
            this.velocity = velocity;
        }
    }

    private final List<Track> tracks = new ArrayList<>();

    public static MidiFile parse(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("No MIDI data");
        }
        return new MidiFile(readAll(in));
    }

    private MidiFile(byte[] data) throws IOException {
        if (data.length < 14) {
            throw new IOException("Not a MIDI file (too short)");
        }
        Reader r = new Reader(data);

        // ---- Header chunk ----
        byte[] magic = r.readBytes(4);
        if (!"MThd".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("Not a MIDI file (missing MThd)");
        }
        int headerLen = r.readU32();
        if (headerLen < 6) {
            throw new IOException("Malformed MIDI header");
        }
        int format = r.readU16();
        if (format < 0 || format > 2) {
            throw new IOException("Unsupported MIDI format " + format);
        }
        int numTracks = r.readU16();
        int division = r.readU16();
        r.skip(headerLen - 6L);
        if (division == 0) {
            throw new IOException("Invalid MIDI time division");
        }

        // ---- Pass 1: collect raw notes and tempo events from every track ----
        List<Track> parsed = new ArrayList<>();
        List<long[]> tempoEvents = new ArrayList<>(); // {tick, microsPerQuarter}
        tempoEvents.add(new long[]{0L, 500_000L});    // 120 BPM default

        int index = 0;
        // Unknown chunk types are skipped; the guard stops a file that keeps
        // announcing tracks it never delivers.
        for (int guard = 0; index < numTracks && guard < numTracks + 4096; guard++) {
            byte[] chunkMagic;
            int chunkLen;
            try {
                chunkMagic = r.readBytes(4);
                chunkLen = r.readU32();
            } catch (IOException eof) {
                break; // tolerate truncated files
            }
            if (chunkLen < 0 || chunkLen > MAX_CHUNK_BYTES || chunkLen > r.remaining()) {
                break;
            }
            byte[] chunk = r.readBytes(chunkLen);
            String id = new String(chunkMagic, StandardCharsets.US_ASCII);
            if ("MTrk".equals(id)) {
                parseTrack(chunk, tempoEvents, parsed, index);
                index++;
            }
        }
        if (parsed.isEmpty()) {
            throw new IOException("This file contains no usable MIDI tracks");
        }

        // ---- Pass 2: convert ticks to seconds with the merged tempo map ----
        TickClock clock = new TickClock(division, tempoEvents);
        for (Track t : parsed) {
            for (RawNote n : t.rawNotes) {
                if (n.endTick <= n.startTick) {
                    n.endTick = n.startTick + 1;
                }
                double start = clock.secondsOf(n.startTick);
                double end = clock.secondsOf(n.endTick);
                t.notes.add(new Note(start, end, n.pitch, n.velocity));
            }
            if (!t.notes.isEmpty()) {
                Collections.sort(t.notes, (a, b) -> Double.compare(a.start, b.start));
                tracks.add(t);
            }
        }
        if (tracks.isEmpty()) {
            throw new IOException("This MIDI file contains no notes");
        }
    }

    private static void parseTrack(byte[] chunk, List<long[]> tempoEvents,
                                   List<Track> out, int index) {
        Reader r = new Reader(chunk);
        Track track = new Track("Track " + (index + 1));
        Deque<RawNote> openNotes = new ArrayDeque<>();
        long tick = 0;
        int running = 0; // running status: the last channel status byte seen

        try {
            while (true) {
                tick += r.readVarLen();
                int b = r.readByte() & 0xFF;
                int firstData = -1;
                int status;
                if (b < 0x80) {
                    // Running status: the byte is the first data byte of the
                    // previous channel message. Ignoring this used to truncate
                    // the rest of the track, which is extremely common.
                    if (running == 0) {
                        return;
                    }
                    status = running;
                    firstData = b;
                } else {
                    status = b;
                    if (status < 0xF0) {
                        running = status;
                    }
                }

                if (status == 0xFF) { // meta event
                    running = 0;
                    int type = r.readByte() & 0xFF;
                    int len = r.readVarLen();
                    byte[] payload = r.readBytes(len);
                    if (type == 0x51 && payload.length >= 3) {
                        long mpqn = ((payload[0] & 0xFFL) << 16)
                                | ((payload[1] & 0xFFL) << 8)
                                | (payload[2] & 0xFFL);
                        if (mpqn > 0) {
                            tempoEvents.add(new long[]{tick, mpqn});
                        }
                    } else if (type == 0x03) {
                        if (track.name.startsWith("Track ")) {
                            String name = printable(new String(payload, StandardCharsets.UTF_8));
                            if (!name.isEmpty()) {
                                track.name = name;
                            }
                        }
                    } else if (type == 0x2F) {
                        break; // end of track
                    }
                } else if (status == 0xF0 || status == 0xF7) { // sysex
                    running = 0;
                    r.readBytes(r.readVarLen());
                } else {
                    switch (status & 0xF0) {
                        case 0x90: { // note on
                            int pitch = firstData >= 0 ? firstData : (r.readByte() & 0xFF);
                            int vel = r.readByte() & 0xFF;
                            firstData = -1;
                            if (vel > 0) {
                                if (track.rawNotes.size() + openNotes.size() >= MAX_NOTES) {
                                    return;
                                }
                                openNotes.addLast(new RawNote(pitch, status & 0x0F, tick, vel));
                            } else {
                                closeNote(track, openNotes, pitch, status & 0x0F, tick);
                            }
                            break;
                        }
                        case 0x80: { // note off
                            int pitch = firstData >= 0 ? firstData : (r.readByte() & 0xFF);
                            r.readByte(); // release velocity
                            firstData = -1;
                            closeNote(track, openNotes, pitch, status & 0x0F, tick);
                            break;
                        }
                        case 0xA0: // poly key pressure
                        case 0xB0: // control change
                        case 0xE0: // pitch bend
                            r.readBytes(2 - (firstData >= 0 ? 1 : 0));
                            break;
                        case 0xC0: // program change
                        case 0xD0: // channel pressure
                            r.readBytes(1 - (firstData >= 0 ? 1 : 0));
                            break;
                        default:
                            return; // unknown status: keep what we have
                    }
                }
            }
        } catch (IOException ignored) {
            // Tolerate truncated tracks: keep the notes parsed so far.
        }

        // Close any note still held at end of track.
        for (RawNote n : openNotes) {
            n.endTick = Math.max(n.endTick, tick);
            track.rawNotes.add(n);
        }
        if (!track.rawNotes.isEmpty()) {
            out.add(track);
        }
    }

    private static void closeNote(Track track, Deque<RawNote> openNotes,
                                  int pitch, int channel, long tick) {
        for (Iterator<RawNote> it = openNotes.descendingIterator(); it.hasNext(); ) {
            RawNote n = it.next();
            if (n.pitch == pitch && n.channel == channel) {
                it.remove();
                n.endTick = Math.max(n.endTick, tick);
                track.rawNotes.add(n);
                return;
            }
        }
    }

    private static String printable(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 32 && c != 127) {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    public List<Track> getTracks() {
        return tracks;
    }

    /**
     * Converts MIDI ticks to seconds using the merged tempo map.
     * Precomputes cumulative time at each tempo-change boundary.
     */
    private static final class TickClock {
        private final int division;
        private final boolean smpte;
        private double smpteSecondsPerTick = 0.0;
        private final long[] segTick;
        private final double[] segTempo;   // micros per quarter
        private final double[] segTime;    // seconds at segment start

        TickClock(int division, List<long[]> tempoEvents) {
            this.division = division;
            this.smpte = (division & 0x8000) != 0;
            if (smpte) {
                int fpsByte = 256 - ((division >> 8) & 0xFF); // e.g. 0xE7 -> -25 -> 25
                int subframes = division & 0xFF;
                if (fpsByte <= 0) {
                    fpsByte = 25;
                }
                if (subframes <= 0) {
                    subframes = 1;
                }
                smpteSecondsPerTick = 1.0 / ((double) fpsByte * subframes);
                this.segTick = new long[0];
                this.segTempo = new double[0];
                this.segTime = new double[0];
                return;
            }
            Collections.sort(tempoEvents, (a, b) -> Long.compare(a[0], b[0]));
            // Merge duplicates at the same tick: keep the last one.
            List<long[]> merged = new ArrayList<>();
            for (long[] e : tempoEvents) {
                if (!merged.isEmpty() && merged.get(merged.size() - 1)[0] == e[0]) {
                    merged.set(merged.size() - 1, e);
                } else {
                    merged.add(e);
                }
            }
            int n = merged.size();
            segTick = new long[n];
            segTempo = new double[n];
            segTime = new double[n];
            double time = 0.0;
            for (int i = 0; i < n; i++) {
                segTick[i] = merged.get(i)[0];
                segTempo[i] = merged.get(i)[1];
                if (i > 0) {
                    long dt = segTick[i] - segTick[i - 1];
                    time += dt * (segTempo[i - 1] / 1_000_000.0) / division;
                }
                segTime[i] = time;
            }
        }

        double secondsOf(long tick) {
            if (smpte) {
                return tick * smpteSecondsPerTick;
            }
            int lo = 0;
            int hi = segTick.length - 1;
            int seg = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (segTick[mid] <= tick) {
                    seg = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return segTime[seg]
                    + (tick - segTick[seg]) * (segTempo[seg] / 1_000_000.0) / division;
        }
    }

    // ---------------- low-level reader ----------------

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - pos;
        }

        int readByte() throws IOException {
            if (pos >= data.length) {
                throw new IOException("Unexpected end of MIDI data");
            }
            return data[pos++];
        }

        byte[] readBytes(int n) throws IOException {
            if (n < 0 || pos + n > data.length) {
                throw new IOException("Unexpected end of MIDI data");
            }
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }

        void skip(long n) throws IOException {
            long amount = Math.max(0L, n);
            if (amount > remaining()) {
                throw new IOException("Unexpected end of MIDI data");
            }
            pos += (int) amount;
        }

        int readU16() throws IOException {
            int hi = readByte() & 0xFF;
            int lo = readByte() & 0xFF;
            return (hi << 8) | lo;
        }

        int readU32() throws IOException {
            int b0 = readByte() & 0xFF;
            int b1 = readByte() & 0xFF;
            int b2 = readByte() & 0xFF;
            int b3 = readByte() & 0xFF;
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        }

        int readVarLen() throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int b = readByte() & 0xFF;
                value = (value << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            return value;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FILE_BYTES) {
                throw new IOException("This MIDI file is too large");
            }
        }
        return out.toByteArray();
    }
}
