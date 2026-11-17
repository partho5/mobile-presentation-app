# AI Video Editing Feature: Specification for the Coding Agent

Read this whole file before writing code. Ask the developer about every item in section 16 before you assume an answer.

## 1. Purpose and scope

Add an AI powered editing feature to an existing Android app (Java). The feature turns a raw short vertical video into a fast, heavily edited, social media style video. The goal is self branding content that viewers do not scroll past.

All AI decisions are made on the server. The app and the server talk in both directions.

Current scope:
- The server AI decides which effects to use, on which target, and at which exact moment.
- The server returns a validated edit plan as JSON.
- The app builds the activity map (section 6) and later renders the plan.

Rendering is described in section 13 for context. Confirm with the developer before building the renderer. Build the planning layer first (sections 4 to 12, milestones M1 to M3).

## 2. Product context

- Videos are vertical (9:16), up to about 2 minutes.
- Each video has a round facecam, usually in the lower part of the frame.
- The app has a built in brush. While recording, the user can draw on text and images shown as slides, one slide after another. Brush use is optional.
- The server uses ElevenLabs speech to text. It returns word level timestamps with very high precision. Use these timestamps to place effects on exact words.
- About 50 or more effects exist. One video may use up to 10 feature effects. Effects are used interchangeably across components.

## 3. Core principles

1. The AI outputs a plan. The app renders it the same way every time. The AI never touches pixels.
2. The AI may only pick effect ids from the catalog and may only place them in free windows given by the server.
3. Code validates every plan. Never trust the AI output without validation.
4. Brush use and slide content are protected. Do not spoil them with extra animation.
5. Everything is fast. No slow animation and no slow transition (section 8).
6. Anchor effects to word indexes, not to raw seconds.
7. One API call is the default. Use more calls only when needed.

## 4. System overview

```
App                                   Server
---                                   ------
Record video (with event log)
Build activity map (event log,
  frame diff as fallback)
Upload audio + metadata  ----------->  Run speech to text (word timestamps)
                                       Phase 1: structure (cuts, speed)
                                       Remap activity map to new timeline
                                       Compute free windows
                                       Phase 2: AI picks effects in free windows
                                       Validate and repair plan
Receive edit plan  <-----------------  Return plan JSON
Render preview and export
```

### API modes

- Mode A, single call (default): one request, the server runs all phases, returns the final plan.
- Mode B, two rounds: the server returns the structure result first (cuts and speed). The app or user confirms. A second call asks for effects on the confirmed timeline. Use this when the user can edit cuts, or when the app must report real durations.
- Revise: send the current plan plus the user changes. Locked items stay. The server re-plans only unlocked parts.

Why phases exist: cuts and speed changes move the timeline. Effects must be planned after the timeline is fixed. This is the real reason for multiple phases.

## 5. Request contract (app to server)

Normalized coordinates use 0 to 1 relative to the frame width and height.

```json
{
  "session_id": "string",
  "mode": "single | structure | effects | revise",
  "video": {
    "duration_ms": 118000,
    "width": 1080,
    "height": 1920,
    "fps": 30,
    "source": "in_app_recording | imported"
  },
  "facecam": { "shape": "circle", "cx": 0.5, "cy": 0.82, "radius": 0.14 },
  "audio_upload_ref": "string",
  "activity_map": [],
  "capabilities": {
    "effects": ["v_punch_in", "c_word_highlight"],
    "person_segmentation": true,
    "style_presets": ["sticker_red", "label_blue"]
  },
  "options": { "max_feature_effects": 10, "style": "high_energy" }
}
```

Transcript word format (kept on the server, also returned to the app):

```json
{ "i": 42, "w": "faster", "start_ms": 31240, "end_ms": 31610, "conf": 0.98 }
```

Store the original high precision timestamps on the server. Convert to integer milliseconds for the plan.

## 6. Activity map and protected spans

Important rule: do not add overlays or video effects when something important is already changing on screen, such as brush strokes on a slide.

### Entry format

```json
{
  "id": "a1",
  "type": "brush | slide_change | motion",
  "start_ms": 12000,
  "end_ms": 15400,
  "region": { "x": 0.1, "y": 0.2, "w": 0.6, "h": 0.3 },
  "strength": 0.8,
  "source": "event_log | frame_diff"
}
```

- `brush` and `motion` entries are protected.
- `slide_change` is a marker with `start_ms == end_ms`. It is not protected. It is an anchor. A short effect right after it is allowed. The effect must end before the next protected span starts.

### Source 1: event log (use first)

The app owns the recorder, so it logs exact events while recording: brush stroke start, stroke end, stroke bounding box, slide change. This is exact and almost free. Merge strokes that are close in time into one span (gap under 400 ms).

### Source 2: frame difference (fallback and extra check)

Use for imported videos and for motion the log does not know about.

1. Decode at low resolution, about 10 frames per second.
2. Convert to small grayscale images (for example 64 by 112).
3. Mask out the facecam circle. Face motion must not count.
4. Compare each frame with the previous one per grid cell (for example 4 by 4). Use mean absolute difference.
5. Mark cells above a threshold. Merge nearby busy cells and frames into spans (gap under 400 ms).
6. Make the threshold configurable.

### Protected span

A busy span expanded by `padding_ms` on both sides (default 300 ms).

### Free windows

Free windows = timeline minus all protected spans. Drop windows shorter than `min_window_ms` (default 500 ms). The server gives the AI the list of free windows. This is more reliable than asking the AI to avoid busy spans.

### v1 policy inside protected spans

Block all of these: overlays, zoom, shake, glitch, flash, speed changes, cuts, layout changes, facecam effects. Captions and sound may continue. Captions must not cover the brush region (see open questions).

Later, the `content_safe` flag in the catalog can allow mild effects inside protected spans. Do not build that now.

When phase 1 changes the timeline, remap the activity map to the output timeline. Phase 1 must never cut or speed up inside a protected span.

## 7. Planning phases on the server

1. Speech to text. Get words with timestamps.
2. Phase 1, structure: silence removal, jump cuts, optional speed changes. Output a list of cuts and speed segments. Derive the output timeline and a word map (source time to output time).
3. Remap the activity map.
4. Compute free windows on the output timeline.
5. Phase 2, effects: send the AI the output transcript, the free windows, the slide change anchors, the catalog summary, and the rules. The AI returns plan items.
6. Validate. Repair or drop invalid items. Return the plan with a warnings list.

## 8. Speed rules (all effects must be fast)

These are defaults. Store them in the registry and keep them configurable.

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

Easing: use fast out curves (ease out cubic, ease out back with small overshoot). Do not use slow ease in out.

Lottie fitting:
- `speed_factor = native_duration_ms / slot_duration_ms`.
- If the factor is above 1, speed the clip up by changing the progress mapping. Do not cut it.
- Cap the factor at 3.0. If more is needed, pick another asset.
- Never slow a Lottie below its native speed. If the slot is longer, hold the last frame or end the clip early.

Removed from the catalog because they are slow: subtle handheld drift, long slow push in.

## 9. Effect catalog

The catalog is a registry, shared by server and app (one source of truth, for example a JSON file). The AI sees a compact summary. The validator uses the full entries.

### Registry entry format

```json
{
  "id": "v_punch_in",
  "layer": "video",
  "target": "full_frame",
  "allowed_components": ["video"],
  "content_safe": false,
  "cost": "L",
  "min_duration_ms": 150,
  "max_duration_ms": 400,
  "default_params": { "scale_to": 1.15, "ease": "out_back" },
  "param_limits": { "scale_to": [1.05, 1.25] },
  "cooldown_ms": 6000,
  "max_per_video": 3,
  "sound": ["whoosh_short", "pop_1"],
  "requires": []
}
```

Fields:
- `target`: `full_frame`, `slide_area`, `facecam`, `overlay`, or `caption_area`. The plan must always state it.
- `content_safe`: true if the effect does not hide, crop, move, or distract from slide or brush content. Reserved for later use.
- `cost`: L, M, or H, for phone GPU load.
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

With a small round facecam, apply person effects inside the circle only.

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
| x_slide_up | Slide up with fade |
| x_typewriter | Typewriter, total time 500 ms max |
| x_one_word | One big word at a time |
| x_sticker | Sticker text: thick outline and shadow |
| x_gradient | Gradient fill text |
| x_label_tag | Rounded label tag, such as "Tip 1" |
| x_tilt_sticker | Tilted sticker with bounce |
| x_underline_draw | Underline draw |
| x_circle_draw | Hand drawn circle around a word |
| x_arrow_draw | Arrow draw on |
| x_count_up | Number count up, 800 ms max |
| x_shake | Text shake on a strong word |

All text effects: target `overlay`, content_safe no, cost L (typewriter and draw effects M). Text is never plain. Use a `style_preset` (section 10) with a background, border, shadow, or gradient.

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

All: target `overlay`, content_safe no, cost L (burst M). Works for both `lottie` and `icon_image` components. Each has an entry and an exit.

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

All: target `full_frame`, content_safe no, cost L to M, duration 150 to 300 ms. Place at cut points from phase 1, not inside protected spans.

### Captions

| id | Description |
|---|---|
| c_word_highlight | Word by word highlight |
| c_active_pop | Active word scale pop |
| c_karaoke | Karaoke two line style |
| c_emphasis_big | Emphasis word larger and in a different color |
| c_emoji_after | Emoji after a keyword |

Captions are the most important effect group for this style. They run for the whole video and are not counted in the 10 feature effect limit.

### Sound pairing

Each effect may list short matching sounds: whoosh, pop, ding, impact. Add an optional `sound` field to plan items. The sound library is an open question.

### Build priority (first wave)

Captions c_word_highlight, c_active_pop, c_emphasis_big, c_karaoke. Video v_punch_in, v_push_in, v_alt_crop, t_speed_ramp, t_jump_cut, l_flash, l_rgb_glitch, y_slide_grow_face_shrink and y_face_grow_slide_shrink. Text x_marker_sweep, x_sticker, x_pop. Icons i_scale_pop. Transitions tr_whip, tr_flash_cut. Person p_bg_blur if segmentation is easy.

## 10. Plan JSON schema (server to app)

All times are integer milliseconds on the OUTPUT timeline (after cuts).

```json
{
  "plan_version": 1,
  "session_id": "string",
  "video": { "output_duration_ms": 96400, "width": 1080, "height": 1920 },
  "structure": {
    "cuts": [ { "src_start_ms": 5200, "src_end_ms": 5900, "reason": "silence" } ],
    "speed_segments": [ { "src_start_ms": 20000, "src_end_ms": 21000, "rate": 1.4 } ]
  },
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
        "text": "10x faster",
        "style_preset": "sticker_red",
        "style_overrides": {}
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
        "query": "rocket launch",
        "asset_id": "resolved_by_server",
        "native_duration_ms": 4000,
        "fit": { "mode": "speed_up", "factor": 2.2 }
      },
      "params": {}
    }
  ],
  "captions": { "effect": "c_word_highlight", "style_preset": "caption_bold", "emphasis_words": [12, 42, 77] },
  "warnings": []
}
```

Notes:
- `anchor.word_index` refers to the transcript word. The app can recompute times if the transcript changes.
- The server resolves Lottie and icon assets from a query to a concrete `asset_id` and `native_duration_ms` before it returns the plan.
- Use `style_preset` ids (a list of about 10 to 20 presets stored on server and app) with optional small overrides. This keeps the AI output consistent.
- Position uses named zones. Default overlay safe rectangle (configurable, confirm against the real layout): x from 0.06 to 0.94, y from 0.10 to 0.62. Keep a margin of 0.02 around the facecam circle. Avoid the bottom and right edges where social apps show their buttons.

## 11. Server validator rules

Reject or repair an item when any rule fails. Log every rejection for tuning.

1. Effect id must exist in the catalog and in the app `capabilities`.
2. Item time range must fit inside one free window (overlays, video effects, layout, facecam).
3. Duration must be inside the effect min and max.
4. Params must be inside `param_limits`.
5. Overlay must not intersect the facecam circle plus margin, and must stay inside the safe rectangle.
6. Maximum feature effects per video: `max_feature_effects` (default 10). Captions and transitions do not count.
7. Cooldown: the same effect id not again within its `cooldown_ms`. `max_per_video` per effect.
8. Maximum 2 effects active at the same moment. No two cost H effects overlapping.
9. At least one strong effect inside the first 3 seconds (hook rule). If missing, add one or warn.
10. Pacing: a visible change at least every 3 seconds (effect start, cut, transition, or caption emphasis). Warn if a gap is longer.
11. Lottie fit factor at most 3.0, never below 1.0 slow down.
12. Text hold time follows the formula in section 8.
13. Anchor word index must exist. If the anchor and the time disagree, trust the anchor and recompute the time.
14. Phase 1 cuts and speed changes must not touch protected spans.
15. Return the plan sorted by `start_ms`, with no duplicate ids.

Repair order: shift inside the same free window, shorten, swap to a cheaper effect, drop the item.

## 12. Planner (AI) prompt guidelines

Use structured output or JSON schema enforcement. The model name is a decision for the developer.

The prompt should state:
- Role: a short form video editor for fast, high energy, face to camera self branding videos.
- Output: JSON only, matching the plan schema.
- Choose only effect ids from the catalog summary.
- Place effects only inside the free windows list.
- Anchor each effect to a word index.
- Use effects on key words and emphasis, not on every sentence.
- Keep a visible change about every 2 to 3 seconds. Make the first 3 seconds strong.
- Vary effect types. Do not repeat the same effect close together.
- Use at most `max_feature_effects` feature effects.
- Everything must be fast (section 8).
- Add a short `reason` for each item.

Give the model: output transcript with word indexes, free windows, slide change anchors, facecam geometry, safe zones, catalog summary (id, layer, target, duration range, one line description), and style presets.

For long videos, split the transcript into chunks of about 30 to 40 seconds and keep a running summary of used effects, so cooldown rules hold across chunks.

## 13. Android notes (for the later renderer stage)

Verify all library names and APIs against current documentation before use.

- Language: Java. Match the existing code style and architecture of the app. Read the existing recorder and export code first.
- Preview and export: AndroidX Media3 (ExoPlayer with video effects for preview, Transformer with Composition for export) is the likely base. Custom video effects use OpenGL shader programs. Overlays can use bitmap or texture overlays.
- Lottie: use lottie-android. For export, draw the Lottie frame for each output timestamp into an overlay by setting the progress from time. Speeding up a clip means scaling the progress mapping.
- Speed ramps and cuts: check how the current Media3 version supports speed changes with audio. Keep audio and video in sync.
- Person segmentation: check ML Kit selfie segmentation or similar. Treat person effects as optional behind a capability flag.
- Frame difference: decode at low resolution with MediaCodec. Do not decode full frames in Java arrays.
- Effect implementations should be plugins that read the registry entry and params. One class or shader per effect id.
- Avoid heavy per frame object allocation. Test on a low end phone.
- Keep `capabilities.effects` accurate so the server never plans an effect the app cannot render.

## 14. Build order

- M1: Effect registry (JSON), plan schema, validator, unit tests. No AI yet.
- M2: Activity map. Event log recording in the app. Protected spans and free window computation on the server. Tests with sample maps.
- M3: Planner call with a mock transcript, then ElevenLabs transcripts. Structure phase (silence cuts, remapping). End to end: audio and activity map in, valid plan out.
- M4: App plan parser and preview renderer for the first wave of effects.
- M5: Export pipeline including Lottie and sound.
- M6: Frame difference detection for imported videos.
- M7: Remaining effects, segmentation based effects, mild effects inside protected spans (content_safe).

Stop and report after each milestone.

## 15. Tests and acceptance

- Validator unit tests for each rule in section 11, with passing and failing fixtures.
- Free window tests: brush spans at the start, middle, and end; overlapping spans; very short gaps.
- Remap tests: cuts and speed changes shift both words and protected spans correctly.
- Golden plans: for 3 to 5 sample transcripts, the plan passes the validator with zero warnings.
- Acceptance: no overlay or video effect starts or runs inside a protected span. No overlay touches the facecam. Every animation duration is inside the speed table.
- Keep every plan and validator log for prompt tuning.

## 16. Open questions (ask the developer)

1. Server language, framework, and where the planner will run.
2. Current structure of the recorder and brush code. Can it log stroke events with timestamps and bounding boxes?
3. Current export pipeline (library and version). Is Media3 already used?
4. Minimum Android version and target device class.
5. Where Lottie and icon assets come from (IconScout API or a local library). How are they searched, cached, and stored? Check the license terms for use inside an app that exports user videos.
6. Sound effect library: source and license.
7. Style presets: who designs the first 10 to 20?
8. Caption position when brush activity is in the lower part of the frame.
9. Is the facecam always a circle in the lower part, or can the user move it? If movable, the app must send the real position per segment.
10. Should the user be able to edit the plan before export (Mode B and revise)?
11. Which AI model and provider for the planner. Cost limit per video?
12. Languages of the videos. This affects captions, fonts, and text effects.
