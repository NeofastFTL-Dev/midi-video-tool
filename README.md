# MIDI Video Generator (Android)

An Android app that turns **one video + one MIDI file** into an
[Otomad Helper](https://github.com/otomad-helper)-style video: the timeline gets
one video segment and one pitch-shifted audio segment per MIDI note, so the
source video "sings" the melody.

Everything runs on the device with the platform codecs — no server, no network
access, no tracking, no ads.

## What it does

- **One segment per note.** Note `i` is shown for `note[i+1].start - note[i].start`
  seconds; the last note gets `(end - start) × multiplier` (slider, 1.0×–3.0×).
  A fractional (Bresenham) accumulator keeps the total length exact.
- **Two loop modes.** *Restart the video on every note* replays the source from
  frame 0 for each note (classic otomad look), or *play the video through* plays
  the source once and freezes the last frame when it runs out.
- **Four flip modes:** alternate, never, always, random.
- **Per-note pitch shift.** The source audio is decoded once to a mono PCM
  mixdown, then each segment is resampled by `2^((note - basePitch)/12)` with a
  short fade at both ends. The base pitch (C3–C7) can be set manually or
  estimated from the video with an NCCF pitch detector.
- **Track selection.** Multi-select the MIDI tracks to merge into the melody.
- **No storage permissions.** Inputs come from the Storage Access Framework and
  renders are written through MediaStore (`Movies/MIDI Video Generator`) or a
  folder you pick. Grants are persisted, so the picks survive a restart.

## Requirements

- Android 10 (API 29) or newer.
- An H.264 decoder/encoder for the source video. Large sources (4K) are
  downscaled automatically until the device's encoder accepts the size.

## Building

```bash
./gradlew testDebugUnitTest      # pure-JVM tests for the MIDI parser, timeline and pitch detector
./gradlew connectedDebugAndroidTest  # on-device render smoke test (needs a device/emulator)
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # minified; signed when a keystore is configured
```

`compileSdk 35`, `minSdk 29`, Java 17. The app itself has **no library
dependencies**, so the APK stays small and there is no framework to crash at
startup. Tests use JUnit on the JVM and `androidx.test` for the instrumentation
test — those live in the test APKs and never ship in the app.

Release signing is opt-in and never stored in the repository. Provide a keystore
through the environment and Gradle signs the release build:

```bash
export KEYSTORE_FILE=/path/to/release.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=midi-video-generator
export KEY_PASSWORD=...
./gradlew assembleRelease
```

Without those variables you get an unsigned release APK, which is useful for
checking shrinking/proguard output.

## CI and releases

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs on every push
to `main`, every pull request and every `v*` tag:

1. JVM unit tests.
2. `assembleDebug` + `assembleRelease` (signed when the keystore secrets are set).
3. APKs uploaded as the `midi-video-generator-apk` artifact.
4. A second job boots an API 34 emulator and runs `connectedDebugAndroidTest`.
   `RenderSmokeTest` renders a three-note MIDI against the bundled 1.5 s
   H.264/AAC clip, asserts the MP4 is non-empty, has a video and an audio track,
   then re-opens it, checks the duration and decodes frame 0 — so MediaCodec
   decode, encode, the YUV transform and the muxer are exercised on real codecs,
   not just compiled. The render is uploaded as `render-smoke-output`.
5. On a `v*` tag, a GitHub release is created and the APKs are attached. The tag
   must match `versionName` in `app/build.gradle`. The release job waits for the
   emulator job, so a tag cannot publish a build whose render path is broken.

Secrets used by the workflow: `ANDROID_KEYSTORE_BASE64` (base64 of the `.jks`),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Layout

```
app/src/main/java/com/neofastftl/midivideogen/
├── MainActivity.java    UI, file picking, state, saving
├── MidiFile.java        SMF parser (running status, tempo map, SMPTE)
├── Segments.java        per-note frame counts
├── VideoGenerator.java  decode → transform → H.264/AAC → MP4
├── AudioPcm.java        audio track → mono 16-bit PCM
└── PitchDetector.java   NCCF pitch estimation

app/src/androidTest/
├── assets/smoke_source.mp4                          1.5 s H.264/AAC clip
└── java/.../RenderSmokeTest.java                    real decode → encode → mux → decode
```

## Fixes in this revision

The app source came from a partially archived tree; it had no Gradle project,
no manifest, and several real crash/correctness bugs that are fixed here:

- **Running status was ignored** in the MIDI parser, so any track that reused the
  previous status byte (extremely common) was cut short at the first such event.
  The parser also rejected/hung on odd header lengths, huge chunk lengths and
  zero SMPTE subframes, and it had no size or note-count limits.
- **Rotation and frame rate were read after being used** when setting up the
  video pipeline, so rotated videos came out with the wrong dimensions and cache
  sizing.
- **Audio never worked**: the AAC track was created before the muxer existed, the
  resulting `NullPointerException` was swallowed by the "audio is optional" catch,
  and every render silently came out video-only.
- **`signalEndOfInputStream()` was called on a ByteBuffer-input encoder**, which
  throws `IllegalStateException` on many devices; it now queues an explicit
  end-of-stream buffer.
- **Encoder selection** now scans `MediaCodecList` for an AVC encoder that really
  accepts YUV420 buffers (planar preferred), instead of failing when the first
  encoder only supported surface input.
- **Rotated/odd/cropped frames** are mapped from the decoder's crop rect and even-
  rounded, and 4K sources are downscaled when the encoder cannot handle them.
- **Rewinding** (restart-per-note) recreated the decoder instead of relying on a
  flush, which kept stale frames from the previous position.
- **Decoder/encoder drain loops** could spin forever when a codec never signalled
  end-of-stream; every loop is now bounded.
- **MediaStore writes** now use `IS_PENDING`, so a half-written MP4 cannot be
  indexed by the gallery; failed writes are deleted.
- **URI grants are persisted**, so the picked MIDI/video/output folder survive a
  restart, and rotation no longer restarts the activity mid-render.
- **The temporary render is deleted** after saving (it used to fill the cache),
  and the progress UI is throttled instead of posting thousands of updates.
- Removed the stray `MidiFile$*.class` build output that was committed with the
  sources, and dropped the AppCompat dependency (and with it the mismatched
  `Theme.AppCompat` + plain `Activity` combination).

## Limitations

- Video is re-encoded with the device's H.264 encoder; output resolution follows
  the source (downscaled only when the encoder requires it).
- The per-note audio plan is capped (10 minutes of source audio, 192 MB of
  output PCM). Longer jobs fall back to a video-only render instead of risking an
  out-of-memory kill.
