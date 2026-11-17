# AI Video Editing Engine — App Build Plan (parts A1–A7)

This file is the design overview of the app side. **The build runs from `to-build/tasks.md`**: self-contained task
cards, one per session, each backend card next to its client app card, with the exact contract JSON. Progress is tracked
there only. Branch `feat/ai-video-edit`.

Top rule, above every other rule: **frictionless, smooth UX that lowers the user's cognitive load.**

- Spec: `ai-video-editing-spec.md` (cited as "spec §N"). Read it before any part.
- Backend tracker for the same feature: `/home/haku/projects/Next.js/Social-Media-Manager/AI_VIDEO_EDIT_BACKEND_PLAN.md`
  (parts B1–B5). App parts that call a route need that backend part built first (noted per part).
- Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## House rules for this feature

1. Package tree inside `:app` (no new Gradle module), strict separation of concerns:
   - `aiedit/capture/` — records the event log while recording.
   - `aiedit/engine/` — renders a plan. Imports only other `aiedit/engine/` classes, the Android SDK and lottie-android.
     Never app UI, publish, auth, settings or subtitle classes; those come in through small interfaces.
   - `aiedit/flow/` — API calls, uploads, job state, foreground service, and the glue to the UI.
2. The master recording is never modified. Every output is a new file.
3. Never log transcript words, plan texts or event log contents to `ActivityLog` or crash reports
   (`ACTIVITY_LOG_PRIVACY.md`). Event logs stay in app-private storage.
4. 9:16 always first wherever ratios are listed, rendered or shown.
5. Match the existing code style (comment density, naming, Java idioms of the neighbouring classes).

## Parts and their task cards (status lives in `to-build/tasks.md`)

| Part | Description | Task cards |
|---|---|---|
| A1 | Event log capture while recording | T1.3 |
| A2 | Engine data layer: plan, contract, timeline (pure Java + JVM tests) | T0.2 |
| A3 | Renderer core: decode → GL compositor → encode, cuts, speed, audio, captions, proxies | T2.3, T2.4 |
| A4 | First-wave effects: text looks, video effects, transitions, Lottie and icons, sounds | T2.4, T4.3, T5.2 |
| A5 | Flow and UX: AI Edit button, uploads, job, foreground render, results, Settings, caption credits | T3.1, T3.2, T6.2 |
| A6 | Frame-difference activity detection for imported videos | T7.1 (later) |
| A7 | Second-wave and person effects | T7.2 (later) |

---

## A1 — Event log capture while recording

**Goal:** every recording gets an exact activity log (spec §10) and facecam track (spec §9), at almost no cost.

**New:** `aiedit/capture/EventLog.java` (one instance per recording; thread-safe; buffered in memory),
`aiedit/capture/EventLogStore.java` (JSON read/write in `getFilesDir()/aiedit/logs/<master name>.json`),
`aiedit/capture/EventLogRemap.java`.

**Clock:** `ScreenRecordService.recordStartedAt` (`SystemClock.elapsedRealtime()`, set right after
`mediaRecorder.start()`, `service/ScreenRecordService.java` ~line 343). Event time = now − recordStartedAt. Events
before start or after stop are dropped.

**Hooks (one call each, no logic in the caller):**
- Strokes: `ui/DrawingBoardView.onTouchEvent` ACTION_DOWN (stroke start) and ACTION_UP / ACTION_CANCEL (stroke end
  + bounding box in screen coordinates); the same for slide ink via `ui/SlideInkController`. Undo, redo and clear log a
  short activity entry.
- Board shown / hidden: `ui/DrawingBoardController` show and hide paths.
- Slide change: `MainActivity.goToNextSlide()` / `goToPreviousSlide()` and the other places that move
  `currentSlideIndex` while recording.
- Facecam: end of drag and resize in `MainActivity.setupDraggableCameraContainer()` and
  `ui/FloatingCamOverlay.DragListener` → a facecam key `{ t_ms, cx, cy, radius }` normalised to the screen.
  The first key is written at recording start.

**At stop:** merge strokes closer than 400 ms into one `brush` span, write the JSON next to the master's name (same
base name, so crops and edited files can find it), free the buffer.

**Remap (`EventLogRemap`):** for a file made by the manual editor, map times through its kept ranges
(`edit/EditProject`); for a crop, map coordinates into the crop rectangle. The server never sees the manual edit.

**Done when:** a recording with board use, slide changes and a facecam drag produces a log whose entries line up with
the video (check by reading against a debug build's `SlideLogger` output on the other PC).

---

## A2 — Engine data layer (pure Java, JVM tests)

**Goal:** parse and reason about plans with no Android graphics, fully unit tested.

- `aiedit/engine/plan/` — `Plan`, `PlanItem`, `PlanComponent` (styled text, lottie, icon image), `PlanCaptions`,
  `Palette`, `PlanParser` (org.json; rejects an unknown `contract_version`).
- `aiedit/engine/contract/` — `EngineContract` (registry entries, text looks, palette, level rules, speed rules,
  zones per ratio) loaded from `app/src/main/assets/aiedit/contract.json` (a copy of the backend's
  `GET /public/v1/ai-edits/contract` answer, written by hand in this part from the B1 contract files) and replaceable
  by a fresh copy downloaded in A5. `Capabilities` lists the effect ids this build can render.
- `aiedit/engine/timeline/` — `OutputTimeline`: source ↔ output time through cuts and speed segments; facecam position
  at an output time.
- Tests in `app/src/test/java/com/jovoc/facecampresentationrecorder/aiedit/`: parser (valid, unknown version, unknown
  effect), timeline (cuts, speed, edges), facecam lookup.

---

## A3 — Renderer core

**Goal:** `Engine.render(RenderJob, Listener)` turns the source video + plan into a finished MP4 per ratio, with cuts,
speed and captions, before any effect exists.

- `aiedit/engine/Engine.java` (facade), `RenderJob` (source file, plan, ratio, output file, asset files, caption painter,
  cancel flag), `Listener` (progress 0–100, done, failed).
- `aiedit/engine/render/` — patterned on `edit/EditRenderer` and `edit/AudioMixRenderer` (copy the patterns; do not
  import them):
  - `RenderPipeline`: MediaExtractor + MediaCodec decode → OpenGL ES 3.0 `Compositor` (source as external texture,
    crop rect per ratio, layers on top) → MediaCodec H.264 encode → MediaMuxer.
  - `OutputSizes`: 9:16 = 1080 × 1920 (12 Mbps), 4:5 = 720 × 900 and 1:1 = 720 × 720 (6 Mbps). Crop rect per ratio
    from the master size (same geometry as `util/VideoCropHelper`).
  - Cuts and speed applied in the decode loop by source timestamps.
  - `AudioPath`: decode to PCM, drop cut ranges with ~5 ms crossfades, resample speed segments, mix sound effects
    under the voice at a fixed low level, AAC encode; audio and video stay in sync.
- Captions: `aiedit/engine/captions/CaptionPainter` interface (draws the caption for an output time onto a Canvas);
  `aiedit/flow/SubtitleCaptionPainter` implements it with `subtitle/SubtitleDrawer` + `subtitle/SubtitleStyle` so the
  AI version uses the user's own subtitle template and its adaptive position by ratio. Emphasis words from the plan.
- Frame overlays drawn with Canvas into a reusable bitmap → texture; no per-frame allocation.
- Cancel and failure clean up the partial output.

**Done when:** a plan with only cuts and captions renders all three ratios, 9:16 first; output length equals the
plan's `output_duration_ms`; the master is unchanged.

---

## A4 — First-wave effects

**Goal:** the first wave of spec §14 renders exactly as the contract describes.

- `aiedit/engine/effects/` — one class per effect id, created by `EffectFactory` from the registry entry and params:
  video `v_punch_in`, `v_push_in`, `v_alt_crop` (transform in the compositor), `l_flash`, `l_rgb_glitch` (shader
  uniforms), `y_slide_grow_face_shrink`, `y_face_grow_slide_shrink` (use the facecam track), captions emphasis
  `c_word_highlight`, `c_active_pop`, `c_emphasis_big`, transitions `tr_whip`, `tr_flash_cut`.
- `aiedit/engine/text/` — the 6 looks (sticker, label pill, 3D, soft glow, gradient fill, highlighter) with
  `StaticLayout` + `TextPaint` (all scripts), stroke and shadow layers, `BlurMaskFilter`, `LinearGradient`, stacked
  copies for 3D; `PaletteResolver` (multi colour rotation, or brand colour → darker shade + black or white text by
  contrast); entry and exit animations (pop, stamp, slide up, word by word; quick exit) on the cached text bitmap's
  transform; text effects `x_pop`, `x_stamp`, `x_sticker`, `x_3d`, `x_glow`, `x_marker_sweep`, `x_label_tag`.
- `aiedit/engine/overlay/` — Lottie with lottie-android (`LottieDrawable` drawn at progress = output time × fit
  factor; never slower than native), icon images, `i_scale_pop`.
- Sounds: the plan's sound ids mixed in `AudioPath` from the downloaded files.
- Add the lottie-android dependency to `app/build.gradle` (check the current stable version when building).
- Keep `Capabilities` exactly equal to what renders.

**Done when:** a golden plan with every first-wave effect renders; every animation time is inside spec §12; no overlay
crosses the facecam at any time.

---

## A5 — Flow and UX (needs backend B2–B5)

**Goal:** one tap from the preview to an AI-edited video, with honest costs and no surprises (spec §4, §5).

- `aiedit/flow/AiEditApi` — `POST /ai-edits`, `GET /ai-edits/:id`, `GET /ai-edits/quote`, `GET /ai-edits/contract`,
  `POST /ai-captions/:id/use`, `POST /ai-captions/:id/release`; same client style as `publish/PublishApi`.
- Uploads — `publish/DirectUploader` with a new `UploadKind.EDIT_PROXY` (purpose `edit_proxy`, `.mp4`). Audio: reuse the
  caption id from `subtitle/SubtitleEmbedStore` when present, else `publish/AudioExtractor` + `uploadAudio`.
  Proxy (standard, heavy only): the engine's MediaCodec `renderProxy` makes pieces of ≤ 60 s, short side 360, ~15 fps,
  low bitrate, mono audio, each ≤ 16 MiB (the FFmpegKit build has no x264); one upload per piece. No video processing
  happens on the server.
- `aiedit/flow/AiEditJob` + `AiEditStore` — state per recording (session id, level, job id, plan path, outputs);
  survives process death.
- `aiedit/flow/AiEditService` — foreground service (pattern: `service/BgReplaceService`) with one notification:
  "Uploading…", "Planning…", "Rendering 9:16…", "Done". Renders 9:16 first and shows it as soon as it is ready.
- Preview (`ui/VideoPlayerDialog`, `res/layout/dialog_video_player.xml`):
  - Primary button **"AI Edit · N credits"** (cost from `quote`, cached); tap = default level, no dialog.
  - A small chevron opens a 3-option sheet (Basic, Standard, Heavy, each with cost and one line).
  - Discard (existing delete) → Undo snackbar instead of a confirm dialog; on final delete call caption `release`.
  - **Record again**: close the preview and return to the recorder on the same slide.
  - Too few credits → the button opens `ui/BillingLink`; failure → one message with "Try again" (credits were
    refunded by the server).
- Results: `<base>_aiedit.mp4` (+ the crop names), put in the master's group with `util/VideoGroupStore.assign`, shown
  with an "AI edit" tag like `RecordingItem.isEdited()` / `adapter/RecordingsAdapter`; opens first in the preview
  carousel next to the original. The plan JSON is kept beside it (re-render without a server call).
- Settings (`ui/SettingsActivity`, pattern `setupSubtitles()`; keys in `util/RecordSettings`): "AI edit level"
  (default Standard), "Text colours" (Multi colour / Brand colour), "Sound effects" (default on).
- Caption credits (B5): the "sub" embed calls `use` once per caption.

**Done when:** record → AI Edit → the 9:16 result appears tagged "AI edit"; a second tap does not charge again; discard
charges nothing; closing the app during rendering resumes or fails cleanly.

---

## A6 — Frame-difference detection (later)

For imported videos without an event log: low-resolution MediaCodec decode at ~10 fps, 64 × 112 grayscale, facecam
masked, 4 × 4 grid mean absolute difference, configurable threshold, spans merged under 400 ms (spec §10, source 2).

## A7 — Second wave and person effects (later)

The rest of spec §14 (speed ramps, look, layout, text, Lottie and facecam effects), then person effects on top of the
existing segmentation in `bgreplace/`, and mild effects inside protected spans (`content_safe`).
