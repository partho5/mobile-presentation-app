Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.
-----------------

RULE: this PC hangs under load, so do not run gradle, unit tests or the emulator. Write code only and verify by reading it against this plan; the owner builds and runs tests on a higher-RAM PC. Build one part at a time and update Progress below.

------------------

# Post-Recording Subtitle Transcription & Embedding — Build Plan

## Background: What the Existing Code Does (Verified)

The AI transcription pipeline is already built and works end-to-end for publishing. The transcript JSON arrives from the server correctly. The subtitle embedding (burn-in) inside `PublishWorker.createSubtitleBurner()` also works. The bug is purely a **timing race**:

1. `PublishPipeline.run()` launches Thread A (audio extract → upload → POST /ai-captions → poll) on line 163.
2. On line 164 it **immediately** calls `upload(job)`, which calls `burnSubtitles()` before Thread A is anywhere near finished.
3. Inside `PublishWorker.createSubtitleBurner()`, `SubtitleCache.load(cacheDir, job.id)` returns `null` because Thread A has barely started.
4. The original un-burned video is uploaded.
5. After upload, Thread A eventually finishes polling and `CaptionFlow.discard()` deletes the subtitle JSON it just wrote.

**Secondary requirement:** The user wants transcription to begin **immediately after recording ends** (during post-recording, before the user taps Publish), so that when Publish is tapped the subtitle JSON is already cached on disk, and the burn-in in `PublishWorker` succeeds on the first call.

---

## Core Invariants (Non-Negotiable)

1. **NEVER touch the master video.** The original recorded MP4 must never be overwritten or modified.
2. **Subtitle burn-in uses a temp file.** `PublishWorker.createSubtitleBurner()` already writes to `cacheDir/subtitled_<jobId>.mp4`. Keep this. Do not invent a new derivative file.
3. **Subtitles disabled = do nothing.** Check `RecordSettings.subtitlesEnabled(context)` first. If false, skip all post-recording subtitle work.
4. **`CaptionFlow` requires a `PublishJob`.** Do NOT try to reuse `CaptionFlow` directly from `ScreenRecordService`. A `PublishJob` row is needed. Use `AudioExtractor` and `PublishApi` directly instead (see Part 1).
5. **Auto-crops are NOT affected.** They run on the raw video geometry. Subtitle burn-in happens only at publish time inside `PublishWorker`. Crops are never touched by subtitle code.

---

## Progress

| Part | Description | Status |
|---|---|---|
| Part 1 | Post-recording audio extraction + transcription dispatch | ⏳ |
| Part 2 | Wait on transcription + fix premature burn-in race in PublishPipeline | ⏳ |
| Part 3 | Fix CaptionFlow.discard() deleting subtitle JSON too early | ⏳ |
| Part 4 | Show processing dialog message during post-recording transcription | ⏳ |

---

## Part 1 — Post-Recording: Extract Audio, Upload, POST /ai-captions, Cache Subtitle JSON

**Goal:** Immediately after recording stops, kick off transcription in the background so `SubtitleCache` is populated by the time the user taps Publish.

**File to modify:** `service/ScreenRecordService.java`

**Exact location:** The inline block starting around line 430 that calls `PublishManager.get(this).beginAuto(file)`. Add the new call **after** `beginAuto` and `enqueue` but **before** `sendBroadcast(finishIntent)`.

**What to add:** A new class: `publish/PostRecordTranscriber.java`

**`PostRecordTranscriber` spec:**
- Static method: `public static void start(Context context, File video)`
- Guards:
  - `RecordSettings.subtitlesEnabled(context)` — if false, return immediately.
  - `SessionManager.get(context).hasSession()` — if false, return immediately.
  - `FeatureSnapshot.allows(Feature.AI_CAPTIONS)` — if false, return immediately.
- Runs on a new background thread (not the main thread, not WorkManager).
- Steps on the background thread:
  1. Call `AudioExtractor.extract(context.getCacheDir(), VIDEO_ONLY_JOB_ID, video)` where `VIDEO_ONLY_JOB_ID` is a stable long derived from `video.getAbsolutePath().hashCode()` cast to long (so it is deterministic and doesn't need a DB row).
  2. If WAV is null (no audio track, extraction failed): return silently.
  3. Create a `PublishApi` instance using the existing `ApiClient` + `SessionManager` repository (same pattern as `PublishWorker`).
  4. Upload the WAV to the direct upload endpoint. The `DirectUploader` and `UploadSessionStore` require a `PublishJob` — **do not use them here**. Instead use `PublishApi`'s legacy transcript_audio upload (check the existing audio upload path already used by `CaptionFlow`'s `AudioUpload` interface in the production wiring in `CaptionFlow.create()`). The simplest correct approach: open `CaptionFlow`'s public `Audio` and `AudioUpload` interfaces and reuse the lambdas from `CaptionFlow.create()` — they don't need a `PublishJob`, only a `jobId` long and a `workspaceId` string. Get `workspaceId` from `SessionManager.get(context).repository().store().workspaceId()`.
  5. Call `PublishApi.createCaption()` — this requires a `PublishJob` for the body builder. Create a minimal synthetic `PublishJob`: set `filePath`, `workspaceId`, `mode = PublishJob.MODE_NOW`, `channelsJson = "[]"` (empty), `caption = ""`. Generate `aiCaptionKey = UUID.randomUUID().toString()`.
  6. Poll `PublishApi.captionStatus()` every 3 seconds until status is READY, NO_SPEECH, FAILED, or 3 minutes elapsed.
  7. If READY: call `SubtitleCache.saveForVideo(context.getCacheDir(), video, status.subtitlesJson)`. This writes `<cacheDir>/subtitles/<videoFileName>.json` which `SubtitleCache.loadForVideo()` will find at publish time.
  8. If anything throws (network, auth): catch silently, delete the temp WAV if it exists, and return. The user is not blocked.
- The background thread must not prevent the app from exiting. Use a daemon thread.

**Call site in `ScreenRecordService.java`:**
```java
// After PublishManager.get(this).onRecordingStopped(file);
// Before sendBroadcast(finishIntent);
PostRecordTranscriber.start(this, file);
```

---

## Part 2 — Fix PublishPipeline Race: Wait for Caption Before Burning

**Goal:** When the user taps Publish, `burnSubtitles()` inside `PublishPipeline.upload()` is called while Thread A (the caption thread) is still running. Fix this so burn happens after caption is settled.

**File to modify:** `publish/PublishPipeline.java`

**The fix:** Move `subtitleBurner.burnSubtitles(job, file)` from inside `upload(job)` to **after** `settleCaption(job, caption)` and **before** `createPost(job)`.

**Current code flow (lines 159–173):**
```
caption = startCaption(job);   // Thread A starts
upload(job);                   // <-- burnSubtitles() called HERE (too early, cache empty)
settleCaption(job, caption);   // Thread A finishes, subtitle JSON written to cache
createPost(job);
```

**Fixed code flow:**
```
caption = startCaption(job);
upload(job);                   // <-- do NOT burn here; upload the original file
settleCaption(job, caption);   // Thread A finishes, subtitle JSON is now in cache
// NOW burn subtitles: cache is populated, race is gone
file = burnSubtitlesIfNeeded(job, new File(job.filePath));
// Update the upload with the burned file if needed (re-upload the subtitled temp)
// OR: re-examine whether the design needs a second upload
```

**IMPORTANT implementation note:** The current design burns subtitles and then uploads the burned file. If burn moves after upload, you can't re-upload. The correct fix is:

- If `SubtitleCache.loadForVideo(cacheDir, videoFile)` returns non-null (populated by Part 1's post-recording transcription): burn is immediate and succeeds before upload. No race.
- If cache is empty (user recorded offline, Part 1 didn't run yet): `settleCaption` waits for Thread A to finish. Move `burnSubtitles` call to inside `upload(job)` but **after** a new call `waitForCaption(caption)` that blocks until Thread A finishes or times out.

**The simplest correct fix:** Inside `upload(job)` in `PublishPipeline.java`, before calling `subtitleBurner.burnSubtitles(job, file)`, call `caption.finish()` (which already exists — it blocks until Thread A completes). Then remove the `settleCaption(job, caption)` call from `run()` since it's already been called.

Concrete changes to `publish/PublishPipeline.java`:

1. Pass the `caption` reference into `upload()` so it can call `caption.finish()` before burn.
2. Or: extract audio + POST /ai-captions in a separate pre-upload step that blocks until done, then upload. This is the cleanest split.

**Actually, the cleanest zero-risk fix:**

Change `run()` to:
```java
caption = startCaption(job);
if (!uploaded) upload(job, caption);  // caption passed in
settleCaption(job, caption);          // keeps state-writing logic
caption = null;
createPost(job);
```

Change `upload()` signature to accept `CaptionFlow.Run caption`. At the point where `subtitleBurner.burnSubtitles()` is called, first call `caption.finish()` (this blocks until Thread A and server polling are done, subtitle JSON is in SubtitleCache). Then `burnSubtitles` reads from the now-populated cache.

Remove the redundant second `caption.finish()` call from `settleCaption` by checking if `caption` is already done.

---

## Part 3 — Fix CaptionFlow.discard() Deleting Subtitle JSON

**File to modify:** `publish/CaptionFlow.java`

**Problem:** `discard(long jobId)` at line 161 calls `SubtitleCache.delete(cacheDir, jobId)`. This deletes `subtitles/sub_<jobId>.json`. This is the job-id-keyed file.

**Key distinction:**
- `SubtitleCache.save(cacheDir, jobId, json)` writes `subtitles/sub_<jobId>.json` — job-id keyed.
- `SubtitleCache.saveForVideo(cacheDir, videoFile, json)` writes `subtitles/<videoFileName>.json` — video-name keyed.
- `SubtitleCache.loadForVideo()` reads the **video-name keyed** file.

**Fix in `CaptionFlow`:** After saving the subtitle JSON in the `poll()` method (line 391: `SubtitleCache.save(cacheDir, job.id, status.subtitlesJson)`), also save it video-name keyed:
```java
SubtitleCache.save(cacheDir, job.id, status.subtitlesJson);
// Also save by video filename so loadForVideo() finds it even after discard():
File videoFile = new File(job.filePath);
SubtitleCache.saveForVideo(cacheDir, videoFile, status.subtitlesJson);
```

**Fix in `discard()`:** Keep `SubtitleCache.delete(cacheDir, jobId)` (deletes the job-id keyed temp file, which is fine). Do NOT delete the video-name keyed file — it's a different file and should persist.

**Result:** After `discard()`, `loadForVideo()` still finds `subtitles/<videoFileName>.json` and burn-in works.

---

## Part 4 — Processing Dialog: Show "Generating AI subtitles…" to User

**Goal:** While Part 1's `PostRecordTranscriber` is running, show a status message in the post-recording processing dialog in `MainActivity`.

**Files to modify:**
- `MainActivity.java` — `showProcessingDialog()` and `processingListener`
- `service/BgReplaceService.java` — `Listener` interface

**Current flow:**
- `BgReplaceService.Listener.onProgress(String stage, int percent)` drives the dialog.
- Dialog shows as long as `BgReplaceService` is running (background replace active).
- If background replace is OFF, the dialog never shows. `PostRecordTranscriber` would run silently.

**Design decision (simple, no new service needed):**
- The transcription dialog does NOT need to block the user. The user can preview their video immediately.
- Instead, show a **non-blocking Toast or a small inline status** in the post-recording `VideoPlayerDialog`:
  - When `VideoPlayerDialog` opens for a freshly recorded video (detected via `SubtitleCache.loadForVideo()` returning null AND subtitles are enabled), show a small chip: *"⏳ Generating subtitles…"*
  - When `PostRecordTranscriber` finishes (saved to cache), it posts a local broadcast. `VideoPlayerDialog` listens and refreshes `KaraokeSubtitleView.setTrack()`.

**If BgReplaceService IS running** (dialog is already showing), add "Generating AI subtitles…" as a secondary line in the existing dialog. Use `BgReplaceService.Listener.onProgress()` stage string — just pass the subtitle stage through the existing channel.

**Broadcast for completion:**
- Define a new broadcast action constant in `PostRecordTranscriber`: `public static final String ACTION_SUBTITLES_READY = "com.jovoc.facecampresentationrecorder.SUBTITLES_READY"`
- Extra: `EXTRA_VIDEO_PATH` (String, absolute path of the video)
- `VideoPlayerDialog` and `VideoEditorActivity` register for this broadcast on `onResume()` / unregister on `onPause()`. On receive: reload `SubtitleCache.loadForVideo()` and call `setTrack()`.

---

## What This Plan Does NOT Do (Scope Boundaries)

- Does NOT create any new `_sub.mp4` derivative files. Subtitles continue to be burned at publish time into a temp file in `cacheDir`.
- Does NOT modify auto-crop logic. Crops run on raw video, are unaffected.
- Does NOT block video preview. The player opens immediately after post-processing.
- Does NOT modify `SubtitleCache`'s storage keys or file format. Existing `saveForVideo` / `loadForVideo` contract is preserved.
