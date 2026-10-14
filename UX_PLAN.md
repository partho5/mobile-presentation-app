# UX_PLAN (M5): trust and reliability for auto-publish

Source: CTO/owner UX review (2026-10-14). Build one part per session ("build part N"), tick its row, commit.
Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## Status

| Part | Scope | Owner | Status |
|---|---|---|---|
| U1 | Undo window before an auto-publish upload starts | app | ✅ Done (2026-10-14): WorkManager initial delay as a gate worker chained to the expedited upload under one unique name (Off / 30 s / 60 s, default 30 s, pref `key_auto_publish_undo_seconds`) applied in `PublishManager.release` via `AutoPublishRelease` (release, sweep and startup share it, deadline kept in `scheduleAt`, no Room change); card "Publishing in N s · Tap to cancel", notification with Cancel (`UndoNotifier`, `PublishCancelReceiver`), Settings radio; 15 new unit tests, unit tests + assembleDebug pass, device QA pending |
| U2 | Wi-Fi-only publishing setting + "Waiting for Wi-Fi" card state | app | ✅ Done (2026-10-14): pref `key_publish_wifi_only` (default off) read in `PublishManager.schedule` (`PublishNetwork.requiredNetwork`: UNMETERED / CONNECTED, manual and auto); card "Waiting for Wi-Fi…" for a QUEUED job outside its undo window when the setting is on and the default network is metered (checked at bind, redrawn on connectivity change via a default-network callback in SavedRecordingsActivity); toggling calls `PublishManager.onWifiOnlyChanged` which swaps the network constraint of the QUEUED jobs' upload work in place with `WorkManager.updateWork` (no cancel / REPLACE, so a just-started upload is never stopped; no key, deadline, attempt-count or gate change; if not applied the job keeps its old constraint); unit tests + assembleDebug pass, device QA pending |
| U3 | In-app first-time setup (channels + timing, no web detour) | backend + app | ⛔ blocked: backend |
| U4 | Direct-to-R2 resumable upload (VPS carries no video bytes) | backend + app | 📝 planned: see `to-build/tasks.md` (DU-B1..B5, DU-A1..A4) |

## Decisions (made; change here if wrong)

1. **Undo applies to auto-publish only.** Manual Publish is already an explicit tap.
2. **The undo delay survives process death.** Use a WorkManager initial delay, not an in-memory timer.
3. **Undo delay is a setting:** Off / 30 s / 60 s, default 30 s (pref `key_auto_publish_undo_seconds`).
4. **Wi-Fi-only is one global setting** (pref `key_publish_wifi_only`, default false so existing users see no silent change). It applies to manual and auto jobs, via the WorkManager `UNMETERED` constraint.
5. **A deferred job is never silent.** The card always says why it is waiting ("Publishing in 30 s: Cancel", "Waiting for Wi-Fi").
6. Server stays the only money truth; no change to credit or key policy.

## Part U1: Undo window
- When `PublishManager.releaseAuto` would enqueue, enqueue `PublishWorker` with an initial delay of the undo setting (0 when Off).
- Card shows "Publishing in N s" with a Cancel action; an ongoing-style notification "Publishing in 30 s" with a Cancel action does the same.
- Cancel = the existing cancel path (unique work cancelled, job marked cancelled, nothing uploaded, no credits touched).
- Settings: "Undo before publishing" choice under Auto-publish.
- Status strings: reuse the String `status` column; no Room schema change (check).
- Tests: delay applied, cancel inside the window uploads nothing, Off = no delay, replay after process kill still fires once.

## Part U2: Wi-Fi-only
- Settings checkbox "Upload on Wi-Fi only" with a one-line hint about 1 GB videos.
- `PublishManager` enqueue: network constraint `UNMETERED` when set, else `CONNECTED` (existing).
- Card state "Waiting for Wi-Fi" when a job is enqueued but blocked by the constraint.
- Toggling the setting re-enqueues pending jobs with the right constraint (no duplicate work; keys unchanged).
- Tests: constraint selection, pending job re-enqueue keeps one unique work.

## Part U3: In-app first-time setup (blocked)
Backend today only exposes preset edit on cookie-auth web routes; device tokens cannot list Sets or change the preset.
Needs, on a device token (scope `posts:write`):
- `GET /public/v1/sets` (id, name, channels), `PUT /public/v1/auto-publish {setId, timing}`, and a way to create a minimal Set from selected channel ids.
- `GET /public/v1/integrations` (connectable channels) if not already available.
App work after that: a setup screen reachable from the Settings status line, replacing the "set up on the web" detour.

## Part U4: Direct-to-R2 multipart upload (planned)
Full plan, contract and part list are in `to-build/tasks.md` (app) and the Social-Media-Manager `to-build/tasks.md` (backend). The phone uploads parts straight to R2 with short-lived presigned URLs from the backend; the VPS handles only small JSON calls. Resumable by design (R2 `ListParts`).
