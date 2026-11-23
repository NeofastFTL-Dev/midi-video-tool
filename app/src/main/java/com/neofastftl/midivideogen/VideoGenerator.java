package com.neofastftl.midivideogen;

import android.content.Context;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Renders the source video one segment per MIDI note and encodes the result
 * to MP4, mirroring the behavior of Otomad Helper (one video event + one
 * audio event per note on the timeline):
 *
 * <ul>
 *   <li>Output frame rate equals the source frame rate.</li>
 *   <li>Note i gets (note[i+1].start - note[i].start) seconds of frames; the
 *       last note gets (note.end - note.start) * multiplier.</li>
 *   <li>"Restart per note" replays the source from frame 0 for every note;
 *       otherwise the source plays through once and frames past its end repeat
 *       the last frame.</li>
 *   <li>Flip decisions are made per note; flipping mirrors the frame.</li>
 *   <li>Audio: the source audio is decoded to mono PCM once, then every output
 *       segment is filled with the source audio starting where the video
 *       segment starts, resampled by 2^((pitch - basePitch)/12) — the classic
 *       per-note pitch shift — with a short fade at both ends.</li>
 * </ul>
 *
 * <p>The video path is deterministic software: the decoder produces YUV
 * Images, and frames are written into encoder input ByteBuffers with explicit
 * output timestamps. Rotation and flip are index permutations of the Y/U/V
 * planes — no GPU, no color conversion, nothing that can silently render
 * black.
 *
 * <p>Every failure mode is reported as an {@link IOException} with a message
 * the UI can show; the render file is removed when the pipeline fails so a
 * half-written MP4 never reaches the gallery.
 */
public final class VideoGenerator {

    private static final String TAG = "VideoGenerator";

    /** Cache budget for transformed frames (restart-per-note replays them). */
    private static final long FRAME_CACHE_BYTES = 48L * 1024 * 1024;
    /** Never decode more than this much source audio. */
    private static final double AUDIO_DECODE_CAP_SECONDS = 600;
    /** Skip audio instead of risking an OutOfMemoryError for long renders. */
    private static final long AUDIO_PLAN_BUDGET_BYTES = 192L * 1024 * 1024;
    private static final int FADE_FRAMES = 128; // ~3 ms de-click
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    public interface ProgressListener {
        void onProgress(int notesDone, int notesTotal);
    }

    /** Horizontal flip policy, applied per note. */
    public enum FlipMode { NEVER, ALWAYS, ALTERNATE, RANDOM }

    private final Context context;
    private final List<MidiFile.Note> notes;
    private final Uri videoUri;
    private final FlipMode flipMode;
    private final boolean restartPerNote;
    private final double lastNoteMultiplier;
    private final int basePitch;
    private final ProgressListener progress;

    private MediaExtractor extractor;
    private MediaCodec decoder;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private MediaMuxer muxer;

    private MediaFormat srcFormat;
    private String srcMime;

    private double fps = 30.0;
    private int rotation;

    private int srcWidth;   // decoded (coded) frame size from the container
    private int srcHeight;
    private int cropLeft;   // visible area inside the coded frame
    private int cropTop;
    private int cropW;
    private int cropH;
    private int scaleDiv = 1; // nearest-neighbour downscale for huge sources
    private int outWidth;     // encoder size (rotation baked in)
    private int outHeight;

    private boolean semiPlanarInput;

    private final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
    private int videoTrack = -1;
    private int audioEncTrack = -1;
    private boolean muxerStarted;

    private boolean decoderEosQueued;
    private boolean sourceEos;
    private int lastDecodedIndex = -1;
    private long lastDecodedPts = -1;

    private long totalFramesWritten;
    private int runningSourceFrame;
    private int[] segmentFrames = new int[0];

    /** Last transformed frame per flip state, used to freeze past EOF. */
    private final byte[][] lastByFlip = new byte[2][];

    /**
     * LRU of fully transformed frames: restart-per-note replays the same
     * source frames for every note, so after warm-up no decode or YUV
     * permutation happens at all. Keyed by sourceFrame*2 + flip.
     */
    private LinkedHashMap<Integer, byte[]> frameCache;
    private int frameCacheFrames;

    // Audio (mono PCM mixdown of the source, resampled per segment).
    private List<AudioChunk> audioChunks;
    private AudioPcm.Mono srcMono;
    private int srcRate;

    private static final class AudioChunk {
        final short[] pcm;
        final long outPtsUs;

        AudioChunk(short[] pcm, long outPtsUs) {
            this.pcm = pcm;
            this.outPtsUs = outPtsUs;
        }
    }

    private static final class EncoderChoice {
        final String codecName;
        final int colorFormat;
        final boolean semiPlanar;
        final int score;

        EncoderChoice(String codecName, int colorFormat, boolean semiPlanar, int score) {
            this.codecName = codecName;
            this.colorFormat = colorFormat;
            this.semiPlanar = semiPlanar;
            this.score = score;
        }
    }

    private VideoGenerator(Builder b) {
        this.context = b.context;
        this.notes = b.notes;
        this.videoUri = b.videoUri;
        this.flipMode = b.flipMode;
        this.restartPerNote = b.restartPerNote;
        this.lastNoteMultiplier = b.lastNoteMultiplier;
        this.basePitch = b.basePitch;
        this.progress = b.progress;
    }

    public static Builder with(Context context, Uri videoUri, List<MidiFile.Note> notes) {
        return new Builder(context, videoUri, notes);
    }

    public static final class Builder {
        private final Context context;
        private final Uri videoUri;
        private final List<MidiFile.Note> notes;
        private FlipMode flipMode = FlipMode.ALTERNATE;
        private boolean restartPerNote = true;
        private double lastNoteMultiplier = 1.5;
        private int basePitch = 72; // C5, Otomad Helper's default
        private ProgressListener progress;

        private Builder(Context c, Uri v, List<MidiFile.Note> n) {
            context = c.getApplicationContext();
            videoUri = v;
            notes = n;
        }

        public Builder flipMode(FlipMode m) { flipMode = m; return this; }
        public Builder restartPerNote(boolean b) { restartPerNote = b; return this; }
        public Builder lastNoteMultiplier(double m) { lastNoteMultiplier = m; return this; }
        public Builder basePitch(int p) { basePitch = p; return this; }
        public Builder progress(ProgressListener l) { progress = l; return this; }

        public VideoGenerator build() { return new VideoGenerator(this); }
    }

    /** Runs the whole render. Returns the generated MP4 file in app cache. */
    public File render() throws IOException {
        if (notes == null || notes.isEmpty()) {
            throw new IOException("No notes to render");
        }
        if (videoUri == null) {
            throw new IOException("No source video selected");
        }
        File output = new File(context.getCacheDir(),
                "midi_output_" + System.currentTimeMillis() + ".mp4");
        try {
            openSource();
            probeGeometry();

            segmentFrames = Segments.frames(notes, fps, lastNoteMultiplier);
            if (segmentFrames.length == 0) {
                throw new IOException("No notes to render");
            }

            createEncoder();
            createMuxer(output);
            prepareAudio();

            // Fresh decoder for the render pass (the probe above used one).
            recreateDecoder();

            Random rng = new Random();
            for (int i = 0; i < segmentFrames.length; i++) {
                boolean flip;
                switch (flipMode) {
                    case ALWAYS:
                        flip = true;
                        break;
                    case NEVER:
                        flip = false;
                        break;
                    case RANDOM:
                        flip = rng.nextBoolean();
                        break;
                    default:
                        flip = (i % 2 == 1);
                        break;
                }
                renderSegment(segmentFrames[i], flip);
                if (progress != null) {
                    progress.onProgress(i + 1, segmentFrames.length);
                }
            }

            finishVideo();
            finishAudio();
            stopMuxer();

            if (!output.exists() || output.length() <= 0) {
                throw new IOException("The render produced an empty file");
            }
            return output;
        } catch (IOException e) {
            deleteQuietly(output);
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(output);
            throw new IOException(describe(e), e);
        } finally {
            releaseAll();
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName() : message;
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- source / geometry ----------------

    private void openSource() throws IOException {
        releaseExtractor();
        extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, videoUri, null);
        } catch (Exception e) {
            releaseExtractor();
            throw new IOException("This video could not be opened (" + describe(e) + ")", e);
        }
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0 || format == null) {
            releaseExtractor();
            throw new IOException("No video track found in the source file");
        }
        extractor.selectTrack(track);
        srcFormat = format;
        srcMime = format.getString(MediaFormat.KEY_MIME);
        if (srcMime == null) {
            throw new IOException("The source video has no MIME type");
        }
        srcWidth = intOr(format, MediaFormat.KEY_WIDTH, 0);
        srcHeight = intOr(format, MediaFormat.KEY_HEIGHT, 0);
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IOException("The source video reports an invalid frame size");
        }
        rotation = normalizeRotation(intOr(format, MediaFormat.KEY_ROTATION, 0));
        int rate = intOr(format, MediaFormat.KEY_FRAME_RATE, 0);
        fps = (rate > 0 && rate <= 240) ? rate : 30.0;
    }

    private static int normalizeRotation(int value) {
        int r = ((value % 360) + 360) % 360;
        if (r == 90 || r == 180 || r == 270) {
            return r;
        }
        return 0;
    }

    /**
     * Decodes the first frame to learn the real visible (cropped) area before
     * the encoder is configured. Coded frames are often padded (1088 rows for
     * 1080p), and encoding the padding produced stretched edge pixels.
     */
    private void probeGeometry() throws IOException {
        int visibleW = srcWidth;
        int visibleH = srcHeight;
        int left = 0;
        int top = 0;
        recreateDecoder();
        Image probe = null;
        try {
            probe = decodeFrame(0);
            if (probe != null) {
                Rect crop = probe.getCropRect();
                if (crop != null && crop.width() > 0 && crop.height() > 0) {
                    visibleW = crop.width();
                    visibleH = crop.height();
                    left = crop.left;
                    top = crop.top;
                }
            }
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Exception ignored) {
                }
            }
            releaseDecoder();
        }

        if (left < 0) {
            left = 0;
        }
        if (top < 0) {
            top = 0;
        }
        if (left + visibleW > srcWidth) {
            left = 0;
            visibleW = srcWidth;
        }
        if (top + visibleH > srcHeight) {
            top = 0;
            visibleH = srcHeight;
        }
        cropLeft = left;
        cropTop = top;
        cropW = Math.max(2, visibleW & ~1);
        cropH = Math.max(2, visibleH & ~1);
    }

    // ---------------- encoder / muxer ----------------

    private void createEncoder() throws IOException {
        boolean rotated = rotation == 90 || rotation == 270;
        EncoderChoice choice = null;
        int chosenW = 0;
        int chosenH = 0;
        int chosenDiv = 1;
        // Large (4K) sources are downscaled until the device's encoder accepts
        // the size instead of failing with an opaque configure error.
        for (int div = 1; div <= 4; div *= 2) {
            int w = Math.max(2, (cropW / div) & ~1);
            int h = Math.max(2, (cropH / div) & ~1);
            if (w < 16 || h < 16) {
                break;
            }
            EncoderChoice candidate = selectEncoder(MediaFormat.MIMETYPE_VIDEO_AVC,
                    rotated ? h : w, rotated ? w : h);
            if (candidate != null) {
                choice = candidate;
                chosenW = w;
                chosenH = h;
                chosenDiv = div;
                break;
            }
        }
        if (choice == null) {
            throw new IOException("This device has no H.264 encoder that accepts "
                    + cropW + "x" + cropH + " frames");
        }
        scaleDiv = chosenDiv;
        outWidth = rotated ? chosenH : chosenW;
        outHeight = rotated ? chosenW : chosenH;
        semiPlanarInput = choice.semiPlanar;

        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                outWidth, outHeight);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat);
        long bitrate = clamp((long) (outWidth * (long) outHeight * fps * 0.18),
                1_500_000L, 25_000_000L);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE,
                (int) Math.max(1, Math.min(240, Math.round(fps))));
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);

        try {
            videoEncoder = MediaCodec.createByCodecName(choice.codecName);
            videoEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoEncoder.start();
        } catch (Exception e) {
            releaseVideoEncoder();
            throw new IOException("The video encoder could not be started (" + describe(e) + ")", e);
        }

        int frameBytes = frameSize();
        int maxFrames = Math.max(2, (int) Math.min(Integer.MAX_VALUE - 8,
                FRAME_CACHE_BYTES / Math.max(1, frameBytes)));
        frameCacheFrames = restartPerNote ? maxFrames : Math.max(2, Math.min(8, maxFrames));
        frameCache = new LinkedHashMap<>(Math.max(4, frameCacheFrames * 2), 0.75f, false);
    }

    /** Picks an AVC encoder that takes YUV420 buffers, preferring planar. */
    private static EncoderChoice selectEncoder(String mime, int width, int height) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        EncoderChoice best = null;
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (ci == null || !ci.isEncoder()) {
                continue;
            }
            String name = ci.getName();
            if (name == null) {
                continue;
            }
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = ci.getCapabilitiesForType(mime);
            } catch (Exception e) {
                continue;
            }
            int color = 0;
            boolean semi = false;
            int colorScore = 0;
            for (int c : caps.colorFormats) {
                if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    color = c;
                    semi = false;
                    colorScore = 3;
                    break;
                }
                if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                        && colorScore < 2) {
                    color = c;
                    semi = true;
                    colorScore = 2;
                } else if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        && colorScore == 0) {
                    color = c;
                    semi = false;
                    colorScore = 1;
                }
            }
            if (color == 0) {
                continue;
            }
            boolean sizeOk = true;
            try {
                sizeOk = caps.getVideoCapabilities().isSizeSupported(width, height);
            } catch (Exception ignored) {
                // Unknown capability: let the encoder decide at configure time.
            }
            if (!sizeOk) {
                continue;
            }
            int score = colorScore * 10 + (ci.isHardwareAccelerated() ? 5 : 0);
            if (best == null || score > best.score) {
                best = new EncoderChoice(name, color, semi, score);
            }
        }
        return best;
    }

    private void createMuxer(File output) throws IOException {
        deleteQuietly(output);
        try {
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Exception e) {
            throw new IOException("Could not create the output file (" + describe(e) + ")", e);
        }
    }

    private int frameSize() {
        return outWidth * outHeight * 3 / 2;
    }

    private long ptsOf(long frameIndex) {
        return (long) (frameIndex * (1_000_000.0 / Math.max(1.0, fps)));
    }

    // ---------------- per-note rendering ----------------

    private void renderSegment(int frames, boolean flip) throws IOException {
        for (int f = 0; f < frames; f++) {
            int sourceFrame = restartPerNote ? f : runningSourceFrame + f;
            writeFrame(sourceFrame, flip);
        }
        if (!restartPerNote) {
            runningSourceFrame += frames;
        }
    }

    /** Produces one output frame from the source frame at {@code index}. */
    private void writeFrame(int index, boolean flip) throws IOException {
        int flipIndex = flip ? 1 : 0;
        int key = index * 2 + flipIndex;
        byte[] yuv = frameCache == null ? null : frameCache.get(key);
        if (yuv == null) {
            Image img = null;
            try {
                img = decodeFrame(index);
                if (img != null) {
                    byte[] frame = new byte[frameSize()];
                    writeYuv(frame, img, flip);
                    yuv = frame;
                    lastByFlip[flipIndex] = frame;
                    putFrameCache(key, frame);
                } else {
                    // Past end of stream: freeze on the last frame of this
                    // flip state, like the original tool freezing the video.
                    yuv = lastByFlip[flipIndex];
                }
            } finally {
                if (img != null) {
                    try {
                        img.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (yuv == null) {
            yuv = new byte[frameSize()]; // nothing decoded yet: black frame
        }
        enqueueFrame(yuv);
    }

    /** Inserts into the LRU, evicting the eldest entry beyond capacity. */
    private void putFrameCache(int key, byte[] yuv) {
        if (frameCache == null) {
            return;
        }
        frameCache.put(key, yuv);
        while (frameCache.size() > frameCacheFrames) {
            Integer eldest = frameCache.keySet().iterator().next();
            if (eldest == key) {
                break; // never evict the entry we just inserted
            }
            frameCache.remove(eldest);
        }
    }

    private void enqueueFrame(byte[] yuv) throws IOException {
        if (videoEncoder == null) {
            throw new IOException("The video encoder is not running");
        }
        int inIdx = videoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (inIdx < 0) {
            drainVideo(false);
            inIdx = videoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US * 5);
        }
        if (inIdx < 0) {
            throw new IOException("The video encoder stopped accepting frames");
        }
        int size = frameSize();
        ByteBuffer buf = videoEncoder.getInputBuffer(inIdx);
        if (buf == null || buf.capacity() < size) {
            throw new IOException("The video encoder buffer is too small for "
                    + outWidth + "x" + outHeight);
        }
        buf.clear();
        buf.put(yuv, 0, size);
        videoEncoder.queueInputBuffer(inIdx, 0, size, ptsOf(totalFramesWritten), 0);
        totalFramesWritten++;
    }

    // ---------------- decoding ----------------

    private void recreateDecoder() throws IOException {
        releaseDecoder();
        if (extractor == null || srcFormat == null) {
            throw new IOException("The source video is no longer open");
        }
        if (srcMime == null) {
            throw new IOException("The source video has no MIME type");
        }
        try {
            decoder = MediaCodec.createDecoderByType(srcMime);
            decoder.configure(srcFormat, null, null, 0);
            decoder.start();
        } catch (Exception e) {
            releaseDecoder();
            throw new IOException("This device cannot decode the source video ("
                    + describe(e) + ")", e);
        }
        decoderEosQueued = false;
        sourceEos = false;
        lastDecodedIndex = -1;
        lastDecodedPts = -1;
    }

    /**
     * Repositions the source. The decoder is recreated instead of flushed:
     * a flush leaves output buffers queued from the previous position, which
     * made restart-per-note replay the wrong frame.
     */
    private void seekTo(long targetUs) throws IOException {
        recreateDecoder();
        try {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        } catch (Exception e) {
            throw new IOException("The source video could not be scanned (" + describe(e) + ")", e);
        }
    }

    /**
     * Decodes source frame {@code index} and returns its YUV Image (caller
     * closes it, which returns the buffer to the codec), or null past end of
     * stream.
     */
    private Image decodeFrame(int index) throws IOException {
        if (sourceEos) {
            return null;
        }
        long targetUs = (long) (index * (1_000_000.0 / Math.max(1.0, fps)));
        if (lastDecodedIndex < 0 || index != lastDecodedIndex + 1) {
            seekTo(targetUs);
        }

        int starved = 0;
        while (true) {
            int outIdx;
            try {
                outIdx = decoder.dequeueOutputBuffer(decInfo, DEQUEUE_TIMEOUT_US);
            } catch (IllegalStateException e) {
                throw new IOException("The video decoder stopped unexpectedly", e);
            }
            if (outIdx >= 0) {
                boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean looksLikeFrame = decInfo.size > 0 || decInfo.presentationTimeUs > 0;
                if (looksLikeFrame && decInfo.presentationTimeUs + 5000 >= targetUs) {
                    Image img;
                    try {
                        img = decoder.getOutputImage(outIdx);
                    } catch (IllegalStateException e) {
                        decoder.releaseOutputBuffer(outIdx, false);
                        throw new IOException("The video decoder returned an unreadable frame", e);
                    }
                    if (img != null) {
                        lastDecodedIndex = index;
                        lastDecodedPts = decInfo.presentationTimeUs;
                        if (eos) {
                            sourceEos = true;
                        }
                        return img;
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                } else {
                    decoder.releaseOutputBuffer(outIdx, false);
                }
                if (eos) {
                    sourceEos = true;
                    return null;
                }
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                feedInput();
                if (decoderEosQueued && ++starved > 60) {
                    sourceEos = true;
                    return null; // drained past EOF: freeze on the last frame
                }
            }
            // INFO_OUTPUT_FORMAT_CHANGED: nothing to do for Image output.
        }
    }

    /** Queues the next source sample. csd reaches the decoder via configure(). */
    private void feedInput() {
        if (decoderEosQueued || extractor == null) {
            return;
        }
        int inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (inIdx < 0) {
            return;
        }
        ByteBuffer bb = decoder.getInputBuffer(inIdx);
        if (bb == null) {
            return;
        }
        int size = extractor.readSampleData(bb, 0);
        if (size < 0) {
            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            decoderEosQueued = true;
        } else {
            long pts = extractor.getSampleTime();
            extractor.advance();
            decoder.queueInputBuffer(inIdx, 0, size, pts, 0);
        }
    }

    // ---------------- transform ----------------

    /**
     * Writes the decoded Image into {@code out} at the encoder's layout
     * (planar I420 or semi-planar NV12), applying rotation and horizontal
     * flip.
     *
     * <p>Mapping: an output pixel (display orientation) maps back to the
     * decoded frame by inverting the clockwise rotation the player would
     * apply:
     * <pre>
     *   R=90:  (x,y) -&gt; (y,          cropH-1-x)
     *   R=180: (x,y) -&gt; (cropW-1-x,  cropH-1-y)
     *   R=270: (x,y) -&gt; (cropW-1-y,  x)
     * </pre>
     * The horizontal flip mirrors x in display space before the inverse
     * rotation.
     */
    private void writeYuv(byte[] out, Image img, boolean flip) {
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length < 3) {
            throw new IllegalStateException("The decoder returned an unsupported frame layout");
        }
        Image.Plane yP = planes[0];
        Image.Plane uP = planes[1];
        Image.Plane vP = planes[2];
        ByteBuffer yb = yP.getBuffer();
        ByteBuffer ub = uP.getBuffer();
        ByteBuffer vb = vP.getBuffer();
        int yRow = yP.getRowStride();
        int yPix = yP.getPixelStride();
        int uRow = uP.getRowStride();
        int uPix = uP.getPixelStride();
        int vRow = vP.getRowStride();
        int vPix = vP.getPixelStride();

        int w = outWidth;
        int h = outHeight;
        int ySize = w * h;
        int cW = w / 2;
        int cH = h / 2;
        int cSize = cW * cH;
        int step = scaleDiv;
        int maxX = cropW - 1;
        int maxY = cropH - 1;

        for (int oy = 0; oy < h; oy++) {
            int sy = oy * step;
            int rowBase = oy * w;
            for (int ox = 0; ox < w; ox++) {
                int sx = ox * step;
                int x = flip ? (maxX - sx) : sx;
                int y = sy;
                int dx;
                int dy;
                switch (rotation) {
                    case 90:
                        dx = y;
                        dy = maxY - x;
                        break;
                    case 180:
                        dx = maxX - x;
                        dy = maxY - y;
                        break;
                    case 270:
                        dx = maxX - y;
                        dy = x;
                        break;
                    default:
                        dx = x;
                        dy = y;
                        break;
                }
                int px = clampInt(cropLeft + dx, cropLeft, cropLeft + maxX);
                int py = clampInt(cropTop + dy, cropTop, cropTop + maxY);
                out[rowBase + ox] = yb.get(py * yRow + px * yPix);
            }
        }

        for (int cy = 0; cy < cH; cy++) {
            int oy = cy * 2;
            int sy = oy * step;
            for (int cx = 0; cx < cW; cx++) {
                int ox = cx * 2;
                int sx = ox * step;
                int x = flip ? (maxX - sx) : sx;
                int y = sy;
                int dx;
                int dy;
                switch (rotation) {
                    case 90:
                        dx = y;
                        dy = maxY - x;
                        break;
                    case 180:
                        dx = maxX - x;
                        dy = maxY - y;
                        break;
                    case 270:
                        dx = maxX - y;
                        dy = x;
                        break;
                    default:
                        dx = x;
                        dy = y;
                        break;
                }
                int px = clampInt((cropLeft + dx) >> 1, cropLeft >> 1,
                        (cropLeft + maxX) >> 1);
                int py = clampInt((cropTop + dy) >> 1, cropTop >> 1,
                        (cropTop + maxY) >> 1);
                byte u = ub.get(py * uRow + px * uPix);
                byte v = vb.get(py * vRow + px * vPix);
                if (semiPlanarInput) {
                    int base = ySize + (cy * cW + cx) * 2;
                    out[base] = u;
                    out[base + 1] = v;
                } else {
                    out[ySize + cy * cW + cx] = u;
                    out[ySize + cSize + cy * cW + cx] = v;
                }
            }
        }
    }

    // ---------------- encoder drain / finish ----------------

    private void drainVideo(boolean untilEos) throws IOException {
        if (videoEncoder == null) {
            return;
        }
        int idle = 0;
        while (true) {
            int idx;
            try {
                idx = videoEncoder.dequeueOutputBuffer(encInfo, untilEos ? DEQUEUE_TIMEOUT_US : 0);
            } catch (IllegalStateException e) {
                if (untilEos) {
                    throw new IOException("The video encoder stopped unexpectedly", e);
                }
                return;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) {
                    return;
                }
                if (++idle > 600) {
                    // Encoder never drained: keep the file usable instead of
                    // hanging the render forever.
                    Log.w(TAG, "video encoder did not drain fully");
                    return;
                }
                continue;
            }
            idle = 0;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addVideoTrack();
                continue;
            }
            if (idx >= 0) {
                boolean eos;
                try {
                    eos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    ByteBuffer out = videoEncoder.getOutputBuffer(idx);
                    if (out != null && encInfo.size > 0 && muxerStarted) {
                        out.position(encInfo.offset);
                        out.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(videoTrack, out, encInfo);
                    }
                } finally {
                    videoEncoder.releaseOutputBuffer(idx, false);
                }
                if (eos) {
                    return;
                }
            }
        }
    }

    private void addVideoTrack() throws IOException {
        if (muxerStarted) {
            throw new IOException("The video encoder changed format mid-stream");
        }
        try {
            videoTrack = muxer.addTrack(videoEncoder.getOutputFormat());
        } catch (Exception e) {
            throw new IOException("The MP4 muxer rejected the video track", e);
        }
        startMuxerIfReady();
    }

    /** MediaMuxer.start() needs every track first, so this waits for audio. */
    private void startMuxerIfReady() throws IOException {
        if (muxerStarted || muxer == null || videoTrack < 0) {
            return;
        }
        if (audioEncoder != null && audioEncTrack < 0) {
            return;
        }
        try {
            muxer.start();
            muxerStarted = true;
        } catch (Exception e) {
            throw new IOException("The MP4 muxer could not start (" + describe(e) + ")", e);
        }
    }

    private void finishVideo() throws IOException {
        if (videoEncoder == null) {
            return;
        }
        boolean queued = false;
        for (int i = 0; i < 200 && !queued; i++) {
            int inIdx = videoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inIdx >= 0) {
                // ByteBuffer input mode: an explicit EOS buffer. Calling
                // signalEndOfInputStream() here throws on many devices.
                videoEncoder.queueInputBuffer(inIdx, 0, 0, ptsOf(totalFramesWritten),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                queued = true;
            } else {
                drainVideo(false);
            }
        }
        drainVideo(true);
    }

    private void stopMuxer() throws IOException {
        if (muxer == null || !muxerStarted) {
            return;
        }
        try {
            muxer.stop();
            muxerStarted = false;
        } catch (Exception e) {
            throw new IOException("The MP4 could not be finalized (" + describe(e) + ")", e);
        }
    }

    // ============ audio: decode source -> resample per note -> AAC ============

    /**
     * Decodes the source audio to a mono PCM mixdown once, then builds one
     * output chunk per note segment. Any audio problem degrades to a
     * video-only render instead of failing the whole job.
     */
    private void prepareAudio() {
        audioChunks = null;
        audioEncTrack = -1;
        try {
            srcMono = AudioPcm.decodeMono(context, videoUri, AUDIO_DECODE_CAP_SECONDS);
            if (srcMono == null || srcMono.pcm.length == 0) {
                srcMono = null;
                return;
            }
            srcRate = srcMono.sampleRate > 0 ? srcMono.sampleRate : 44100;

            long totalFrames = 0;
            for (int f : segmentFrames) {
                totalFrames += f;
            }
            double outSeconds = totalFrames / Math.max(1.0, fps);
            long estimatedBytes = (long) (outSeconds * srcRate * 2L);
            if (estimatedBytes > AUDIO_PLAN_BUDGET_BYTES) {
                Log.w(TAG, "audio plan would need " + estimatedBytes
                        + " bytes; rendering video only");
                srcMono = null;
                return;
            }

            List<AudioChunk> chunks = new ArrayList<>(notes.size());
            double outSec = 0.0;
            for (int i = 0; i < notes.size(); i++) {
                double segDur = segmentFrames[i] / Math.max(1.0, fps);
                double ratio = Math.pow(2.0, (notes.get(i).pitch - basePitch) / 12.0);
                double srcStart = restartPerNote ? 0.0 : notes.get(i).start;
                chunks.add(resampleSegment(srcStart, segDur, ratio, outSec));
                outSec += segDur;
            }
            audioChunks = chunks;
            prepareAudioEncoder();
        } catch (Exception e) {
            Log.w(TAG, "audio disabled: " + describe(e), e);
            releaseAudioEncoder();
            audioChunks = null;
            srcMono = null;
            audioEncTrack = -1;
        }
    }

    /** Linear-interpolated resample of [srcStart, srcStart + segDur*ratio) of
     *  the source mixdown into segDur seconds of output, with fades. */
    private AudioChunk resampleSegment(double srcStart, double segDur,
                                       double ratio, double outStart) {
        int outFrames = Math.max(1, (int) Math.round(segDur * srcRate));
        short[] pcm = new short[outFrames];
        double pos = srcStart * srcRate;
        short[] src = srcMono == null ? new short[0] : srcMono.pcm;
        int len = src.length;
        int fadeIn = Math.min(FADE_FRAMES, outFrames / 2);
        int fadeOut = fadeIn;
        for (int k = 0; k < outFrames; k++) {
            int i0 = (int) pos;
            float frac = (float) (pos - i0);
            float s0 = (i0 >= 0 && i0 < len) ? src[i0] : 0f;
            float s1 = (i0 + 1 >= 0 && i0 + 1 < len) ? src[i0 + 1] : 0f;
            float v = s0 + (s1 - s0) * frac;
            if (fadeIn > 0 && k < fadeIn) {
                v *= k / (float) fadeIn;
            } else if (fadeOut > 0 && k > outFrames - 1 - fadeOut) {
                v *= (outFrames - 1 - k) / (float) fadeOut;
            }
            pcm[k] = (short) clamp((long) v, -32768L, 32767L);
            pos += ratio;
        }
        return new AudioChunk(pcm, (long) (outStart * 1_000_000.0));
    }

    /**
     * Creates + starts the AAC encoder and adds its track to the muxer.
     *
     * <p>The muxer must exist by now: the previous version called this before
     * the muxer was created, the resulting NullPointerException was swallowed
     * by the audio fallback, and audio silently never appeared.
     */
    private void prepareAudioEncoder() throws IOException {
        if (muxer == null) {
            return;
        }
        try {
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat enc = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, srcRate, 1);
            enc.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            enc.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            enc.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
            audioEncoder.configure(enc, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
        } catch (Exception e) {
            releaseAudioEncoder();
            throw new IOException("The audio encoder could not be started", e);
        }

        // Grab the output format (with csd) BEFORE the muxer starts.
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int i = 0; i < 200; i++) {
            int idx;
            try {
                idx = audioEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (IllegalStateException e) {
                break;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    audioEncTrack = muxer.addTrack(audioEncoder.getOutputFormat());
                } catch (Exception e) {
                    releaseAudioEncoder();
                    audioChunks = null;
                    audioEncTrack = -1;
                }
                return;
            }
            if (idx >= 0) {
                audioEncoder.releaseOutputBuffer(idx, false);
            }
        }
        // Never got a format: give up on audio.
        releaseAudioEncoder();
        audioChunks = null;
        audioEncTrack = -1;
    }

    /** Feeds the resampled PCM chunks to the AAC encoder and muxes output. */
    private void finishAudio() throws IOException {
        if (audioEncoder == null || audioChunks == null || audioEncTrack < 0 || !muxerStarted) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int chunkIdx = 0;
        int slicePos = 0;
        boolean inputEos = false;
        boolean outputEos = false;
        int idle = 0;

        for (int guard = 0; guard < 500_000 && !outputEos; guard++) {
            int inIdx = audioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inIdx >= 0) {
                if (chunkIdx < audioChunks.size()) {
                    AudioChunk c = audioChunks.get(chunkIdx);
                    ByteBuffer bb = audioEncoder.getInputBuffer(inIdx);
                    if (bb == null) {
                        throw new IOException("The audio encoder buffer is unavailable");
                    }
                    int maxShorts = bb.capacity() / 2;
                    int count = Math.min(maxShorts, c.pcm.length - slicePos);
                    bb.order(ByteOrder.LITTLE_ENDIAN);
                    bb.clear();
                    bb.asShortBuffer().put(c.pcm, slicePos, count);
                    long pts = c.outPtsUs
                            + (long) (slicePos * 1_000_000.0 / srcRate);
                    audioEncoder.queueInputBuffer(inIdx, 0, count * 2, pts, 0);
                    slicePos += count;
                    if (slicePos >= c.pcm.length) {
                        chunkIdx++;
                        slicePos = 0;
                    }
                } else if (!inputEos) {
                    audioEncoder.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputEos = true;
                }
            }
            int outIdx = audioEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            if (outIdx >= 0) {
                idle = 0;
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    ByteBuffer ob = audioEncoder.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        muxer.writeSampleData(audioEncTrack, ob, info);
                    }
                } finally {
                    audioEncoder.releaseOutputBuffer(outIdx, false);
                }
                outputEos = eos;
            } else if (inputEos && ++idle > 400) {
                Log.w(TAG, "audio encoder did not drain fully");
                return;
            }
        }
    }

    // ---------------- cleanup ----------------

    private void releaseDecoder() {
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {
            }
            try {
                decoder.release();
            } catch (Exception ignored) {
            }
            decoder = null;
        }
        decoderEosQueued = false;
    }

    private void releaseVideoEncoder() {
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                videoEncoder.release();
            } catch (Exception ignored) {
            }
            videoEncoder = null;
        }
    }

    private void releaseAudioEncoder() {
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                audioEncoder.release();
            } catch (Exception ignored) {
            }
            audioEncoder = null;
        }
    }

    private void releaseExtractor() {
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            extractor = null;
        }
    }

    private void releaseAll() {
        releaseDecoder();
        releaseVideoEncoder();
        releaseAudioEncoder();
        if (muxer != null) {
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
        muxerStarted = false;
        releaseExtractor();
        srcFormat = null;
        srcMime = null;
        lastByFlip[0] = null;
        lastByFlip[1] = null;
        if (frameCache != null) {
            frameCache.clear();
            frameCache = null;
        }
        srcMono = null;
        audioChunks = null;
    }

    private static int intOr(MediaFormat fmt, String key, int fallback) {
        try {
            return fmt.containsKey(key) ? fmt.getInteger(key) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
