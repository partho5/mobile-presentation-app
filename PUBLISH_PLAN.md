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

- `POST /public/v1/upload` multipart field `file` (scope media:write) -> `{id, name, path, ...}`; video cap 1 GB, `.mp4` path required by post DTO.
- `GET /public/v1/integrations` -> `[{id, name, identifier, picture, disabled, profile, customer?}]`.
- `POST /posts/charge-preview {integrationIds}` -> `{amount, newChannels, releasedChannels}` (app route; Bearer is accepted by AuthMiddleware, api.token-auth). Cost must load before Confirm; failure = "cost unavailable", Confirm stays disabled (ux.cost-preview).
- `POST /public/v1/posts` `{type:'now'|'schedule', shortLink, date(ISO UTC), tags:[], posts:[{integration:{id}, value:[{content, image:[{id,path}]}], settings:{...}}]}` -> `[{postId, integration}]`. `settings.__type` is filled by the server; provider-required settings are sent per `ProviderSettings` (youtube, tiktok, instagram, x).
- Errors: 402 `INSUFFICIENT_CREDITS {required, available, message}` (whole request, nothing created); 400 validation `{provider, name, message}`; 409 `IDEMPOTENCY_IN_PROGRESS`; 422 `IDEMPOTENCY_KEY_REUSED`; 401 TOKEN_EXPIRED/SESSION_REVOKED handled by `AuthRepository`.
- Idempotency (api.idempotency): same key + same body = replay of the first answer (24 h). 5xx/throw are not stored, so a retry re-runs. **4xx IS stored**, so after any definitive 4xx (e.g. 402) the key is rotated before the next attempt; the key is kept only when the outcome is unknown (network drop, 5xx, 409 in progress, 429).

## Design

- `auth/ApiClient.upload(...)` multipart with progress + cancel; `AuthRepository.executeUpload` shares the refresh/retry logic of `execute` via one private `executeAuthed(Sender)`.
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

## Contract facts used (exact)

- `GET /public/v1/auto-publish` (scope posts:read) -> `{configured, setName?, timing?: 'now'|'next_slot', channels:[{id,name,identifier,picture,disabled}], cost, available, setupUrl}`. `configured` is false with no preset or no usable channel. `setupUrl` is `<FRONTEND_URL>/w/<id>/settings?tab=sets`.
- `POST /public/v1/auto-publish` (scope posts:write) `{media:{id,path}, title}` + `Idempotency-Key` -> 201 `[{postId, integration}]` (same shape as `/posts`; same validation, credit reservation and idempotency path on the server). Errors: 409 `AUTO_PUBLISH_NOT_CONFIGURED`, 404 `MEDIA_NOT_FOUND`, 402 `INSUFFICIENT_CREDITS {required, available}`, 400 validation, 429.
- `GET /public/v1/posts/status?ids=a,b,...` (up to 50, workspace scoped) -> `[{id, state:'QUEUE'|'PUBLISHED'|'ERROR'|'DRAFT'|'DELETED', publishDate, releaseURL?, error?}]`; unknown or foreign ids are omitted; 400 `INVALID_IDS` for 0 or more than 50 ids.
- Idempotency is as in M2 (4xx stored for 24 h, 5xx / network not stored). A 401 is never stored either (rejected by the auth layer first), so it does not rotate the key.
- Upload path: Node `requestTimeout` 60 min on the server; no Caddy or nginx change. Production API base `https://social-media-schedular.nanybot.com/api` (debug and release).

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
- **Connection (A4):** `ApiClient.uploadClient`: no call timeout, 120 s write stall limit, 5 min read timeout for the answer after the last byte; ordinary calls keep connect 15 s / read 30 s / write 30 s.

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
- [ ] Backend hand checks (see `to-build/tasks.md` "Proven by"): preset marked -> GET shows channels and cost; same key twice -> one post group; zero balance -> 402 with nothing created; foreign workspace -> 403.
