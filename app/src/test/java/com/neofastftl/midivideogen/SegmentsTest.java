package com.neofastftl.midivideogen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SegmentsTest {

    private static MidiFile.Note note(double start, double end) {
        return new MidiFile.Note(start, end, 60, 100);
    }

    @Test
    public void splitsTimelineByNoteStart() {
        List<MidiFile.Note> notes = new ArrayList<>();
        notes.add(note(0.0, 0.3));
        notes.add(note(0.5, 0.8));
        notes.add(note(1.0, 2.0));

        int[] frames = Segments.frames(notes, 30.0, 1.5);

        assertEquals(3, frames.length);
        assertEquals(15, frames[0]);   // 0.5 s * 30
        assertEquals(15, frames[1]);   // 0.5 s * 30
        assertEquals(45, frames[2]);   // (2.0 - 1.0) * 1.5 * 30
    }

    @Test
    public void overlappingNotesStillGetOneFrame() {
        List<MidiFile.Note> notes = new ArrayList<>();
        notes.add(note(1.0, 2.0));
        notes.add(note(1.0, 2.0));
        notes.add(note(1.0, 2.0));

        int[] frames = Segments.frames(notes, 30.0, 1.0);

        assertEquals(1, frames[0]);
        assertEquals(1, frames[1]);
        assertEquals(30, frames[2]);
    }

    @Test
    public void capsRunawaySegments() {
        List<MidiFile.Note> notes = new ArrayList<>();
        notes.add(note(0.0, 60.0));
        notes.add(note(3600.0, 3601.0));

        int[] frames = Segments.frames(notes, 30.0, 1.0);

        assertEquals(Segments.MAX_SEGMENT_FRAMES, frames[0]);
        assertEquals(30, frames[1]);
    }

    @Test
    public void fallsBackToThirtyFps() {
        List<MidiFile.Note> notes = new ArrayList<>();
        notes.add(note(0.0, 0.0));
        notes.add(note(1.0, 1.0));

        int[] frames = Segments.frames(notes, 0.0, 1.0);

        assertEquals(30, frames[0]);
        assertEquals(1, frames[1]);
    }

    @Test
    public void emptyInputGivesEmptyPlan() {
        assertEquals(0, Segments.frames(null, 30.0, 1.0).length);
        assertEquals(0, Segments.frames(new ArrayList<>(), 30.0, 1.0).length);
    }

    /** The accumulator must not drift: 7 gaps of 1/7 s at 30 fps are 30 frames. */
    @Test
    public void accumulatorAvoidsDrift() {
        List<MidiFile.Note> notes = new ArrayList<>();
        for (int i = 0; i <= 7; i++) {
            notes.add(note(i / 7.0, i / 7.0 + 0.1));
        }
        int[] frames = Segments.frames(notes, 30.0, 1.0);
        int total = 0;
        for (int i = 0; i < 7; i++) {
            total += frames[i];
        }
        assertEquals(30, total);
    }
}
