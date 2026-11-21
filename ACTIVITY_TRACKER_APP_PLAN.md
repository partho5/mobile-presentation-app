# Activity Tracker — App Plan & Progress Tracker

A fresh library, `ActivityTracker`, records every user activity and error on the phone. It keeps them in the local
DB and uploads them in batches, so the backend's super-admin page can replay each device's journey "as if sitting
beside the user": what they opened, where they tapped, how long each step took, where they got stuck or left, which
features they really use, and what broke. The old `activitylog` system is removed completely.

- Contract (frozen): `/home/haku/projects/Next.js/Social-Media-Manager/_planning/02_contracts/api.activity-tracker.md`
  (copied into `to-build/tasks.md` → Shared context)
- Executable cards: `to-build/tasks.md` (A1–A5). Backend: `ACTIVITY_TRACKER_BACKEND_PLAN.md` in the backend repo (B1–B6).

## Status
| Card | Scope | Pairs with | Status |
|---|---|---|---|
| A1 | Tracker core: API, records, Meta, Room table + migration 8→9, device id, prefs, session clock | — | ☐ |
| A2 | Upload: policy (5 days / > 20), worker, scheduler, internet check, gzip API | B2 | ☐ |
| A3 | Auto-tracking: app open + source, foreground/background, screens, crash, exit reasons, notification entry | — | ☐ |
| A4a | Convert calls: `MainActivity` | — | ☐ |
| A4b | Convert calls: `ui/*` screens and sheets | — | ☐ |
| A4c | Convert calls: services, publish, adapter, SavedRecordings | — | ☐ |
| A4d | Convert calls: auth, entitlements, the rest | — | ☐ |
| A4e | Delete the old `activitylog` system + migration 9→10 + leftover WorkManager jobs | — | ☐ |
| A5 | `ACTIVITY_TRACKER_PRIVACY.md` + Play Data-safety answers | — | ☐ |

## Owner intent (2026-11-21)
1. One small API usable anywhere: `ActivityTracker.log("click", "edit topics", Meta.of("screen","home"))`. Each record holds the name,
   a brief description, start time, end time and optional metadata such as the trigger source (app opened via notification).
2. Errors and crashes go through the same library (`kind = error`), with enough detail to understand exactly what happened.
3. The user id is the MediaDrm-based UUID, fixed per device, signed in or not. On sign-in the backend links it to the email.
4. Records are stored as JSON in the local DB. They are sent **every 5 days**, or sooner once **more than 20** are waiting. Both numbers are constants.
   **Any** working internet is fine (the payload is small), and internet is checked before sending.
5. **Zero user impact**: no UI, toast, dialog or notification, ever. Every failure (null, empty list, DB, network, server) is caught silently and retried later.

## Improvements over the proposal
- `seq` + `session` on every record: exact order and "+gap" even with equal timestamps.
- Spans (`start()` → `end()`): screens, time away, recording and AI jobs get real durations.
- Breadcrumbs on errors: the current screen and the activity just before the error.
- `ApplicationExitInfo` read on the next start (minSdk 31, always available): ANRs (with their trace), native crashes and
  low-memory kills that an uncaught-exception handler never sees.
- A notification-entry trampoline activity, so **every** notification tap is seen, even when the app was already running.
- Per-record app build, so records buffered across an update keep the right version.
- Poison-batch rule: a batch the server can never accept is deleted, so it can't block the queue forever.

## Alternatives considered
| Option | Verdict |
|---|---|
| Firebase Analytics + Crashlytics, PostHog, Mixpanel | No step-by-step replay keyed by our MediaDrm id, a third party owns the data, errors and activities split, an extra SDK. |
| Session-video SDKs (UXCam, Smartlook) | Heavy, captures the screen (sensitive for a screen-recorder app), costly. |
| Send each event live | Radio wake-up per tap (battery, data), lost offline. |
| Send from `onStop` on a thread | Dies with the process, only runs while the app is open. |
| **Own tracker → Room buffer → WorkManager batch upload (chosen)** | No UI, survives offline and process death, a few KB a few times a week, our own data. The most frictionless option. |

## Side effects and their handling
| Side effect | Handling | Conflicts with goal/UX? |
|---|---|---|
| Light users appear up to 5 days late | Accepted: the replay is complete, just not live | No |
| Mobile data use | gzip, ≤ 50 records per request, a few KB to tens of KB, a background job | No |
| Disk use | `LOCAL_MAX_ROWS = 3000`. When full, the oldest are dropped and one `tracker_overflow` record is written | No |
| Crash path delay | The write waits at most `CRASH_WAIT_MS = 500`, then the normal crash flow runs | No |
| Main-thread cost | Callers only enqueue to one low-priority daemon thread. Nothing throws. A no-op before init | No |
| Error messages/stacks can hold file names | Disclosed in `ACTIVITY_TRACKER_PRIVACY.md` and the Play Data safety form (A5) | No |
| Old WorkManager jobs point at deleted classes | A4e cancels them by unique name at init | No |
| Old app builds | Keep posting to the removed `/activity/batches`, get 404, retry silently, no UI | No |
| Phones without Widevine | Random UUID fallback, so a reinstall there looks like a new device | Minor |
| Notification trampoline | A translucent, no-animation activity that forwards in the same frame. The user sees the same target screen | No |

## Design summary (details in the cards)
- Package `com.jovoc.facecampresentationrecorder.tracker`: `ActivityTracker`, `Tracked`, `Meta`, `TrackerConfig`, `TrackedRecord`, `ErrorInfo`,
  `DeviceId`, `TrackerPrefs`, `SessionClock`, `TrackerStore`, `UploadPolicy`, `TrackerPayload`, `TrackerApi`, `TrackerUploadWorker`,
  `TrackerScheduler`, `Connectivity`, `AutoTracker`, `TrackerCrashHandler`, `ExitReasons`, `NotificationEntryActivity`, `TrackerNotifications`.
- Room: table `tracked_activities` (`id`, `seq`, `created_at`, `json`, `batch_id`), entity `db/TrackedActivity`, DAO `db/TrackedActivityDao`.
- Wiring: `FaceCamApp.onCreate` → `ActivityTracker.init`, `TrackerScheduler.schedulePeriodic`, `TrackerCrashHandler.install`, `AutoTracker` callbacks.

## Manual QA checklist (owner)
1. Debug build: use the app for a minute. `adb logcat -s ActivityTracker` shows each record as JSON.
2. Airplane mode: tap > 20 things, then turn internet on → within minutes the backend shows the batch. No UI appeared at any point.
3. Tap a recording notification while the app is in the background → the replay shows `app_foreground` with `source = notification`.
4. Force a test crash in a debug build → after relaunch, the replay shows a red `crash` row with the stack.
5. Sign in → the device appears under your email in super-admin → Activity.
