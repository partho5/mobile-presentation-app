<!-- runner-config
REPOS=/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder /home/haku/projects/Next.js/Social-Media-Manager
BRANCH=feat/ai-video-edit
SCRATCH=test_audio.wav test_video.mp4 run.log
-->

RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 5 tests in `EngineContractTest` /
`SoundTrackTest` fail on a clean HEAD; they are not yours.

One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

# FaceCam app: AI edit follow-ups (cards A1, L6)

## Shared context
- App (Android, Java), branch `feat/ai-video-edit`. Java paths are relative to
  `app/src/main/java/com/jovoc/facecampresentationrecorder/`. Tests: `app/src/test/java/...`.
- The AI edit flow: server plans (`aiedit/flow/AiEditRunner`, `AiEditApi`), the phone renders (`aiedit/engine/*`).
  Verified on a real phone: the full flow now works end to end.
- Commit per card: `feat(ai-edit): <card id> <what>` (or `fix(ai-edit): ...`), ending with the `Co-Authored-By` line the
  session gives. Never commit `test_audio.wav`, `test_video.mp4`, `run.log`.
- Rules: engine code in `aiedit/engine/` imports only `aiedit/engine/*` and the Android SDK; flow glue in
  `aiedit/flow/`; no new Gradle module. The master recording is never modified. **Never log transcript words, plan
  texts or event log contents** (`ACTIVITY_LOG_PRIVACY.md`). Only video/* files may be created under DCIM (names end
  in `.mp4`); other temp files go to `filesDir` / `cacheDir` (see `VideoCropHelper.PART_SUFFIX`).
- **Do not touch the subtitle code** (`subtitle/*`, `aiedit/flow/SubtitleCaptionPainter`).
- Backend tracing plan (cards L1-L5) is in `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md`.

## Progress

| Card | What | Depends on | Status |
|---|---|---|---|
| A1 | Show the server's real status (Transcribing / Planning) in progress text | - | ✅ |
| L6 | Send the render receipt to the server | backend L5 | ⬜ |

---

## A1 - Real server status in the progress text [app]
**Goal:** the user sees what the server is really doing instead of a fixed "Planning…".
**Read first:** Shared context; `aiedit/flow/AiEditStatus.java`, `AiEditRunner.java` (`awaitPlan`, the
`listener.onProgress(..., Stage.PLANNING, ...)` call), `AiEditListener.java` (`Stage`), `AiEditService.java`
(notification text, ~line 308), `ui/AiEditPanel.java` (~line 222), `res/values/strings.xml`.
**Build:** the poll of `GET /ai-edits/:id` already returns `PENDING | TRANSCRIBING | PLANNING`; map it to a stage
(add `Stage.TRANSCRIBING`; `PENDING` shows as queued/"Starting…") and show it in the notification and the panel:
"Transcribing…", "Planning the edit…". Strings in `strings.xml` (and every existing `values-*` that has the
neighbouring AI edit strings). Report a stage only when it changes. No change to timeouts, retries or the render.
**Tests:** a JVM test for the status-to-stage mapping (unknown status keeps the last stage). Build with
`./gradlew --offline :app:assembleDebug`.

## L6 - Render receipt [app]
**Goal:** the server learns what the phone did with the plan, so a failed or odd render can be traced to a decision.
**Needs:** backend card L5 shipped (route exists).
**Read first:** Shared context; `aiedit/flow/AiEditApi`, `AiEditRunner` (`stop(...)`, `render`),
`aiedit/engine/Engine.java`, `engine/text/TextOverlayPainter`, `engine/overlay/IconOverlayPainter`,
`engine/RenderJob.java` (where painters skip or shrink).
**Contract (server side):** `POST /public/v1/ai-edits/:id/render-report`, body <= 64 KB, idempotent per
`(editId, attemptId)`:
`{attemptId, served_sha256, parse_ok, app_version, device, outputs:[{ratio, ok, error_code?, duration_ms?, bytes?}],
warnings:[{code, item_id?}]}`. Warning codes: `overlay_skipped_no_room`, `text_shrunk`, `asset_missing`.
**Build:** `AiEditApi.reportRender(...)`; sha256 of the plan body as received; per ratio the result with our own error
codes (e.g. `RENDER_FAILED`, never exception text); a small `RenderNotes` passed in `RenderJob` (new optional ctor
arg, existing ctors keep working) that the text and icon painters fill with warning codes and item ids; `app_version`
and device model; header `x-app-version` on plan GETs. Sent once per attempt after the last ratio, or after the
failure; failures silent, retried on the next app start, never blocks showing results. No words, plan texts or paths
in the receipt.
**Tests:** JVM test for the receipt builder (codes only, hash, per-ratio outcomes).
