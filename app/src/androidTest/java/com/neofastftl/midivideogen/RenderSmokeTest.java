package com.neofastftl.midivideogen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the real render pipeline on a device/emulator: it decodes the bundled
 * 1.5 s H.264/AAC clip, encodes the MIDI-synced video with MediaCodec, muxes
 * the result with MediaMuxer, then re-opens and decodes the output.
 *
 * <p>This is the only check that exercises the hardware/software codecs; the
 * JVM unit tests cannot. It fails if the MP4 is empty, if a track or frame is
 * missing, or if the first frame decodes to a flat image (which is what a
 * broken YUV transform looks like).
 */
@RunWith(AndroidJUnit4.class)
public class RenderSmokeTest {

    private static final String TAG = "RenderSmokeTest";
    private static final String SOURCE_ASSET = "smoke_source.mp4";
    private static final long MIN_OUTPUT_BYTES = 1024L;

    @Test
    public void shortMidiAndVideoRenderToPlayableMp4() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = copyAssetToCache(app);
        List<MidiFile.Note> notes = smokeNotes();
        assertEquals("the smoke MIDI should contain three notes", 3, notes.size());

        File output = VideoGenerator.with(app, Uri.fromFile(source), notes)
                .flipMode(VideoGenerator.FlipMode.ALTERNATE)
                .restartPerNote(true)
                .lastNoteMultiplier(1.0)
                .basePitch(60)
                .build()
                .render();

        assertTrue("render() must return a file", output != null && output.exists());
        long bytes = output.length();
        Log.i(TAG, "rendered " + bytes + " bytes to " + output.getAbsolutePath());
        assertTrue("the rendered MP4 is empty (" + bytes + " bytes)",
                bytes > MIN_OUTPUT_BYTES);

        MediaExtractor extractor = new MediaExtractor();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            extractor.setDataSource(output.getAbsolutePath());
            int videoTrack = -1;
            int videoWidth = 0;
            int videoHeight = 0;
            int audioTracks = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (videoTrack < 0 && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                } else if (mime.startsWith("audio/")) {
                    audioTracks++;
                }
            }
            assertTrue("the render has no video track", videoTrack >= 0);
            assertTrue("the render reports an invalid " + videoWidth + "x" + videoHeight
                    + " frame size", videoWidth >= 16 && videoHeight >= 16);
            assertEquals("the source audio is missing from the render", 1, audioTracks);

            extractor.selectTrack(videoTrack);
            ByteBuffer sample = ByteBuffer.allocateDirect(1 << 20);
            int frames = 0;
            while (extractor.readSampleData(sample, 0) > 0) {
                frames++;
                extractor.advance();
            }
            Log.i(TAG, "muxed video samples: " + frames);
            assertTrue("no video samples were muxed into the MP4", frames >= 4);

            retriever.setDataSource(output.getAbsolutePath());
            String durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull("the render has no duration", durationMs);
            long duration = Long.parseLong(durationMs);
            Log.i(TAG, "duration: " + duration + " ms");
            assertTrue("the render is only " + duration + " ms long", duration >= 300);

            Bitmap frame = retriever.getFrameAtTime(0);
            assertNotNull("the first encoded frame could not be decoded", frame);
            try {
                assertTrue("the decoded frame is " + frame.getWidth() + "x" + frame.getHeight(),
                        frame.getWidth() >= 16 && frame.getHeight() >= 16);
                int colors = distinctColors(frame);
                Log.i(TAG, "distinct colors in frame 0: " + colors);
                assertTrue("frame 0 decoded to a flat " + frame.getWidth() + "x"
                                + frame.getHeight() + " image (" + colors
                                + " colors): the YUV transform wrote no picture",
                        colors > 8);
            } finally {
                frame.recycle();
            }
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }

        publishForCi(app, output);
    }

    /** Copies the asset from the test APK into the app cache as a real file. */
    private static File copyAssetToCache(Context app) throws Exception {
        InputStream expected = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open(SOURCE_ASSET);
        File target = new File(app.getCacheDir(), SOURCE_ASSET);
        try (InputStream in = expected; OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        assertTrue("the bundled source clip is empty", target.length() > 0);
        return target;
    }

    /** Leaves a copy in app-private storage so CI can pull it with run-as. */
    private static void publishForCi(Context app, File render) {
        try {
            File dir = app.getFilesDir();
            if (dir == null) {
                return;
            }
            File target = new File(dir, "smoke-render.mp4");
            try (InputStream in = new FileInputStream(render);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            Log.i(TAG, "copied the render to " + target.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "could not copy the render for the CI artifact", e);
        }
    }

    private static int distinctColors(Bitmap bitmap) {
        Set<Integer> colors = new HashSet<>();
        int stepX = Math.max(1, bitmap.getWidth() / 24);
        int stepY = Math.max(1, bitmap.getHeight() / 24);
        for (int y = stepY / 2; y < bitmap.getHeight(); y += stepY) {
            for (int x = stepX / 2; x < bitmap.getWidth(); x += stepX) {
                colors.add(bitmap.getPixel(x, y));
            }
        }
        return colors.size();
    }

    /** Parses a three-note SMF built in memory (C4/E4/G4, 120 bpm). */
    private static List<MidiFile.Note> smokeNotes() throws Exception {
        List<MidiFile.Note> merged = new ArrayList<>();
        for (MidiFile.Track track : MidiFile.parse(new ByteArrayInputStream(smokeMidi()))
                .getTracks()) {
            merged.addAll(track.notes);
        }
        Collections.sort(merged, (a, b) -> Double.compare(a.start, b.start));
        return merged;
    }

    private static byte[] smokeMidi() throws Exception {
        ByteArrayOutputStream events = new ByteArrayOutputStream();
        event(events, 0, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20); // 500000 us/quarter
        event(events, 0, 0xC0, 0x00);
        event(events, 0, 0x90, 0x3C, 0x64);   // C4 on
        event(events, 240, 0x80, 0x3C, 0x40); // C4 off  -> 0.25 s
        event(events, 0, 0x90, 0x40, 0x64);   // E4 on
        event(events, 240, 0x80, 0x40, 0x40); // E4 off  -> 0.25 s
        event(events, 0, 0x90, 0x43, 0x64);   // G4 on
        event(events, 480, 0x80, 0x43, 0x40); // G4 off  -> 0.50 s
        event(events, 0, 0xFF, 0x2F, 0x00);   // end of track

        byte[] track = events.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0x01, (byte) 0xE0});
        file.write(new byte[]{'M', 'T', 'r', 'k',
                (byte) (track.length >>> 24), (byte) (track.length >>> 16),
                (byte) (track.length >>> 8), (byte) track.length});
        file.write(track);
        return file.toByteArray();
    }

    private static void event(ByteArrayOutputStream out, int delta, int... data) {
        variableLength(out, delta);
        for (int value : data) {
            out.write(value & 0xFF);
        }
    }

    private static void variableLength(ByteArrayOutputStream out, int value) {
        int v = value & 0x0FFFFFFF;
        int[] buffer = new int[5];
        int length = 0;
        buffer[length++] = v & 0x7F;
        v >>>= 7;
        while (v > 0) {
            buffer[length++] = (v & 0x7F) | 0x80;
            v >>>= 7;
        }
        for (int i = length - 1; i >= 0; i--) {
            out.write(buffer[i]);
        }
    }
}
