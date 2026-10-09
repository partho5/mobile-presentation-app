# Face Cam Background Replacement — Plan & Progress Tracker

Each new session: the user says **"build part N"**. Read this file, build only that part,
compile (`./gradlew -q --offline :app:compileDebugJavaWithJavac`), test on the wireless-debug
device if connected, tick its boxes here, update **Status**, and commit.

Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## Status

| Part | Scope | Status |
|---|---|---|
| 1 | Standalone 20 s test: record face clip → replace background offline → save MP4 | ✅ Works (user: "worked a bit") — not committed |
| 2 | Integrate into screen recording (replace bg inside the circle of the screen video) | ✅ Works on device (user: "it worked") — not committed |
| 3 | Speed + reliability (was ~11× video length) | 🚧 Built + installed (~4× now), awaiting user check — not committed |
| 4 | Settings: background picker as a 2-per-row image grid (preview + name in one card) | 🚧 Built + installed, awaiting user check — not committed |
| 5 | Quality: remaining jitter, edges, natural look | ⏳ |
| 6 | UX: non-blocking processing, keep original, more background types | ⏳ |
| 7 | Cleanup: dead code, debug hooks, unused assets/deps | ⏳ |

## Decisions already confirmed by the user (do not re-ask)

- **Live real-time replacement is rejected** (extreme jitter). Background is replaced **after
  recording ends**. Live preview shows the **plain camera**.
- Background image = the one already selected in Settings (`facecam_prefs` → `bg_asset_path`,
  asset path, default `default_bg.png`). No picker after recording for now.
- Quality approach: keep the current model + **bidirectional temporal smoothing** (uses past
  *and* future frames) + **joint bilateral edge refinement** guided by full-res camera colour.
  Only move to RVM (Part 3) if that is not good enough.
- Part 2 approach (changed for reliability, recording code untouched): the screen recording
  already contains the face circle. Its position/size is logged during recording
  (`bgreplace/BubbleTrack`, polled at 30 Hz by `MainActivity` and `ui/FloatingCamOverlay`);
  after stop, `bgreplace/FaceBgReplaceTask` replaces the background **inside the circle only**
  (ring kept), re-encodes once at 12 Mbps, and swaps the file in. Any failure keeps the original.
- User wants **no new UI**; the test screen is debug-only, launched via adb:
  `adb shell am start -n com.jovoc.facecampresentationrecorder/.bgreplace.BgTestActivity`
- **Do not commit** until the user confirms reliability (Parts 1–2 are still uncommitted).
- Users never download models; models ship inside the APK `assets/`.
- **Post-recording order is fixed: background replace first (only if enabled), then auto-crop.**
  Auto-crop (`util/VideoCropHelper.cropAsync`, ratios from Settings) must always run on the
  *processed* file, never the original, so every crop also has the new background. If replacement
  is off, fails, or is cancelled, auto-crop runs on the original as before. Today this is done in
  `BgReplaceService.runJob` (`FaceBgReplaceTask.replace` → `VideoCropHelper.cropAsync`), started from
  `ScreenRecordService.stopRecordingInternal`; `MainActivity.onRecordingReady` only shows the result. Any change that moves processing to the background
  (Parts 3 and 6) must keep this chain: start auto-crop from the replacement's completion callback,
  not from the recording-finished event.

## Facts a fresh session needs

- Device: Redmi Note 14 (`24117RN76G`), Helio G99 / **Mali-G57 MC2**, Android 14, GLES 3.2.
- Model: `assets/selfie_multiclass_256x256.tflite` (MediaPipe, 16 MB, float32).
  Input `[1,256,256,3]` RGB normalised **(v − 127.5) / 127.5**. Output `[1,256,256,6]`
  **raw logits** (0 bg, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other) →
  person = 1 − softmax[0]. Runs 100 % on TFLite GpuDelegate (OpenCL) on this device.
- Do **not** use MediaPipe `selfie_segmentation.tflite` / `selfie_segmenter.tflite`: they use the
  `Convolution2DTransposeBias` custom op, which plain TFLite cannot load (even with GPU delegate).
- `glReadPixels` rows are bottom-up; model expects top-down → flip rows on input and output.
- Antigravity's live pipeline (`bgreplace/BackgroundReplacerRecorder`, `GLRenderer`,
  `MaskRefiner`, `Camera2Controller`, `SegmentationProcessor`) is no longer wired in; delete in Part 3.

## Part 1 — Standalone 20 s test

- [x] Remove live replacement from `MainActivity.startCameraPreview` and `ui/FloatingCamOverlay` (plain camera)
- [x] `bgreplace/OfflineBgProcessor`: pass 1 decode → 256² masks (GPU delegate); temporal Gaussian ±3 frames; pass 2 decode → joint bilateral refine + composite → MediaCodec AVC 8 Mbps → MediaMuxer, audio track copied
- [x] `bgreplace/BgTestActivity`: front-camera preview, record (auto-stop 20 s), process with progress, play result, save to `DCIM/<app name>/`
- [x] ~~Settings button~~ dropped (no new UI); debug-only adb launch instead
- [x] Device test: stretch/orientation fixed; user says it works

## Part 2 — Screen recording integration

- [x] Log bubble position/size over time (in-app card + floating overlay, drag/resize)
- [x] `OfflineBgProcessor` single pass with 7-frame sliding window (memory bounded for long videos), region/circle mode
- [x] After stop: progress dialog, process, swap file in, then the existing player dialog + auto-crop run on the result
- [x] Settings checkbox relabelled "Replace face cam background (after recording)" (pref `bg_replace_enabled`, default on)
- [x] Device test: in-app recording works (8 s recording → replaced, file swapped in)
- [ ] Still untested: drag/resize mid-recording, leave app (floating overlay), recording > 1 min

## Part 3 — Speed + reliability (do first)

Measured on device, 8 s screen recording (215 frames, 1080×2400): **89 s total** =
~18 s GPU delegate init (OpenCL kernel compile, every run) + ~330 ms/frame.
Target: ≤ 1.5× video length. Measure each stage before optimising (add `System.nanoTime()` timers
around decode, frame copy, crop+`glReadPixels`, input conversion, inference, softmax, composite, encode).

**Result (2.4 s screen recording, 116 frames, 1080×2400, device above):** 9.8 s total ≈ 4× video
length (was ~11×). Per-frame averages: inference 43 ms (≈86 ms per inferred frame, GPU fp16),
composite+encode 15 ms, decode 4 ms, crop+readPixels 3 ms, post 7.6 ms, input convert 1.5 ms, ring copy 0.2 ms.
The ≤ 1.5× target is not reachable with this model: inference alone is ~1.1× at stride 2. Options if
more speed is wanted: stride 3, or a lighter model (Part 5 RVM goes the other way).
A `Timing:` line is logged at the end of every run (`adb logcat -s OfflineBgProcessor`).

- [x] Cache compiled GPU kernels (`setSerializationParams(cacheDir/gpu_kernels, model+versionCode+size)`):
      GPU/model init **18.6 s → 0.3 s** from the second run on.
- [x] `Segmenter.segment`: bulk `byte[]`/`float[]` copies, 256-entry normalise table, exp lookup table with
      early-out for clear pixels. Post-processing 32 ms → 7.6 ms per frame.
- [x] Duplicate frames: byte-identical crop of the same region reuses the previous mask. Implemented;
      0 hits on the test clip (the live camera never repeats exactly), so it only helps static content.
- [x] Model runs on every 2nd frame (`INFERENCE_STRIDE`) while the circle stays put; skipped frames get the
      average of their neighbours' masks. Falls back to inferring every frame while the circle moves.
      Jitter vs. before not yet judged by the user.
- [x] Frame ring copy: measured at 0.2 ms/frame, not a cost. Left as is.
- [x] `service/BgReplaceService` (foreground, `dataSync`, partial wake lock, progress notification) runs
      replacement then auto-crop. `ScreenRecordService.stopRecordingInternal` enqueues it itself, so it works
      even when stopped from the notification with the app closed. `MainActivity` shows a progress dialog while
      started and the player when it finishes; if the app is not visible a "recording is ready" notification
      appears instead. Verified on device with a real recording (replaced + `_9x16` made); not yet checked:
      leaving the app mid-processing, the notification itself.
- [x] Free-space check (`FaceBgReplaceTask.hasEnoughSpace`, needs 1.5× the file): skips with a toast. Untested at low space.
- [x] Flip / resize icons are placed fully outside the circle while recording (`MainActivity.updateResizeHandlePosition`).
      Not yet checked on screen.
- [ ] Verify `BubbleTrack` timestamps line up with video frame pts while dragging the circle
      (log both; offset if needed). Verify the floating overlay path and a circle partly off-screen.
- [x] Per-frame logging (`logFrame`) now only runs in debug builds.

## Part 4 — Settings background picker as an image grid

Goal: in Settings → "AI Virtual Background (Face Cam)", show each background as a preview image,
so the user can never tap a name thinking it belongs to a different image.

- [x] `res/layout/dialog_record_settings.xml`: replace `RadioGroup rg_bg_sample` (3 radio buttons)
      with a 2-column `GridLayout` (`columnCount=2`, `layout_columnWeight=1`). Three cards, so row 2 has one.
- [x] Each card = one clickable unit: preview `ImageView` (centerCrop, ~16:9) on top, name directly
      underneath **inside the same card border**, with a gap between cards so cards stay clearly separate.
- [x] Selected card: thick purple (`#6200EE`) border + check icon (`ic_check_circle`) top-right;
      unselected: thin grey border. New drawable with `state_selected` selector.
- [x] `MainActivity` settings dialog (~line 3019): load thumbnails from `assets/bg_samples/`
      downsampled (`inSampleSize`, `room-2.png` is 120 KB) and track the selected card instead of the RadioButtons.
- [x] Keep the saved values exactly: pref `facecam_prefs` → `bg_asset_path` =
      `bg_samples/room-2.png` (default, "Studio Room") / `bg_samples/ai-generated-…photo.jpg` ("Bright Studio & Vase") /
      `bg_samples/white-bg-1.png` ("Clean Minimal Studio"); restore selection from the saved value on open.
- [ ] Grid disabled/greyed when "Replace face cam background" checkbox is off (optional, ask user).
- [ ] Device test: tap each card, Save, reopen Settings → same card selected; record → correct background used.

## Part 5 — Quality

User still sees **jitter** in the result. Ideas in order of cost:

- [ ] Wider temporal window: ±5 frames, sigma 2.5 (`TEMPORAL_RADIUS`, `TEMPORAL_SIGMA`; `WINDOW`
      and `FRAME_RING` follow). Costs 2 more frames of GPU memory per extra radius.
- [ ] Motion-aware weights: lower a neighbour's weight when its crop differs a lot from the current
      frame (mean abs diff of the 256² crops), so fast head moves don't ghost while still frames stay rock steady.
- [ ] Average **logits / probabilities before** thresholding (already the case) and soften the final
      `smoothstep(0.35, 0.75)`; a softer edge flickers less visibly.
- [ ] Model input for the circle: the square crop includes black corners + the white ring. Fill
      outside the inner circle with edge-extended camera pixels before inference so the model never
      sees an artificial black/white border.
- [ ] Proper guided filter (box-filter mean/variance passes) instead of the 7×7 joint bilateral, for
      hair and glasses edges.
- [ ] Natural look: slight blur on the background image (depth of field) and a 1–2 px light wrap /
      colour despill at the edge; hides small mask errors.
- [ ] Only if still poor: RVM (Robust Video Matting, MobileNetV3) converted to TFLite; it has its own
      recurrent temporal state and much better hair. Check it loads without custom ops on this device.

## Part 6 — UX

- [ ] Don't block the app: process in the background (Part 3 service) with one progress
      notification covering both steps ("Replacing background… 40%" → "Cropping 9:16…").
      Show the player only after both finish (or show the original with a "processing" label and
      reload it when done). Auto-crop must not start until replacement is done.
- [ ] Cancel button on the progress UI; show remaining time ("about 1 min left") from frames/sec.
      Cancel = keep the original and continue to auto-crop on it.
- [ ] Setting "Keep original copy too" (saves `…-original.mp4`), off by default.
- [ ] More background types: **blur my real background** (cheap, very popular, no image needed),
      solid colour, and a custom image from the gallery (photo picker, copy into app storage).
- [ ] Before recording, a one-frame preview of the chosen background on the face cam (run the
      model once on the current preview frame) so the user knows what to expect.
- [ ] First-time hint: "Background is replaced after you stop recording; takes about N× the video length."
- [ ] Saved Recordings list: badge on processed videos; option to re-run replacement with a different
      background on an existing recording (needs the original kept, see above). Re-running must also
      regenerate that recording's crops (`…_9x16.mp4` etc.) so they match.

## Part 7 — Cleanup

- [ ] Delete Antigravity's dead live pipeline: `bgreplace/BackgroundReplacerRecorder`, `GLRenderer`,
      `MaskRefiner`, `Camera2Controller`, `SegmentationProcessor` (check `EGLCore` stays — used by
      `OfflineBgProcessor`).
- [ ] Unused assets: `assets/default_bg.jpg`; `assets/default_bg.png` duplicates `bg_samples/room-2.png`
      (same size) — point the default pref at the sample and delete it. `icon_assets/tmp/` (untracked).
- [ ] `app/build.gradle`: `tensorflow-lite-api` / `tensorflow-lite-gpu-api` are pulled in transitively; drop if build still passes.
- [ ] `ScreenRecordService.stopRecordingInternal`: the `MediaStore` insert with `_data` always fails on
      Android 11+ ("Mutation of _data is not allowed", caught); the scan right after does the real work. Remove it.
- [ ] Decide whether to keep `BgTestActivity` (debug-only, `src/debug/AndroidManifest.xml`) and its
      `input` / `circle` adb extras; remove before release if not needed.
