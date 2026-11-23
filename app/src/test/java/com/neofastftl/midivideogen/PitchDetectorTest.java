package com.neofastftl.midivideogen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PitchDetectorTest {

    private static short[] sine(double hz, int sampleRate, double seconds, double amplitude) {
        int n = (int) (sampleRate * seconds);
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            pcm[i] = (short) (amplitude * Math.sin(2 * Math.PI * hz * i / sampleRate));
        }
        return pcm;
    }

    @Test
    public void detectsConcertA() {
        int midi = PitchDetector.detectMidi(sine(440, 44100, 0.5, 8000), 44100);
        assertEquals(69, midi);
    }

    @Test
    public void detectsLowerOctave() {
        int midi = PitchDetector.detectMidi(sine(220, 44100, 0.5, 8000), 44100);
        assertEquals(57, midi);
    }

    @Test
    public void silenceIsUndetectable() {
        assertEquals(-1, PitchDetector.detectMidi(new short[44100], 44100));
    }

    @Test
    public void handlesDegenerateInput() {
        assertEquals(-1, PitchDetector.detectMidi(null, 44100));
        assertEquals(-1, PitchDetector.detectMidi(new short[10], 44100));
        assertEquals(-1, PitchDetector.detectMidi(new short[44100], 0));
    }

    @Test
    public void resultIsAlwaysAValidMidiNote() {
        int midi = PitchDetector.detectMidi(sine(80, 44100, 0.4, 8000), 44100);
        assertTrue(midi == -1 || (midi >= 0 && midi <= 127));
    }
}
