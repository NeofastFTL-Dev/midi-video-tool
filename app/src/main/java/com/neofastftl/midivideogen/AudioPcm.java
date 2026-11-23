package com.neofastftl.midivideogen;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;

/** Decodes a media file's audio track to a mono 16-bit PCM mixdown. */
final class AudioPcm {

    /** Give up after this many consecutive empty dequeue rounds. */
    private static final int MAX_IDLE_ROUNDS = 50;

    /** Mixdown result: mono samples at {@link #sampleRate}. */
    static final class Mono {
        final short[] pcm;
        final int sampleRate;

        Mono(short[] pcm, int sampleRate) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
        }
    }

    private AudioPcm() {
    }

    /**
     * Decodes up to {@code maxSeconds} of audio (starting at 0) into mono
     * PCM. Returns null when the file has no audio track.
     */
    static Mono decodeMono(Context context, Uri uri, double maxSeconds) throws Exception {
        MediaExtractor ax = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ax.setDataSource(context, uri, null);
            int audioTrack = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ax.getTrackCount(); i++) {
                MediaFormat f = ax.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    fmt = f;
                    break;
                }
            }
            if (audioTrack < 0 || fmt == null) {
                return null;
            }
            ax.selectTrack(audioTrack);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                return null;
            }
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            int channels = intOr(fmt, MediaFormat.KEY_CHANNEL_COUNT, 2);
            int sampleRate = intOr(fmt, MediaFormat.KEY_SAMPLE_RATE, 44100);
            if (channels < 1) {
                channels = 1;
            }
            long maxFrames = (long) (Math.max(0.1, maxSeconds) * Math.max(8000, sampleRate));

            ArrayList<short[]> blocks = new ArrayList<>();
            long totalFrames = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            int idleRounds = 0;

            while (totalFrames < maxFrames) {
                int inIdx = dec.dequeueInputBuffer(10_000);
                if (inIdx >= 0 && !inputEos) {
                    ByteBuffer bb = dec.getInputBuffer(inIdx);
                    int size = bb == null ? -1 : ax.readSampleData(bb, 0);
                    if (size < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        dec.queueInputBuffer(inIdx, 0, size, ax.getSampleTime(), 0);
                        ax.advance();
                    }
                }

                int outIdx = dec.dequeueOutputBuffer(info, 10_000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output can differ from the container format (mono, 48 kHz…).
                    MediaFormat out = dec.getOutputFormat();
                    channels = Math.max(1, intOr(out, MediaFormat.KEY_CHANNEL_COUNT, channels));
                    sampleRate = Math.max(8000, intOr(out, MediaFormat.KEY_SAMPLE_RATE, sampleRate));
                    maxFrames = (long) (Math.max(0.1, maxSeconds) * sampleRate);
                    continue;
                }
                if (outIdx >= 0) {
                    idleRounds = 0;
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    try {
                        totalFrames += appendBlock(dec, outIdx, info, channels,
                                maxFrames - totalFrames, blocks);
                    } finally {
                        dec.releaseOutputBuffer(outIdx, false);
                    }
                    if (eos) {
                        break;
                    }
                } else if (inputEos) {
                    // Drained: some decoders never emit EOS. Stop instead of
                    // spinning forever (the old code could hang here).
                    if (++idleRounds > MAX_IDLE_ROUNDS) {
                        break;
                    }
                }
            }

            if (totalFrames <= 0) {
                return null;
            }
            int length = (int) Math.min(Integer.MAX_VALUE - 8, totalFrames);
            short[] pcm = new short[length];
            int pos = 0;
            for (short[] b : blocks) {
                int n = Math.min(b.length, pcm.length - pos);
                if (n <= 0) {
                    break;
                }
                System.arraycopy(b, 0, pcm, pos, n);
                pos += n;
            }
            return new Mono(pcm, sampleRate);
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                try {
                    dec.release();
                } catch (Exception ignored) {
                }
            }
            try {
                ax.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Mixes one decoder output buffer down to mono and appends it. */
    private static int appendBlock(MediaCodec dec, int outIdx, MediaCodec.BufferInfo info,
                                   int channels, long room, ArrayList<short[]> blocks) {
        if (room <= 0 || info.size < 2 * channels) {
            return 0;
        }
        ByteBuffer pcmBuf = dec.getOutputBuffer(outIdx);
        if (pcmBuf == null) {
            return 0;
        }
        pcmBuf.order(ByteOrder.LITTLE_ENDIAN);
        pcmBuf.position(info.offset);
        pcmBuf.limit(info.offset + info.size);
        ShortBuffer sb = pcmBuf.asShortBuffer();
        int frames = Math.min(sb.remaining() / channels, (int) Math.min(room, Integer.MAX_VALUE));
        if (frames <= 0) {
            return 0;
        }
        short[] inter = new short[frames * channels];
        sb.get(inter);
        short[] mono = new short[frames];
        if (channels == 1) {
            System.arraycopy(inter, 0, mono, 0, frames);
        } else {
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += inter[i * channels + c];
                }
                mono[i] = (short) (sum / channels);
            }
        }
        blocks.add(mono);
        return frames;
    }

    private static int intOr(MediaFormat fmt, String key, int fallback) {
        try {
            return fmt.containsKey(key) ? fmt.getInteger(key) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
