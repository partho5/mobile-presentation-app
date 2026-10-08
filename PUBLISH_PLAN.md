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
