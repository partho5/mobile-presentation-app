# Product Ideas Backlog (not part of AI video editing)

Owner's raw notes, sorted out of the AI editing notes on 2026-11-17. AI editing items live in `ai-video-editing-spec.md`.
Each idea has a short status from the code and anything to clarify before building. Nothing here is built yet unless marked.

Top rule for all of these: frictionless, smooth UX that lowers the user's cognitive load.

## 0. Audio-only recording that becomes a post (future feature)

The user starts an **audio-only** recording (no screen, no camera). The server transcribes it and it becomes a post. For video
platforms, the video is the **hook title written over a pre-designed image background**, with the spoken words as captions.

- Reuses: ElevenLabs transcript and AI captions (`ai.video-captions`), the AI editing engine's text styles and captions
  (`ai-video-editing-spec.md`, section 13), the publish flow.
- Needs: an audio-only record button and flow, about 10 designed backgrounds (brand colour aware), a hook title from the
  transcript (LLM), and a renderer path that takes a still background instead of a source video.
- Text-only input (no voice) can use the same background + hook title path, without captions.

## 1. Onboarding first page: name the target audience

After "makes your face well known", add "to your target audience" and continue the sentence (the rest of the sentence was cut
off in the note: "and establish…").

- Where: `ui/OnboardingActivity` (screen titles `onb_title_*`, story cards `item_onboarding_story_*`).
- Clarify: the full sentence after "and establish".

## 2. Brand details (video metadata of my brand)

"Set details video metadata of my brand."

- The backend already stores per brand (workspace): name, colour, avatar, time zone, locale, and a creative profile (tagline,
  audience, voice, pillars, banned words), editable on the web (`brand.profile` contract). The app does not show or edit it.
- Clarify which one is meant:
  a. let the user set these brand details from the app (they also feed AI captions and AI editing text), or
  b. write metadata into the exported MP4 file (title, author, copyright, description).

## 3. 16:9 option for YouTube long videos

The note says "9:16 for YouTube long video format"; YouTube long videos are 16:9, and 9:16 already exists, so this reads as
**16:9**.

- Settings auto-crop today: 9:16 (first), 4:5, 1:1 (`util/RecordSettings`, `util/VideoCropHelper`).
- Problem: the recording is the phone screen in portrait (for example 1080 x 2400). A 16:9 crop of it is a thin 1080 x 608 strip.
  A real 16:9 video needs either recording in landscape, or a composed layout (slide area on the left, facecam on the right, on a
  1920 x 1080 canvas), which means a render, not a crop.
- Order: keep 9:16 first; add 16:9 after 1:1.
- Clarify: landscape recording, or composed layout?

## 4. Manual editor: traditional style video preview

"In the video editing screen show video preview like traditional video editing software. Do not change anything else."

- Partly done: commit 1688abd made the preview bigger (`ui/VideoEditorActivity`).
- Remaining meaning to confirm: a large preview on top with the timeline below and the playhead synced (as in CapCut or
  Premiere), keeping every current control as it is.

## 5. Drawing board remembers its state per slide

Today the board must be opened manually after a slide change. Expected: if the user used the board on a slide, the board opens by
itself (with that slide's drawing) when that slide starts. The user can still toggle it.

- Today: `util/DrawingBoardStorage` keeps **one** board drawing for all slides; a slide-change swipe outside the board hides it.
- Needed: board drawing and shown/hidden state saved per slide id (like `util/SlideInkStorage` already does for slide ink),
  restored on slide entry, during recording and presenting.
- Also useful for AI editing: board show/hide events go into the event log.

## 6. "Sub" button embeds subtitles without charging twice

Status: **built** (commits 6ff4473, 249b153, dcedf5b). The preview has the "sub" button (`btn_embed_subtitles` in
`ui/VideoPlayerDialog`); `subtitle/SubtitleEmbedStore` remembers burned files by fingerprint and reuses the caption id so the
user is not charged again; all crop versions get the subtitles.

- To verify on a device: tap "sub" twice, and on an already embedded video → no second burn, no second charge.
- Related money issue (in the AI spec, section 5): auto transcription after recording charges caption credits even for takes
  that are later discarded.

## 7. Auto move the face cam while the user draws (setting, default off)

When the user is actively showing something (drawing with the pen on a slide or on the board), move the face cam down as low as
possible; when drawing stops, move it back. Only the vertical position changes, at a smooth, calm pace.

- Setting in Settings, default **off**.
- Where: `MainActivity` camera container (`cameraRootWrapper`), and `ui/FloatingCamOverlay` outside the app. Triggers: stroke
  start and end from `ui/DrawingBoardView` and `ui/SlideInkController`, with a short delay before moving back (for example
  1.5 s with no stroke) so it does not bounce between strokes.
- Animate with a fast-out, slow-in curve over about 400 to 600 ms; never move while the user is dragging the cam.
- Every move is logged in the facecam track used by AI editing (`ai-video-editing-spec.md`, section 9).
- Clarify: should it also move away when the drawing is in the lower part of the screen (move up instead of down)?
