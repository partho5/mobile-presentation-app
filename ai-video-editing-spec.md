# AI Video Editing Engine: Specification for the Coding Agent

Read this whole file before writing code.

**Top rule, above every other rule in this file: frictionless, smooth UX that lowers the user's cognitive load.**
When two options both work, pick the one that asks the user for fewer decisions, fewer taps and less reading.

Status: spec revised 2026-11-17 from the code of both repos and the owner's notes; all decisions settled by the owner
(section 23). The build tracker is section 21.

Repos:
- App (Android, Java): `/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder`. App code paths below are
  relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.
- Backend (NestJS monorepo, Postiz fork): `/home/haku/projects/Next.js/Social-Media-Manager`. It is built under its own
  contract-first system: read its `AGENTS.md`, `_planning/MASTER_PROMPT.md` and `_planning/system/STANDARDS.md` before backend
  work. `L/` below means `libraries/nestjs-libraries/src/`.
- Rule for both repos: this PC hangs under load. Do not run gradle, `pnpm build`, tests or emulators. Write code and verify it
  by reading it against this spec; the owner builds and runs tests on another PC.

## 1. Purpose and scope

Add an AI powered editing engine. It turns a raw recording into a fast, edited, social media style video. The goal is self
branding content that viewers do not scroll past.

- The **AI editing engine is a new, separate thing**. It is not the manual editor that already exists
  (`ui/VideoEditorActivity`, `edit/EditRenderer`, `VIDEO_EDITOR_PLAN.md`: trim, split, delete, audio lane). Do not change the
  manual editor for this feature.
- The engine has **three levels: Basic, Standard, Heavy** (section 6).
- The engine is **modular**. It will later be rewritten as desktop software in another language. So the engine is defined by a
  language-neutral contract (plan JSON, effect registry, style presets, timing rules, golden fixtures). The Android renderer is
  one implementation of that contract (section 7). Do not design anything for desktop now; only keep the engine separate.
- All AI decisions are made on the server. The server returns a validated edit plan as JSON. The phone renders the plan.
- Output is **1080p** (section 19).

## 2. Product context (verified in the code, 2026-11-17)

- Recording: `service/ScreenRecordService` records the **whole phone screen** with `MediaProjection` + `MediaRecorder`: H.264,
  30 fps, 8 Mbps, AAC, at the screen's real size (for example 1080 x 2400, not 9:16). The master file is never modified.
- Auto crop: `util/VideoCropHelper` makes 9:16, 4:5 and 1:1 versions with FFmpeg stream copy (lossless). 9:16 is always first
  wherever ratios are listed.
- Facecam: a round camera bubble the user can **drag and resize** before and during recording (`MainActivity`,
  `ui/FloatingCamOverlay` outside the app). It is not always in the lower part. A planned setting moves it down automatically
  while the user draws (`PRODUCT_IDEAS_BACKLOG.md`, item "auto move face cam"). So the facecam position is a **track over
  time**, not one fixed circle.
- Optional background replacement for the facecam (`bgreplace/`) runs after recording on the master.
- Drawing: two tools. The **drawing board** (`ui/DrawingBoardController`, `ui/DrawingBoardView`): a square board that slides
  over the slide from the top, with pen, eraser, text, undo, redo, clear. **Slide ink** (`ui/SlideInkController`): pen
  strokes directly on a slide. Both are drawn by the app, so the event log can record them exactly.
- Slides: text, image and browser slides, changed by swipe or corner taps.
- Speech to text: the backend already runs ElevenLabs `scribe_v2` with word timestamps (`L/muse/elevenlabs.transcription.service.ts`,
  `L/database/prisma/ai-captions/`, contract `_planning/02_contracts/ai.video-captions.md`). The app already starts
  transcription right after recording when subtitles are on (`publish/PostRecordTranscriber`), caches the words
  (`subtitle/SubtitleCache`), shows karaoke subtitles in the preview (`ui/KaraokeSubtitleView`) and can burn them in
  (`subtitle/SubtitleDrawer`, "sub" button in `ui/VideoPlayerDialog`, guarded against double burn and double charge by
  `subtitle/SubtitleEmbedStore`).
- Languages: any. Bengali and English are the main ones (ElevenLabs returns ISO 639-3 codes such as `ben`). All text drawing
  uses Android's own text engine (`Canvas`, `StaticLayout`), which shapes every script with no bundled fonts.
- Credits: one account, one credit balance, many brand workspaces. Every paid action goes through the backend credit engine
  (reserve, then commit or release; idempotent; price from `L/database/prisma/credits/pricing/pricing.config.ts`, never a literal).
- Brand: each workspace already has one brand `color` (#RRGGBB) and a creative profile (tagline, audience, voice, pillars,
  banned words) in `_planning/02_contracts/brand.profile.md`.
- Videos are about 2 minutes or less. One video may use up to 10 feature effects (Heavy).

## 3. Core principles

1. UX first (top rule). One tap to process. No setup screens in the flow. Defaults are set once in Settings.
2. The AI outputs a plan. The renderer renders it the same way every time. The AI never touches pixels.
3. The AI may only pick from closed lists (effect ids, phrase library, style presets) and may only place items in free
   windows computed by code.
4. Code validates every plan. Never trust AI output without validation.
5. Brush use and slide content are protected. Do not spoil them with extra animation.
6. Everything is fast. No slow animation and no slow transition (section 12).
7. Anchor effects to word indexes, not to raw seconds.
8. The master recording is never modified. The AI version is a new file in the same group, tagged "AI edit".
9. Credits are charged only when the user taps Process, never for recording, previewing or discarding (section 5).
10. The engine contract is language-neutral and versioned. Renderers implement it; they do not extend it on their own.

## 4. User flow: review before processing

Never start paid AI processing automatically after recording.

1. The user finishes recording. The existing preview panel (`ui/VideoPlayerDialog`) opens as today, with karaoke subtitle preview.
2. The preview row gets one new primary button, **AI Edit**, labelled with its cost from the server, for example
   "AI Edit · 2 credits". It is the most visible button. Tapping it starts processing at the default level. No dialog.
3. A small chevron on the button opens a 3 option sheet (Basic, Standard, Heavy, each with its cost and one line of what it
   does) for this video only. Most users never open it.
4. **Discard** (the existing delete) deletes the recording with an Undo snackbar for a few seconds instead of a confirm
   dialog. Free.
5. **Record again**: closes the preview and returns to the recorder on the same slide, ready to record. The take stays in
   Saved Recordings (nothing is deleted silently). Free.
6. While processing: the user can leave the screen or the app. A notification shows progress ("Planning…", "Rendering 9:16…").
   When done, the AI version opens in the preview carousel first, tagged "AI edit", next to the original.
7. On failure: one short message with one action ("Try again"). A system failure refunds the credits automatically; the user
   never asks for a refund.
8. Too few credits: the button shows the cost and opens the existing top-up path (`ui/BillingLink`) instead of a dead end.

Settings (set once): "AI edit level" (default **Standard**), "Brand colours for text" (default: multi colour; brand colour
option reads the workspace colour), "Sound effects" (default on).

Auto-publish ON is unchanged in v1: it publishes the original. A later setting may "AI edit before auto-publish".

## 5. Credits

- Recording, previewing, reviewing and discarding are always free.
- Credits are reserved when the user taps AI Edit, committed when the server delivers a valid plan, and released (refunded)
  when planning fails for a system reason.
- One price per submitted video per level, transcription included. Pricing actions (in `pricing.config.ts`, values to be
  confirmed by the owner): `ai.edit.basic`, `ai.edit.standard`, `ai.edit.heavy`, unit ITEM, for videos up to the length limit.
- Rendering runs on the phone. The plan is stored on the server; re-downloading it and re-rendering (for example after a
  crash or for another ratio) is free.
- A repeated tap or a retry uses an idempotency key per (recording, level), so one video is never charged twice.
- If the recording already has a READY caption (`aiCaptionId` in `SubtitleEmbedStore`), the edit reuses its transcript. No
  second speech to text run and no second charge.

**Conflict found in the current code:** `PostRecordTranscriber.start` runs right after every recording when subtitles are on,
and `POST /ai-captions` charges `ai.captions.per_minute` (1 credit per whole minute, so takes under 1 minute are free). A
2 minute take that is then discarded still costs 2 credits. Proposed fix (needs the owner, section 23): keep auto
transcription for the instant subtitle preview, but hold the caption credits as a reservation and commit only when the
transcript is used (embed, publish or AI edit); release on discard or after 7 days unused. This amends the frozen backend
contract `ai.video-captions` through its `_drift.md`.

## 6. Editing levels

| | Basic | Standard (default) | Heavy |
|---|---|---|---|
| Silence removal and jump cuts | yes | yes | yes |
| Captions (user's subtitle style) with emphasis words | yes | yes | yes |
| Feature effects (max) | 3 | 6 | 10 |
| Text overlays | keyword echo only | + commentary phrases | + complementary phrases |
| Video effects (zoom, flash, glitch, layout) | punch in only | yes | yes |
| Icons and Lottie | no | up to 2 | up to 4 |
| Transitions at cuts | no | flash or whip | all |
| Sound effects | no | yes | yes |
| Video analysis (Clef-omni, section 11) | no (audio and event log only) | yes | yes |
| Text writing LLM call | no | no | yes |

Basic uses no paid AI model besides the transcript, so it is the cheapest to run. Each registry entry has a `min_level`
(section 14); the planner never uses an effect above the job's level.

## 7. Engine architecture and modularity

The engine is four layers. Only layer 4 is platform code.

1. **Contract** (language-neutral, versioned): plan JSON schema (section 16), effect registry JSON (section 14), style presets
   JSON (section 13), event log schema (section 10), timing and easing rules (section 12), golden fixtures (sample plans with the
   expected frame at given timestamps). One source of truth lives in the backend repo at `L/ai-video-edit/contract/`. The app
   bundles a synced copy in the engine module's assets. `contract_version` travels in every request and plan.
2. **Planner core** (server, TypeScript, pure functions, no Nest or Prisma imports): registry loading, free windows, timeline
   remap, plan assembly, validator. Folder `L/ai-video-edit/core/`.
3. **Planner services** (server, Nest): job service and repository, speech to text reuse, Clef-omni client, writer LLM client,
   asset resolver, Temporal workflow, public API routes.
4. **Renderer** (Android): inside the `:app` module, in its own package tree `aiedit/engine/`, with strict separation of
   concerns: engine packages import only each other, the Android SDK and lottie-android, never app UI, publish, auth or
   settings classes. Inputs come in through the `Engine` facade and small interfaces. Its packages:
   - `plan`: plan and registry models and JSON parser (pure Java, unit tested on the JVM).
   - `timeline`: source to output time math, cut and speed mapping (pure Java).
   - `render`: MediaCodec decode, OpenGL ES compositor, MediaCodec encode, MediaMuxer, audio path.
   - `effects`: one class per effect id, created from the registry entry and params.
   - `text`: style presets, brand palette, text layout and animation (Canvas into textures).
   - `assets`: interface for Lottie, icon and sound files (the app injects the downloader and cache).
   - `Engine` facade: `Engine.render(RenderJob, Listener)`; `RenderJob` = source file, plan, target ratio, output file.

   App side (packages `aiedit/capture/` and `aiedit/flow/`): event log recorder, API client, job worker and foreground
   service, preview and Settings UI. Capture and UX live there; rendering lives in `aiedit/engine/`.

A desktop version later re-implements layer 4 (and may reuse the server for layers 2 and 3) against the same contract and
golden fixtures.

## 8. System overview and API

```
App                                        Server
---                                        ------
Record video + event log
User taps AI Edit (level)
Upload audio (or reuse aiCaptionId)
  + low-res proxy (Standard, Heavy)  --->  POST /public/v1/ai-edits  (reserve credits, 202)
                                           Workflow:
                                             transcript (reuse or ElevenLabs)
                                             structure: silence cuts, speed
                                             remap event log, free windows
                                             perception: Clef-omni per chunk
                                             writing: LLM (Heavy only)
                                             assemble, resolve assets, validate
                                             commit credits (or release on failure)
Poll GET /public/v1/ai-edits/:id   <----   READY + plan JSON + asset URLs
Download assets, render per ratio
  on the phone, 9:16 first
```

Routes (public API, device token, workspace by `x-workspace-id`, `Idempotency-Key` applies; same pattern as `ai-captions`):

| Route | Answer |
|---|---|
| `POST /upload/multipart` with `purpose: 'edit_proxy'` | existing upload flow, new purpose for the low-res proxy (size cap) |
| `POST /ai-edits` `{ level, aiCaptionId?, audioUploadId?, proxyUploadId?, request }` | 202 `{ id, status, cost }`; 402 `INSUFFICIENT_CREDITS` |
| `GET /ai-edits/:id` | `{ id, status, error?, plan?, assets?: [{ id, url, kind, native_duration_ms }] }` |
| `GET /ai-edits/quote` | cost per level for this workspace (the button label) |
| `GET /ai-edits/contract` | current `contract_version` and registry, for capability checks |

`status`: `PENDING`, `TRANSCRIBING`, `PLANNING`, `READY`, `NO_SPEECH`, `FAILED`. The audio and proxy uploads are deleted from
storage when the job ends (same rule as caption audio).

API modes: v1 has only the single call above. "Revise" (send the plan plus locked items, re-plan the rest) and a two-round
mode (confirm cuts first) come later; they add friction and are not needed for v1.

Why phases exist inside the job: cuts and speed changes move the timeline. Effects must be planned after the timeline is fixed.

## 9. Request contract (app to server)

Normalized coordinates use 0 to 1 of the master frame width and height.

```json
{
  "contract_version": 1,
  "session_id": "uuid",
  "level": "basic | standard | heavy",
  "video": {
    "duration_ms": 118000, "width": 1080, "height": 2400, "fps": 30,
    "source": "in_app_recording | imported",
    "outputs": ["9:16", "4:5", "1:1"]
  },
  "facecam_track": [
    { "t_ms": 0, "shape": "circle", "cx": 0.78, "cy": 0.80, "radius": 0.13 },
    { "t_ms": 41200, "shape": "circle", "cx": 0.78, "cy": 0.90, "radius": 0.13 }
  ],
  "activity_map": [],
  "manual_edit": { "kept_ranges": [[0, 52000], [54100, 118000]] },
  "brand": { "text_colors": ["#FF3B30", "#FFD60A", "#34C759"], "mode": "multi | brand" },
  "language": "ben",
  "capabilities": {
    "effects": ["v_punch_in", "c_word_highlight"],
    "style_presets": ["sticker_red", "label_blue"],
    "person_segmentation": false
  },
  "options": { "sound_effects": true }
}
```

- `facecam_track`: one entry per change (drag, resize, auto move), on the master timeline. A step change at `t_ms`.
- `manual_edit`: present when the user cut the video in the manual editor first; the server maps the event log and the
  transcript through the kept ranges. Absent means the whole master.
- `brand.text_colors`: from the workspace colour (brand mode) or the default multi colour palette (section 13).
- Transcript word format (kept on the server, also returned to the app):
  `{ "i": 42, "w": "faster", "start_ms": 31240, "end_ms": 31610, "conf": 0.98 }`. Store the original precise timestamps; use
  integer milliseconds in the plan.

## 10. Activity map and protected spans

Important rule: do not add overlays or video effects when something important is already changing on screen, such as brush
strokes on a slide.

### Entry format

```json
{
  "id": "a1",
  "type": "brush | board_show | board_hide | slide_change | motion",
  "start_ms": 12000,
  "end_ms": 15400,
  "region": { "x": 0.1, "y": 0.2, "w": 0.6, "h": 0.3 },
  "strength": 0.8,
  "source": "event_log | frame_diff"
}
```

- `brush` and `motion` entries are protected.
- `slide_change`, `board_show` and `board_hide` are markers (`start_ms == end_ms`). Not protected. They are anchors: a short
  effect right after one is allowed, and it must end before the next protected span starts.

### Source 1: event log (use first)

The app owns the recorder, so it logs exact events while recording, on the recording's clock (the time `MediaRecorder` started,
taken from `SystemClock.elapsedRealtime`):
- stroke start, stroke end and stroke bounding box, from `DrawingBoardView` and `SlideInkController` touch handling (pen and
  eraser and board text; undo, redo and clear count as a short activity);
- board shown and hidden (`DrawingBoardController`);
- slide changes;
- facecam drag, resize and auto move (into `facecam_track`).

Merge strokes closer than 400 ms into one span. Save the log as JSON next to the master in app private storage (it is
metadata about the user's content; never send it to the activity log or crash reports). Keep event logging behind one small
class so it costs nothing when no AI edit is ever requested.

### Source 2: frame difference (fallback, imported videos)

1. Decode at low resolution, about 10 frames per second, with MediaCodec (no full frames in Java arrays).
2. Small grayscale images (for example 64 x 112).
3. Mask out the facecam circle; face motion must not count.
4. Per grid cell (for example 4 x 4), mean absolute difference with the previous frame.
5. Cells above a configurable threshold are busy. Merge nearby busy cells and frames into spans (gap under 400 ms).

### Protected span and free windows

Protected span = busy span expanded by `padding_ms` on both sides (default 300). Free windows = timeline minus protected spans,
dropping windows shorter than `min_window_ms` (default 500). The AI only ever sees free windows. This is more reliable than
asking the AI to avoid busy spans.

v1 policy inside protected spans: block overlays, zoom, shake, glitch, flash, speed changes, cuts, layout changes and facecam
effects. Captions and sound continue. Captions must not cover the brush region: move the caption band up or down for that span.
The `content_safe` flag may later allow mild effects inside protected spans; do not build that now.

Phase 1 must never cut or speed up inside a protected span. After phase 1, remap the activity map and the facecam track to the
output timeline.

## 11. Planning pipeline on the server

One Temporal workflow per job (new file, versioned; never edit a workflow already on `main`). Steps:

1. **Transcript.** Reuse the READY caption's words when `aiCaptionId` is given; else transcribe with the existing
   `TranscriptionProvider`.
2. **Structure (phase 1, code only).** Silence removal and jump cuts from word gaps (configurable gap threshold), never inside
   protected spans. Optional speed segments (Heavy). Output cuts, speed segments, the output timeline and a word map.
3. **Remap** the activity map and facecam track. Compute free windows on the output timeline.
4. **Candidate slots (code).** In each free window, candidate anchor words: numbers, long or rare words, words before a pause,
   words that repeat the topic, sentence starts after a slide change. The first 3 seconds always get a hook slot.
5. **Perception (Standard, Heavy): Cloudflare Clef-omni.** Clef-omni (Workers AI `@cf/cloudflare/clef-omni`, $0.15 per million
   input tokens, built on Qwen3-Omni) takes audio, video, images and text in one call. It is a **decision model: it scores fixed
   options in a schema; it does not write free text**. So use it only for closed questions, per chunk of about 20 seconds of the
   low-res proxy with audio:
   - `mood`: enum (excited, serious, sad, funny, surprising, calm);
   - `energy`: number 0 to 1;
   - `emphasized_words`: multi select from the chunk's candidate word indexes;
   - `screen_busy`: boolean (extra check on top of the event log; imported videos);
   - `commentary`: enum from the phrase library (section 13) or `none`, per slot;
   - `effect_family`: enum (camera, look, text, icon, none) per slot.
   Verify the exact request schema, video length limit and token cost per second of video in the Workers AI docs before
   building. Clef-omni is cheap, so a 2 minute video is about 6 small calls.
6. **Writing (Heavy only): one LLM call.** Writes the complementary phrases and the icon search queries for the chosen slots,
   in the video's language, with the brand voice and banned words from the brand profile. Use the existing `OpenaiService` and
   Prompt Book keys (`edit.writer.general`, overridable per workspace), JSON schema output. The model is set in the Prompt Book,
   not in code.
7. **Assemble (code).** Turn scores into plan items with deterministic rules: level budget, pacing (a visible change about every
   2 to 3 seconds), variety, cooldowns, hook rule, position zones per output ratio, text hold times, sound pairing.
8. **Resolve assets** (section 15) to concrete asset ids with native durations.
9. **Validate** (section 17): repair or drop invalid items. Store the plan, the scores and the validator log. Commit credits.

A failed Clef or LLM call degrades the job (drop that slot's text or effect family) instead of failing it. Only a missing
transcript or a broken pipeline fails the job.

## 12. Speed rules (all effects must be fast)

Defaults; store them in the registry and keep them configurable.

| Item | Range |
|---|---|
| Entry animation | 120 to 300 ms |
| Exit animation | 100 to 200 ms |
| Transition | 150 to 300 ms |
| Beat effect (zoom, shake, flash, bounce) | 80 to 400 ms total |
| Flash | 80 to 150 ms |
| Fast push in | 400 to 800 ms maximum |
| Slow motion (rare) | 500 ms maximum |
| Text hold time | at least 500 ms + 150 ms per word, at most 2500 ms |
| Icon or Lottie hold time | 500 to 1500 ms |

Easing: fast out curves (ease out cubic, ease out back with small overshoot). No slow ease in out.

Lottie fitting: `speed_factor = native_duration_ms / slot_duration_ms`. Above 1, speed up by scaling the progress mapping (do not
cut). Cap at 3.0; above that pick another asset. Never slow a Lottie below native speed; if the slot is longer, hold the last
frame or end early.

Removed because they are slow: subtle handheld drift, long slow push in.

## 13. Text overlays

### Kinds of text

| Kind | Example | Source | Levels |
|---|---|---|---|
| Keyword echo | Subtitle says "ADHD impact is more severe than you think"; overlay shows **SEVERE** | a word or short phrase picked from the transcript (word index) | all |
| Commentary | "Interesting", "So sad", "Attention", "Did you know?", "Wait…", "Facts", "Big mistake" | closed phrase library per language, picked by Clef-omni | Standard, Heavy |
| Complementary phrase | while the speaker explains a cost: "Save 2 hours a day" | written by the LLM, 1 to 4 words | Heavy |
| Hook title | the video's promise, first 3 seconds | LLM (Heavy), else the first sentence's keyword | all |

The phrase library is a JSON file in the contract (id, text per language, mood tags). Start with about 40 phrases in English and
Bengali; missing languages fall back to keyword echo.

### How text looks in heavily edited video

- Short and big: 1 to 3 words, 2 to 4 times caption size, bold or heavy weight, all caps for Latin scripts (never for scripts
  without case).
- Never plain. Every text uses a style preset: one of
  - **sticker**: thick outline (stroke) plus hard drop shadow, slight tilt (−6° to 6°);
  - **label pill**: solid rounded background, contrasting text, small shadow;
  - **3D**: 4 to 8 stacked offset copies in a darker shade under the face, looks extruded;
  - **soft glow**: blurred outer glow in the accent colour, thin bright border;
  - **gradient fill**: two-colour vertical gradient with outline;
  - **highlighter**: marker sweep behind the word (the existing `MarkerTextView` look).
- Entry 120 to 300 ms: pop with overshoot (scale 0.6 → 1.08 → 1.0), stamp (2.0 → 1.0 with a tiny shake on impact), slide up with
  fade, or word by word. Exit 100 to 200 ms: quick scale down with fade, or cut out on the beat.
- Placement: upper zone, inside the safe rectangle, away from the caption band and the facecam (section 16). One text overlay
  on screen at a time.
- Pair with a short sound (pop, whoosh) when sound effects are on.

### Brand colour (text only)

Images, icons, Lottie and video assets keep their own colours. Text uses a palette:
- Default **multi colour** palette: rotate through about 5 high contrast accents (for example red #FF3B30, yellow #FFD60A,
  green #34C759, cyan #32D7FF, magenta #FF2D92), each paired with black or white for outline and text.
- **Brand** mode: the workspace `color` is the accent; the renderer derives a darker shade (3D, outline) and picks black or white
  text by contrast. A future brand profile version may add up to 3 brand colours.
- Style presets name colour roles (`accent`, `accent_dark`, `on_accent`), never fixed colours, so one preset works with any
  palette.

## 14. Effect catalog

The catalog is a registry, shared by server and app (one JSON file in the contract). The AI sees ids and closed choices only.
The validator uses the full entries.

### Registry entry format

```json
{
  "id": "v_punch_in",
  "layer": "video",
  "target": "full_frame",
  "allowed_components": ["video"],
  "min_level": "basic",
  "content_safe": false,
  "cost": "L",
  "min_duration_ms": 150,
  "max_duration_ms": 400,
  "default_params": { "scale_to": 1.15, "ease": "out_back" },
  "param_limits": { "scale_to": [1.05, 1.25] },
  "cooldown_ms": 6000,
  "max_per_video": 3,
  "sound": ["whoosh_short", "pop_1"],
  "brand_colorable": false,
  "requires": []
}
```

Fields:
- `target`: `full_frame`, `slide_area`, `facecam`, `overlay` or `caption_area`. The plan must always state it.
- `min_level`: lowest level that may use the effect.
- `content_safe`: true if the effect does not hide, crop, move or distract from slide or brush content. Reserved for later.
- `cost`: L, M or H, for phone GPU load.
- `brand_colorable`: true for text effects (section 13).
- `requires`: for example `person_segmentation`.

### Video: camera movement

| id | Description | Target | content_safe | Cost |
|---|---|---|---|---|
| v_punch_in | Jump to 110 to 120% on a key word, quick settle | full_frame | no | L |
| v_push_in | Fast push to 106 to 110% | full_frame | no | L |
| v_pull_out | Scale from 115% to 100% | full_frame | no | L |
| v_pan_bounce | Quick pan sideways and return | full_frame | no | L |
| v_alt_crop | Switch wide and close crop each sentence (two virtual cameras) | full_frame | no | L |
| v_beat_rotate | Rotate 2 to 4 degrees and back | full_frame | no | L |
| v_bounce_scale | Scale down then up | full_frame | no | L |
| v_shake | Short shake on an emotional word | full_frame | no | L |

### Video: time

| id | Description | Target | content_safe | Cost |
|---|---|---|---|---|
| t_speed_ramp | Fast ramp to 1.5 to 2x and back | full_frame | no | M |
| t_slow_beat | Slow motion, 500 ms max, use rarely | full_frame | no | M |
| t_freeze_outline | Freeze frame with outline | full_frame | no | M |
| t_rewind_short | Short rewind | full_frame | no | M |
| t_stutter | Repeat the last 300 ms twice | full_frame | no | M |
| t_jump_cut | Silence removal cut, optional alternate crop | full_frame | no | L |
| t_echo_word | Repeat a key word with audio echo | full_frame | no | M |

`t_jump_cut` and speed ramps that change length belong to phase 1.

### Video: look and color

| id | Description | Target | content_safe | Cost |
|---|---|---|---|---|
| l_flash | White flash on a beat | full_frame | yes | L |
| l_color_pop | Short saturation boost | full_frame | yes | L |
| l_bw_to_color | Black and white to color | full_frame | no | L |
| l_rgb_glitch | RGB split glitch | full_frame | no | M |
| l_vhs | Retro VHS look, short | full_frame | no | M |
| l_film_grain | Film grain | full_frame | yes | L |
| l_vignette_pulse | Vignette pulse | full_frame | yes | L |
| l_light_leak | Light leak sweep | full_frame | yes | M |
| l_bloom | Bloom glow | full_frame | yes | M |
| l_duotone | Short duotone | full_frame | no | L |

### Video: layout

| id | Description | Target | content_safe | Cost |
|---|---|---|---|---|
| y_split_copy | Split screen with two copies | full_frame | no | M |
| y_mirror | Mirror | full_frame | no | L |
| y_face_grow_slide_shrink | Facecam grows, slide area shrinks | facecam | no | M |
| y_slide_grow_face_shrink | Slide area grows, facecam shrinks | slide_area | no | M |
| y_slide_blur_face_sharp | Blur the slide area, keep the face sharp | slide_area | no | M |
| y_letterbox | Letterbox bars for a dramatic line | full_frame | no | L |
| y_color_frame | Colored frame border | full_frame | yes | L |

### Video: person (needs person segmentation)

| id | Description | Target | content_safe | Cost |
|---|---|---|---|---|
| p_bg_blur | Background blur inside the facecam | facecam | yes | H |
| p_outline_glow | Glowing outline around the person | facecam | yes | H |
| p_bg_color_swap | Background color swap | facecam | yes | H |
| p_text_behind | Large text placed behind the person | overlay | no | H |

With a small round facecam, apply person effects inside the circle only. The app already has a segmentation pipeline
(`bgreplace/SegmentationProcessor`, TFLite); check whether it can run offline on the master before building these.

### Facecam

| id | Description | Target | content_safe | Cost |
|---|---|---|---|---|
| f_pop_in | Pop in with overshoot | facecam | yes | L |
| f_pulse | Pulse on an emphasis word | facecam | yes | L |
| f_glow_ring | Glow ring color pulse | facecam | yes | L |
| f_corner_shrink | Shrink to a corner when content needs space | facecam | no | M |
| f_bounce_entry | Bounce entry | facecam | yes | L |
| f_border_mood | Border color change by mood | facecam | yes | L |

### Styled text (overlay)

| id | Description |
|---|---|
| x_marker_sweep | Marker highlight sweep behind a word |
| x_pop | Pop with overshoot |
| x_stamp | Stamp: large to normal with impact shake |
| x_slide_up | Slide up with fade |
| x_typewriter | Typewriter, total time 500 ms max |
| x_one_word | One big word at a time |
| x_sticker | Sticker text: thick outline and shadow |
| x_3d | Extruded 3D text |
| x_glow | Soft glow border text |
| x_gradient | Gradient fill text |
| x_label_tag | Rounded label tag, such as "Tip 1" |
| x_tilt_sticker | Tilted sticker with bounce |
| x_underline_draw | Underline draw |
| x_circle_draw | Hand drawn circle around a word |
| x_arrow_draw | Arrow draw on |
| x_count_up | Number count up, 800 ms max |
| x_shake | Text shake on a strong word |

All text effects: target `overlay`, content_safe no, cost L (typewriter and draw effects M), `brand_colorable` true. Text is
never plain; it always has a `style_preset` (section 13).

### Lottie and icon image (overlay)

| id | Description |
|---|---|
| i_scale_pop | Scale pop in and out |
| i_slide_in | Slide in from an edge |
| i_stroke_draw | Stroke draw on |
| i_spin_in | Spin in |
| i_float_fade | Float up and fade |
| i_wiggle | Wiggle to attract attention |
| i_stamp | Stamp: large to small with impact |
| i_burst | Confetti or emoji burst |
| i_sparkle | Sparkle |
| i_wipe_reveal | Wipe reveal |

All: target `overlay`, content_safe no, cost L (burst M), `min_level` standard. Works for `lottie` and `icon_image`
components. Each has an entry and an exit.

### Transitions between segments

| id | Description |
|---|---|
| tr_whip | Whip pan |
| tr_zoom_blur | Zoom blur |
| tr_glitch_cut | Glitch cut |
| tr_flash_cut | Flash cut |
| tr_spin | Spin |
| tr_shape_wipe | Shape mask wipe |
| tr_push | Push slide |
| tr_dip_color | Dip to color |

All: target `full_frame`, content_safe no, cost L to M, 150 to 300 ms. Only at phase 1 cut points, never inside protected spans.

### Captions

| id | Description |
|---|---|
| c_word_highlight | Word by word highlight |
| c_active_pop | Active word scale pop |
| c_karaoke | Karaoke two line style |
| c_emphasis_big | Emphasis word larger and in a different color |
| c_emoji_after | Emoji after a keyword |

Captions use the user's subtitle template from Settings (`subtitle/SubtitleStyle`: green pill, yellow pill, clean white, pro
bounce) and its adaptive position by ratio, so the AI version looks like the user's other videos. The engine adds emphasis words
on top. Captions run for the whole video and do not count toward the feature effect limit. If the user turned subtitles off,
the AI edit still adds captions (they are core to this style) unless the owner decides otherwise (section 23).

### Sound pairing

Each effect may list short matching sounds: whoosh, pop, ding, impact. Plan items have an optional `sound` field. Mixed under the
voice at a fixed low level.

### Build priority (first wave)

Captions c_word_highlight, c_active_pop, c_emphasis_big. Video v_punch_in, v_push_in, v_alt_crop, t_jump_cut, l_flash,
l_rgb_glitch, y_slide_grow_face_shrink, y_face_grow_slide_shrink. Text x_pop, x_stamp, x_sticker, x_3d, x_glow,
x_marker_sweep, x_label_tag. Icons i_scale_pop. Transitions tr_whip, tr_flash_cut. Sounds pop, whoosh.
Second wave: t_speed_ramp, the rest of look, layout and text, Lottie effects, facecam effects. Last: person effects.

## 15. Assets: icons, Lottie, sounds

- Source: **IconScout API v3** (key to be provided by the owner; kept only on the server, never in the app). Search with
  `GET /v3/search` (Client-ID); download with `POST /v3/items/{uuid}/api-download` (Client-ID and Client-Secret). Types:
  icons, illustrations, 3D, Lottie. Their price is in IconScout API credits (about 1 per icon, 20 per Lottie per their API page),
  so the server **caches every downloaded asset** in its own storage (R2) by IconScout id and reuses it for every user.
- Before shipping, confirm in IconScout's terms that the API plan allows (a) using assets inside videos users export and
  publish, and (b) caching one download for many users. If (b) is not allowed, cost per video goes up a lot.
- Video clip overlays: prefer Lottie (vector, small, renders per frame on the phone). MP4 clips would need a second decoder in
  the renderer; leave them for later.
- The server resolves the writer's query (or the keyword) to an asset id plus `native_duration_ms` before returning the plan.
  The app downloads assets once into an app private cache keyed by id.
- **Development mode** (`ICONSCOUT_DOWNLOAD=preview`, the default until the owner switches it): the server searches with the
  Client-ID only and downloads the file from the URL already in the search result JSON with a plain HTTP GET, instead of the paid
  `api-download` call. `ICONSCOUT_DOWNLOAD=api` switches to the licensed download for production.
- Sounds: a small fixed list of short effects (`pop_1`, `pop_2`, `whoosh_short`, `swoosh`, `ding`, `impact_soft`, `click`), served
  by the server from `AI_EDIT_SOUNDS_BASE_URL` (`<base>/<id>.wav`) and returned in the plan's asset list like icons, so they can
  be swapped without an app release. Only CC0 sources: Kenney "Interface Sounds" and "UI Audio" (kenney.nl, CC0) for pops,
  clicks and dings; Freesound filtered to the CC0 licence for whooshes and soft impacts. BOOM Library's free "Trailer Basics" is
  not used: it has trailer impacts only (no pops or whooshes), it is 96 kHz and 0.9 GB, and BOOM says building a product such as
  an app needs a developer licence.

## 16. Plan JSON schema (server to app)

All times are integer milliseconds on the OUTPUT timeline (after cuts). Positions are zones, resolved per output ratio by the
renderer.

```json
{
  "contract_version": 1,
  "plan_version": 1,
  "session_id": "uuid",
  "level": "standard",
  "video": { "output_duration_ms": 96400 },
  "structure": {
    "cuts": [ { "src_start_ms": 5200, "src_end_ms": 5900, "reason": "silence" } ],
    "speed_segments": [ { "src_start_ms": 20000, "src_end_ms": 21000, "rate": 1.4 } ]
  },
  "facecam_track": [ { "t_ms": 0, "cx": 0.78, "cy": 0.80, "radius": 0.13 } ],
  "palette": { "mode": "multi", "colors": ["#FF3B30", "#FFD60A"] },
  "items": [
    {
      "id": "e1",
      "effect": "x_sticker",
      "layer": "text",
      "target": "overlay",
      "start_ms": 31200,
      "end_ms": 32600,
      "anchor": { "word_index": 42, "offset_ms": -80 },
      "position": { "zone": "upper_center" },
      "component": {
        "type": "styled_text",
        "kind": "keyword_echo | commentary | complementary | hook",
        "text": "SEVERE",
        "style_preset": "sticker",
        "color_slot": 1
      },
      "params": {},
      "sound": { "id": "pop_1", "offset_ms": 0 },
      "reason": "Key claim, spoken emphasis"
    },
    {
      "id": "e2",
      "effect": "i_scale_pop",
      "layer": "icon",
      "target": "overlay",
      "start_ms": 40000,
      "end_ms": 41800,
      "anchor": { "word_index": 77, "offset_ms": 0 },
      "position": { "zone": "upper_right" },
      "component": {
        "type": "lottie",
        "asset_id": "is_1234567",
        "native_duration_ms": 4000,
        "fit": { "mode": "speed_up", "factor": 2.2 }
      },
      "params": {}
    }
  ],
  "captions": { "effect": "c_word_highlight", "emphasis_words": [12, 42, 77] },
  "warnings": []
}
```

Notes:
- `anchor.word_index` refers to the transcript word. The app can recompute times if needed.
- `color_slot` picks a colour from `palette` (rotation for multi colour mode).
- Zones: `upper_left`, `upper_center`, `upper_right`, `middle_center`, `lower_center`. Default overlay safe rectangle per output
  ratio (configurable): x from 0.06 to 0.94, y from 0.10 to 0.62 for 9:16; the renderer maps zones into each ratio's rectangle.
  Keep a margin of 0.02 around the facecam circle and stay clear of the caption band. Avoid the bottom and right edges where
  social apps show their buttons.

## 17. Server validator rules

Reject or repair an item when any rule fails. Log every rejection for tuning.

1. `contract_version` matches; effect id exists in the registry and in the app `capabilities`.
2. Effect `min_level` is at or below the job's level.
3. Item time range fits inside one free window (overlays, video effects, layout, facecam).
4. Duration is inside the effect min and max.
5. Params are inside `param_limits`.
6. Overlays do not intersect the facecam (at that time, from the facecam track) plus margin, stay inside the safe rectangle of
   every requested output ratio, and do not overlap the caption band.
7. Feature effects per video at most the level's maximum (section 6). Captions and transitions do not count.
8. Cooldown: the same effect id not again within `cooldown_ms`; `max_per_video` per effect.
9. At most 2 effects active at the same moment; no two cost H effects overlapping; at most one text overlay at a time.
10. Hook rule: at least one strong effect in the first 3 seconds. If missing, add one or warn.
11. Pacing: a visible change at least every 3 seconds (effect start, cut, transition or caption emphasis). Warn on longer gaps.
12. Lottie fit factor at most 3.0, never below 1.0.
13. Text hold time follows section 12. Text is 1 to 4 words, and contains no banned word from the brand profile.
14. The anchor word exists. If anchor and time disagree, trust the anchor and recompute the time.
15. Phase 1 cuts and speed changes do not touch protected spans.
16. Plan sorted by `start_ms`, no duplicate ids.

Repair order: shift inside the same free window, shorten, swap to a cheaper effect, drop the item.

## 18. Prompts

Clef-omni questions (section 11, step 5) are fixed schemas in code; their instruction texts live in the Prompt Book
(`edit.perception.*`) so they can be tuned without a deploy.

Writer LLM prompt (Heavy, section 11, step 6), Prompt Book key `edit.writer.general`:
- Role: a short form video editor writing on-screen text for fast, face to camera self branding videos.
- Output: JSON only, matching the writer schema (slot id → text, optional icon query).
- 1 to 4 words per text, in the video's language, in the brand's voice; never a banned word; never repeat the caption verbatim
  unless the slot is a keyword echo.
- Icon queries: 1 to 3 English words for a concrete object (IconScout search works best in English).

For long videos, split into chunks of about 30 to 40 seconds and keep a running list of used effects so cooldown rules hold
across chunks.

## 19. Rendering on Android

- Where: on the phone, in `aiedit/engine/` (section 7), run from a foreground service with a progress notification so the
  user can leave the app.
- Pipeline: MediaExtractor + MediaCodec decode (hardware), OpenGL ES 3.0 compositor (video effects as shader programs; text,
  captions, icons and Lottie drawn with Canvas into textures and blended), MediaCodec H.264 encode, MediaMuxer. Same family as the
  existing `edit/EditRenderer` and `subtitle/SubtitleDrawer`, which already burn subtitles in hardware. Do not add Media3 or
  FFmpeg for this; FFmpeg stays for the lossless crop only.
- Output size per ratio (one table in `aiedit/engine/render/OutputSizes`): **9:16 = 1080 x 1920 (1080p)**; 4:5 = 720 x 900 and
  1:1 = 720 x 720 (feed formats watched on phones; smaller is faster to render). Render one file per enabled ratio, 9:16 first
  and shown as soon as it is ready. Rendering per ratio (not cropping the 9:16 result) keeps overlays inside each ratio's frame.
  If the master is narrower than the output, upscale and accept the quality limit. Bitrate about 12 Mbps for 1080p, 6 Mbps for
  720 wide.
- Cuts and speed: apply in the decode loop by source timestamps; keep audio in sync (audio resampled for speed segments, crossfade
  of a few ms at each cut to avoid clicks).
- Lottie: lottie-android; draw the frame for each output timestamp by setting progress from time; speed up by scaling the
  progress mapping.
- Text: `StaticLayout` + `TextPaint` (all scripts), stroke and shadow layers, `BlurMaskFilter` glow, `LinearGradient` fills,
  stacked copies for 3D. Cache a text bitmap per item and animate with the transform matrix, not by redrawing each frame.
- Avoid per frame allocation. Test on a low end phone. Target: a 2 minute 9:16 render in under about 2 minutes on a mid phone.
- Keep `capabilities.effects` accurate so the server never plans an effect the app cannot render.
- The result is a new file in the recording's group, tagged "AI edit" (same mechanism as the manual editor's "Edited" tag). The
  plan JSON is kept next to it so a re-render needs no server call.

## 20. (removed)

Server-side rendering frameworks were considered and dropped by the owner (2026-11-17). Rendering stays on the phone.

## 21. Build order and tracker

Build the parts in order. Tick each row when done and commit per part. Backend parts follow the backend's `_planning` system
(a new contract `_planning/02_contracts/ai.video-editing.md` first; changes to frozen contracts go through `_drift.md`).

| Part | Repo | Scope | Status |
|---|---|---|---|
| B1 | backend | Contract files (registry, style presets, phrase library, plan and event log schemas) in `L/ai-video-edit/contract/`; planner core: free windows, remap, structure phase, validator; unit tests with fixtures | ⬜ |
| B2 | backend | Prisma model `AiVideoEdit` + migration; repository and service; pricing actions `ai.edit.*`; reserve, commit, release; routes `POST/GET /ai-edits`, `quote`, `contract`; `edit_proxy` upload purpose | ⬜ |
| B3 | backend | Workflow and activities: transcript reuse, structure, candidate slots, Clef-omni client and perception, writer LLM (Prompt Book keys), assembler, validator, failure refunds | ⬜ |
| B4 | backend | IconScout resolver with R2 cache; sound ids | ⬜ |
| B5 | backend | Caption credits held until used, released on discard (only if the owner approves, section 5) | ⬜ |
| A1 | app | Event log recorder (strokes, board, slides, facecam track) saved next to the master; remap through manual edit kept ranges | ⬜ |
| A2 | app | `aiedit/engine/` packages: plan, registry and timeline with JVM unit tests; contract sync copy | ⬜ |
| A3 | app | Renderer core: decode, GL compositor, encode 1080p per ratio, cuts and speed with audio, captions with the user's template | ⬜ |
| A4 | app | First wave effects (section 14): text styles with palette, video effects, transitions, icons and Lottie, sounds | ⬜ |
| A5 | app | Flow: AI Edit button with cost and level sheet, Discard with undo, Record again, Settings (level, brand colours, sounds), upload and job polling, foreground render, result in the group tagged "AI edit", errors and top-up path | ⬜ |
| A6 | app | Frame difference detection for imported videos | ⬜ |
| A7 | both | Second wave and person effects; mild effects inside protected spans (content_safe) | ⬜ |

## 22. Tests and acceptance

- Validator unit tests for each rule in section 17, with passing and failing fixtures.
- Free window tests: brush spans at the start, middle and end; overlapping spans; very short gaps.
- Remap tests: cuts, speed changes and manual edit kept ranges shift words, protected spans and the facecam track correctly.
- Golden plans: for 3 to 5 sample transcripts (English and Bengali), the plan passes the validator with zero warnings.
- Golden frames: for each first wave effect, the renderer's frame at fixed timestamps matches the fixture within a tolerance
  (this is also the acceptance test for a future desktop renderer).
- Money: tap AI Edit twice → one charge; planning fails → balance back to before; discard a take → no charge.
- Acceptance: no overlay or video effect starts or runs inside a protected span. No overlay touches the facecam at any time.
  Every animation duration is inside the speed table. The master file is byte for byte unchanged.
- Keep every plan, score set and validator log for prompt tuning (never the transcript in app logs).

## 23. Decisions

### Settled (from the code and the owner's notes, 2026-11-17)

1. Server: the existing NestJS backend; jobs run as Temporal workflows in `apps/orchestrator`; routes in the public API.
2. Recorder and brush: the app draws the board and slide ink itself, so stroke events with timestamps and bounding boxes can be
   logged exactly (section 10).
3. Export pipeline: MediaCodec + OpenGL ES (`EditRenderer`, `SubtitleDrawer`); FFmpegKit only for lossless crops; no Media3.
   The AI renderer is a new module on the same technology (section 19).
4. Minimum Android 12 (minSdk 31), target SDK 36.
5. Assets from IconScout API, cached on the server (licence check open, below).
6. Facecam is movable and resizable, so the app sends a facecam track (section 9).
7. No plan editing by the user in v1 (single call mode). Revise mode later.
8. Video analysis with Cloudflare Clef-omni for closed decisions; an LLM through the existing `OpenaiService` and Prompt Book only
   for Heavy text writing.
9. Languages: all; Bengali and English first for the phrase library.
10. Review before processing; credits only on Process; refunds on system failure (sections 4 and 5).
11. Brand colour applies to text only; default multi colour.
12. Render on the phone; 1080p for 9:16, 720 wide for 4:5 and 1:1 (section 19). No server-side rendering framework.
13. Engine is a package tree inside `:app` with strict separation of concerns, not a separate Gradle module.
14. Prices: Basic 1, Standard 2, Heavy 3 credits per video up to 3 minutes; longer videos: the level price per started
    3 minutes. Values live in `pricing.config.ts`.
15. Caption credits are held until the transcript is used (embed, publish, AI edit) and released on discard or after 7 days
    unused (part B5).
16. Standard and Heavy upload a 360p low bitrate proxy for Clef-omni. Server env: `CLOUDFLARE_ACCOUNT_ID`,
    `CLOUDFLARE_AI_TOKEN`.
17. IconScout: development uses the URL in the search result JSON (plain download); production switches to `api-download`
    (section 15). Env: `ICONSCOUT_CLIENT_ID`, `ICONSCOUT_CLIENT_SECRET`, `ICONSCOUT_DOWNLOAD`.
18. Sounds: CC0 only (Kenney, Freesound CC0), served from `AI_EDIT_SOUNDS_BASE_URL` (section 15).
19. The AI edit always adds captions, even when subtitles are off in Settings.
20. The 6 text looks in section 13 are the first style preset set.
21. Hook title over a designed background for voice-only content is a separate future feature (audio-only recording that becomes
    a post): `PRODUCT_IDEAS_BACKLOG.md`.
