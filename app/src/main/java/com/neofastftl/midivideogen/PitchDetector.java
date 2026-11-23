package com.neofastftl.midivideogen;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Estimates the dominant pitch of mono PCM with normalized autocorrelation
 * (NCCF) over ~46 ms frames — the same method validated against the
 * reference sample offline. Returns the median voiced-frame pitch as a MIDI
 * note number (69 = A440).
 */
final class PitchDetector {

    /** Below this NCCF peak a frame counts as unvoiced. */
    private static final double MIN_CONFIDENCE = 0.5;
    /** Frames quieter than this RMS (0..1 full scale) are skipped. */
    private static final double SILENCE_GATE = 0.02;

    private PitchDetector() {
    }

    /** Median voiced pitch in MIDI notes, or -1 when nothing is confident. */
    static int detectMidi(short[] pcm, int sampleRate) {
        if (pcm == null || sampleRate <= 0) {
            return -1;
        }
        int win = (int) (0.046 * sampleRate);
        if (win < 32 || pcm.length < win) {
            return -1;
        }
        int hop = win / 2;
        int lagMin = Math.max(1, sampleRate / 1000);        // up to 1000 Hz
        int lagMax = Math.min(win - 1, sampleRate / 60);    // down to 60 Hz
        if (lagMax <= lagMin) {
            return -1;
        }

        ArrayList<Float> midis = new ArrayList<>();
        for (int start = 0; start + win <= pcm.length; start += hop) {
            // RMS silence gate (matches the offline probe)
            double sum2 = 0;
            for (int i = start; i < start + win; i++) {
                sum2 += (double) pcm[i] * pcm[i];
            }
            if (Math.sqrt(sum2 / win) / 32768.0 < SILENCE_GATE) {
                continue;
            }

            double mean = 0;
            for (int i = start; i < start + win; i++) {
                mean += pcm[i];
            }
            mean /= win;
            double denom = 0;
            for (int i = start; i < start + win; i++) {
                double v = pcm[i] - mean;
                denom += v * v;
            }
            if (denom <= 0) {
                continue;
            }

            double bestR = 0;
            int bestLag = 0;
            for (int lag = lagMin; lag <= lagMax; lag++) {
                double tail = 0;
                double r = 0;
                double d = denom;
                for (int i = start + win - lag; i < start + win; i++) {
                    double a = pcm[i] - mean;
                    tail += a * a;
                }
                for (int i = start; i < start + win - lag; i++) {
                    double a = pcm[i] - mean;
                    double b = pcm[i + lag] - mean;
                    r += a * b;
                }
                d -= tail;
                if (d > 0) {
                    r = r / Math.sqrt(denom * d);
                    if (r > bestR) {
                        bestR = r;
                        bestLag = lag;
                    }
                }
            }
            if (bestR > MIN_CONFIDENCE && bestLag > 0) {
                double f0 = sampleRate / (double) bestLag;
                midis.add((float) (69 + 12 * (Math.log(f0 / 440.0) / Math.log(2))));
            }
        }
        if (midis.isEmpty()) {
            return -1;
        }
        Collections.sort(midis);
        int midi = Math.round(midis.get(midis.size() / 2));
        return Math.max(0, Math.min(127, midi));
    }
}
