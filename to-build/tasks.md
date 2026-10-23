Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.
-----------------

RULE: this PC hangs under load, so do not run gradle, unit tests or the emulator. Write code only and verify by reading it against this plan; the owner builds and runs tests on a higher-RAM PC. Build one part at a time and update Progress below.

------------------

# AI captions from the video's speech — app side (decided 2026-10-21)

Goal: a paid user (any plan above Free) never writes captions. When a video is published (auto or manual), the app extracts the speech audio as a small WAV in parallel with the video upload and uploads it; the server transcribes it (Meta Muse) and writes a caption, title and hashtags fitted to each platform (GPT-5 Nano); non-video channels get a text post. 1 credit per whole minute of speech audio, rounded down (a 2 min 50 s video costs 2; under 1 minute costs 0); the server computes it from the uploaded WAV. Backend side, decisions and the full contract: `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md` (contract copied below). RULE above applies. Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## What people see
- **Auto publish (paid):** record → stop → undo window as today → one notification "Uploading…" (it reads "Writing captions…" only if the video finished first) → posts go live, each with its own caption; switched-on text channels get a text post. Card: "Live on 5 · AI captions"; scheduled: "Scheduled 6:00 PM · Edit captions". No extra taps; normally no extra wait.
- **Manual publish (paid):** Publish screen gets one switch "AI captions · +N credits" (on by default, remembered; N from the server for this video's length, "free" when 0). With it on, the caption field is optional; hint: "Optional: a line to keep word for word (link, offer). AI writes the rest." The cost line shows the server's numbers.
- **Never an error because of AI.** The post always goes out; the card adds one quiet line: "Posted with your text: not enough credits for AI captions" (tap → top up), "Posted with your text: no speech found" (no charge), "Posted with your text: AI captions unavailable". "Now" posts wait at most 120 s for captions.
- **Free:** the switches show locked with the crown and open the existing upgrade sheet; no audio work at all.
- **Text-post channels:** on the Auto upload screen, non-video platforms the server marks `textPost` get a switch "Text post from your video" (off by default; owner call). Others stay "Manual posting only".
- **See/edit:** card action "Edit captions" (scheduled, not yet live) or "See captions" (posted) opens the web caption page signed in; editing happens in the existing web editor. No in-app editor.

## How to change things later
- Plan that unlocks it: server `PRICING.features.ai_captions`; mirror in `entitlements/Feature.DEFAULT_LOWEST` at the next release.
- Price: server only (`GET /public/v1/auto-publish` `aiCaptions.costPerMinute`, charge-preview `aiCaptions` for a given length); the app never hardcodes the price or the rounding rule.
- Wait limit after the video upload: `CAPTION_WAIT_MS` (120 000) in one place in `publish/`.
- Prompts: web (Settings → AI prompts, owner `/admin/prompts`); nothing in the app.

## Decisions
- **Who decides "paid"**: `Entitlements.has(Feature.AI_CAPTIONS)`; for auto publish the `FeatureSnapshot` taken at recording start (same as `auto_publish`, `bg_replace`); for manual publish the value when Publish is tapped. Plus the pref `KEY_AI_CAPTIONS` (default true) and a signed-in session.
- **Audio**: FFmpegKit (already in `app/build.gradle`, `ffmpeg-kit-full:8.1.7`; sync call pattern in `util/VideoCropHelper`): `-vn -ac 1 -ar 16000 -c:a pcm_s16le -t 3600 out.wav` from the exact file that will be uploaded (`AutoPublishFiles.pick` result for auto, the chosen file for manual), into the app cache dir. Skip when the file has no audio track (`MediaExtractor` + `findTrack(…, "audio/")` like `bgreplace/OfflineBgProcessor`; recordings made with `key_record_audio` off have none). ≈1.9 MB per minute; capped at 60 min. If the backend's AC4 finds Muse accepts compressed audio, switch to an AAC copy (`-vn -c:a copy out.m4a`) — the server contract will say so.
- **Order inside the worker** (`service/PublishWorker` → `publish/PublishPipeline.run`): only after the undo window has passed (nothing is charged for an undone recording). Thread A: extract → upload audio (`purpose: transcript_audio`) → `POST /public/v1/ai-captions`. Thread B: video upload exactly as today. After B: if a caption id exists and its status is not final, poll `GET /public/v1/ai-captions/:id` every 3 s until READY / NO_SPEECH / FAILED or `CAPTION_WAIT_MS`; then the publish call. Wi-Fi-only and the network constraint apply to both uploads (same worker).
- **AI never fails the job**: every caption step runs in its own try block that only writes `aiCaptionState` on the job; a 402/403/404/409/5xx/timeout from a caption call never reaches `PublishPipeline.fail()` and never maps to NEEDS_PLAN. A 403 `FEATURE_NOT_IN_PLAN` only triggers `EntitlementsRefresher.refreshNow()` (no upgrade sheet mid-publish).
- **Idempotency (G-018 trap found in review)**: the server hashes the raw body per `Idempotency-Key`; a changed body under the same key is 422 `IDEMPOTENCY_KEY_REUSED`, which the app treats as final and rotates the key → duplicate post. So before the first publish call the job stores `aiCaptionId` (or the literal "none") and, for a manual "now" post, the `date` it sends; every retry under that key sends the same values. This also fixes today's bug where a manual "now" retry rebuilt its body with a new timestamp (`PublishRequestBuilder.java` ~L54, L129). The caption create uses its own stable key `aiCaptionKey` (UUID stored on the job), never the rotating publish key (`spendsKey` in `PublishPipeline`). Caption create is sent only after the audio `complete` (a 409 would be replayed for 24 h).
- **Database** (`db/AppDatabase`, version 7 → 8, `MIGRATION_7_8` in the existing array, like `MIGRATION_5_6`): new table `audio_upload_sessions` (same columns as `upload_sessions`, primary key `job_id`) so the live resumable video sessions are untouched; `ALTER TABLE publish_jobs ADD COLUMN` `aiCaptionId TEXT`, `aiCaptionState TEXT`, `aiCaptionKey TEXT`, `publishDate TEXT`. `aiCaptionState`: `off` | `extracting` | `uploading` | `pending` | `ready` | `skipped_credits` | `no_speech` | `unavailable` | `timeout` | `no_audio`.
- **Logging**: never log transcript or caption text (`ACTIVITY_LOG_PRIVACY.md` already promises that).

## API contract (from the backend plan — keep exact)
Public API (`/public/v1`, device Bearer token, `x-workspace-id`).
```
POST /upload/multipart  { filename, size, contentType?, purpose?: 'media' | 'transcript_audio' }   // default 'media'
  transcript_audio: '.wav' only, size ≤ 120 MiB; own pending cap and throttle bucket (does not block video uploads).
  parts / status / abort exactly as for video.
POST /upload/multipart/:id/complete { parts } → transcript_audio: 200 { id }   // upload id; NO path, NO poster
  A repeated complete answers the same { id }.

POST /ai-captions  { audioUploadId, mode: 'auto' | 'manual', integrationIds?: string[] /*manual only*/,
                     title?: string /*recording name*/, userText?: string /*manual typed caption, plain text*/ }
  + Idempotency-Key (aiCaptionKey)
  202 { id, status: 'PENDING', cost, minutes }      // cost = credits reserved (0 under 1 min)
  200 { status: 'SKIPPED', reason: 'CREDITS' }        → aiCaptionState skipped_credits, publish without aiCaptionId
  403 FEATURE_NOT_IN_PLAN · 404 AUDIO_NOT_FOUND · 409 AUDIO_NOT_COMPLETE · 503 AI_CAPTIONS_NOT_CONFIGURED → unavailable
  Same audioUploadId again → the same caption.
GET  /ai-captions/:id → 200 { id, status: 'PENDING'|'TRANSCRIBING'|'GENERATING'|'READY'|'NO_SPEECH'|'FAILED',
                               error?, language?, outputs: [{ integrationId, provider, kind: 'video'|'text', title?, text }] }

POST /auto-publish { media, title, aiCaptionId? }       // response unchanged
POST /posts        { ...today's body, aiCaptionId? }     // response unchanged
  Not READY / unknown → the server uses today's text. Body fixed per Idempotency-Key (see Decisions).
  With aiCaptionId on /posts the server writes each channel's text and title itself; the app keeps sending
  its usual body (the typed caption as content) and passes the typed caption as userText at caption create.
GET  /auto-publish → adds aiCaptions: { costPerMinute }
GET  /platforms    → adds per platform textPost: boolean, per channel textPostOn: boolean
PUT  /auto-publish/text-channels/:integrationId { enabled } → 200 { textIntegrations: string[] } · 404 · 400 TEXT_POST_NOT_SUPPORTED
POST /web-link { target: 'ai-caption', identifier: <captionId> } → { url }
```
Web route the app already reads: `POST /posts/charge-preview { integrationIds, group?, aiCaptionsSeconds?: number }` → adds `aiCaptions: <credits>` = whole minutes of that length, rounded down, × price (amount stays the publish cost). The real charge uses the uploaded WAV's length, so the app sends the audio track's duration (`MediaFormat.KEY_DURATION` of the audio track), not the video's. Feature key `ai_captions` (STARTER); an unknown key from an older server is ignored as today.

## Parts
- [x] **M1** Feature and switches. `entitlements/Feature`: `AI_CAPTIONS = "ai_captions"`, `DEFAULT_LOWEST` → `PLAN_STARTER`; `ui/FeatureLabels` label "AI captions and text posts". Pref `KEY_AI_CAPTIONS` (default true) beside `KEY_AUTO_PUBLISH` in `util/RecordSettings`. `ui/AutoPublishActivity`: an "AI captions" switch row under the master switch (locked + crown via `ui/Gate` / `ui/PremiumMark` → `PaywallSheet` for Free) with the cost from `GET /auto-publish` `aiCaptions.costPerMinute` ("1 credit per minute of video, rounded down"). `ui/PublishActivity`: same switch above the caption field; caption becomes optional when on (hint text above); charge-preview called with `aiCaptionsSeconds` = the audio track duration of the chosen file and the line shows "N credits + M for AI captions" (M from the server; "AI captions free" when 0); Publish stays disabled until the cost loads (as today). Strings in `res/values/strings_publish.xml`. Spec (JVM): default lowest plan, switch hidden/locked logic.
- [x] **M2** Audio extraction and upload. New `publish/AudioExtractor` (has-audio check, FFmpegKit command, output in cache, delete after the upload or on cancel; returns null when no audio). `publish/DirectUploader` + `DirectUploadApi`: a kind-aware session store (video → `upload_sessions`, audio → `audio_upload_sessions`), `purpose` in the create body, `.wav` allowed for audio only, its own parser for the audio `complete` answer `{ id }` (`publish/CompletedUpload.parse` requires `path`, keep it for video), cancel aborts both uploads. `db/`: `AudioUploadSession` entity/DAO, `MIGRATION_7_8`, new `PublishJob` fields. Spec: migration SQL, parser, session store keyed by kind.
- [x] **M3** Caption job and publish. In `publish/PublishPipeline` / `service/PublishWorker`: thread A (extract → upload → create with `aiCaptionKey`) in parallel with the video upload; wait/poll rule and `CAPTION_WAIT_MS`; write `aiCaptionId` (or "none") and the manual `publishDate` on the job before the first publish call and reuse them on every retry; `PublishRequestBuilder` reads the stored date and adds `aiCaptionId` to both bodies (`autoPublishBody`, `createPostBody`); manual mode sends `integrationIds` and `userText` (plain typed caption) at caption create, auto mode sends `title`. Separate try block for every caption call (Decisions). Notification text "Writing captions…" only while waiting after the video. `publish/PublishLabels`: the card lines for `ready` ("AI captions"), `skipped_credits`, `no_speech`, `unavailable`, `timeout`. Spec (JVM, fake API): body identical across retries, "now" date reused, caption failure never fails the job, SKIPPED → no aiCaptionId, timeout → publish without waiting longer.
- [x] **M4** Text-post channels. `publish/Platforms` parses `textPost` / `textPostOn`; `ui/PlatformListAdapter` (build on the uncommitted `TYPE_ADD` row change already in the working tree): for a platform with `textPost`, each channel row gets a switch "Text post from your video" instead of "Manual posting only" (paid; Free sees it locked with the crown → upgrade sheet), calling `PUT /auto-publish/text-channels/:id` with the same optimistic-then-confirm handling as `onChannelToggled`. `ui/PublishActivity`: when AI captions are on, non-video channels in the checklist show "Text post (AI)" under their name. Spec: parsing, row type per platform.
- [x] **M5** See and edit captions. `adapter/RecordingsAdapter` card menu: "Edit captions" when the newest job has `aiCaptionId`, was scheduled and is not yet live (per `PostStatusSync` state); "See captions" once posted. Both call `POST /public/v1/web-link { target:'ai-caption', identifier }` and open the browser with the same fallback handling as the existing web-link calls in `publish/PublishApi` (on any non-200, a toast "Open the web dashboard → AI captions"). Hidden when the job has no caption.
- [x] **M6** Activity log and privacy list. Events at their call sites via `activitylog/ActivityLog.log`: `ai_captions_toggle {on, where}`, `ai_captions_result {state, wait_ms, mode}`, `text_channel_toggle {enabled}`; never any text. Add them to the user-facing list in `ACTIVITY_LOG_PRIVACY.md`.

- [x] **N1** Retry inside the wait. `publish/CaptionFlow.Run.retrying`: the audio upload and the caption create are tried up to `CALL_ATTEMPTS` (4) times on NETWORK / SERVER / RATE_LIMITED / IN_PROGRESS, pauses 2 s, 4 s, 8 s, stopping at once when the run is given up or cancelled; same `aiCaptionKey` on every try. Spec: `CaptionFlowTest`.
- [x] **N2** Tell the user. `publish/AutoPublishNotifier`: the auto-publish result notification gets the caption line ("· AI captions" or "Posted with your text: …", expanded with BigTextStyle) and, when captions were skipped for credits, a "Top up credits" action to `AccountActivity`. Spec: `AutoPublishNotifierTest`.
- [x] **N3** Race tests. `CaptionFlowTest`: a real thread A that is late, ignores the interrupt, is abandoned or cancelled writes no state or id after the wait; the WAV is always discarded.
- [x] **N4** Use the undo window. `publish/AudioExtractor.prepare` (called from `PublishManager.release` on a low-priority `audioPrep` thread when the job is queued behind the undo window): the WAV is made under `.part.wav`, renamed when whole, one extraction at a time, reused by the worker's `extract`, deleted if the job was undone, leftovers over 24 h purged. Spec: `AudioExtractorTest`.

## Progress (runner-maintained)
- OWNER, on a higher-RAM PC, after M1–M6: gradle build + unit tests; then on a device against the deployed backend: Starter account, auto publish a 2-min talk to YouTube + Instagram + X with GMB text switch on → each channel has its own caption and GMB a text post; turn mobile data to slow and check the notification order; zero credits → posted with the file name and the "not enough credits" line; record with audio off → no audio upload, card unchanged; manual publish with a typed link → the link appears word for word after the AI caption; kill the app mid-upload → the worker resumes and posts once (one post group, no duplicate).
- OWNER also checks N1–N4: toggle airplane mode for a few seconds during the audio upload (captions still arrive); a Starter account with zero credits gets the notification with "Top up credits"; with the undo window on, the WAV appears in `cache/ai_audio` before the upload starts and is gone after a tap on Undo.
