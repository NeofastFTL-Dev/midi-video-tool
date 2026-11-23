package com.neofastftl.midivideogen;

import java.util.List;

/**
 * Precomputes how many output frames every MIDI note gets.
 *
 * <p>Note {@code i} lasts until the next note starts; the final note lasts
 * {@code (end - start) * lastNoteMultiplier}. A fractional accumulator
 * (Bresenham) spreads rounding error so the total length stays exact instead
 * of drifting by a frame per note.
 */
final class Segments {

    /** Hard cap for one segment: 2 minutes at 240 fps. */
    static final int MAX_SEGMENT_FRAMES = 240 * 120;

    private Segments() {
    }

    static int[] frames(List<MidiFile.Note> notes, double fps, double lastNoteMultiplier) {
        int total = notes == null ? 0 : notes.size();
        int[] out = new int[total];
        if (total == 0) {
            return out;
        }
        double safeFps = (fps > 0 && fps <= 240) ? fps : 30.0;
        double multiplier = lastNoteMultiplier > 0 ? lastNoteMultiplier : 1.0;
        double accum = 0.0;
        for (int i = 0; i < total; i++) {
            MidiFile.Note note = notes.get(i);
            double duration = (i < total - 1)
                    ? notes.get(i + 1).start - note.start
                    : (note.end - note.start) * multiplier;
            // Overlapping notes (chords merged from several tracks) and
            // zero-length notes still get one frame instead of disappearing.
            if (!(duration > 0)) {
                duration = 1.0 / safeFps;
            }
            double exact = duration * safeFps;
            if (exact > MAX_SEGMENT_FRAMES) {
                exact = MAX_SEGMENT_FRAMES;
            }
            int frames = (int) exact;
            accum += exact - frames;
            if (accum >= 1.0) {
                frames += 1;
                accum -= 1.0;
            }
            if (frames < 1) {
                frames = 1;
            }
            out[i] = frames;
        }
        return out;
    }
}
