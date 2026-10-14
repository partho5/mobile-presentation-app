Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.

# Auto-publish v1 (M4): Plan & Progress Tracker

Planning only; no code yet. Build one part per session ("build part N"), tick its row, commit.
App parts A1-A2 can be built against the contract before the backend ships; A3 and QA need B3-B6 deployed.

## Status

| Part | Scope | Owner | Status |
|---|---|---|---|
| B | Backend B1–B7 (preset, auto-publish routes, post status, upload timeouts, contract) | backend agent | ✅ Done (2026-10-14): built and committed in Social-Media-Manager (e2afe2d7), contract `api.mobile-auto-publish`; nothing run there yet, needs deploy + the hand checks below |
| A1 | Settings: auto-publish checkbox + status line | app | ✅ Done (2026-10-14): Settings "Auto-publish" section (pref `key_auto_publish`) + status line from `PublishApi.autoPublishInfo()` (`AutoPublishInfo` parse, 13 unit tests); unit tests + assembleDebug pass, device check pending |
| A2 | Auto trigger, startup sweep, worker `auto` mode, result notification | app | ✅ Done (2026-10-14): `PublishManager.onRecordingStopped/releaseAuto/sweepAuto` (row `mode=auto`, status `WAITING_PROCESSING`; no Room schema change, both are Strings), pipeline pre-flight → upload → `POST /auto-publish`, result notification, 9 new pipeline tests + file-pick/builder tests; unit tests + assembleDebug pass, device QA pending |
| A3 | Live post status on cards | app | ✅ Done (2026-10-14): `PublishApi.postStatus` + `PostStatusSync` (7-day non-final DONE jobs, one call per workspace per 50 ids) run from Saved Recordings `onResume` (20 s throttle, own executor); Room v5 migration adds 5 nullable columns (`post_integrations`, `remote_state/channel/error/url`); card shows Live (tap opens releaseURL) / Failed on <channel>: <error> (Retry = new key) / Deleted on web; unit tests + assembleDebug pass, device QA pending |
| A4 | Connection hardening, device QA, docs | app | ⬜ Todo |

---

## Context

**What the app already does (M1–M3, built and unit-tested; device QA still pending):**
- Email OTP sign-in, token refresh, and devices; workspace switch; credit balance.
- Manual **Publish** from a recording card:
  - pick channels, caption, and Now or Schedule;
  - the server shows the cost (`/posts/charge-preview`);
  - upload goes to `/public/v1/upload`, then the post to `/public/v1/posts`;
  - runs under WorkManager with idempotency keys, retry and cancel, and per-card status.

**What it does not do:**
- Nothing is published without a manual tap.
- After the backend accepts a post, the app never learns whether it actually went live or failed (no status read).
- Provider settings are hardcoded defaults in `publish/ProviderSettings.java`.

**What the backend already has (verified in code):**
- Bearer device tokens on `/public/v1/*` and `x-workspace-id`.
- Credit engine:
  - reserve at schedule time, commit on publish, release on failure or delete;
  - 402 `INSUFFICIENT_CREDITS`.
- `GET /credits/balance`, `POST /posts/charge-preview`, `GET /public/v1/find-slot/:id`, and the idempotency middleware.
- **Sets**: saved post templates that hold channels, per-provider settings and text. They are made in the normal post editor ("Save as Set") and stored as the editor's post data in `Sets.content`.

**Goal:** the app gets one checkbox, "Auto-publish new recordings". When it is ticked, every finished recording is uploaded and published with no further taps. All details live on the web: which channels, the caption, provider settings, and now or next free slot. The app only connects and consumes. Credits are checked and charged only by the server.

## Decisions (made; change here if wrong)

1. **The preset is a Set.** On the web, the user marks one Set per workspace as "Mobile auto-publish". This reuses the existing editor for provider settings, so no new settings UI is needed for channels or providers.
2. **Timing is server-side.** It is stored with the preset as `now` or `next_slot`. `next_slot` uses the existing `findFreeDateTime`. The phone's clock is never used.
3. **Which file is published:** the 9:16 crop when one was made (9:16 is on by default), otherwise the processed original. There is one auto-publish per recording.
4. **The toggle is local** (SharedPreferences), off by default, per device. It only does anything while signed in.
5. **The workspace is pinned at the moment the recording stops**, as manual jobs already pin it.
6. **Network:** auto jobs use the same rule as manual ones (any connection). There is no Wi-Fi-only option in v1.
7. **The server is the only source of money truth.** The app shows the server's cost and balance, does a pre-flight check to avoid a pointless 1 GB upload, and the server still decides at post time.

## End-to-end flow

```
Recording stops ──(toggle on + signed in)──> PublishJob row mode=auto, status=WAITING_PROCESSING
Post-processing done (BgReplaceService end, or no processing) ──> pick file, enqueue PublishWorker
PublishWorker (mode=auto):
  1. GET  /public/v1/auto-publish            pre-flight: configured? cost <= available?
        not configured -> FAILED "Set up auto-publish on the web" (no upload)
        cost > available -> FAILED "Needs X credits, you have Y" (no upload, Retry on card)
  2. POST /public/v1/upload                  (existing step, media id remembered)
  3. POST /public/v1/auto-publish {media, title}  + Idempotency-Key (existing key policy)
        -> [{postId, integration}]  => status ACCEPTED (Published / Scheduled for …)
  4. Result notification (success or failure, tap opens Saved Recordings)
Later (Saved Recordings opened / app resumed): GET /public/v1/posts/status?ids=…
        -> card shows Live / Failed on <channel>: <reason>
```

---

## Backend tasks (hand to the backend agent; Social-Media-Manager)

Follow that repo's `_planning` flow: add one contract, `api.mobile-auto-publish`, that freezes the routes below. Keep Postiz core edits minimal: use a new table rather than new columns on `Organization`.

- **B1 Preset storage.**
  - New table `AutoPublishPreset {organizationId unique, setId → Sets (on delete cascade), timing 'now'|'next_slot' default 'now', updatedAt}`, shipped as a migration.
- **B2 Web UI**, in the existing Sets list (`apps/frontend/src/components/sets/sets.tsx`):
  - a "Use for mobile auto-publish" choice (one per workspace) plus a timing select;
  - shows which Set is active;
  - a hint that the caption may contain `{title}`, which is replaced by the recording name.
- **B3 `GET /public/v1/auto-publish`** → `{configured, setName?, timing?, channels:[{id,name,identifier,picture,disabled}], cost, available, setupUrl}`.
  - `cost` comes from `PublishChargingService.preview` over the Set's usable channels.
  - `available` comes from `CreditBalanceService.view`.
  - `setupUrl` is the web page where the preset is chosen, with the `/w/<id>` prefix.
  - `configured` is false when there is no preset, or when no usable channel is left.
- **B4 `POST /public/v1/auto-publish`** `{media:{id,path}, title}` with `Idempotency-Key`. It builds the create-post body from the Set:
  - channels that are not disabled, with their stored settings;
  - text with `{title}` replaced;
  - the Set's media replaced by this one video;
  - a YouTube or TikTok title falls back to `title` when empty;
  - `type` is `now`, or `schedule` with the `findFreeDateTime` date.

  It then runs **the same code path as `POST /public/v1/posts`**: extract that controller body into one shared method, with no copy. That path covers validation (400 `{provider,name,message}`), `RESTRICT_UPLOAD_DOMAINS`, credit reservation (402 `INSUFFICIENT_CREDITS`) and creation. The response has the same shape as posts: `[{postId, integration}]`.

  Additional errors:
  - 409 `AUTO_PUBLISH_NOT_CONFIGURED`;
  - 404 when the media does not belong to the workspace.
- **B5 `GET /public/v1/posts/status?ids=a,b,…`** (up to 50, scoped to the workspace) → `[{id, state:'QUEUE'|'PUBLISHED'|'ERROR'|'DELETED', publishDate, releaseURL?, error?}]`. Unknown or foreign ids are omitted.
- **B6 Large-upload path (1 GB video over slow mobile data).** Upload chain, checked in code: outer Caddy (`deploy/Caddyfile`) → nginx inside the image (`var/docker/nginx.conf`, :5000) → Node backend (:3000) → streamed to R2 (`MulterStreamEngine`).
  - **Caddy, no change:** `read_timeout`/`write_timeout 10m` are idle limits per read or write, not a cap on total request time. A long upload that keeps flowing is not cut.
  - **nginx, no change:** `client_max_body_size 2G` covers the 1 GB cap. nginx buffers the whole body before passing it on (`proxy_request_buffering` is on by default), which shields Node from slow phones.
    - Check that the VPS has about 1 GB of free temp disk per concurrent upload.
  - **Change, `apps/backend/src/main.ts`:** Node's `server.requestTimeout` defaults to 300 s (Node 18+) for receiving a whole request.
    - Why it bites: nginx feeds the body to Node only as fast as R2 accepts it. Below about 27 Mbit/s to R2, a 1 GB upload passes 300 s and Node answers 408.
    - Fix: after `app.listen`, set `app.getHttpServer().requestTimeout = 60 * 60 * 1000` (60 min). Leave `headersTimeout` at its default.
  - **Proven by:** a 1 GB mp4 sent to `/api/public/v1/upload` through the public URL answers 200 `{id, path}` and the file is in R2. The backend logs no 408.
- **B7 Freeze** the app routes the phone already reads straight from source (`GET /user/organizations`, `GET /user/self`, `POST /posts/charge-preview`) in the contract, so they cannot change silently.
- **Proven by:**
  - mark a Set, then a device token GET shows its channels and cost;
  - POST twice with the same key gives one post group and one reservation per channel;
  - zero balance gives 402 with nothing created;
  - no preset gives 409;
  - status route returns PUBLISHED or ERROR after the worker runs;
  - a foreign workspace gets 403.

### Part B result (for the app parts)

Built to the routes above; facts the app can rely on:
- `GET /public/v1/auto-publish` → `{configured, setName?, timing?, channels[], cost, available, setupUrl}`; `setupUrl` is `<FRONTEND_URL>/w/<id>/settings?tab=sets` (opens the Sets tab).
- `POST /public/v1/auto-publish` → 201 `[{postId, integration}]`; 409 `AUTO_PUBLISH_NOT_CONFIGURED`, 404 `MEDIA_NOT_FOUND`, 402 `INSUFFICIENT_CREDITS {required, available}`. The attached file is the server's stored path of `media.id`.
- `GET /public/v1/posts/status?ids=` → `state` is `QUEUE|PUBLISHED|ERROR|DRAFT|DELETED` (DRAFT is the one value beyond the plan); `error` only on ERROR; `releaseURL` when published; 400 `INVALID_IDS` for 0 or more than 50 ids.
- Device token scopes: both GETs need `posts:read`, the POST `posts:write`; a device token holds every scope.
- Web side: Settings → Sets has "Use for mobile auto-publish" per Set and a timing select (Publish now / Next free slot).
- Upload: Node `requestTimeout` is 60 min; no Caddy or nginx change.
- Not yet verified by running anything (the backend repo's rule): see the hand checks in the hand-off.

## App parts (one part per session; tick and commit each)

Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`. **Reuse:** `PublishManager`, `PublishWorker`, `PublishPipeline` (upload-once + idempotency key), `PublishPolicy` (key rotation), `AuthRepository.execute`, `PublishLabels`, `WebLinks`, and the `SettingsActivity.bindCheckBox` pattern.

### Part A1: Settings section
- `ui/SettingsActivity` plus its layout: an "Auto-publish" section with one checkbox, "Auto-publish new recordings" (pref `key_auto_publish`, default false).
- One status line under it, loaded from `GET /public/v1/auto-publish` when the screen opens:
  - signed out: "Sign in to use" (tap opens sign-in);
  - not configured: "Not set up yet: set up on the web" (opens `setupUrl`);
  - configured: "Posts to YouTube, TikTok · N credits per video · Available M";
  - load failed: "Couldn't load, tap to retry".
- `publish/PublishApi`: `autoPublishInfo()` parse, plus unit tests.

### Part A2: Auto trigger and worker mode
- **On recording stop** (`service/ScreenRecordService`, where `ACTION_RECORDING_FINISHED` is sent): if the toggle is on and a session exists, insert a `PublishJob` with `mode="auto"` and `status=WAITING_PROCESSING`. The workspace is pinned then. The card immediately reads "Auto-publish: waiting for processing".
- **On processing done:** at the end of the `service/BgReplaceService` job, after the crops, call `PublishManager.releaseAuto(originalPath)`. When there was no processing, call it straight from `ScreenRecordService`. It picks the 9:16 crop if it exists, otherwise the original, sets `filePath`, and enqueues.
- **Startup sweep:** `PublishManager.sweepAuto()` runs on app start and when Saved Recordings opens. It releases `WAITING_PROCESSING` rows whose processing is no longer running, which covers a process killed mid-crop. Nothing is silently lost.
- **Worker:**
  - `PublishPipeline` branches on `mode=auto`: pre-flight `GET /auto-publish`, then the existing upload step, then `POST /public/v1/auto-publish`.
  - Errors map through the existing `PublishPolicy` rules (402 → rotate key, FAILED with "needs X, you have Y"; 5xx/offline → retry; 409 not configured → FAILED with a "Set up on the web" action).
  - Room: no schema change; `mode` is already a String. Check this.
- **Result notification:** "Published to N channels" / "Scheduled for …" / "Auto-publish failed: reason". Tapping opens Saved Recordings.
- Signed out mid-job: the existing FAILED (signed out) state plus a notification, "Sign in to finish auto-publishing". Retry on the card works after sign-in.
- Tests: a pipeline test for the auto branch (pre-flight fail, no upload; 402 rotation; replay after kill) and a file-pick test.

### Part A3: Live status on cards
- `PublishApi.postStatus(ids)` → `GET /public/v1/posts/status`.
- When Saved Recordings opens or the app resumes, accepted jobs (manual or auto) from the last 7 days with no final state are checked in one call, and the card updates:
  - "Live" (with a link to `releaseURL` when present);
  - "Failed on <channel>: <error>", with Retry → a new publish;
  - "Deleted on web".
- No background polling and no push in v1.

### Part A4: Connection hardening and QA
- `auth/ApiClient` upload: no call timeout. Use a generous write timeout, and a read timeout of 5 m for the server's answer after the last byte.
- Confirm the release `API_BASE_URL` (`https://social-media-schedular.nanybot.com/api`) is the production one. Remove the stale "TODO placeholder" note in `AUTH_PLAN.md`.
- Run the device QA below. Write the `AUTO_PUBLISH` section into `PUBLISH_PLAN.md` (contract facts used, status table).

## Robust connection rules

All but the last two already hold and must be kept.

- Every authed call goes through `AuthRepository`: proactive refresh, one refresh plus retry on `TOKEN_EXPIRED`, forced sign-out on `SESSION_REVOKED`.
- `x-workspace-id` is pinned per job. A 403 `WORKSPACE_FORBIDDEN` gives a final FAILED with a clear message.
- An idempotency key is used per job. It is kept on unknown outcomes (network, 5xx, 409 in-progress, 429) and rotated after a definitive 4xx. Media is uploaded once and its id remembered.
- WorkManager unique work per job, with the CONNECTED constraint (offline time does not burn attempts), backoff, and a cap of 6 attempts.
- Money is never shown optimistically. Every number is the server's.
- New: the pre-flight check before upload, and the startup sweep, so a recording never silently misses auto-publish.

## Out of scope for v1
- Push notifications.
- Resumable or chunked upload.
- A different file per channel.
- Wi-Fi-only.
- Editing the preset in the app.
- Auto-publishing old recordings.
- Account deletion route.

## Verification (device QA, after the backend B1–B6 are deployed)
- [ ] Toggle off: recording finishes and nothing happens. Toggle on but signed out: nothing is queued, and Settings says "Sign in to use".
- [ ] No preset on the web: Settings says "Not set up" and the link opens the right web page. A recording ends FAILED "Set up on the web" with no upload.
- [ ] Preset set: Settings shows channels, cost and balance. A recording is uploaded, then the card shows Published / Scheduled for …, a notification appears, and after a while it shows "Live".
- [ ] 9:16 crop on: the crop is uploaded. Crop off: the original is uploaded.
- [ ] Credits below cost: FAILED "needs X, you have Y" with no upload. Top up, Retry, and it succeeds once.
- [ ] Airplane mode during upload, then reconnect: exactly one post per channel. Kill the app mid-publish: no duplicate.
- [ ] Kill the app during cropping: reopen, the sweep publishes it once.
- [ ] Switch workspace after recording: the post still goes to the original workspace.
- [ ] Session revoked on the web mid-job: forced sign-out, card FAILED, notification. Sign in and Retry works.
- [ ] Manual Publish from the card still works unchanged.
