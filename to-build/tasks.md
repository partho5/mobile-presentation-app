Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.
-----------------

# Direct-to-R2 upload (DU): the phone uploads to R2, the VPS never touches video bytes

Status: planned 2026-10-14. Build one part at a time, tick its row, commit. Backend parts first; app parts need them deployed.

## Why
Today the phone sends the whole file to `POST /public/v1/upload`: Caddy → nginx (buffers up to 1 GB on disk) → Node → R2. The VPS carries every byte, holds a connection for the whole upload, and needed a 60 min `requestTimeout`. Goal: the phone sends the bytes straight to R2 with short-lived presigned part URLs. The VPS only handles a few small JSON calls, so there is no bandwidth, disk or RAM pressure from video.

## Is it reliable? Yes, with these facts (checked in code, 2026-10-14)
- Backend already has the S3 multipart code for the web dashboard: `libraries/nestjs-libraries/src/upload/r2.uploader.ts` (`createMultipartUpload`, `prepareUploadParts`, `listParts`, `completeMultipartUpload` with magic-byte check, `abortMultipartUpload`, `signPart`) using `@aws-sdk/client-s3` and `@aws-sdk/s3-request-presigner`. Those routes are cookie-auth (`/media/:endpoint`), so a device token cannot use them. Reuse the logic, do not copy it.
- Gaps in that code that must be closed for the phone path: no ownership check on `key`/`uploadId` (anyone authed who knows them can complete or abort), no size or part-count cap, no per-org pending limit, no cleanup of abandoned uploads.
- R2 multipart rules the design depends on: every part except the last must be the same size and at least 5 MiB; at most 10,000 parts; parts upload independently and can be retried; `ListParts` returns what R2 already has, so an upload resumes after the phone process dies; R2 does not support S3 POST policies, so size is bounded by the server choosing part size and part count, then verified with a `HEAD` on complete.
- A phone is a native client: no CORS needed. Presigned URLs only work on the `<account>.r2.cloudflarestorage.com` host.

## Decisions (made; change here if wrong)
1. **Ownership is a DB row, not a key prefix.** New table `MobileUpload {id, organizationId, objectKey, uploadId, filename, size, partSize, partCount, status 'pending'|'completed'|'aborted'|'expired', mediaId?, createdAt, expiresAt}` (migration; no column on `Organization`). Every route checks `organizationId`. This keeps the flat random object key the rest of the system already expects.
2. **Fixed part size 16 MiB** (a 1 GB file is 64 parts). `partCount = ceil(size / partSize)`, must be ≤ 10,000. Max file size env `MOBILE_UPLOAD_MAX_BYTES`, default 2 GiB.
3. **Allowed types:** same as the existing multipart path (`.mp4`, plus `.mov` only when `UploadFactory.processorEnabled()`), validated by extension at create and by magic bytes at complete.
4. **Presigned part URLs are short-lived (15 min) and requested in batches of at most 20 part numbers**, so a long upload keeps asking for fresh URLs and a leaked URL is nearly useless.
5. **`complete` is the only moment bytes become trusted:** `CompleteMultipartUpload`, `HEAD` size must equal the declared size, magic-byte check (shared function extracted from `r2.uploader.ts`), then the media row is created exactly as `POST /public/v1/upload` does. Any failure deletes the object and marks the row `aborted`.
6. **`complete` returns the same body as the old upload, `{id, path}`**, so `POST /public/v1/auto-publish`, `/posts` and every idempotency rule stay unchanged.
7. **`POST /public/v1/upload` stays** (images, small files, older app versions, fallback). Nothing is removed.
8. **No credit charge at upload.** Credits are still reserved at post time. The app's existing pre-flight stays.
9. **The R2 PUT must never carry the device token.** The app uses a separate OkHttp client with no auth interceptor for presigned URLs.
10. **Abandoned uploads:** a cron aborts `pending` rows older than 24 h (and their R2 multipart), plus an R2 lifecycle rule "abort incomplete multipart uploads after 1 day" as a backstop (human action in the Cloudflare dashboard).

## Contract (routes under `/public/v1`, device token, scope `posts:write`)

| Route | Body → Response | Errors |
|---|---|---|
| `POST /upload/multipart` | `{filename, size, contentType?}` → 201 `{id, partSize, partCount}` (`id` is the MobileUpload id) | 400 `UNSUPPORTED_TYPE`, 400 `FILE_TOO_LARGE`, 429 `TOO_MANY_PENDING_UPLOADS` (max 5 per workspace) |
| `POST /upload/multipart/:id/parts` | `{partNumbers:[1..20 ints]}` → 200 `{urls:{"1":"https://…"}, expiresIn:900}` | 404 `UPLOAD_NOT_FOUND`, 400 `INVALID_PARTS` (out of 1..partCount or more than 20), 409 `UPLOAD_NOT_PENDING` |
| `GET /upload/multipart/:id` | → 200 `{status, size, partSize, partCount, parts:[{n, etag, size}]}` (from `ListParts`, paged) | 404 |
| `POST /upload/multipart/:id/complete` | `{parts:[{n, etag}]}` → 201 `{id, path}` (media). Idempotent: a repeat on a `completed` row returns the same media | 400 `SIZE_MISMATCH`, 400 `CONTENT_TYPE_MISMATCH`, 400 `INVALID_PARTS`, 404, 409 `UPLOAD_NOT_PENDING` |
| `DELETE /upload/multipart/:id` | → 204, aborts the R2 upload and marks the row `aborted` | 404 |

All errors keep the existing shape `{statusCode, code, message}`.

## End-to-end flow
```
app: POST /upload/multipart {filename,size}            → id, partSize, partCount
loop (2 parts in parallel, per part retry):
  app: POST /upload/multipart/:id/parts {partNumbers}  → presigned URLs
  app: PUT <url> (raw bytes, no token)                 → ETag header, saved in Room
app: POST /upload/multipart/:id/complete {parts}       → {id, path}
app: POST /auto-publish {media:{id,path}, title}       (unchanged)
resume after kill: GET /upload/multipart/:id → skip parts R2 already has
```

## App parts (this repo: FaceCam-Presentation-Recorder)

Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`. Backend parts DU-B1..B5 are built in `/home/haku/projects/Next.js/Social-Media-Manager` (guide: `to-build/mobile-auto-publish-api-guide.md`, contract `_planning/02_contracts/api.mobile-direct-upload.md`; the contract is the source of truth). App parts can be built and unit-tested against the contract before the backend is deployed; device QA needs it deployed. **Reuse:** `AuthRepository.execute` (all backend calls), `PublishPipeline` upload step and "upload once, remember the media id" rule, `PublishException` mapping, `PublishWorker` foreground progress, `PublishNetwork`, `ApiClient.uploadClient`. Do not change idempotency-key, credit, undo-window or Wi-Fi-only behaviour.

| Part | Scope | Status |
|---|---|---|
| DU-A1 | `DirectUploadApi` (create, parts, status, complete, abort) + models + unit tests | ⬜ |
| DU-A2 | `DirectUploader`: part loop, parallelism, retry, resume, progress; Room v6 `UploadSession` | ⬜ |
| DU-A3 | Pipeline integration + fallback to legacy `/upload` + error mapping | ⬜ |
| DU-A4 | Device QA, docs (PUBLISH_PLAN.md, UX_PLAN.md), VPS before/after check | ⬜ |

### DU-A1 API layer
- `publish/DirectUploadApi`: the five calls above through `AuthRepository.execute`, same headers as other `/public/v1` calls (`Authorization`, `x-workspace-id`). Models: `UploadSessionInfo {id, partSize, partCount}`, `PartUrls`, `UploadedPart {n, etag, size}`.
- Parse errors into `PublishException` (new codes: `UNSUPPORTED_TYPE`, `FILE_TOO_LARGE`, `SIZE_MISMATCH`, `CONTENT_TYPE_MISMATCH` are final and rotate nothing; `TOO_MANY_PENDING_UPLOADS` and 5xx retry).
- Unit tests with MockWebServer as in `PublishApiAutoPublishTest`: request shapes, parsing, error mapping.

### DU-A2 Uploader and resume
- `publish/DirectUploader.upload(file, sessionStore, progress)`:
  1. Resume check: if a stored session exists for this job, call `GET status` and trust R2's list (not only the local etags) of finished parts. Otherwise create.
  2. Request URLs in batches of 20; PUT each part with `RequestBody` reading a file slice (no whole-part buffering beyond one OkHttp buffer), exact Content-Length, **using a separate OkHttp client with no auth interceptor** (never send the device token to R2).
  3. 2 parts in parallel. Per part: up to 5 tries with exponential backoff; 403 (expired URL) re-requests URLs; a missing `ETag` response header is a failure of that part.
  4. Persist each finished part's `{n, etag}` immediately (Room), so a kill loses at most the parts in flight.
  5. Progress callback in total bytes, throttled, feeding the existing foreground notification and card.
  6. `complete` with all parts; return `{id, path}` for the pipeline.
- Room: new entity `UploadSession {jobId PK, uploadId, size, partSize, partCount, partsJson, status}`, database v6 with a real migration and a migration test (as v5 was done). Delete the row when the job finishes or is cancelled; cancel also calls `DELETE /upload/multipart/:id` best-effort.
- A file that changed size since the session was created (re-processed) invalidates the session: abort and start over.
- Tests (pure parts): part slicing and last-part size, batching, resume skipping finished parts, backoff, session invalidation.

### DU-A3 Pipeline integration
- In `PublishPipeline` the upload step calls `DirectUploader` instead of the legacy multipart-form upload for video files. The media id/path remembered per job, the pre-flight, the `POST /auto-publish` step and key rotation stay as they are.
- Fallback: if `POST /upload/multipart` answers 404 (older server) use the legacy `/upload` once; remember the choice for the job. Images and anything not video keep the legacy path.
- Map: 400 `SIZE_MISMATCH` / `CONTENT_TYPE_MISMATCH` → FAILED with a plain message and Retry (new session); network loss mid-part → retry with CONNECTED/UNMETERED constraint as now (offline time does not burn attempts).
- Pipeline tests: direct upload happy path, resume after interrupt makes no duplicate create, 404 fallback, size mismatch final.

### DU-A4 QA and docs
- Update `PUBLISH_PLAN.md` AUTO_PUBLISH (upload section, new error codes), mark U4 in `UX_PLAN.md` done.
- Remove the stale statement that Node `requestTimeout` matters for the phone path; keep it for the fallback.
- Device QA:
  - [ ] 1 GB upload on Wi-Fi completes; VPS network, RAM and disk graphs stay flat (the main goal).
  - [ ] Kill the app at 50%: reopen, upload resumes and re-sends only missing parts; one media, one post.
  - [ ] Airplane mode mid-upload, then reconnect: continues.
  - [ ] Cancel mid-upload: R2 upload aborted, no leftover row.
  - [ ] Wi-Fi-only on a metered connection: waits, then uploads directly.
  - [ ] Backend not deployed (404): falls back to legacy upload.
  - [ ] The device token never appears in a request to `r2.cloudflarestorage.com`.
