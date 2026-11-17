RULE: this PC hangs under load, so do not run gradle, `pnpm build`, unit tests, specs, dev servers or the emulator. Write
code only and verify by reading it against this file and the spec; the owner builds and runs tests on a higher-RAM PC.

One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list, nothing else is assumed.

-----------------

# AI Video Editing Engine — Task Plan (backend + client app, card by card)

Top rule, above every other rule: **frictionless, smooth UX that lowers the user's cognitive load.**

## Shared context (every card reads this)

### What we are building
An AI editing engine: the phone records, the user taps **AI Edit**, the server plans a fast social-media style edit
(cuts, captions, text pop-ups, zooms, icons, sounds) as JSON, and the phone renders it. Three levels: Basic, Standard,
Heavy. Full spec: `ai-video-editing-spec.md` in the app repo root (cited "spec §N").

### Repos, branches, paths
- App (Android, Java): `/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder`. Java paths below are
  relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`. Tests: `app/src/test/java/...`.
- Backend (NestJS monorepo, Postiz fork): `/home/haku/projects/Next.js/Social-Media-Manager`. `L/` =
  `libraries/nestjs-libraries/src/`. Before any backend card read `CLAUDE.md` and `_planning/system/STANDARDS.md`.
- Both repos: branch `feat/ai-video-edit`. Commit per card: `feat(ai-edit): <card id> <what>`, ending with the
  `Co-Authored-By` line the session gives. Never commit `test_audio.wav` / `test_video.mp4`.
- Design overviews (background only, no status): app `AI_VIDEO_EDIT_APP_PLAN.md`, backend
  `AI_VIDEO_EDIT_BACKEND_PLAN.md`. **This file is the only progress tracker.**

### Rules for every card
1. Match the neighbouring code: naming, comment density (the code here has a one-line "what" comment on classes and
   methods), idioms. Backend: DTO → Controller → Service → Repository, Prisma only, migrations only add, workflows and
   activities already on `main` are never edited (add new ones). Money only through `AiChargingService` /
   `CreditsService` with an idempotency key; prices only from `pricing.config.ts`.
2. App engine code lives in `aiedit/engine/` and imports only `aiedit/engine/*`, the Android SDK and lottie-android.
   Capture code in `aiedit/capture/`, network/job/UI glue in `aiedit/flow/`. No new Gradle module.
3. The master recording is never modified. Outputs are new files.
4. Never log transcript words, plan texts, brand text or event log contents (app `ACTIVITY_LOG_PRIVACY.md`; backend
   logs). Errors to the phone use our own codes and words.
5. No video processing on the server (it has no ffmpeg). The phone makes anything video-shaped with MediaCodec.
6. 9:16 is always first wherever ratios are listed, rendered or shown.
7. Every workspace-owned row is read and written with its `organizationId`; another workspace's id answers 404.

### Facts verified in the code (2026-11-17)
- Recording: `service/ScreenRecordService` — whole screen (e.g. 1080 × 2400), H.264 30 fps 8 Mbps, AAC;
  `recordStartedAt = SystemClock.elapsedRealtime()` right after `mediaRecorder.start()`.
- Crops (`util/VideoCropHelper`): **top-anchored, full width**; only rows are dropped from the bottom (lossless
  `h264_metadata=crop_bottom`). Crop height = width × 16/9 (9:16), × 5/4 (4:5), × 1 (1:1). So the facecam can be
  outside a crop.
- FFmpegKit build has **no x264** (it re-encodes to poor MPEG-4), so every encode is MediaCodec.
- Facecam: round, draggable and resizable (`MainActivity.setupDraggableCameraContainer`, `ui/FloatingCamOverlay`).
- Captions: `subtitle/SubtitleDrawer` (+ `resolveAdaptivePositionY`: 0.95 for 9:16, ~0.908 4:5, 0.885 1:1),
  `subtitle/SubtitleStyle`, `subtitle/SubtitleCache`, `subtitle/SubtitleEmbedStore` (caption id per recording).
- Backend transcript words: `L/muse/transcription.provider.ts` `TranscriptWord { text, startMs, endMs, type }`;
  stored in `AiCaption.segments[].words`.
- Backend money: `L/database/prisma/credits/ai/ai.charging.service.ts` (`reserve`, `finish`, `estimate`); nightly
  reconciliation releases any AI reservation older than 24 h.
- The app's `account/Workspace` has no brand colour; the server reads it (workspace `color`, `brand.profile`).

### The contract (exact; T0.1 writes it on the server, T0.2 mirrors it in the app)

`GET /public/v1/ai-edits/contract` answers (and the app bundles as `app/src/main/assets/aiedit/contract.json`):

```json
{
  "contract_version": 1,
  "levels": {
    "basic":    { "max_feature_effects": 3,  "max_icons": 0, "text_kinds": ["keyword_echo", "hook"], "transitions": false, "sounds": false, "perception": false, "writer": false },
    "standard": { "max_feature_effects": 6,  "max_icons": 2, "text_kinds": ["keyword_echo", "hook", "commentary"], "transitions": true, "sounds": true, "perception": true, "writer": false },
    "heavy":    { "max_feature_effects": 10, "max_icons": 4, "text_kinds": ["keyword_echo", "hook", "commentary", "complementary"], "transitions": true, "sounds": true, "perception": true, "writer": true }
  },
  "speed": { "entry_ms": [120, 300], "exit_ms": [100, 200], "transition_ms": [150, 300], "beat_ms": [80, 400],
             "flash_ms": [80, 150], "push_in_max_ms": 800, "slow_motion_max_ms": 500,
             "text_hold": { "base_ms": 500, "per_word_ms": 150, "max_ms": 2500 }, "icon_hold_ms": [500, 1500], "lottie_max_factor": 3.0 },
  "protection": { "padding_ms": 300, "min_window_ms": 500, "merge_gap_ms": 400 },
  "outputs": {
    "9:16": { "width": 1080, "height": 1920, "bitrate": 12000000, "safe": { "x0": 0.06, "x1": 0.94, "y0": 0.10, "y1": 0.62 } },
    "4:5":  { "width": 720,  "height": 900,  "bitrate": 6000000,  "safe": { "x0": 0.06, "x1": 0.94, "y0": 0.08, "y1": 0.66 } },
    "1:1":  { "width": 720,  "height": 720,  "bitrate": 6000000,  "safe": { "x0": 0.06, "x1": 0.94, "y0": 0.08, "y1": 0.68 } }
  },
  "zones": { "upper_left": [0.22, 0.20], "upper_center": [0.5, 0.20], "upper_right": [0.78, 0.20],
             "middle_center": [0.5, 0.55], "lower_center": [0.5, 0.85] },
  "effects": [ /* RegistryEntry, spec §14 format, every id of spec §14 */ ],
  "text_styles": [ /* see "Text looks" below */ ],
  "palette": { "multi": ["#FF3B30", "#FFD60A", "#34C759", "#32D7FF", "#FF2D92"], "ink": "#111111", "paper": "#FFFFFF" },
  "phrases": [ { "id": "did_you_know", "mood": ["surprising"], "text": { "en": "Did you know?", "bn": "জানেন কি?" } } ],
  "sounds": ["pop_1", "pop_2", "whoosh_short", "swoosh", "ding", "impact_soft", "click"]
}
```
Zones are centres as fractions of the output's **safe rectangle** (x across x0–x1, y across y0–y1).

**Text looks** (sizes are fractions of the text size `s`; colour roles: `accent`, `accent_dark` = accent × 0.55
brightness, `on_accent` = ink or paper by contrast, `ink`, `paper`):

| id | fill | stroke | extras |
|---|---|---|---|
| `sticker` | accent | ink, 0.14 s | hard shadow ink (0.05 s, 0.06 s, no blur); tilt −6°…6° |
| `label_pill` | on_accent | — | rounded rect accent, radius 0.5 × height, padding 0.35 s × 0.20 s; soft shadow ink 35 % (0, 0.04 s, blur 0.08 s) |
| `3d` | accent | ink, 0.06 s | 6 copies in accent_dark, each offset (0.012 s, 0.016 s) |
| `glow` | paper | accent, 0.04 s | outer glow accent 85 %, blur radius 0.35 s |
| `gradient` | vertical paper → accent | ink, 0.10 s | — |
| `highlighter` | ink | — | bar accent 85 % behind the text, height 0.55 of the line, sweeps left → right during entry |

Overlay text size: 2.5 × the caption text size, shrunk so the block is ≤ 0.84 of the output width (1–2 lines);
all caps only for scripts that have case. Entry animations: `pop` (scale 0.6 → 1.08 → 1.0, out_back), `stamp`
(2.0 → 1.0, then 3 shakes of 0.01 W within 120 ms), `slide_up` (from +0.08 H, alpha 0 → 1, out_cubic),
`word_by_word` (each word at its spoken start). Exit: scale 1.0 → 0.85 with alpha → 0, out_cubic.

**Request** (`POST /public/v1/ai-edits` body; spec §9 updated):

```json
{
  "sessionId": "uuid of the recording", "level": "basic|standard|heavy",
  "aiCaptionId": "optional, a READY caption of this recording", "audioUploadId": "else a completed transcript_audio upload",
  "proxyUploadIds": ["standard/heavy: completed edit_proxy uploads, in time order, each <= 60 s"],
  "request": {
    "contract_version": 1,
    "video": { "duration_ms": 118000, "width": 1080, "height": 2400, "fps": 30, "source": "in_app_recording|imported" },
    "outputs": [ { "ratio": "9:16", "crop": { "x": 0, "y": 0, "w": 1, "h": 0.8 }, "caption_band": { "y0": 0.88, "y1": 0.99 } } ],
    "facecam_track": [ { "t_ms": 0, "cx": 0.78, "cy": 0.62, "r": 0.13 } ],
    "activity_map": [ { "id": "a1", "type": "brush|board_show|board_hide|slide_change|motion", "start_ms": 0, "end_ms": 0,
                        "region": { "x": 0, "y": 0, "w": 0, "h": 0 }, "strength": 1, "source": "event_log|frame_diff" } ],
    "text_colors": "multi|brand", "language": "optional ISO 639-3 from the caption",
    "capabilities": { "effects": ["..."], "text_styles": ["..."] },
    "options": { "sound_effects": true }
  }
}
```
`crop`, `cx`, `cy`, `region` are fractions of the master width / height; `r` is a fraction of the master width;
`caption_band` is a fraction of that output's height. The phone remaps everything through manual-editor cuts first.

**Plan** (`GET /public/v1/ai-edits/:id` → `{ id, status, error?, cost, plan?, assets? }`): spec §16, plus
`palette: { "colors": ["#..."] }` already resolved by the server (multi colours, or the workspace colour), and
`assets: [{ "id", "kind": "lottie|icon|sound", "url", "native_duration_ms"?, "width"?, "height"? }]`. A component's
`asset_id` and a sound's `id` name an entry of `assets`. Statuses: `PENDING`, `TRANSCRIBING`, `PLANNING`, `READY`,
`NO_SPEECH`, `FAILED`. Error codes: 402 `INSUFFICIENT_CREDITS` / `AI_CAP_REACHED`, 400 `VIDEO_TOO_LONG`
(> `AI_EDIT_MAX_SECONDS`, default 600), 404 `EDIT_NOT_FOUND` / `CAPTION_NOT_FOUND` / `AUDIO_NOT_FOUND`,
409 `UPLOAD_NOT_COMPLETE`, 503 `AI_EDIT_NOT_CONFIGURED`.

**Prices:** `ai.edit.basic` 1, `ai.edit.standard` 2, `ai.edit.heavy` 3 credits per started 3 minutes.
`GET /public/v1/ai-edits/quote?seconds=N` → `{ "basic": 1, "standard": 2, "heavy": 3 }` for this workspace and length.

## Progress

Order = the order below. "Basic end to end" works after T3.2; everything after adds quality.

| Card | Repo | What | Depends on | Status |
|---|---|---|---|---|
| T0.1 | backend | Contract modules + `GET /ai-edits/contract` | — | ✅ |
| T0.2 | app | Contract mirror, plan model, parser, timeline (JVM tests) | T0.1 | ⬜ |
| T1.1 | backend | Core: words, protected spans, free windows, silence cuts, remap | T0.1 | ⬜ |
| T1.2 | backend | Core: slots, assembler, validator, golden plans | T1.1 | ⬜ |
| T1.3 | app | Event log capture while recording + remap | T0.2 | ⬜ |
| T2.1 | backend | Data model, credits, `edit_proxy` upload purpose | T1.2 | ⬜ |
| T2.2 | backend | Routes + Basic workflow (transcript → plan → validate → commit) | T2.1 | ⬜ |
| T2.3 | app | Renderer core: decode → GL → encode, crops, cuts, audio, `renderProxy` | T0.2 | ⬜ |
| T2.4 | app | Captions painter, text looks, palette, `v_punch_in` | T2.3 | ⬜ |
| T3.1 | app | Job flow: API client, uploads, job store, foreground service, results | T2.2, T2.4, T1.3 | ⬜ |
| T3.2 | app | UX: AI Edit button, level sheet, discard undo, record again, Settings | T3.1 | ⬜ |
| T4.1 | backend | Perception with Clef-omni (Standard, Heavy) | T2.2 | ⬜ |
| T4.2 | backend | Writer LLM (Heavy) + brand voice, banned words, palette | T4.1 | ⬜ |
| T4.3 | app | Video effects + transitions (first wave) | T2.4 | ⬜ |
| T5.1 | backend | Assets: IconScout + shared R2 cache + sound URLs | T2.2 | ⬜ |
| T5.2 | app | Asset cache, Lottie and icon overlays, sound mix | T5.1, T4.3 | ⬜ |
| T6.1 | backend | Caption credits held until used; use / release routes | T2.2 | ⬜ |
| T6.2 | app | Caption use on embed, release on discard, AI edit reuses the caption | T6.1, T3.2 | ⬜ |
| T7.1 | app | Frame-difference detection for imported videos | T3.1 | later |
| T7.2 | both | Second-wave and person effects | all | later |

---

## T0.1 — Contract modules + contract route [backend]

**Goal:** the single source of truth of the engine contract, served as JSON.
**Read first:** spec §6, §12, §13, §14, §16; `L/database/prisma/ai-captions/ai.captions.service.ts` (style only, first
100 lines); `apps/backend/src/public-api/routes/v1/public.integrations.controller.ts` (the ai-captions routes);
`L/database/prisma/database.module.ts`.
**Build:**
- `L/ai-video-edit/contract/edit.contract.ts` — `CONTRACT_VERSION`, types (`EditLevel`, `OutputRatio`, `EditRequest`,
  `ActivityEntry`, `FacecamKey`, `EditWord`, `Plan`, `PlanItem`, `PlanComponent`, `PlanCaptions`, `RegistryEntry`,
  `TextLook`), `LEVEL_RULES`, `SPEED_RULES`, `PROTECTION`, `OUTPUTS`, `ZONES` — values exactly as in Shared context.
- `edit.registry.ts` — every effect id of spec §14 in the registry format (with `min_level`, `brand_colorable`);
  first wave with real defaults (punch_in scale 1.15 out_back 150–400 ms; push_in 1.06–1.10 400–800 ms; alt_crop close
  scale 1.18 centred at (0.5, 0.4); flash peak alpha 0.85; rgb_glitch offset 0.012 W 150–300 ms; layout grow 1.0 → 1.5
  facecam copy, frame 0.92 behind); helpers `effectById`, `effectsForLevel(level, capabilities)`.
- `edit.styles.ts` (text looks table), `edit.phrases.ts` (≈ 40 phrases, `en` + `bn`, mood tags), `edit.sounds.ts`.
- `edit.contract.view.ts` — `contractView()` → the JSON above.
- `L/database/prisma/ai-video-edits/ai.video.edits.service.ts` with only `contract()`; register in `database.module.ts`.
- Route `GET /ai-edits/contract` (`posts:read`) in the public controller, same style as the caption routes.
- `_planning/02_contracts/ai.video-editing.md` (house contract format, satisfies `G-008`) + its row in `_planning/_state.md`.
- Spec: `edit.contract.view.spec.ts` (version, every level, 9:16 first, every first-wave id present, phrases have `en`).
**Done when:** nothing in `L/ai-video-edit/` imports Nest, Prisma or a network client.

## T0.2 — Contract mirror, plan model, parser, timeline [app]

**Goal:** the app reads the contract and plans with no Android graphics, unit tested.
**Read first:** Shared context; spec §16; the backend's `L/ai-video-edit/contract/` (built in T0.1) to copy values.
**Build:**
- `app/src/main/assets/aiedit/contract.json` — exact JSON of `contractView()`.
- `aiedit/engine/contract/` — `EngineContract` (load from assets or a newer downloaded copy in
  `getFilesDir()/aiedit/contract.json`; refuses an unknown `contract_version`), `RegistryEntry`, `TextLook`,
  `OutputSpec`, `Capabilities` (ids this build can render; starts with captions + `t_jump_cut`).
- `aiedit/engine/plan/` — `Plan`, `PlanItem`, `PlanComponent`, `PlanCaptions`, `Palette`, `PlanAsset`, `PlanParser`
  (org.json).
- `aiedit/engine/timeline/OutputTimeline` — source ↔ output through cuts and speed segments; `facecamAt(outputMs)`;
  `cropFor(ratio, masterW, masterH)` (top-anchored).
- Tests (`app/src/test/java/com/jovoc/facecampresentationrecorder/aiedit/`): parser valid / bad version / unknown effect;
  timeline cuts, speed, edges; crop rects for 1080 × 2400.

## T1.1 — Core: words, protected spans, free windows, silence cuts, remap [backend]

**Goal:** the timing half of the planner as pure functions.
**Read first:** Shared context; spec §7 (phases), §10, §11 steps 1–3; `L/muse/transcription.provider.ts`;
`L/ai-video-edit/contract/edit.contract.ts`.
**Build** in `L/ai-video-edit/core/`:
- `edit.words.ts` — `toEditWords(words)`: `type === 'word'` only, index from 0, integer ms.
- `edit.windows.ts` — merge `brush`/`motion` closer than 400 ms, pad 300 ms → protected spans; markers never protected;
  `freeWindows(durationMs, spans)` drops windows < 500 ms.
- `edit.timeline.ts` — `silenceCuts(words, spans, { gapMs: 600, keepMs: 120 })` (never inside a span);
  `buildMap(durationMs, cuts, speedSegments)`; `remapWords`, `remapEntries`, `remapFacecam`.
- Specs with fixtures in `core/fixtures/`: brush at start / middle / end, overlapping spans, tiny gaps; cuts and speed
  shift words, spans and facecam correctly.

## T1.2 — Core: slots, assembler, validator, golden plans [backend]

**Goal:** the decision half of the planner, deterministic and fully validated.
**Read first:** Shared context; spec §6, §11 steps 4 and 7, §13, §16, §17; `L/ai-video-edit/core/*.ts` from T1.1.
**Build** in `L/ai-video-edit/core/`:
- `edit.slots.ts` — candidate slots in free windows (numbers, long or rare words, word before a pause, topic repeats,
  first sentence after a slide change) + one hook slot in the first 3 s.
- `edit.assembler.ts` — `assemble({ level, words, windows, slots, outputs, facecam, scores?, texts?, assets? })` →
  items: level budget, pacing (a visible change about every 2–3 s), variety, cooldowns, one text overlay at a time,
  zones, text hold time, sound pairing, `color_slot` rotation. Works with no scores and no texts (Basic: keyword echo
  in `sticker` or `label_pill` + `v_punch_in` + captions emphasis).
- `edit.validator.ts` — every rule of spec §17 (overlay vs facecam only in outputs where the facecam is inside the
  crop; overlays inside every output's safe rect and outside its `caption_band`), repair order, `{ plan, warnings,
  rejections }`, sorted, unique ids.
- Specs: one pass + one fail per validator rule; golden plans for 3 English and 2 Bengali fixture transcripts at each
  level → zero warnings.

## T1.3 — Event log capture while recording [app]

**Goal:** an exact activity log and facecam track per recording (spec §10), at almost no cost.
**Read first:** Shared context; spec §10; `service/ScreenRecordService.java` (start ~line 337, stop path);
`ui/DrawingBoardView.java` (`onTouchEvent`), `ui/SlideInkController.java`, `ui/DrawingBoardController.java`;
`MainActivity.java` (`goToNextSlide`, `goToPreviousSlide`, `setupDraggableCameraContainer`);
`ui/FloatingCamOverlay.java` (`DragListener`); `edit/EditProject.java` (kept ranges).
**Build:**
- `aiedit/capture/EventLog` — static `begin(startElapsedMs, screenW, screenH)` / `end(File master)`; `stroke(start|end,
  rect)`, `boardShown(bool)`, `slideChanged()`, `facecam(cx, cy, r)`; events outside a recording are ignored; strokes
  merged under 400 ms at end; a new `sessionId` (uuid) per recording.
- `aiedit/capture/EventLogStore` — JSON at `getFilesDir()/aiedit/logs/<master base name>.json`
  `{ sessionId, video: { width, height, duration_ms }, activity_map, facecam_track }`.
- `aiedit/capture/EventLogRemap` — through manual-editor kept ranges (for `*_edited` files) → request fields.
- One-line hook calls in the files listed above; the first facecam key at recording start.
**Done when:** callers hold no logic; nothing is logged to `ActivityLog`.

## T2.1 — Data model, credits, `edit_proxy` upload purpose [backend]

**Goal:** storage and money for edit jobs, and the proxy upload type.
**Read first:** Shared context; `L/database/prisma/schema.prisma` (`AiCaption`, `MobileUpload`, `Organization`);
`L/database/prisma/migrations/20261021120000_ai_captions/migration.sql`; `L/database/prisma/credits/pricing/
pricing.config.ts` and `caption.minutes.ts`; `L/database/prisma/credits/ai/ai.charging.service.ts`;
`L/dtos/mobile-upload/mobile.upload.create.dto.ts`; `L/database/prisma/mobile-upload/mobile.upload.service.ts` and
`.repository.ts`; `L/upload/r2.uploader.ts` (top); `L/throttler/throttler.provider.ts`; `_planning/_drift.md` (format).
**Build:**
- Prisma `AiVideoEdit` (`id`, `organizationId` + relation `aiVideoEdits`, `userId?`, `sessionId`, `level`, `status`
  default `PENDING`, `error?`, `aiCaptionId?`, `audioUploadId?`, `proxyUploadIds String[]`, `request Json`,
  `words Json?`, `scores Json?`, `plan Json?`, `assets Json?`, `log Json?`, `language?`, `sourceSeconds Int`, `cost Int`,
  `deletedAt?`, timestamps; `@@unique([organizationId, sessionId, level])`, `@@index([organizationId, createdAt])`)
  + migration `<timestamp>_ai_video_edits`.
- Pricing actions `ai.edit.basic|standard|heavy`; `edit.blocks.ts` (+ spec): started 3-minute blocks, ≥ 1.
- `AiKind` += `'edit'` with `editLevel`; `priced()` → `ai.edit.<level>` × blocks; `POST /ai/quote` accepts it.
- `edit_proxy` purpose: `.mp4` only, ≤ `AI_EDIT_MAX_PROXY_BYTES` (16 MiB), pending cap 4, no media row, 24 h sweep,
  `hasCaption` also true when an edit job names the upload, throttled in the audio bucket. Audio behaviour unchanged.
- Drift entries: `credits.ai-charging`, `api.mobile-direct-upload`.
- Specs: blocks; charging `edit` quote; upload purpose rules.

## T2.2 — Routes + Basic workflow [backend]

**Goal:** a phone can create a job, is charged once, and gets a valid Basic plan.
**Read first:** Shared context; `L/database/prisma/ai-captions/ai.captions.service.ts` (create, steps, expire, settle);
`ai.captions.repository.ts`; `apps/orchestrator/src/workflows/ai.captions.workflow.ts`;
`apps/orchestrator/src/activities/ai.captions.activity.ts`; `apps/orchestrator/src/app.module.ts`;
`apps/orchestrator/src/workflows/index.ts`; the public controller caption routes; `L/ai-video-edit/core/`.
**Build:**
- `L/dtos/ai-video-edits/ai.video.edit.create.dto.ts` (Shared context request; exactly one of caption / audio; proxies
  required for standard and heavy; activity map ≤ 2000).
- `ai.video.edits.repository.ts`; service `create` (repeat → same job; checks; seconds from caption `audioSeconds` or
  the WAV header; > 600 s → `VIDEO_TOO_LONG`; reserve key `ai:edit:<id>`; 402 removes the row, nothing held; start
  workflow `ai_video_edit_<id>`), `get` (stale 3 h → ended on read, like captions), `quote(orgId, seconds)`.
- Routes: `POST /ai-edits` (`posts:write`, 202), `GET /ai-edits/quote`, `GET /ai-edits/:id` (`posts:read`).
- New workflow `aiVideoEditWorkflow({ editId })` + `AiVideoEditActivity` (one object parameter per method):
  `transcribeEdit` (caption words, else `TranscriptionProvider` via the `MuseTranscriptionService` token, then delete the
  audio; < 3 words → `NO_SPEECH`, released) → `perceiveEdit` / `writeEdit` (no-ops until T4.1 / T4.2) → `planEdit`
  (T1 core; palette multi) → `finishEdit` (commit, `READY`); `failEdit` releases, `FAILED`, deletes uploads.
- Specs with mocks: create repeat, 402, too long, workflow start failure (released), Basic happy path, no speech.

## T2.3 — Renderer core [app]

**Goal:** `Engine.render` writes a finished MP4 per ratio with cuts and speed; `Engine.renderProxy` writes a proxy piece.
**Read first:** Shared context; spec §19; `edit/EditRenderer.java` (decode / GL / encode loop, `setUpGl`, `drawFrame`,
`drainEncoder`); `edit/AudioMixRenderer.java`; `aiedit/engine/` from T0.2.
**Build** in `aiedit/engine/`:
- `Engine` facade, `RenderJob` (source, plan, ratio, output, assets dir, `CaptionPainter`, cancel flag), `Listener`.
- `render/RenderPipeline` — MediaExtractor + MediaCodec decode → GL ES 3.0 `Compositor` (external texture, top-anchored
  crop, output size from contract) → MediaCodec H.264 → MediaMuxer; cuts and speed by source timestamps.
- `render/AudioPath` — PCM decode, drop cut ranges with ~5 ms crossfades, resample speed segments, a mix bus for later
  sounds, AAC encode, in sync.
- `render/LayerCanvas` — one reusable bitmap + texture for Canvas-drawn layers per frame (no per-frame allocation).
- `Engine.renderProxy(source, startMs, endMs, out)` — 640 × 360-class (short side 360), 15 fps, ~600 kbps H.264, mono AAC
  32 kbps, ≤ 60 s.
- `captions/CaptionPainter` interface (`draw(Canvas, outputMs, outputW, outputH)`).
**Done when:** an empty plan with cuts renders 9:16 first, then 4:5 and 1:1; output length = `output_duration_ms`;
cancel deletes the partial file; the master is untouched.

## T2.4 — Captions painter, text looks, palette, `v_punch_in` [app]

**Goal:** a Basic plan renders completely: captions, keyword-echo text, punch-in.
**Read first:** Shared context (text looks); `subtitle/SubtitleDrawer.java` (incl. `resolveAdaptivePositionY`),
`subtitle/SubtitleStyle.java`, `subtitle/SubtitleTrack.java`; `aiedit/engine/` from T2.3.
**Build:**
- `aiedit/flow/SubtitleCaptionPainter` — implements `CaptionPainter` with the user's subtitle style, words mapped to
  output time, plan emphasis words drawn bigger (`c_emphasis_big`).
- `aiedit/engine/text/` — `TextLookRenderer` (the 6 looks, `StaticLayout` + `TextPaint`), `PaletteResolver` (roles from
  the plan palette and `color_slot`), `TextAnimator` (pop, stamp, slide_up, word_by_word, exit) on a cached bitmap.
- `aiedit/engine/effects/` — `EffectFactory`, `PunchIn`; `Capabilities` += `v_punch_in`, the text ids, caption ids.
**Done when:** every animation time stays inside contract `speed`; text never crosses the facecam (track) or the
caption band.

## T3.1 — Job flow [app]

**Goal:** from a recording to a rendered AI version, in the background, with no double charge.
**Read first:** Shared context; `publish/PublishApi.java`, `publish/DirectUploader.java` (`uploadAudio`, `run`),
`publish/UploadKind.java`, `publish/AudioExtractor.java`, `publish/PostRecordTranscriber.java` (upload + poll pattern),
`subtitle/SubtitleEmbedStore.java`, `service/BgReplaceService.java` (foreground pattern), `util/VideoGroupStore.java`,
`edit/EditGroupMath.java`, `model/RecordingItem.java`, `util/RecordSettings.java` (`autoCropRatios`).
**Build** in `aiedit/flow/`:
- `AiEditApi` — contract, quote, create, get (same client style as `PublishApi`).
- `UploadKind.EDIT_PROXY` (purpose `edit_proxy`, `.mp4`) in `publish/`; audio via caption id from
  `SubtitleEmbedStore`, else `AudioExtractor` + `uploadAudio`; proxies (standard, heavy) via `Engine.renderProxy`.
- `AiEditStore` — per recording: sessionId (from the event log, else a new uuid), level, job id, plan path, outputs.
- `AiEditService` — foreground service, one notification ("Uploading…", "Planning…", "Rendering 9:16…", "Done");
  polls every 3 s for a minute then every 5 s, gives up after 15 min; downloads the plan; renders the enabled ratios
  (9:16 first) with `Engine`; survives process death (resume from the store).
- Outputs `<base>_aiedit.mp4` (+ crop names), assigned to the master's group, `RecordingItem.isAiEdited()` and an
  "AI edit" tag beside "Edited" in `adapter/RecordingsAdapter`; plan JSON kept beside the file.
**Done when:** a repeated start for the same recording and level reuses the job; a failure leaves no partial file.

## T3.2 — UX [app]

**Goal:** one tap from preview to AI version (spec §4).
**Read first:** Shared context; spec §4, §5; `ui/VideoPlayerDialog.java` (action row, delete, subtitle status),
`res/layout/dialog_video_player.xml`; `ui/SettingsActivity.java` (`setupSubtitles`), its layout;
`util/RecordSettings.java`; `ui/BillingLink.java`; `aiedit/flow/` from T3.1.
**Build:**
- Preview row: primary **"AI Edit · N credits"** (quote by length, cached), tap = default level; a small chevron → a
  3-option sheet (Basic, Standard, Heavy: cost + one line each). Progress and result shown in place.
- Discard: Undo snackbar for a few seconds instead of a confirm dialog. **Record again**: close the preview, back to
  the recorder on the same slide.
- 402 → `BillingLink` top-up; failure → one line + "Try again".
- Settings section "AI edit": level (default Standard), text colours (Multi colour / Brand colour), sound effects (on).
- All strings in `res/values/strings.xml`.

## T4.1 — Perception with Clef-omni [backend]

**Goal:** Standard and Heavy get mood, energy, emphasis and per-slot choices from the video.
**Read first:** Shared context; spec §11 step 5; **the model's `schema-input.json` / `schema-output.json` linked from
https://developers.cloudflare.com/workers-ai/models/clef-omni/** (exact media embedding and answer keys);
`L/database/prisma/ai-video-edits/ai.video.edits.service.ts`; `L/ai-video-edit/core/edit.assembler.ts`.
**Build:**
- `L/ai-video-edit/clef.omni.client.ts` — `POST https://api.cloudflare.com/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/run/@cf/cloudflare/clef-omni`,
  bearer `CLOUDFLARE_AI_TOKEN`; ≤ 2 videos (≤ 60 s, ≤ 16 MiB each) and ≤ 64 questions per call; types `noul`, `choice`,
  `score` only. Per piece: `mood` (choice), `energy` (score), `screen_busy` (noul), `emph_<i>` (noul per candidate
  word), `comm_<slot>` (choice of mood-matching phrase ids + `none`), `fam_<slot>` (choice of effect family).
- `perceiveEdit` fills `scores`; any failure → no scores for that piece; proxies deleted after.
- `.env.example` and `deploy/.env.example`: `CLOUDFLARE_AI_TOKEN`.
- Specs with the client mocked: success, timeout (plan still valid), > 64 questions batched.

## T4.2 — Writer LLM + brand [backend]

**Goal:** Heavy writes complementary phrases and icon queries; every level uses the brand palette when asked.
**Read first:** Shared context; spec §11 step 6, §13, §18; `L/openai/openai.service.ts` (`writeCaption`);
`_planning/02_contracts/brand.profile.md`; the brands service it names.
**Build:**
- `OpenaiService.writeEditTexts(model, effort, messages)` (same shape as `writeCaption`, zod `{ slots: [{ id, text,
  icon_query? }] }`); prompt constant `L/ai-video-edit/edit.writer.prompt.ts`; model env `AI_EDIT_WRITER_MODEL`
  (default `PROMPT_BOOK_MODEL`).
- `writeEditTexts` step: brand voice + banned words; texts 1–4 words in the video's language; failure → Standard texts.
- Palette: `text_colors: brand` → workspace colour as the single accent; else contract `multi`.
- Specs: banned word dropped, writer down, brand palette.

## T4.3 — Video effects + transitions [app]

**Goal:** the rest of the first wave of video effects (spec §14 build priority).
**Read first:** Shared context (registry defaults in T0.1); `aiedit/engine/render/Compositor`, `effects/` from T2.4.
**Build** (one class per id, factory-created): `v_push_in`, `v_alt_crop`, `l_flash`, `l_rgb_glitch` (shader
uniforms), `y_face_grow_slide_shrink` / `y_slide_grow_face_shrink` (circle-masked copy of the facecam region from the
track over the frame scaled 0.92), `c_word_highlight`, `c_active_pop`, transitions `tr_flash_cut`, `tr_whip` at cut
points. Facecam effects skip outputs whose crop does not contain the facecam. `Capabilities` updated.

## T5.1 — Assets: IconScout + shared cache + sounds [backend]

**Goal:** icon and Lottie slots get real files, downloaded once for all users.
**Read first:** Shared context; spec §15; `L/upload/r2.uploader.ts` (`R2`, `R2_BUCKET`, `deleteObject`, `mediaUrl`
import); `L/database/prisma/ai-video-edits/`.
**Build:**
- `L/ai-video-edit/assets/iconscout.client.ts` — `GET https://api.iconscout.com/v3/search` (header `Client-ID`);
  `ICONSCOUT_DOWNLOAD=preview` (default): plain GET of the URL in the search JSON; `api`: `POST /v3/items/{uuid}/api-download`
  with `Client-Secret`.
- Prisma `AiEditAsset` (`provider`, `providerId`, `kind`, `objectKey`, `nativeDurationMs?`, `width?`, `height?`;
  unique provider + id) + migration; R2 key `ai-edit/assets/<provider>/<id>.<ext>`; Lottie duration `(op − ip) / fr`.
- `edit.assets.service.ts` — the resolver used by `planEdit`: cache hit, else search + download + store; failure drops
  the slot. Sounds → `AI_EDIT_SOUNDS_BASE_URL/<id>.wav` in `assets`.
- Env: `ICONSCOUT_CLIENT_ID`, `ICONSCOUT_CLIENT_SECRET`, `ICONSCOUT_DOWNLOAD`, `AI_EDIT_SOUNDS_BASE_URL`.
- Owner action written at the end: upload CC0 sounds (Kenney "Interface Sounds" / "UI Audio", Freesound CC0) as `<id>.wav`.

## T5.2 — Asset cache, Lottie and icon overlays, sound mix [app]

**Goal:** plans with icons, Lottie and sounds render.
**Read first:** Shared context; `aiedit/flow/AiEditService`, `aiedit/engine/` (`LayerCanvas`, `AudioPath`, effects).
**Build:** asset download into `getFilesDir()/aiedit/assets/<id>` (once, by id); lottie-android dependency in
`app/build.gradle` (current stable at build time); `engine/overlay/LottieLayer` (progress = time × fit factor, never
slower than native, hold or end early), `IconLayer`; `i_scale_pop`; sound mix in `AudioPath` under the voice;
`Capabilities` updated.

## T6.1 — Caption credits held until used [backend]

**Goal:** recording and discarding are free (spec §5).
**Read first:** Shared context; `L/database/prisma/ai-captions/ai.captions.service.ts` (`finishCaption`, `settle`,
`applyToPosts`, `linkPosts`) and its spec; `_planning/02_contracts/ai.video-captions.md`;
`L/database/prisma/credits/reconcile/credits.reconcile.service.ts`.
**Build:** drift entry; `AiCaption.creditState` (default `SETTLED`; `HELD`, `COMMITTED`, `RELEASED`, `INCLUDED`) +
migration; `finishCaption` keeps the hold (`HELD`); `useCaption` (commit; reserve again if the reconciliation released
it; never throws into a publish); `releaseCaption`; publish path calls `useCaption`; an edit job that reuses a caption
releases it and marks `INCLUDED`; routes `POST /ai-captions/:id/use` and `/release` (`posts:write`); update the specs
that expected a commit at finish. Note in the contract: ship together with T6.2.

## T6.2 — Caption use, release, reuse [app]

**Goal:** the app tells the server when a transcript is used or thrown away.
**Read first:** Shared context; `ui/VideoPlayerDialog.java` ("sub" embed path, delete path),
`subtitle/SubtitleEmbedStore.java`, `aiedit/flow/AiEditApi`.
**Build:** "sub" embed → `use` once per caption; final delete (after Undo expires) → `release`; AI edit sends
`aiCaptionId` when one exists. Failures are silent and retried on the next app start.

## T7.1 / T7.2 — later

T7.1: frame-difference activity for imported videos (spec §10 source 2). T7.2: second-wave and person effects, mild
effects inside protected spans.
