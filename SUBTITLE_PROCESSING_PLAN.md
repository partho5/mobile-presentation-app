# Subtitle Processing & Post-Recording Architecture Plan

## 1. Context & Identified Root Cause of the Bug

### Root Cause Analysis (Code-Verified)
The mobile app successfully generates the AI transcript on the backend, but fails to embed (burn in) the subtitles into the video due to a **concurrency race condition / timing inversion** in [`PublishPipeline.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/PublishPipeline.java) and [`PublishWorker.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/PublishWorker.java):

1. **Race Condition in [`PublishPipeline.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/PublishPipeline.java#L162-L173):**
   ```java
   // Line 163: Asynchronously launches Thread A to extract audio WAV, upload to R2,
   // call POST /public/v1/ai-captions, and poll until status == READY
   caption = startCaption(job);

   // Line 164: IMMEDIATELY on the same thread, executes video upload:
   if (!uploaded) upload(job);
   ```
2. ** Premature Burn-in Attempt in [`PublishPipeline.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/PublishPipeline.java#L250-L259):**
   Inside `upload(job)`:
   ```java
   if (subtitleBurner != null) {
       File subtitled = subtitleBurner.burnSubtitles(job, file);
       if (subtitled != null && subtitled.isFile() && subtitled.length() > 0) {
           file = subtitled;
       }
   }
   ```
3. **Empty Subtitle Cache at Burn Time in [`PublishWorker.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/PublishWorker.java#L223-L230):**
   Inside `createSubtitleBurner()`:
   ```java
   File cacheDir = ctx.getCacheDir();
   SubtitleTrack track = SubtitleCache.load(cacheDir, job.id);
   if (track == null) {
       track = SubtitleCache.loadForVideo(cacheDir, originalFile);
   }
   if (track == null || track.isEmpty()) {
       return originalFile; // <--- RETURNS UN-SUBTITLED ORIGINAL FILE IMMEDIATELY!
   }
   ```
   Because Thread A has only just started (extracting/uploading audio), the backend transcription has not finished. `SubtitleCache.load` returns `null`. The un-burned original video is uploaded to R2/server.
4. **Too-Late Polling & Cache Eviction in [`CaptionFlow.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/CaptionFlow.java#L391):**
   Only *after* `upload(job)` finishes does `settleCaption(job, caption)` wait for `caption.finish()`, which polls the status and writes to `SubtitleCache.save(cacheDir, job.id, status.subtitlesJson)`.
   Then, in the `finally` block of [`PublishPipeline.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/PublishPipeline.java#L180), `captions.discard(jobId)` is invoked, calling `SubtitleCache.delete(cacheDir, jobId)`, immediately deleting the newly downloaded subtitle file!
5. **Key Discrepancy in Storage:**
   `CaptionFlow` saves subtitles as `sub_<jobId>.json` (keyed by `job.id`), whereas `VideoPlayerDialog` and `VideoEditorActivity` look for `<videoBaseName>.json` or `<videoBaseName>.sub.json` via `SubtitleCache.loadForVideo`.

---

## 2. Problem Statement & User Requirement

1. **Shift Subtitle Processing Timing:**
   Instead of embedding subtitles immediately before publishing, the entire subtitle generation and embedding process should start **immediately after recording ends**.
2. **User Communication (UI/UX):**
   When this processing occurs, display a proper, clear status message to the user (e.g., via the post-recording processing dialog/overlay or progress notification).
3. **Preserve Compatibility & Zero Regressions:**
   Must not break existing features:
   - Background replacement ([`BgReplaceService.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/BgReplaceService.java))
   - Auto-crop generation ([`VideoCropHelper.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/util/VideoCropHelper.java) for 9:16, 4:5, 1:1)
   - Video preview overlay ([`VideoPlayerDialog.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/ui/VideoPlayerDialog.java) with [`KaraokeSubtitleView`](app/src/main/java/com/jovoc/facecampresentationrecorder/ui/KaraokeSubtitleView.java))
   - Video editor ([`VideoEditorActivity.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/ui/VideoEditorActivity.java) and [`EditRenderer.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/edit/EditRenderer.java))
   - Publishing flow ([`PublishPipeline.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/PublishPipeline.java))

---

## 3. Alternative Approaches Considered

### Approach A: Integrated Sequential Post-Recording Pipeline (Recommended for frictionless UX)
- **Mechanism:**
  When recording stops in [`ScreenRecordService.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/ScreenRecordService.java#L430), the post-recording service executes the stages in strict order:
  1. **Background Replacement** (if enabled in settings)
  2. **AI Transcription**: Extract audio WAV from master video $\rightarrow$ upload audio to API $\rightarrow$ poll until `READY` $\rightarrow$ save subtitle track using `SubtitleCache.saveForVideo(cacheDir, masterFile, json)`
  3. **Subtitle Burn-in**: Overlaid via `EditRenderer` using user's chosen subtitle style $\rightarrow$ updates master file or produces subtitled master
  4. **Auto-Crops**: Produces 9:16, 4:5, 1:1 crops from the subtitled master (so all cropped short-form clips also contain subtitles!)
- **UI:**
  Updates `dialog_processing` in `MainActivity`:
  - "Replacing facecam background..." (0–100%)
  - "Generating AI subtitles..." (indeterminate/progress)
  - "Burning subtitles into video..." (0–100%)
  - Auto-crops continue seamlessly.
- **Why it ensures frictionless UX:**
  When the user arrives at `VideoPlayerDialog` or `VideoEditorActivity`, the video is completely ready. The user does not wait at publish time, auto-crops immediately have burned subtitles, and the timing race at upload time is 100% eliminated.

### Approach B: Asynchronous Subtitle Generation with Soft Overlay Preview (Non-blocking)
- **Mechanism:**
  Start transcription post-recording in background worker. Do not block the player: open `VideoPlayerDialog` immediately after background replacement. Display an inline chip/progress "Generating subtitles..." in the player. Once ready, `KaraokeSubtitleView` dynamically turns on. Burn-in happens only upon user export or during publishing.
- **Why it is less frictionless for the stated goal:**
  If the user immediately taps "Publish" or exports to social media right away, the video must either pause and block, or risk uploading without burned-in subtitles. Furthermore, auto-crops created right after recording would lack the subtitles.

### Approach C: Two-Phase Post-Recording (Burn Master on Demand)
- **Mechanism:**
  Transcribe immediately post-recording, save subtitle track alongside video (`.sub.json`), but do not burn pixels until "Save" or "Publish".
- **Why it conflicts with user intent:**
  The user explicitly noted: *"subtitle is embedded immediately before publishing. But expected is start the process immediately after recording."*

---

## 4. Side Effects & Mitigations Analysis

| Potential Side Effect / Risk | Impact on Core Goal / UX | Mitigation in Implementation |
|---|---|---|
| **Extended Post-Recording Wait Time** | Waiting for transcription + burn-in takes an extra 15–30s right after recording. | Display stepped progress and stage title ("Generating subtitles...", "Embedding subtitles into video...") in `dialog_processing.xml`. Allow graceful cancellation without corrupting original recording. |
| **Network Failure / Offline Recording** | Transcription requires internet. If offline, post-recording pipeline could hang or fail. | Check network and API availability with timeouts. If network fails or user is offline, log error, preserve original recording unburned, and allow offline preview/editing without blocking the user. |
| **Credit / Account Entitlement Limits** | AI captions cost credits (`Feature.AI_CAPTIONS`). | Verify entitlement snapshot (`FeatureSnapshot.allows(Feature.AI_CAPTIONS)`) and check balance before uploading audio. If credits are exhausted, bypass subtitle stage cleanly without failing the video. |
| **Auto-Crop Sequencing** | Auto-crops created before subtitles would lack burned subtitles. | Ensure auto-crops run *after* subtitle burning, using the subtitled video as source, so TikTok/Reels crops have captions. |
| **Video Editor Re-edits** | If subtitles are permanently burned into master pixels, trimming in editor cuts words mid-sentence, and style cannot be altered. | Save the raw subtitle JSON file (`<video>.sub.json`) alongside the video. Video Editor can edit project cuts with awareness of the track. |
| **Publish Pipeline Redundancy** | If subtitles are already burned post-recording, `PublishPipeline` must not re-burn. | In `PublishPipeline`, check if video already has subtitles burned or if clean master is used. Pass subtitled file cleanly. |

---

## 5. Affected Files & Code Locations

1. **Post-Recording Coordination:**
   - [`ScreenRecordService.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/ScreenRecordService.java#L430-L460): Initiates post-recording pipeline upon recording stop.
   - [`BgReplaceService.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/BgReplaceService.java#L282-L325): Execution of post-recording jobs (currently bg replace + crops; to integrate transcription & burn-in).
   - [`MainActivity.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L3752-L3880): `processingListener`, `showProcessingDialog`, and status message display (`processing_title`).
   - [`dialog_processing.xml`](app/src/main/res/layout/dialog_processing.xml): Post-recording progress dialog UI.
2. **AI Captioning & Subtitle Cache:**
   - [`CaptionFlow.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/CaptionFlow.java): Audio extraction, upload, caption creation, and polling.
   - [`AudioExtractor.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/AudioExtractor.java): Media extraction of WAV from video.
   - [`SubtitleCache.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/subtitle/SubtitleCache.java): Caching and association of subtitles with video files.
3. **Publishing Pipeline (Fixing Timing Gap):**
   - [`PublishPipeline.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/publish/PublishPipeline.java#L162-L173, #L250-L259): Synchronization between audio transcription and video upload.
   - [`PublishWorker.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/service/PublishWorker.java#L217-L250): `createSubtitleBurner` logic.
4. **Rendering & Playback:**
   - [`EditRenderer.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/edit/EditRenderer.java#L281-L293): Subtitle burn-in rendering.
   - [`VideoPlayerDialog.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/ui/VideoPlayerDialog.java#L618-L620): Post-recording video playback dialog.
   - [`VideoEditorActivity.java`](app/src/main/java/com/jovoc/facecampresentationrecorder/ui/VideoEditorActivity.java#L121-L127): Video editor playback and subtitle display.
