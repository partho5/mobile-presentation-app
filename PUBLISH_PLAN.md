# Publish Recordings (M2) — Plan & Progress Tracker

Builds on M1 (`AUTH_PLAN.md`: ApiClient, TokenStore, AuthRepository). Backend is read-only reference.
Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`. This file covers **M2 only**.

## Status

| Step | Scope | Status |
|---|---|---|
| 1 | Plan, WorkManager dep, multipart upload in ApiClient/AuthRepository | ✅ Done |
| 2 | `publish/` core: PublishApi, PublishPipeline, request builder, error policy + unit tests (61 pass) | ✅ Done |
| 3 | Room v3: `publish_jobs` table + migration (SQL checked against Room's generated schema), DAO | ✅ Done |
| 4 | PublishWorker (expedited, foreground notif, cancel) + PublishManager | ✅ Done (device QA pending) |
| 5 | UI: PublishActivity, card status + menu entries in SavedRecordings | ✅ Done (device QA pending) |
| 6 | Build, tests, report | ✅ Done |

## Contract facts used (exact)

- `POST /public/v1/upload` multipart field `file` (scope media:write) -> `{id, name, path, ...}`; video cap 1 GB, `.mp4` path required by post DTO. Since DU (see "Upload: direct to R2" below) the phone sends `.mp4` videos straight to R2 instead; this route is now the legacy fallback and the path for anything that is not an `.mp4`.
- `GET /public/v1/integrations` -> `[{id, name, identifier, picture, disabled, profile, customer?}]`.
- `POST /posts/charge-preview {integrationIds}` -> `{amount, newChannels, releasedChannels}` (app route; Bearer is accepted by AuthMiddleware, api.token-auth). Cost must load before Confirm; failure = "cost unavailable", Confirm stays disabled (ux.cost-preview).
- `POST /public/v1/posts` `{type:'now'|'schedule', shortLink, date(ISO UTC), tags:[], posts:[{integration:{id}, value:[{content, image:[{id,path}]}], settings:{...}}]}` -> `[{postId, integration}]`. `settings.__type` is filled by the server; provider-required settings are sent per `ProviderSettings` (youtube, tiktok, instagram, x).
- Errors: 402 `INSUFFICIENT_CREDITS {required, available, message}` (whole request, nothing created); 400 validation `{provider, name, message}`; 409 `IDEMPOTENCY_IN_PROGRESS`; 422 `IDEMPOTENCY_KEY_REUSED`; 401 TOKEN_EXPIRED/SESSION_REVOKED handled by `AuthRepository`.
- Idempotency (api.idempotency): same key + same body = replay of the first answer (24 h). 5xx/throw are not stored, so a retry re-runs. **4xx IS stored**, so after any definitive 4xx (e.g. 402) the key is rotated before the next attempt; the key is kept only when the outcome is unknown (network drop, 5xx, 409 in progress, 429).

## Design

- `auth/ApiClient.upload(...)` multipart with progress + cancel (the legacy upload; videos now take `publish/DirectUploader`, see "Upload: direct to R2"); `AuthRepository.executeUpload` shares the refresh/retry logic of `execute` via one private `executeAuthed(Sender)`.
- `publish/PublishApi` (listChannels, upload, chargePreview, createPost; pure parse/build, no Android) -> `publish/PublishPipeline` (state machine over a `JobStore`: upload once, remember media id, create post with the stored key; returns DONE / RETRY / FAILED / GONE). `publish/PublishPolicy` maps a response to an outcome and decides key rotation.
- `db/PublishJob` + `PublishJobDao`; `AppDatabase` v2 -> v3 with `MIGRATION_2_3` (CREATE TABLE only, no data loss). One row per publish intent; keyed by file path (rename updates it); one active job per file.
- `service/PublishWorker` (WorkManager `Worker`, unique name `publish-<id>`, CONNECTED constraint, expedited with foreground notification, Cancel action). Offline/5xx -> `Result.retry()` with backoff, capped at 6 attempts then FAILED. Progress written to the row (throttled) + local broadcast `ACTION_PUBLISH_STATE_CHANGED` (like the crop broadcast).
- `publish/PublishManager` enqueues / cancels / retries; UI never touches WorkManager directly.
- UI: `ui/PublishActivity` (channels with checkboxes, caption, Now / Schedule with date+time pickers, cost line, Publish button; loading / offline / no-channels states). `SavedRecordingsActivity` + `RecordingsAdapter`: card status line + progress bar, menu items Publish / Publish again / Retry / Cancel.
- Status shown per recording: Not published (no row) · Waiting · Uploading n% · Publishing · Published / Scheduled for … · Failed (reason). "Published" = the backend accepted the post; it publishes asynchronously and M2 does not poll the post state.

## Files

New: `publish/{PublishApi,PublishPipeline,PublishPolicy,PublishRequestBuilder,ProviderSettings,PublishManager,Channel}.java`, `db/{PublishJob,PublishJobDao}.java`, `service/PublishWorker.java`, `ui/PublishActivity.java`, `res/layout/{activity_publish,item_channel}.xml`, `res/values/strings_publish.xml`, tests under `app/src/test/.../publish/`.
Edited: `app/build.gradle` (work-runtime), `AndroidManifest.xml` (activity, SystemForegroundService type), `auth/{ApiClient,AuthRepository}.java`, `db/AppDatabase.java`, `SavedRecordingsActivity.java`, `adapter/RecordingsAdapter.java`, `ui/VideoPlayerDialog.java` (rename hook only if needed), `res/layout/item_recording_card.xml`, `res/menu/recording_card_menu.xml`.

## Manual QA checklist

- [ ] Publish from a card menu: channels load, pick one, caption, Now. Cost shows before Publish is enabled.
- [ ] Upload shows progress on the card and in the notification; app backgrounded: upload continues.
- [ ] Airplane mode mid-upload: card shows waiting; reconnect resumes/retries; exactly one post on the channel.
- [ ] Kill the app during "Publishing": reopen, the card still shows the job and it completes without a second post.
- [ ] Cancel from the card and from the notification: upload stops, card returns to Not published.
- [ ] Insufficient credits: Failed with "needs X, you have Y"; after top-up, Retry succeeds (new key).
- [ ] Schedule for a future time: card says Scheduled for …; post appears scheduled on the web.
- [ ] Session revoked on web during upload: forced sign-out, job Failed (signed out), no crash.
- [ ] No channels connected: clear message to connect on the web. Existing slides/recording/board unaffected; DB upgrade keeps slides.

---

# AUTO_PUBLISH (M4): auto-publish new recordings

Full plan and decisions: `to-build/tasks.md`. One Settings checkbox ("Auto-publish new recordings", pref `key_auto_publish`, off by default, per device). When it is on and the user is signed in, every finished recording is uploaded and published with no taps. Channels, caption, provider settings and timing (now / next free slot) are a **Set marked "Use for mobile auto-publish"** on the web; the app only connects and consumes. Credits are checked and charged only by the server.

## Status

| Part | Scope | Status |
|---|---|---|
| B | Backend: preset table, `GET/POST /public/v1/auto-publish`, `GET /public/v1/posts/status`, upload timeout, contract `api.mobile-auto-publish` (Social-Media-Manager e2afe2d7) | ✅ Built and committed; not deployed or run yet |
| A1 | Settings checkbox + status line (`AutoPublishInfo`) | ✅ Done, device QA pending |
| A2 | Trigger on recording stop, startup sweep, worker `auto` branch, result notification | ✅ Done, device QA pending |
| A2 fixes | "Set up on the web" action on the card, rename-while-waiting, durable row before processing is queued, 401 keeps the key | ✅ Done (`fix(publish)` commit) |
| A3 | Live post status on cards | ✅ Done, device QA pending |
| A4 | Upload timeouts, production URL confirmed, docs | ✅ Done (this section); device QA pending |
| DU | Direct-to-R2 resumable upload (`DirectUploadApi`, `DirectUploader`, `UploadPlan`, Room v6 `upload_sessions`, pipeline integration, legacy fallback): the VPS carries no video bytes. Details in "Upload: direct to R2" below and `to-build/tasks.md` (DU-A1..A4) | ✅ Done; device QA pending |
| RET | `MEDIA_PURGED` card state + re-upload on Retry (RET-A1), poster frame sent with `complete` (RET-A2) | ✅ Done; device QA pending |
| UX U2 | "Upload on Wi-Fi only" (pref `key_publish_wifi_only`, default off): upload work requires `NetworkType.UNMETERED` instead of `CONNECTED` for manual and auto jobs; card shows "Waiting for Wi-Fi…"; toggling updates the constraint of QUEUED jobs' upload work in place via `WorkManager.updateWork` (no cancel / REPLACE; same key and undo deadline). Details in `UX_PLAN.md`. | ✅ Done, device QA pending |

## Contract facts used (exact)

- `GET /public/v1/auto-publish` (scope posts:read) -> `{configured, setName?, timing?: 'now'|'next_slot', channels:[{id,name,identifier,picture,disabled}], cost, available, setupUrl}`. `configured` is false with no preset or no usable channel. `setupUrl` is `<FRONTEND_URL>/w/<id>/settings?tab=sets`.
- `POST /public/v1/auto-publish` (scope posts:write) `{media:{id,path}, title}` + `Idempotency-Key` -> 201 `[{postId, integration}]` (same shape as `/posts`; same validation, credit reservation and idempotency path on the server). Errors: 409 `AUTO_PUBLISH_NOT_CONFIGURED`, 404 `MEDIA_NOT_FOUND`, 402 `INSUFFICIENT_CREDITS {required, available}`, 400 validation, 429.
- `GET /public/v1/posts/status?ids=a,b,...` (up to 50, workspace scoped) -> `[{id, state:'QUEUE'|'PUBLISHED'|'ERROR'|'DRAFT'|'DELETED', publishDate, releaseURL?, error?}]`; unknown or foreign ids are omitted; 400 `INVALID_IDS` for 0 or more than 50 ids.
- Idempotency (guide section 5): every answer below 500 is stored 24 h under the key, **including 4xx and the API throttler's 429** (replayed without `Retry-After`). 5xx / dropped connection are not stored; a 401 and the auth layer's `AUTH_RATE_LIMITED` 429 never reach it. So: same key on network / 5xx / 409 `IDEMPOTENCY_IN_PROGRESS` / `AUTH_RATE_LIMITED`; new key after a final 4xx (402, 404, 409 not configured, 400, 403, 422) and after the throttler 429 (body `{"statusCode":429,"message":"ThrottlerException: Too Many Requests"}`; nothing was created). `POST /posts` and `POST /auto-publish` have separate throttle counters (`API_LIMIT` per hour each). Re-publishing after an ERROR is a new key and goes to every channel again.
- Headers: `Authorization: Bearer`, `x-workspace-id` (always; the job's pinned workspace), `Idempotency-Key` only on `POST /auto-publish` (never on `/upload`). Scopes are never refused to a device token.
- Errors on `POST /auto-publish`: 409 `AUTO_PUBLISH_NOT_CONFIGURED`, 404 `MEDIA_NOT_FOUND` (forget the upload), 402 `INSUFFICIENT_CREDITS {required, available}` (server message already states the amounts), 400 `{provider, name, message}` (shown as "name: message"), 400 `{message:[...]}` (app bug), 422 `IDEMPOTENCY_KEY_REUSED`, 403 `WORKSPACE_FORBIDDEN` (final), 401 `TOKEN_EXPIRED` (refresh once) / `SESSION_REVOKED` and `{msg:"Invalid API key"}` (sign out). `POST /upload`: 400 `File is too large.` (final, TOO_LARGE), `Unsupported file type.` (final), `Failed to read file` (retry), success is 201.
- `timing: next_slot` with **no posting times** in the workspace: the server publishes immediately (type `now`, date now) instead of waiting. Request and response shapes are unchanged and `GET /auto-publish` still reports `timing: "next_slot"`, so the app cannot tell: the card says "Scheduled" (`auto_slot`) until the status poll reports PUBLISHED / ERROR, then "Live" / "Failed on ...". This is expected, not a bug.
- Not built on the server (so not in the app): push, deleting uploaded media from the app, editing the preset from the app. (Resumable upload exists since DU: see "Upload: direct to R2".)
- Upload path: videos go phone -> R2 directly (presigned part URLs); the VPS sees only small JSON calls, so no nginx / Caddy / Node `requestTimeout` setting matters for them. Node `requestTimeout` 60 min (and the nginx 1 GB buffer) matter **only for the legacy `POST /upload` fallback** (older server, non-`.mp4` file). Production API base `https://social-media-schedular.nanybot.com/api` (debug and release).

## What was built

- **Settings (A1):** `ui/SettingsActivity` "Auto-publish" section; status line from `PublishApi.autoPublishInfo()` (`publish/AutoPublishInfo`): signed out -> sign in, not set up -> opens `setupUrl`, configured -> "Posts to X, Y . N credits per video . Available M", load failed -> tap to retry.
- **Room v5 (A3):** `MIGRATION_4_5` adds five nullable columns to `publish_jobs`: `post_integrations` (channel id per post id), `remote_state`, `remote_channel`, `remote_error`, `remote_url`. A2 itself needed no schema change: `mode` and `status` are Strings.
- **Modes and states:** `PublishJob.mode` is `now` / `schedule` (manual) or `auto` / `auto_slot` (auto-publish; `auto_slot` is learnt from the pre-flight `timing = next_slot` and only changes the label to "Scheduled"). New status `WAITING_PROCESSING`: the row exists, the recording is still being replaced / cropped.
- **Trigger (A2):** `ScreenRecordService` calls `PublishManager.beginAuto(file)` (toggle on + session: insert the `WAITING_PROCESSING` row, workspace pinned now) **before** `BgReplaceService.enqueue`, then `onRecordingStopped(file)` after it (release at once when nothing is processing). `BgReplaceService` calls `releaseAuto(path)` when its job ends. `sweepAuto()` runs from `StartupActivity` and Saved Recordings: it releases waiting rows whose processing is not running (process killed mid-crop), skipping rows whose processing is just being queued.
- **File pick:** `AutoPublishFiles.pick` = the `_9x16` crop when it exists and is non-empty, else the processed original. A recording renamed while waiting is tracked by `publish/RenameAliases` (in memory) so the job is still found and the crop written under the old name is picked.
- **Worker:** `PublishPipeline` for an auto job: pre-flight `GET /auto-publish` (skipped once the video is uploaded, so a replay is not failed by a balance the post may already have spent) -> not configured: FAILED `AUTO_NOT_CONFIGURED`, no upload; cost > available: FAILED `CREDITS_SHORT` "needs X, you have Y", no upload (Retry on the card) -> existing upload step -> `POST /auto-publish` with the job's key. 402 / definitive 4xx rotate the key; 5xx / offline retry with the same key; 404 `MEDIA_NOT_FOUND` forgets the upload.
- **Result notification (A2):** `AutoPublishNotifier`: "Published to N channels" / "Scheduled" / "Auto-publish failed: reason" / "Sign in to finish auto-publishing", with a "Set up" action for a missing preset. Tap opens Saved Recordings.
- **Live status (A3):** `PostStatusSync` (7-day non-final DONE jobs, one call per workspace per 50 ids) runs from Saved Recordings `onResume`, throttled to once per 20 s, no background polling. Card: Live (tap opens `releaseURL`) / Failed on <channel>: <error> (Retry = new key) / Deleted on web.
- **Card actions for a missing preset:** the failed card's status line and its menu ("Set up on the web") open the server's setup link, or the Sets tab (`WebLinks.setsUrl()`).
- **Connection (A4):** `ApiClient.uploadClient`: no call timeout, 120 s write stall limit, 5 min read timeout for the answer after the last byte; ordinary calls keep connect 15 s / read 30 s / write 30 s. Since DU this client only carries the legacy `POST /upload` fallback; part PUTs to R2 use their own client (below).

- **Undo window (UX_PLAN U1):** an auto-publish no longer uploads the moment the recording is ready. Settings > Auto-publish > "Undo before publishing" (Off / 30 s / 60 s, default 30 s, pref `key_auto_publish_undo_seconds`) delays the worker by that long (WorkManager initial delay, survives process death). The card reads "Publishing in N s · Tap to cancel" and a "Publishing soon" notification has a Cancel action; both use the normal cancel (nothing uploaded, no credits touched). Retry and manual Publish are not delayed. The deadline lives in `PublishJob.scheduleAt` (unused by auto jobs otherwise), so no Room change. Delayed work cannot be expedited, so it runs as normal work (device QA: a 1 GB upload after the delay with the app in the background).

## Upload: direct to R2 (DU-A1..A3, as built)

Contract: `Social-Media-Manager/_planning/02_contracts/api.mobile-direct-upload.md`; guide section 4.6. Scope `media:write`, headers `Authorization` + `x-workspace-id` (the job's pinned workspace) like every `/public/v1` call. **None of these calls carries an `Idempotency-Key`**, and a failure of the upload step never rotates the post's key (`PublishException.forUploadStep`).

Used for every `.mp4` (manual and auto jobs share `PublishPipeline`, so both). Anything else (an image, a `.mov`) and a server without the routes use the legacy `POST /upload`. **Size caps:** the direct route has no client-side cap: the app sends `create` with the real size and the server's `MOBILE_UPLOAD_MAX_BYTES` (4 GiB by default, so an edited recording re-encoded at ~12 Mbps of over ~24 min still fits) answers `400 FILE_TOO_LARGE` (final). The 1 GiB cap (`PublishApi.LEGACY_MAX_VIDEO_BYTES`) belongs to the legacy `POST /upload` only: `PublishPipeline.upload` checks it right before that route, so a non-`.mp4` over 1 GiB, or a `.mp4` over 1 GiB whose direct routes are missing (code-less 404 fallback), fails `TOO_LARGE` ("video is over the 1 GB limit") without sending a byte.

**Flow** (`publish/DirectUploader` over `publish/DirectUploadApi`; pure decisions in `publish/UploadPlan`):

1. `POST /upload/multipart {filename, size}` -> `{id, partSize, partCount}`. `partSize` and `partCount` are the server's (16 MiB today) and are never hardcoded; if `partCount` does not equal `ceil(size / partSize)` the upload is aborted and the job fails SERVER. The `upload_sessions` row is written **before** anything else, so a lost id cannot hold one of the workspace's 5 pending slots.
2. `POST /upload/multipart/:id/parts {partNumbers}`: URLs are signed as the parts are about to be sent, **in batches of 4** (`UploadPlan.URL_BATCH`; the server allows 20, a URL lives 900 s). A URL is not reused once 66 % of its life is spent.
3. `PUT <url>` with the raw bytes of one part (exact length, read from the file slice by `FilePartBody`), **2 parts in parallel** (`UploadPlan.PARALLEL_PARTS`), by a **separate OkHttp client with no interceptor and no authenticator** (`DirectUploader.newR2Client`: no call timeout, 60 s write / 60 s read stall limits). The device token never reaches `r2.cloudflarestorage.com`. The `ETag` response header is saved in Room immediately after each part (`upload_sessions.parts_json`), so a kill loses at most the two parts in flight.
4. `POST /upload/multipart/:id/complete {parts:[{n, etag}], poster?}` -> `{id, path}`. Idempotent on the server. The pipeline stores `mediaId` / `mediaPath`, then deletes the session row, then goes on to the unchanged `POST /auto-publish` (or `/posts`) with the job's key.

**Per part:** up to 5 tries (`UploadPlan.MAX_TRIES`), backoff 1 s, 2 s, 4 s ... capped at 30 s. A 403 (expired URL) signs that part again at once. A 2xx answer **without an ETag** is a failure of the part. When the tries run out: an HTTP answer from R2 ends SERVER, a dropped connection ends NETWORK (both retryable, see below).

**Resume** (process killed, offline, reboot): the stored session is checked with `GET /upload/multipart/:id`. R2's list is the truth, not the local ETags: a part counts only when listed with the exact expected length; a part R2 does not list is sent again. Exception: an empty list while every part is stored locally (a `complete` that ended 502 after R2 finished) goes straight to `complete`. Status `completing` is polled every 4 s for about 2 min (then SERVER, retried); `completed` with every ETag stored replays `complete`; `aborted` / `expired` / 404 `UPLOAD_NOT_FOUND`, a changed file size (the recording was re-processed) or a different `partSize` drops the row (abort first when it is still usable) and starts a new upload. The decision loop is capped at 8 rounds, then SERVER and a clean slate.

**Cancel:** `PublishManager.cancel` and the notification Cancel (`PublishWorker.settleAfterStop`) delete the job and then `DELETE /upload/multipart/:id` best-effort (frees a pending slot; a failure only leaves it to expire) and delete the row. A job the system merely stopped goes back to QUEUED and keeps its session.

**Legacy fallback:** only a **code-less 404** from a direct route (`PublishException.isRouteMissing`: an older deploy or a proxy with no such route) falls back to `POST /upload`. A 404 with a code (`UPLOAD_NOT_FOUND`, `MEDIA_NOT_FOUND`) is a real answer and never falls back. The choice is remembered per job as an `upload_sessions` row with `status = legacy` (empty `upload_id`), so a retry does not ask again. A legacy upload has no poster.

**Room v6:** `MIGRATION_5_6` creates `upload_sessions` (`job_id` PK, `upload_id`, `size`, `part_size`, `part_count`, `parts_json`, `status` = `pending` / `completed` / `legacy`); no other table changes. Row deleted when the media id is stored, the job is cancelled or fails with an error the session cannot survive.

**Progress:** total bytes sent out of the file size, throttled (`ProgressThrottle`: at least 64 KiB or 0.5 % of the file (the larger) and 250 ms apart), the pipeline writes the row every 2 % and the foreground notification updates every 5 %. A failed try's bytes are taken back out, so the bar never overstates.

### Error codes and what the app does

Mapping lives in `PublishException.fromUpload` (routes with a code) and `from` (everything else); the card text is `PublishLabels.reason`. "Retried" = the job goes back to QUEUED with WorkManager backoff (30 s, exponential), at most 6 runs (`PublishWorker.MAX_ATTEMPTS`), then FAILED with the same reason; a Retry on the card restarts it. No upload code spends or rotates the post key.

| Answer | `Kind` | App action | Card / notification text |
|---|---|---|---|
| 400 `UNSUPPORTED_TYPE` | UNSUPPORTED_TYPE | final, FAILED; session dropped | "this video isn't a valid MP4 and can't be uploaded" |
| 400 `FILE_TOO_LARGE` | FILE_TOO_LARGE | final, FAILED; session dropped | "video is over the upload size limit" |
| (client) over 1 GiB on the legacy route | TOO_LARGE | final, FAILED; nothing sent (only non-`.mp4` or the 404 fallback; also a legacy 413 / 400 `File is too large.`) | "video is over the 1 GB limit" |
| 400 `SIZE_MISMATCH` (at `complete`) | SIZE_MISMATCH | final for this upload (server aborted it), FAILED; session dropped, **Retry uploads again as a new session** | "the upload didn't arrive intact, retry to upload it again" |
| 400 `CONTENT_TYPE_MISMATCH` | CONTENT_TYPE_MISMATCH | final, FAILED; session dropped | "the uploaded file isn't a valid MP4, it was not published" |
| 429 `TOO_MANY_PENDING_UPLOADS` (5 unfinished) | TOO_MANY_PENDING_UPLOADS | retried with the WorkManager backoff (`Retry-After` is not honoured) | "too many unfinished uploads, try again later" |
| 502 `STORAGE_ERROR` | STORAGE_ERROR | retried; the row stays `pending`, the same call is safe | "storage problem, try again" |
| 400 `INVALID_PARTS` | INVALID_PARTS | handled inside the uploader: `GET status`, re-send the parts R2 does not hold, `complete` again | "the upload was interrupted, retry to upload it again" only if it still ends a job |
| 409 `UPLOAD_NOT_PENDING` | UPLOAD_NOT_PENDING | handled inside: `GET status` then replay (completed), wait (completing) or start over (aborted / expired) | same as above, only if it ends a job |
| 404 `UPLOAD_NOT_FOUND` | UPLOAD_NOT_FOUND | handled inside: drop the row, create a new upload | same as above, only if it ends a job |
| 400 `INVALID_POSTER` | INVALID_POSTER | handled inside: `complete` is repeated **without** the poster; never fails the upload | n/a |
| 404 with no `code` on a direct route | (REJECTED, `routeMissing`) | legacy `POST /upload`, remembered per job | n/a |
| 429 without a code (API throttler on create) | RATE_LIMITED | retried with the WorkManager backoff (`Retry-After` is not honoured); no key involved, nothing rotates | "too many requests, try again soon" |
| 5xx, network drop, part out of tries | SERVER / NETWORK | retried; offline waits for the `CONNECTED` (or `UNMETERED` with Wi-Fi only) constraint | "server problem, try again" / "no connection" |
| 401 `TOKEN_EXPIRED` / `SESSION_REVOKED` | SIGNED_OUT | refresh once / sign out (unchanged) | "please sign in again" |

Post-step codes added or changed with this work: 404 `MEDIA_NOT_FOUND` (the server no longer has the uploaded video: the media id and path are forgotten, FAILED, a Retry uploads again; text "the uploaded video was lost, retry to upload it again") and 410 `MEDIA_PURGED` (below). Post-step 4xx still rotate the key; the direct-upload codes never do.

## Poster frame (RET-A2)

The server keeps a small poster after it purges the video, so the web post preview still shows something. `complete` takes an optional base64 `poster` (jpeg, png or webp by magic bytes, at most 512 KiB decoded, about 50 KB aimed at; the route body limit is 1 MB).

- `publish/PosterMaker` (pure choices) over `publish/BitmapImaging` (`MediaMetadataRetriever`, `Bitmap`, jpeg compress). Frame at 1 s (the middle of a video under 2 s, the start when the duration is unknown, frame 0 as a fallback). Long side 480 px, shrunk by 3/4 steps down to 160 px; jpeg qualities 80 / 65 / 50 / 35; the first result under 50 KB wins, otherwise the smallest under 512 KiB; nothing under the cap gives no poster.
- Made **from the file actually uploaded** (the `_9x16` crop when the user edited, never the master) and **lazily, just before `complete`**, so the frame grab never delays the first byte and a resume that needs no `complete` makes none (`DirectUploader` poster supplier, `PublishPipeline.posterFor`).
- The poster is a bonus: any failure (including `OutOfMemoryError`) means no poster and the upload goes on; `400 INVALID_POSTER` repeats `complete` once without it. The legacy `POST /upload` fallback has no poster field, so such an upload has none.

## Media removed from the server (RET-A1)

After the retention window (the server's `MEDIA_RETENTION_DAYS`, default 1 day after the last channel's URL; `MEDIA_RETENTION_ERROR_DAYS`, default 7, when a channel failed; never while a post still references the file) a mobile-uploaded video is purged. Creating or re-publishing a post with it then answers **410 `MEDIA_PURGED`** (`Kind.MEDIA_PURGED`).

- Final, **never retried automatically**. The answer is stored under the key, so the key is replaced; the job ends FAILED (`PublishPipeline.fail`), the media id / path / progress are forgotten (`PurgedMedia.forgetMedia`) and any stored upload session of the job is dropped.
- Card (`PublishLabels`): amber "Video removed from server" when the local recording still exists, or "Video removed from server. The file is no longer on this phone, so it can't be published again." when it does not (`PurgedMedia.is`, `fileExists`). The auto-publish notification says the same ("the video was removed from the server and the file is no longer on this phone" when the file is gone).
- **Retry** (`PublishManager.retry`): offered only when the local file exists (`PurgedMedia.cannotRetry` hides it otherwise; the manager also refuses). It forgets the media id and deletes the session row, then runs the normal path again: a new direct upload (new media id), then the post with the job's (new) key. The credits pre-flight for auto jobs runs again because the video is not uploaded.

## Guide audit (2026-10-14)

Checked `PublishApi`, `PublishPipeline`, `PostStatusSync`, `PostStatus`, `AutoPublishInfo`, `PublishException` and their tests against `mobile-auto-publish-api-guide.md` and the contract. Routes, headers, request/response field names and types, nullable fields, state enum, 50-id limit and chunking all matched. There is no `PublishPolicy` class: the key-rotation policy is `PublishException.spendsKey()` applied in `PublishPipeline.fail`. Mismatches fixed:

1. **429 reused the idempotency key.** The server stores the throttler's 429 under the key, so six same-key retries only replayed it. Now a throttler 429 (`message` contains `ThrottlerException`, not `AUTH_RATE_LIMITED`) is retried with a **new** key (also rotated if the attempts run out). An `AUTH_RATE_LIMITED` or unrecognised 429 keeps the key (an unknown-outcome post must not be duplicated). `PublishException.spendsKey`, `PublishPipeline.fail`.
2. **401 `{msg:"Invalid API key"}`** was returned as SIGNED_OUT without ending the session. `AuthRepository.executeAuthed` now signs out like `SESSION_REVOKED`.
3. **400 validation `{provider, name, message}`** showed only `message`; now "name: message" (`PublishException.withChannelName`).
4. **Upload 400s:** `File is too large.` now maps to TOO_LARGE (the dead 413 path was the only mapping); `Failed to read file` is retried (SERVER kind) instead of failing at once.
5. **Title cut** at 500 chars could split an emoji's surrogate pair (`PublishRequestBuilder.autoTitle`).

Left as is (guide recommendations, not contract): `Retry-After` is not honoured (WorkManager exponential backoff from 30 s, six attempts, about 15 min; a throttled job past that ends FAILED "too many requests" and a Retry sends a fresh key); polling keeps asking for a post missing from the answer for the whole 7-day window; `{title}` is not HTML-escaped by the server (a file name with `<` or `&` goes into the caption raw); a 403 `WORKSPACE_FORBIDDEN` on status polling is retried quietly. Nothing was run against a real server.

## Manual QA checklist (pending, needs the backend deployed and a device)

- [ ] Toggle off: recording finishes and nothing happens. Toggle on but signed out: nothing is queued, and Settings says "Sign in to use".
- [ ] No preset on the web: Settings says "Not set up" and the link opens the right web page. A recording ends FAILED "Set up on the web" with no upload; the card's "Set up on the web" action opens the setup page.
- [ ] Preset set: Settings shows channels, cost and balance. A recording is uploaded, then the card shows Published / Scheduled for ..., a notification appears, and after a while it shows "Live".
- [ ] 9:16 crop on: the crop is uploaded. Crop off: the original is uploaded.
- [ ] Credits below cost: FAILED "needs X, you have Y" with no upload. Top up, Retry, and it succeeds once.
- [ ] Airplane mode during upload, then reconnect: exactly one post per channel. Kill the app mid-publish: no duplicate.
- [ ] Kill the app during cropping: reopen, the sweep publishes it once.
- [ ] Rename a recording while it is still processing: it is published once, with the crop.
- [ ] Switch workspace after recording: the post still goes to the original workspace.
- [ ] Session revoked on the web mid-job: forced sign-out, card FAILED, notification. Sign in and Retry works (and does not post twice).
- [ ] Manual Publish from the card still works unchanged.
- [ ] A 1 GB upload over mobile data completes (no 408 from the server, no client timeout).
- [ ] Direct upload (DU-A4; the same boxes are in `to-build/tasks.md`, human): 1 GB on Wi-Fi completes and VPS network / RAM / disk stay flat; kill at 50% then reopen re-sends only the missing parts (one media, one post); airplane mode mid-upload then reconnect continues; Cancel mid-upload leaves no pending upload or row; Wi-Fi-only on a metered link waits, then uploads directly; backend not deployed (404) falls back to the legacy upload; the device token never appears in a request to `r2.cloudflarestorage.com`.
- [ ] Retention: a post a day after it went live, then Retry on a purged video: card says "Video removed from server", Retry uploads again as a new media and posts once; with the local file deleted the card explains and offers no Retry. The web post preview shows the poster.
- [ ] Backend hand checks (see `to-build/tasks.md` "Proven by"): preset marked -> GET shows channels and cost; same key twice -> one post group; zero balance -> 402 with nothing created; foreign workspace -> 403.
