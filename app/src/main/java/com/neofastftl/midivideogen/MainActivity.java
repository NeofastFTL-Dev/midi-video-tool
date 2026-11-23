package com.neofastftl.midivideogen;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry screen: pick a MIDI file, pick a video, choose a track, configure
 * flip/loop modes, then render the MIDI-synced video.
 *
 * <p>Everything that touches storage goes through the Storage Access Framework
 * or MediaStore, so the app needs no storage permission on any supported
 * Android version.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIDI = 1;
    private static final int REQ_VIDEO = 2;
    private static final int REQ_FOLDER = 3;

    private static final String PREFS = "settings";
    private static final String PREF_FOLDER = "output_folder";
    private static final String PREF_MIDI = "midi_uri";
    private static final String PREF_VIDEO = "video_uri";

    private static final String FLIP_ALTERNATE = "Alternate flips every note";
    private static final String FLIP_NEVER = "Never flip";
    private static final String FLIP_ALWAYS = "Flip every note";
    private static final String FLIP_RANDOM = "Random flip";

    private static final String LOOP_RESTART = "Restart the video on every note";
    private static final String LOOP_PLAY_THROUGH = "Play the video through to the end";

    private static final int BUFFER_SIZE = 65536;
    private static final long PROGRESS_INTERVAL_MS = 80L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Uri midiUri;
    private Uri videoUri;
    private Uri outputFolderUri;
    private List<MidiFile.Track> tracks = new ArrayList<>();
    private final List<Integer> selectedTrackIndexes = new ArrayList<>();
    private ListView trackList;
    private TextView tvStatus;
    private TextView tvLastNoteValue;
    private SeekBar seekLastNote;
    private SeekBar seekBasePitch;
    private TextView tvBasePitchValue;
    private Button btnGenerate;
    private Button btnDetectPitch;
    private View progressContainer;
    private TextView tvProgress;

    private Spinner flipSpinner;
    private Spinner loopSpinner;
    private TextView tvFolder;

    private boolean generating;
    private boolean detectingPitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        trackList = findViewById(R.id.track_list);
        tvStatus = findViewById(R.id.tv_status);
        tvLastNoteValue = findViewById(R.id.tv_last_note_value);
        seekLastNote = findViewById(R.id.seek_last_note);
        seekBasePitch = findViewById(R.id.seek_base_pitch);
        tvBasePitchValue = findViewById(R.id.tv_base_pitch_value);
        btnGenerate = findViewById(R.id.btn_generate);
        btnDetectPitch = findViewById(R.id.btn_detect_pitch);
        progressContainer = findViewById(R.id.progress_container);
        tvProgress = findViewById(R.id.tv_progress);
        flipSpinner = findViewById(R.id.spinner_flip);
        loopSpinner = findViewById(R.id.spinner_loop);
        tvFolder = findViewById(R.id.tv_folder);

        findViewById(R.id.btn_pick_midi).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // No MIME filter: many file managers label .mid as octet-stream.
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_MIDI);
        });

        findViewById(R.id.btn_pick_video).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_VIDEO);
        });

        findViewById(R.id.btn_pick_folder).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FOLDER);
        });

        ArrayAdapter<String> flipAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{FLIP_ALTERNATE, FLIP_NEVER, FLIP_ALWAYS, FLIP_RANDOM});
        flipSpinner.setAdapter(flipAdapter);
        ArrayAdapter<String> loopAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{LOOP_RESTART, LOOP_PLAY_THROUGH});
        loopSpinner.setAdapter(loopAdapter);

        seekLastNote.setMax(20); // 1.0 .. 3.0 step 0.1
        seekLastNote.setProgress(5);
        tvLastNoteValue.setText(formatMultiplier(1.5));
        seekLastNote.setOnSeekBarChangeListener(simpleSeek((bar, value) ->
                tvLastNoteValue.setText(formatMultiplier(1.0 + value / 10.0))));

        seekBasePitch.setMax(48); // MIDI 48 (C3) .. 96 (C7)
        seekBasePitch.setProgress(24);
        tvBasePitchValue.setText(formatBasePitch(72));
        seekBasePitch.setOnSeekBarChangeListener(simpleSeek((bar, value) ->
                tvBasePitchValue.setText(formatBasePitch(48 + value))));

        btnDetectPitch.setOnClickListener(v -> detectBasePitch());

        trackList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        trackList.setOnItemClickListener((parent, view, position, id) -> readTrackSelection());

        btnGenerate.setOnClickListener(v -> startGeneration());

        restoreOutputFolder();
        restoreInputs();
        updateGenerateReady();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_MIDI) {
            midiUri = uri;
            persistReadPermission(uri);
            prefs().edit().putString(PREF_MIDI, uri.toString()).apply();
            loadTracks(uri);
        } else if (requestCode == REQ_VIDEO) {
            videoUri = uri;
            persistReadPermission(uri);
            prefs().edit().putString(PREF_VIDEO, uri.toString()).apply();
            tvStatus.setText(getString(R.string.picked_video_fmt, displayName(uri, "video")));
        } else if (requestCode == REQ_FOLDER) {
            outputFolderUri = uri;
            persistOutputFolder(uri);
            tvFolder.setText(getString(R.string.folder_fmt, describeFolder(uri)));
        }
        updateGenerateReady();
    }

    // ---------------- state ----------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * Keeps the document grants across restarts. Without this the app forgot
     * the picked files as soon as the process died and every pick was lost.
     */
    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Not every provider offers persistable grants; the URI still
            // works for this session.
        }
    }

    private boolean hasReadAccess(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            return true;
        }
        try {
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void restoreInputs() {
        String midi = prefs().getString(PREF_MIDI, null);
        if (midi != null) {
            Uri uri = Uri.parse(midi);
            if (hasReadAccess(uri)) {
                midiUri = uri;
                loadTracks(uri);
            } else {
                prefs().edit().remove(PREF_MIDI).apply();
            }
        }
        String video = prefs().getString(PREF_VIDEO, null);
        if (video != null) {
            Uri uri = Uri.parse(video);
            if (hasReadAccess(uri)) {
                videoUri = uri;
                tvStatus.setText(getString(R.string.picked_video_fmt, displayName(uri, "video")));
            } else {
                prefs().edit().remove(PREF_VIDEO).apply();
            }
        }
    }

    private void persistOutputFolder(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        prefs().edit().putString(PREF_FOLDER, uri.toString()).apply();
    }

    private void restoreOutputFolder() {
        String s = prefs().getString(PREF_FOLDER, null);
        if (s == null) {
            return;
        }
        try {
            Uri uri = Uri.parse(s);
            // Persisted grants can be revoked (app data cleared, provider gone).
            boolean hasAccess = false;
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isWritePermission()) {
                    hasAccess = true;
                    break;
                }
            }
            if (hasAccess) {
                outputFolderUri = uri;
                tvFolder.setText(getString(R.string.folder_fmt, describeFolder(uri)));
            } else {
                prefs().edit().remove(PREF_FOLDER).apply();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- MIDI ----------------

    private void loadTracks(Uri uri) {
        tvStatus.setText(R.string.loading_midi);
        executor.execute(() -> {
            List<MidiFile.Track> parsed = null;
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    error = "the file could not be opened";
                } else {
                    parsed = MidiFile.parse(in).getTracks();
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final List<MidiFile.Track> result = parsed;
            final String message = error;
            runOnUiThread(() -> applyTracks(result, message));
        });
    }

    private void applyTracks(List<MidiFile.Track> result, String error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        tracks = result != null ? result : new ArrayList<>();
        selectedTrackIndexes.clear();
        List<String> labels = new ArrayList<>();
        for (MidiFile.Track t : tracks) {
            String label = TextUtils.isEmpty(t.name)
                    ? getString(R.string.track_unnamed) : t.name;
            labels.add(getString(R.string.track_notes_fmt, label, t.notes.size()));
        }
        if (labels.isEmpty()) {
            trackList.setAdapter(null);
        } else {
            trackList.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_multiple_choice, labels));
        }
        if (error != null) {
            tvStatus.setText(getString(R.string.err_read_midi_fmt, error));
        } else if (labels.isEmpty()) {
            tvStatus.setText(R.string.no_track);
        }
        updateGenerateReady();
    }

    private void readTrackSelection() {
        SparseBooleanArray checked = trackList.getCheckedItemPositions();
        selectedTrackIndexes.clear();
        if (checked != null) {
            for (int i = 0; i < checked.size(); i++) {
                int position = checked.keyAt(i);
                if (checked.valueAt(i) && position >= 0 && position < tracks.size()) {
                    selectedTrackIndexes.add(position);
                }
            }
        }
        updateGenerateReady();
    }

    private void updateGenerateReady() {
        btnGenerate.setEnabled(!generating && videoUri != null
                && !selectedTrackIndexes.isEmpty());
    }

    // ---------------- pitch detection ----------------

    /**
     * Estimates the video's original voice pitch (NCCF over the first
     * minute of audio) and moves the base-pitch slider there.
     */
    private void detectBasePitch() {
        if (videoUri == null) {
            Toast.makeText(this, R.string.pick_video_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (detectingPitch) {
            return;
        }
        detectingPitch = true;
        btnDetectPitch.setEnabled(false);
        btnDetectPitch.setText(R.string.detecting_pitch);
        final Uri uri = videoUri;
        executor.execute(() -> {
            int midi = -1;
            try {
                AudioPcm.Mono mono = AudioPcm.decodeMono(getApplicationContext(), uri, 60.0);
                if (mono != null) {
                    midi = PitchDetector.detectMidi(mono.pcm, mono.sampleRate);
                }
            } catch (Throwable ignored) {
                midi = -1;
            }
            final int value = midi;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                detectingPitch = false;
                btnDetectPitch.setEnabled(true);
                btnDetectPitch.setText(R.string.detect_pitch);
                if (value >= 48 && value <= 96) {
                    seekBasePitch.setProgress(value - 48);
                    tvBasePitchValue.setText(getString(R.string.pitch_detected_fmt,
                            formatBasePitch(value)));
                } else {
                    tvBasePitchValue.setText(formatBasePitch(48 + seekBasePitch.getProgress()));
                    Toast.makeText(this,
                            value >= 0
                                    ? getString(R.string.pitch_out_of_range,
                                            formatBasePitch(value))
                                    : getString(R.string.pitch_detect_failed),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ---------------- generation ----------------

    private void startGeneration() {
        if (generating) {
            return;
        }
        if (midiUri == null || videoUri == null || selectedTrackIndexes.isEmpty()) {
            updateGenerateReady();
            return;
        }
        List<MidiFile.Note> merged = new ArrayList<>();
        for (int idx : selectedTrackIndexes) {
            if (idx >= 0 && idx < tracks.size()) {
                merged.addAll(tracks.get(idx).notes);
            }
        }
        if (merged.isEmpty()) {
            Toast.makeText(this, R.string.no_notes, Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(merged, (a, b) -> Double.compare(a.start, b.start));

        VideoGenerator.FlipMode flipMode;
        String flipLabel = (String) flipSpinner.getSelectedItem();
        if (FLIP_ALWAYS.equals(flipLabel)) {
            flipMode = VideoGenerator.FlipMode.ALWAYS;
        } else if (FLIP_NEVER.equals(flipLabel)) {
            flipMode = VideoGenerator.FlipMode.NEVER;
        } else if (FLIP_RANDOM.equals(flipLabel)) {
            flipMode = VideoGenerator.FlipMode.RANDOM;
        } else {
            flipMode = VideoGenerator.FlipMode.ALTERNATE;
        }

        final boolean restart = LOOP_RESTART.equals(loopSpinner.getSelectedItem());
        final double mult = 1.0 + seekLastNote.getProgress() / 10.0;
        final int basePitch = 48 + seekBasePitch.getProgress();
        final Uri source = videoUri;
        final List<MidiFile.Note> notes = merged;

        generating = true;
        // A render can take minutes; keep the screen awake so the process is
        // not backgrounded mid-job.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressContainer.setVisibility(View.VISIBLE);
        btnGenerate.setEnabled(false);
        tvProgress.setText(getString(R.string.generating));

        executor.execute(() -> {
            File rendered = null;
            String saved = null;
            String error = null;
            final long[] lastUpdate = {0L};
            try {
                VideoGenerator generator = VideoGenerator
                        .with(getApplicationContext(), source, notes)
                        .flipMode(flipMode)
                        .restartPerNote(restart)
                        .lastNoteMultiplier(mult)
                        .basePitch(basePitch)
                        .progress((done, total) -> {
                            long now = SystemClock.elapsedRealtime();
                            if (done < total && now - lastUpdate[0] < PROGRESS_INTERVAL_MS) {
                                return;
                            }
                            lastUpdate[0] = now;
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    tvProgress.setText(getString(R.string.progress_fmt,
                                            done, total));
                                }
                            });
                        })
                        .build();
                rendered = generator.render();
                Uri target = saveRender(rendered);
                saved = target != null ? displayName(target, target.toString())
                        : rendered.getAbsolutePath();
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            } finally {
                if (rendered != null) {
                    //noinspection ResultOfMethodCallIgnored
                    rendered.delete();
                }
            }

            final String failure = error;
            final String destination = saved;
            runOnUiThread(() -> {
                generating = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                progressContainer.setVisibility(View.GONE);
                updateGenerateReady();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failure != null) {
                    tvStatus.setText(getString(R.string.err_encode, failure));
                } else if (destination != null) {
                    tvStatus.setText(getString(R.string.saved_to, destination));
                    Toast.makeText(this, R.string.done, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Copies the render into the chosen folder, else MediaStore Movies. */
    private Uri saveRender(File src) {
        if (outputFolderUri != null) {
            Uri target = saveToFolder(src, outputFolderUri);
            if (target != null) {
                return target;
            }
        }
        return saveToMovies(src);
    }

    /**
     * Copies the rendered file into the user-chosen SAF folder (a tree URI
     * from ACTION_OPEN_DOCUMENT_TREE). Creates the document if missing.
     */
    private Uri saveToFolder(File src, Uri treeUri) {
        Uri target = null;
        try {
            Uri folder = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            String name = "midi_video_" + System.currentTimeMillis() + ".mp4";
            target = DocumentsContract.createDocument(
                    getContentResolver(), folder, "video/mp4", name);
            if (target == null) {
                return null;
            }
            copy(src, target);
            return target;
        } catch (Exception e) {
            if (target != null) {
                try {
                    DocumentsContract.deleteDocument(getContentResolver(), target);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    /** Copies the rendered file into MediaStore Movies (no permission needed). */
    private Uri saveToMovies(File src) {
        Uri target = null;
        try {
            String name = "midi_video_" + System.currentTimeMillis() + ".mp4";
            Uri collection = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/MIDI Video Generator");
            // IS_PENDING hides the half-written file from other apps; without
            // it the gallery could index a 0-byte entry.
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            target = getContentResolver().insert(collection, cv);
            if (target == null) {
                return null;
            }
            copy(src, target);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(target, done, null, null);
            return target;
        } catch (Exception e) {
            if (target != null) {
                try {
                    getContentResolver().delete(target, null, null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    private void copy(File src, Uri target) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(target)) {
            if (os == null) {
                throw new IOException("the destination could not be opened");
            }
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        }
    }

    /** Best-effort human-readable folder name for display. */
    private String describeFolder(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null && docId.contains(":")) {
                String path = docId.substring(docId.indexOf(':') + 1);
                String root = docId.substring(0, docId.indexOf(':'));
                if (path.isEmpty()) {
                    return root;
                }
                return root + "/" + path;
            }
            return docId == null ? treeUri.toString() : docId;
        } catch (Exception e) {
            return treeUri.toString();
        }
    }

    /** Display name of a document, falling back to the last path segment. */
    private String displayName(Uri uri, String fallback) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        String segment = uri.getLastPathSegment();
        return TextUtils.isEmpty(segment) ? fallback : segment;
    }

    // ---------------- helpers ----------------

    private interface OnSeekChanged {
        void onChanged(SeekBar bar, int value);
    }

    private static SeekBar.OnSeekBarChangeListener simpleSeek(OnSeekChanged l) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                l.onChanged(bar, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        };
    }

    private static String formatBasePitch(int midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int note = Math.max(0, Math.min(127, midi));
        return String.format(Locale.US, "%d (%s%d)", note, names[note % 12], note / 12 - 1);
    }

    private static String formatMultiplier(double v) {
        return String.format(Locale.US, "%.1fx", v);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
