
RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 
One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

# Activity Tracker — app (cards A1–A5)
<!-- runner-config
REPOS=/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder
BRANCH=feat/ai-video-edit
-->

**How to run:** from `to-build/`: `MODEL=claude-haiku-5-5 ALLOWED_TOOLS="Read Write Edit Glob Grep Bash(git *) Bash(ls *) Bash(cat *) Bash(mkdir *) Bash(grep *) Bash(wc *) Bash(head *) Bash(tail *) Bash(sed *) Bash(python3 *) Bash(./gradlew *)" ./autonomous-agent-runner.sh`
(the runner's default tool list has no gradle, and these cards need `./gradlew` for their tests), or tell any Claude session "run `to-build/agent-runner.md`".

Owner gate: plan confirmed 2026-11-21. Build the cards **in order** (A1, A2, A3, A4.1 … A4.5, A5), one card per session.
Backend cards B1–B6: `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md`.

## Shared context (read before any card)

### Goal
Record every user activity and error, so the backend can replay each device's journey step by step (what was opened or tapped,
when, how long, what broke) and show which features are really used. Records are stored as JSON in Room and uploaded in batches
**every 5 days**, or sooner once **more than 20** are waiting, over **any** validated internet. **The user must never notice**:
no UI, no toast, no dialog, no notification, no jank, no exception escapes. Every failure is swallowed and retried later.

### Why this design
- Chosen: own tracker → Room buffer → WorkManager batch upload. It has no UI, survives offline and process death, sends a few KB a few times a week, and we own the data.
- Rejected: Firebase/PostHog/Mixpanel (no replay keyed by our device id, a third party), session-video SDKs (heavy, record the screen),
  live per-event sends (battery/data, lost offline), sending from `onStop` (dies with the process).
- Side effects accepted: light users show up up to 5 days late. Error messages/stacks may hold file names (disclosed in A5).
  Disk is capped at 3000 rows. Crash write waits ≤ 500 ms. Old WorkManager jobs are cancelled in A4.5.

### App facts
- Java only (no Kotlin, no Compose). minSdk 31, targetSdk 36. Base package `com.jovoc.facecampresentationrecorder` →
  path `app/src/main/java/com/jovoc/facecampresentationrecorder/` (written `P/` below). Tests: `app/src/test/java/com/jovoc/facecampresentationrecorder/` (`T/`).
- Tests are JUnit 4 + `org.json` + OkHttp MockWebServer. **No Robolectric, no Mockito.** `android.*` classes do not work in unit
  tests (no `returnDefaultValues`), so all logic under test lives in **pure Java classes** that never touch `android.*`
  (not even `android.util.Log`). Android glue stays thin and untested.
- Room 2.6.1 at `P/db/AppDatabase.java` (now version 8, hand-written `Migration` objects in `MIGRATIONS[]`, `exportSchema = false`).
  Migrations are tested in `T/db/AppDatabaseMigrationTest.java` with a Proxy that records `execSQL`.
- HTTP: `P/auth/ApiClient.java` (OkHttp 4.12), `send(method, path, json, headers)` → `ApiResponse` (`status`, `isSuccess()`).
  Base URL `BuildConfig.API_BASE_URL` (`https://social.nanybot.com/api`). Optional token: `SessionManager.get(ctx).repository().quietAccessToken()`
  (never prompts, never signs out; null when signed out).
- WorkManager 2.9.1. Application class: `P/FaceCamApp.java`. Launcher: `P/ui/StartupActivity.java`.
- Build rule (top of this file): one gradle command at a time, only `./gradlew --offline :app:assembleDebug` or `./gradlew --offline :app:testDebugUnitTest --tests '<pattern>'`.
- Commit per card: `feat(tracker): A<n> <what>`, a short body, and the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  Set the card's Status to ✅ in **Progress** (bottom) in the same commit. Never stage `app/src/debug/AndroidManifest.xml`.

### Code rules for the tracker
1. **Every public `ActivityTracker` method** body is `try { … } catch (Throwable ignored) { }`. It never throws and never returns null
   (`start()` returns `Tracked.NOOP` when it can't track).
2. Callers **never wait**. All disk and DB work runs on one single-thread executor `tracker` (daemon, `Thread.MIN_PRIORITY`,
   bounded queue `QUEUE_CAP`, `DiscardPolicy`). The only exception is `fatal()` (crash), which waits ≤ `CRASH_WAIT_MS`.
3. Nothing is shown to the user. Debug builds only: `if (BuildConfig.DEBUG) Log.d("ActivityTracker", json)` in the Android glue.
4. Null/empty safe everywhere: null name → the record is dropped; null description/meta → omitted; an empty list → nothing sent.

### Constants — `P/tracker/TrackerConfig.java` (all tunable here only)
```java
public final class TrackerConfig {
    public static final int  SEND_EVERY_DAYS = 5;          // time rule
    public static final int  SEND_WHEN_MORE_THAN = 20;     // volume rule: send when pending > this
    public static final long MIN_GAP_MS = 15L * 60 * 1000; // volume rule fires at most every 15 min (0 = no limit)
    public static final int  BATCH_SIZE = 50;              // records per request (backend max 50)
    public static final int  MAX_BATCHES_PER_RUN = 10;     // then a follow-up run
    public static final int  LOCAL_MAX_ROWS = 3000;        // device buffer cap
    public static final int  TRIM_CHUNK = 100;             // rows dropped at once when full
    public static final int  QUEUE_CAP = 500;              // writer queue
    public static final long CRASH_WAIT_MS = 500;
    public static final long SESSION_GAP_MS = 30L * 60 * 1000;
    public static final int  MAX_UPLOAD_ATTEMPTS = 6;
    private TrackerConfig() { }
}
```

### Contract (identical copy in the backend repo's `to-build/tasks.md`)
`POST {API_BASE_URL}/tracker/batches`. Headers: `Content-Type: application/json`, `Content-Encoding: gzip`, and when signed in `Authorization: Bearer <access>`.
```jsonc
{ "batchId": "<uuid>", "deviceId": "<uuid>", "sentAt": 1763712000000,
  "app": { "version": "1.0", "build": 4, "package": "com.jovoc.facecampresentationrecorder" },
  "device": { "os": "Android 14", "sdk": 34, "manufacturer": "Google", "model": "Pixel 7", "locale": "en-US" },
  "activities": [ {
     "id": "<uuid>", "seq": 1042, "session": "a1b2c3d4", "kind": "activity",   // or "error"
     "name": "click", "description": "edit topics",
     "startedAt": 1763711990000, "endedAt": 1763711992500,                     // endedAt null = instant
     "tz": 360, "appBuild": 4, "screen": "MainActivity", "source": "button",
     "meta": { "count": 3, "on": true },
     "error": { "type": "java.lang.NullPointerException", "message": "…", "stack": "…",
                "causes": [ { "type": "…", "message": "…" } ], "thread": "main", "fatal": true,
                "lastActivity": "click — record" } } ] }
```
Limits (the app cuts to these before storing): `name` `^[a-z0-9_]{1,40}$` · description 120 · screen 60 · source 40 · meta ≤ 20 keys `^[a-z0-9_]{1,32}$`,
values String(≤ 200)/Number/Boolean · error.type 120 · message 1000 · stack 8000 · causes ≤ 5 (message 300) · thread 60 · lastActivity 120 ·
session `^[A-Za-z0-9_-]{1,16}$` · activities per batch 1..50.
Responses: **2xx** → delete the batch rows, `lastSentAt = now` · **400/413/422** → delete the rows (poison) · **401** → resend once without
`Authorization` · **404/429/5xx/IOException** → keep the rows, retry later.

### Activity vocabulary
`app_open` (description `cold start`; source launcher/notification/recents/other; meta `first_run`, `notif`) · `app_foreground` (`came back`) ·
`app_background` span (`minimized`) · `screen` span (description = Activity simple name) · `click` / `double_click` / `long_press` (description =
what was tapped) · `toggle` (meta `on`) · `setting` (description = key, meta `value`) · `permission` (description = permission, meta `step`, `granted`) ·
`record` span · `crash` (error, fatal) · `app_exit` (from ApplicationExitInfo) · `tracker_overflow`. Other names are free (`topic_add`, `publish`, …).

### Old-call conversion table (used by A4.1–A4.4)
Old form: `ActivityLog.log("<action>", k1, v1, k2, v2, …)`. New: `ActivityTracker.log(name, description, Meta.of(...))`.
Import `com.jovoc.facecampresentationrecorder.tracker.ActivityTracker` and `…tracker.Meta`; remove the `…activitylog.ActivityLog` import.
| old action (meta) | new call |
|---|---|
| `screen_view`, `app_open`, `app_crash` | **delete** (A3 records these automatically) |
| `drawer_open` | `log("click", "drawer")` |
| `drawer_item` (item) | `log("click", "drawer " + item)` |
| `camera_flip` (to) | `log("click", "camera flip", Meta.of("to", to))` |
| `drawing_board_toggle` (on) / `menu_toggle` (visible) | `log("toggle", "drawing board" / "menu bar", Meta.of("on", x))` |
| `ai_captions_toggle`, `text_channel_toggle`, `channel_toggle`, `autopublish_toggle`, `topic_toggle` | `log("toggle", "<ai captions / text post channel / channel / auto publish / topic>", Meta.of(<same keys>))` |
| `record_tap` | `log("click", "record")` |
| `record_countdown_cancel` | `log("click", "cancel countdown")` |
| `record_blocked` (reason, type) | `log("record_blocked", reason, Meta.of("type", type))` |
| `record_start` (countdown_s, topics, …) in MainActivity | `log("click", "start recording", Meta.of(<same keys>))` (the `record` span itself lives in ScreenRecordService, see A4.3) |
| `record_stop` / `record_saved` | see A4.3 (they end the `record` span) |
| `record_error` (kind[, type]) | `ActivityTracker.error("record", kind, <Throwable in scope or null>, Meta.of(<other keys>))` |
| `permission_prompt` (perm) | `log("permission", perm, Meta.of("step", "prompt"))` |
| `permission_rationale` (action[, perms]) | `log("permission", "rationale", Meta.of("step", action, "perms", perms))` |
| `permission_result` (perm, granted) | `log("permission", perm, Meta.of("step", "result", "granted", granted))` |
| `setting_change` (key, value) | `log("setting", key, Meta.of("value", value))` |
| `topics_open` (count) | `log("click", "edit topics", Meta.of("count", count))` |
| `topic_add` / `topic_edit` (type) | `log("topic_add" / "topic_edit", type)` |
| `topic_delete` / `topics_delete_all` (count) | `log("topic_delete", null)` / `log("topic_delete_all", null, Meta.of("count", count))` |
| `topic_move` (dir) | `log("topic_move", dir)` |
| `topic_prev` / `topic_next` (recording) | `log("click", "previous topic" / "next topic", Meta.of("recording", r))` |
| `bgreplace_start` / `bgreplace_done` / `bgreplace_cancel` in BgReplaceService | a `bg_replace` span, see A4.3 |
| `bgreplace_cancel` in MainActivity | `log("click", "cancel background replace")` |
| `player_action` (action, …) / `player_close` | `log("player", action, Meta.of(<rest>))` / `log("click", "close player")` |
| `video_play` / `howto_video_play` | `log("click", "play video" / "how-to video", Meta.of(<same keys>))` |
| `paywall_view` (feature, plan) | `log("paywall", "shown", Meta.of("feature", f, "plan", p))` |
| `paywall_action` (action, feature) | `log("click", "paywall " + action, Meta.of("feature", feature))` |
| `plans_view` | `log("sheet", "plans", Meta.of(<same keys>))` |
| `drawing_tool` (tool) / `ink_tool` (tool[, on]) | `log("click", "drawing " + tool)` / `log("click", "ink " + tool, Meta.of("on", on))` |
| `account_row` (item) | `log("click", "account " + item)` |
| `publish_tap` (channels, mode) | `log("click", "publish", Meta.of("channels", c, "mode", m))` |
| `publish_result` ok=true / ok=false (kind, mode) | `log("publish", "done", Meta.of("mode", m))` / `error("publish", kind, null, Meta.of("mode", m))` |
| `ai_captions_result` (state, wait_ms, mode) | `log("ai_captions", state, Meta.of("wait_ms", w, "mode", m))` |
| `undo_choice` (choice) | `log("click", "undo " + choice)` |
| `signin_start` / `signin_code_requested` / `signin_success` | `log("signin", "start" / "code requested" / "success")` |
| `signin_fail` (kind…) | `error("signin", "failed", <Throwable in scope or null>, Meta.of(<same keys>))` |
| `signout` / `session_ended` (…) | `log("signout", null)` / `log("session_ended", <reason value or null>, Meta.of(<rest>))` |
| `entitlement_change` (from, to) | `log("plan_change", from + " → " + to, Meta.of("from", from, "to", to))` |
| `workspace_switch` / `brand_row` | `log("click", "switch brand" / "brand row", Meta.of(<same keys>))` |
| `web_link` (host) | `log("click", "web link", Meta.of("host", host))` |
| `device_revoke`, `contact_email`, `contact_copy`, `account_delete`, `onboarding_skip` | `log("click", "revoke device" / "contact email" / "copy contact" / "delete account" / "skip onboarding", Meta.of(<same keys>))` |
| anything else | `log("<same action>", null, Meta.of(<same key/value pairs>))` |
Extra rule: in every `catch` block of the converted file that already reports a failure to the user or to the old log (Toast, error dialog, `record_error`),
make sure there is an `ActivityTracker.error("<area>", "<what failed>", e)` with the caught exception. **Never** add tracking inside tight loops
(`onDraw`, touch-move, the per-frame render). Track the gesture's end instead.

---

## A1 — Tracker core

**Read first:** Shared context; `P/db/AppDatabase.java`; `T/db/AppDatabaseMigrationTest.java`; `app/src/main/res/xml/data_extraction_rules.xml`.

**Create in `P/tracker/`:**
1. `TrackerConfig.java`: exactly as in Shared context.
2. `Meta.java` (pure):
   ```java
   public final class Meta {
       final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
       /** Meta.of("count", 3, "on", true). Odd tail, null keys and null values are skipped. */
       public static Meta of(Object... keyValues) { Meta m = new Meta(); if (keyValues != null) for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]); return m; }
       public Meta put(String key, Object value) { /* k = TrackedRecord.cleanKey(key); skip null k / null value / when size == 20;
           Boolean, Integer, Long, Double, Float kept (non-finite doubles → skipped); everything else → TrackedRecord.cut(String.valueOf(value), 200) */ return this; }
       public Meta merge(Meta other) { /* put each entry of other (null-safe) */ return this; }
       boolean isEmpty() { return values.isEmpty(); }
       JSONObject toJson() throws JSONException { /* null when empty */ }
   }
   ```
3. `ErrorInfo.java` (pure): `static ErrorInfo from(Throwable t, boolean fatal, String thread, String lastActivity)`. Fields `type`
   (`t.getClass().getName()`, cut 120), `message` (`t.getMessage()` cut 1000, null allowed), `stack`, `causes`, `thread` (cut 60), `fatal`, `lastActivity` (cut 120).
   Stack text, built **by hand** (never `android.util.Log.getStackTraceString`):
   ```java
   StringBuilder sb = new StringBuilder(); Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
   for (Throwable c = t; c != null && seen.add(c) && sb.length() < 8000; c = c.getCause()) {
       if (c != t) sb.append("Caused by: ").append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
       for (StackTraceElement f : c.getStackTrace()) { sb.append("at ").append(f).append('\n'); if (sb.length() >= 8000) break; }
   }
   stack = TrackedRecord.cut(sb.toString(), 8000);
   ```
   `causes`: up to 5 of `t.getCause()` chain (cycle-safe as above), each `{type, message cut 300}`. `JSONObject toJson()`. A null `t` → `from` returns null.
4. `TrackedRecord.java` (pure, the record + its JSON):
   - fields: `String id` (UUID), `long seq`, `String session`, `String kind` ("activity"|"error"), `String name`, `String description`,
     `long startedAt`, `Long endedAt`, `int tz`, `int appBuild`, `String screen`, `String source`, `Meta meta`, `ErrorInfo error`.
   - `static String cleanName(String raw)`: null → null; `raw.trim().toLowerCase(Locale.ROOT)`, `[\s\-]+` → `_`, remove `[^a-z0-9_]`, cut 40; empty → null.
     (So callers may write `"double click"` → `double_click`, `"app open"` → `app_open`.)
   - `static String cleanKey(String raw)`: the same but cut 32.
   - `static String cut(String s, int max)`: null/blank → null, trim, then `substring(0, max)` when longer.
   - `String toJson()`: the contract's activity object. Omit `endedAt`/`description`/`screen`/`source`/`meta`/`error` when null. Throws nothing (catch `JSONException` → return null).
5. `SessionClock.java` (pure): `interface Store { String sessionId(); long lastActiveAt(); void save(String id, long at); }`.
   `String current(long now)`: when `sessionId()` is null or `now − lastActiveAt() > SESSION_GAP_MS` (or `now < lastActiveAt()`), make a new id (8 hex chars from
   `UUID.randomUUID()`). Then `save(id, now)` and return it. The constructor takes `Store` and a `Supplier<String>` for ids (tests pass a fixed one).
6. `DeviceId.java`: `static String derive(byte[] raw)` (pure): SHA-256 of `"facecam-tracker-v1"` bytes + raw, first 16 bytes, then
   `b[6] = (b[6] & 0x0f) | 0x50; b[8] = (b[8] & 0x3f) | 0x80;` and format as a UUID string. `static String get(Context)`: cached
   `TrackerPrefs.deviceId()`; else read `new MediaDrm(new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL)).getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)`
   in try/catch (`close()` it in `finally`); on any failure `UUID.randomUUID().toString()`. Save and return. Call it **only off the main thread** (worker / tracker thread).
7. `TrackerPrefs.java`: SharedPreferences `tracker_prefs`. Getters/setters: `deviceId`, `lastSentAt` (when 0: set it to now and return now, so
   the 5-day clock starts at first run), `nextSeq` (`long reserveSeq()` returns the value and stores value + 1 with `apply()`), `sessionId` + `lastActiveAt`
   (implements `SessionClock.Store`), `lastExitCheck`, `backgroundSince`, `firstOpenDone`.
8. `TrackerStore.java`: the writer thread + Room. `static void write(Context, TrackedRecord r, Runnable afterStored)` and
   `static boolean writeAndWait(Context, TrackedRecord r, long maxWaitMs)`. On the thread: `json = r.toJson()`; if null, return. When
   `dao.count() >= LOCAL_MAX_ROWS`, run `dao.deleteOldest(TRIM_CHUNK)` and insert a `tracker_overflow` record (meta `dropped`). Insert the row.
   Notify the listener (`TrackerStore.Listener.onStored(int pending)`, set by A2). Catch `Throwable` around everything.
9. `Tracked.java`: a span handle. `public static final Tracked NOOP`. `public void end()` and `public void end(Meta extra)`: the first call only
   (`AtomicBoolean`) sets `endedAt = now`, merges `extra`, and hands the record to `ActivityTracker` to store. Later calls do nothing.
10. `ActivityTracker.java`: the static facade.
    ```java
    public static void init(Context context)                       // once from FaceCamApp; safe to call again
    public static void log(String name)                            // = log(name, null, null)
    public static void log(String name, String description)
    public static void log(String name, String description, Meta meta)
    public static Tracked start(String name, String description)   // span; never null
    public static Tracked start(String name, String description, Meta meta)
    public static void error(String name, String description, Throwable t)
    public static void error(String name, String description, Throwable t, Meta meta)
    static void fatal(Throwable t, Thread thread)                  // A3 crash handler: also ends every open span (meta ended_by=crash), waits ≤ CRASH_WAIT_MS
    static void setScreen(String screen)                           // A3: current screen, stamped on every record
    static void setEntry(String source, String notif)              // A3: pending source for the next app_open / app_foreground
    static void onStored(TrackerStore.Listener l)                  // A2
    ```
    Building a record: `id = UUID`, `seq = prefs.reserveSeq()`, `session = clock.current(now)`, `startedAt = now`,
    `tz = TimeZone.getDefault().getOffset(now) / 60000`, `appBuild = BuildConfig.VERSION_CODE`, `screen = currentScreen`,
    `kind = "activity"` (or `"error"` with `ErrorInfo.from(t, false, Thread.currentThread().getName(), lastActivity)`. A null `t` still makes an error record without the `error` block).
    Keep `volatile String lastActivity = name + (description == null ? "" : " — " + description)` for every non-`screen` record.
    Keep open spans in `Collections.newSetFromMap(new ConcurrentHashMap<>())` so `fatal` can end them. Every public method follows Code rule 1.
    Before `init`, every method is a no-op and `start` returns `Tracked.NOOP`.

**Room:**
- `P/db/TrackedActivity.java`: `@Entity(tableName = "tracked_activities")`, fields `@PrimaryKey @NonNull public String id;`,
  `public long seq;`, `@ColumnInfo(name = "created_at") public long createdAt;`, `@NonNull public String json;`, `@ColumnInfo(name = "batch_id") public String batchId;`.
- `P/db/TrackedActivityDao.java`:
  ```java
  @Insert(onConflict = OnConflictStrategy.IGNORE) void insert(TrackedActivity row);
  @Query("SELECT COUNT(*) FROM tracked_activities") int count();
  @Query("DELETE FROM tracked_activities WHERE id IN (SELECT id FROM tracked_activities WHERE batch_id IS NULL ORDER BY created_at LIMIT :n)") int deleteOldest(int n);
  @Query("SELECT batch_id FROM tracked_activities WHERE batch_id IS NOT NULL LIMIT 1") String openBatchId();
  @Query("UPDATE tracked_activities SET batch_id = :batchId WHERE id IN (SELECT id FROM tracked_activities WHERE batch_id IS NULL ORDER BY created_at LIMIT :n)") int assignBatch(String batchId, int n);
  @Query("SELECT * FROM tracked_activities WHERE batch_id = :batchId ORDER BY created_at") List<TrackedActivity> inBatch(String batchId);
  @Query("DELETE FROM tracked_activities WHERE batch_id = :batchId") int deleteBatch(String batchId);
  ```
- `AppDatabase`: add `TrackedActivity.class` to `entities`, `public abstract TrackedActivityDao trackedActivityDao();`, version **9**, and
  ```java
  /** The table of {@link TrackedActivity}; kept as text so a test can check what a migration runs. */
  static final String CREATE_TRACKED_ACTIVITIES = "CREATE TABLE IF NOT EXISTS `tracked_activities` ("
          + "`id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, "
          + "`json` TEXT NOT NULL, `batch_id` TEXT, PRIMARY KEY(`id`))";
  // Activity tracker buffer; every other table is untouched.
  static final Migration MIGRATION_8_9 = new Migration(8, 9) { public void migrate(@NonNull SupportSQLiteDatabase db) { db.execSQL(CREATE_TRACKED_ACTIVITIES); } };
  ```
  Append it to `MIGRATIONS`. **Do not touch** the old `activity_events` entity/table yet (A4.5 removes it).
- `data_extraction_rules.xml`: add `<exclude domain="sharedpref" path="tracker_prefs.xml" />` in **both** sections (next to the existing excludes), with a comment saying
  the device id must not travel to another phone.

**Tests** (`T/tracker/`, pure JUnit):
- `MetaTest`: `of("a",1,"b")` keeps only a. Null value skipped. A 300-char string cut to 200. The 21st key ignored. Key `"Topic Type"` → `topic_type`. A nested object → its String form. `NaN` skipped.
- `TrackedRecordTest`: `cleanName("double click")` = `double_click`; `"  App-Open "` = `app_open`; `"!!!"` → null; 50 chars → 40. `toJson` of an instant record has no `endedAt`.
  A span has both times. `kind` error with an `ErrorInfo` has an `error` object.
- `ErrorInfoTest`: an `IllegalStateException` wrapping an `IOException` → type = the full class name, 1 cause, the stack contains `Caused by: java.io.IOException`.
  A 5000-char message cut to 1000. A self-referencing cause chain terminates. A null throwable → null.
- `SessionClockTest`: same id within 30 min, a new id after 30 min + 1 ms, a new id when the clock goes back.
- `DeviceIdTest`: `derive` is deterministic, has version nibble `5` and variant `8..b`, and gives different ids for different raw bytes.
- `T/db/AppDatabaseMigrationTest`: update the version test to **9 / 8 steps**. Add `migration8to9_createsTrackedActivitiesOnly` (1 statement, starts with
  `CREATE TABLE IF NOT EXISTS \`tracked_activities\``, contains the 5 columns, no other table name).

**Done when:** `./gradlew --offline :app:testDebugUnitTest --tests '*tracker*'`, then `--tests '*AppDatabaseMigrationTest*'`, then `:app:assembleDebug` all pass.
Commit `feat(tracker): A1 tracker core, records and local buffer`.

---

## A2 — Upload (pairs with backend B2)

**Read first:** Shared context (Contract, Constants); `P/auth/ApiClient.java`; `P/auth/ApiResponse.java`; the A1 classes.

1. **`ApiClient.sendGzipJson(String path, String json, Map<String,String> headers) throws IOException`**: a new method next to `send`.
   ```java
   ByteArrayOutputStream bos = new ByteArrayOutputStream();
   try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(json.getBytes(StandardCharsets.UTF_8)); }
   Request.Builder rb = new Request.Builder().url(baseUrl + path).header("Accept", "application/json").header("Content-Encoding", "gzip");
   if (headers != null) for (Map.Entry<String,String> h : headers.entrySet()) rb.header(h.getKey(), h.getValue());
   rb.post(RequestBody.create(bos.toByteArray(), JSON));
   try (Response r = http.newCall(rb.build()).execute()) { /* build ApiResponse exactly like send() does */ }
   ```
2. **`P/tracker/UploadPolicy.java`** (pure):
   ```java
   static final long DAY_MS = 24L * 60 * 60 * 1000;
   /** Whether a send is due now. pending ≤ 0 → never. */
   static boolean isDue(int pending, long lastSentAt, long now) {
       if (pending <= 0) return false;
       long since = now - lastSentAt;
       if (since < 0) return true;                                   // clock went back: send once, which resets it
       if (pending > TrackerConfig.SEND_WHEN_MORE_THAN && since >= TrackerConfig.MIN_GAP_MS) return true;
       return since >= TrackerConfig.SEND_EVERY_DAYS * DAY_MS;
   }
   /** ms to wait before the volume rule may fire (0 when it may fire now). */
   static long delayForVolume(long lastSentAt, long now) { long w = lastSentAt + TrackerConfig.MIN_GAP_MS - now; return (w < 0 || w > TrackerConfig.MIN_GAP_MS) ? 0 : w; }
   ```
3. **`P/tracker/TrackerPayload.java`** (pure): `static String build(String batchId, String deviceId, long sentAt, AppInfo app, DeviceInfo device, List<String> rowJsons)`.
   It parses each row's JSON with `new JSONObject(s)` and skips unreadable ones. When **none** remain, it returns null (the caller drops the batch). `AppInfo{version, build, pkg}` and
   `DeviceInfo{os, sdk, manufacturer, model, locale}` are plain value classes filled by the worker from `BuildConfig`/`Build`/`Locale.getDefault().toLanguageTag()`.
4. **`P/tracker/TrackerApi.java`**: `enum Outcome { SENT, DROP, RETRY }`, constructor `(ApiClient client, Supplier<String> token)`, and `Outcome send(String body)`:
   ```java
   String t = token == null ? null : token.get();
   ApiResponse r = client.sendGzipJson(PATH, body, t == null ? null : Collections.singletonMap("Authorization", "Bearer " + t));
   if (r.status == 401 && t != null) r = client.sendGzipJson(PATH, body, null);       // resend once anonymously
   if (r.isSuccess()) return SENT;
   if (r.status == 400 || r.status == 413 || r.status == 422) return DROP;
   return RETRY;                                                                       // 404, 429, 5xx
   ```
   with `catch (IOException e) → RETRY` and `catch (RuntimeException e) → RETRY`. `PATH = "/tracker/batches"`.
5. **`P/tracker/Connectivity.java`**: `static boolean hasInternet(Context c)`. `ConnectivityManager` → `getActiveNetwork()` → `getNetworkCapabilities(n)`.
   True only when `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED` are present. Any exception → false. (The permission `ACCESS_NETWORK_STATE` is already in the manifest.)
6. **`P/tracker/TrackerUploadWorker.java`** (`extends Worker`), `KEY_FORCE = "force"`:
   ```java
   public Result doWork() {
     try {
       Context c = getApplicationContext();
       if (!Connectivity.hasInternet(c)) return again();                  // "check internet before sending"
       TrackedActivityDao dao = AppDatabase.getInstance(c).trackedActivityDao();
       TrackerPrefs prefs = new TrackerPrefs(c);
       boolean force = getInputData().getBoolean(KEY_FORCE, false);
       if (!force && !UploadPolicy.isDue(dao.count(), prefs.lastSentAt(), System.currentTimeMillis())) return Result.success();
       TrackerApi api = new TrackerApi(new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEBUG),
               () -> SessionManager.get(c).repository().quietAccessToken());
       String deviceId = DeviceId.get(c);
       for (int i = 0; i < TrackerConfig.MAX_BATCHES_PER_RUN; i++) {
         String batchId = dao.openBatchId();                                // an unconfirmed batch is resent unchanged
         if (batchId == null) { batchId = UUID.randomUUID().toString(); if (dao.assignBatch(batchId, TrackerConfig.BATCH_SIZE) == 0) return Result.success(); }
         List<TrackedActivity> rows = dao.inBatch(batchId);
         if (rows == null || rows.isEmpty()) continue;
         String body = TrackerPayload.build(batchId, deviceId, System.currentTimeMillis(), appInfo(), deviceInfo(), jsonsOf(rows));
         if (body == null) { dao.deleteBatch(batchId); continue; }
         switch (api.send(body)) {
           case SENT: dao.deleteBatch(batchId); prefs.setLastSentAt(System.currentTimeMillis()); break;
           case DROP: dao.deleteBatch(batchId); break;
           default:   return again();
         }
       }
       if (dao.count() > 0) TrackerScheduler.drainNow(c);                 // more left: a follow-up run
       return Result.success();
     } catch (Throwable t) { return again(); }
   }
   private Result again() { return getRunAttemptCount() >= TrackerConfig.MAX_UPLOAD_ATTEMPTS ? Result.success() : Result.retry(); }
   ```
   (`Result.success()` after the last attempt is on purpose: WorkManager stops retrying, and the next trigger or the daily job tries again.)
7. **`P/tracker/TrackerScheduler.java`** implements `TrackerStore.Listener`:
   - `static void schedulePeriodic(Context)`: `PeriodicWorkRequest` every **24 h**, `NetworkType.CONNECTED`, backoff exponential 10 min,
     `enqueueUniquePeriodicWork("tracker-upload-periodic", KEEP, …)`. This carries the 5-day rule.
   - `onStored(int pending)`: when `pending > SEND_WHEN_MORE_THAN` and the last check was ≥ 60 s ago, enqueue a one-time run with
     `setInitialDelay(UploadPolicy.delayForVolume(lastSentAt, now))`, CONNECTED, unique `"tracker-upload-now"`, `ExistingWorkPolicy.KEEP`.
   - `static void drainNow(Context)`: a one-time run, input `force = true`, unique `"tracker-upload-drain"`, `KEEP`, CONNECTED.
   - Everything in try/catch (scheduling is best effort).
8. **Wire it** in `FaceCamApp.onCreate`, inside a new `try { } catch (Throwable ignored) { }` block placed **before** the old activity-log block:
   `ActivityTracker.init(this); ActivityTracker.onStored(new TrackerScheduler(this)); TrackerScheduler.schedulePeriodic(this);`

**Tests** (`T/tracker/`):
- `UploadPolicyTest`: pending 0 → false at any time. 20 pending, 1 h after send → false. 21 pending, 1 h → true. 21 pending, 5 min → false (gap). 1 pending at
  5 days − 1 ms → false, at exactly 5 days → true. A clock 1 h behind lastSentAt → true. `delayForVolume`: 5 min after send → 10 min; 20 min after → 0.
- `TrackerPayloadTest`: 2 good + 1 broken row → `activities` has 2, the envelope fields are present, `sentAt` is set. All broken → null. An empty list → null.
- `TrackerApiTest` (MockWebServer + `new ApiClient(server.url("/").toString(), new OkHttpClient(), true)`): 200 → SENT, and the request has
  `Content-Encoding: gzip`, a gunzipped body equal to the input, and `Authorization: Bearer t`. 400, 413 and 422 → DROP. 401 then 200 → SENT, the second request has no
  Authorization. 503 → RETRY. 404 → RETRY. Server shut down (IOException) → RETRY. Token supplier returns null → no Authorization header.

**Done when:** these tests pass and `assembleDebug` succeeds. Manual check after B2 is deployed: airplane mode, tap > 20 things, internet on → the batch appears in super-admin → Activity within minutes, with no UI on the phone.
Commit `feat(tracker): A2 batched silent upload`.

---

## A3 — Auto-tracking: app open + source, foreground/background, screens, crash, exit reasons, notification entry

**Read first:** Shared context; `P/FaceCamApp.java` (`ScreenTracker`); `P/ui/StartupActivity.java`; the A1/A2 classes;
the notification builders: `P/service/ScreenRecordService.java` (≈ L168 and L194-200), `P/service/BgReplaceService.java` (≈ L486-491),
`P/aiedit/flow/AiEditService.java` (≈ L403-408), `P/publish/AutoPublishNotifier.java` (≈ L78-103: the content intent + "Set up" + "Top up" actions).

1. **`P/tracker/AppOpenSource.java`** (pure): `static String of(boolean fromNotification, String action, boolean hasLauncherCategory, int flags)`
   gives `"notification"` | `"recents"` (when `(flags & 0x00100000) != 0`, i.e. `Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. Use the literal in the pure class and
   note it in a comment) | `"launcher"` (`"android.intent.action.MAIN"` + launcher category) | `"other"`.
2. **`P/tracker/AutoTracker.java`** implements `Application.ActivityLifecycleCallbacks`. Register it in `FaceCamApp` right after `ActivityTracker.init`.
   - Fields: `int started; boolean recreating; boolean processFirst = true; Tracked background; Tracked rotatingSpan; Map<Activity, Tracked> screens = new WeakHashMap<>()`.
     All callbacks run on the main thread, so no locking is needed. Wrap each callback body in try/catch (Code rule 1).
   - `onActivityStarted(a)`:
     - If `recreating`: set it false, `started++`, `if (rotatingSpan != null) screens.put(a, rotatingSpan); rotatingSpan = null;`, return
       (a rotation is the same visit: the span moves to the recreated Activity object).
     - If `started++ == 0` (coming to the front):
       - `source` = the pending entry from `ActivityTracker.setEntry` (then clear it), else `AppOpenSource.of(false, i.getAction(), i.hasCategory(Intent.CATEGORY_LAUNCHER), i.getFlags())` with `i = a.getIntent()` (null-safe).
       - If `processFirst`: `log("app_open", "cold start", Meta.of("first_run", !prefs.firstOpenDone(), "notif", notif))` with `source` as the record's source
         (add `static void logWithSource(String name, String description, String source, Meta m)` to ActivityTracker), then `prefs.setFirstOpenDone()` and `processFirst = false`.
       - Else: `if (background != null) background.end(); background = null;` then `logWithSource("app_foreground", "came back", source, meta)`.
       - Always `prefs.setBackgroundSince(0)`.
     - If `a` is not `StartupActivity` or `NotificationEntryActivity`: `screens.put(a, ActivityTracker.start("screen", a.getClass().getSimpleName()))` and `ActivityTracker.setScreen(simpleName)`.
   - `onActivityStopped(a)`:
     - `started--`.
     - If `a.isChangingConfigurations()`: `recreating = true; rotatingSpan = screens.remove(a);` and return (the span stays open).
     - `Tracked s = screens.remove(a); if (s != null) s.end();`
     - If `started == 0`: `background = ActivityTracker.start("app_background", "minimized"); prefs.setBackgroundSince(now)`.
   - **Process-death case**, in `AutoTracker`'s constructor: when `prefs.backgroundSince() > 0`, store one `app_background` record with that start, `endedAt = null`
     and meta `process_ended=true` (add a package-private `ActivityTracker.logAt(name, description, startedAt, Meta)`), then clear it.
3. **Remove from `FaceCamApp.ScreenTracker`** only the `ActivityLog.*` calls (`appForeground`, `appBackground`, `screen_view`). Its other jobs (entitlement refresh,
   billing, plan notice) stay. **Remove** the `ActivityLog.log("app_open", …)` line from `StartupActivity`.
4. **`P/tracker/TrackerCrashHandler.java`**: `static void install()` wraps `Thread.getDefaultUncaughtExceptionHandler()`. In `uncaughtException(thread, e)`:
   `try { ActivityTracker.fatal(e, thread); } catch (Throwable ignored) {}`, then `previous.uncaughtException(thread, e)` when previous is not null.
   `fatal` makes the error record `name = "crash"`, `description = "uncaught"`, `ErrorInfo.from(e, true, thread.getName(), lastActivity)`, and ends open spans with
   `Meta.of("ended_by", "crash")`, using `TrackerStore.writeAndWait(..., CRASH_WAIT_MS)`. Install it in `FaceCamApp` in the tracker block. Leave the old `CrashLogger.install()`
   in place for now (A4.5 deletes it). Install the new handler **after** it, so ours runs first and then calls the old one.
5. **`P/tracker/ExitReasons.java`**: `static void recordNew(Context c, TrackerPrefs prefs)`, run once on the tracker thread at init.
   ```java
   ActivityManager am = c.getSystemService(ActivityManager.class);
   List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 10);
   long last = prefs.lastExitCheck();
   if (last == 0) { prefs.setLastExitCheck(System.currentTimeMillis()); return; }   // first run: don't import old history
   long newest = last;
   for (ApplicationExitInfo x : list) {
     if (x.getTimestamp() <= last) continue;
     newest = Math.max(newest, x.getTimestamp());
     String reason = reasonName(x.getReason());   // ANR, CRASH, CRASH_NATIVE, LOW_MEMORY, USER_REQUESTED, USER_STOPPED, EXIT_SELF, SIGNALED,
                                                  // EXCESSIVE_RESOURCE_USAGE, INITIALIZATION_FAILURE, PERMISSION_CHANGE, DEPENDENCY_DIED, OTHER, UNKNOWN
     Meta m = Meta.of("reason", reason, "status", x.getStatus(), "importance", x.getImportance(), "pss_kb", x.getPss(), "rss_kb", x.getRss(), "detail", x.getDescription());
     boolean isError = reason.equals("ANR") || reason.equals("CRASH_NATIVE") || reason.equals("INITIALIZATION_FAILURE");
     // ANR: read the first 8000 chars of x.getTraceInputStream() (may be null) as the error stack (ErrorInfo with type "ANR")
     // store with startedAt = x.getTimestamp(), name "app_exit", description = reason, kind error/activity
   }
   prefs.setLastExitCheck(newest);
   ```
   Put `reasonName(int)` in a pure class `ExitReasonNames` with the `ApplicationExitInfo.REASON_*` int values as literals (they are fixed API constants: ANR 6,
   CRASH 4, CRASH_NATIVE 5, DEPENDENCY_DIED 12, EXCESSIVE_RESOURCE_USAGE 9, EXIT_SELF 1, INITIALIZATION_FAILURE 7, LOW_MEMORY 3, OTHER 13, PERMISSION_CHANGE 8,
   SIGNALED 2, UNKNOWN 0, USER_REQUESTED 10, USER_STOPPED 11) so it can be unit-tested.
   Wrap everything in try/catch.
6. **Notification entry.** `P/tracker/NotificationEntryActivity.java` (extends `Activity`, not AppCompat):
   ```java
   protected void onCreate(Bundle b) {
     super.onCreate(b);
     try {
       ActivityTracker.setEntry("notification", getIntent().getStringExtra(EXTRA_NOTIF));
       @SuppressWarnings("deprecation") Intent target = getIntent().getParcelableExtra(EXTRA_TARGET);
       if (target != null) { target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(target); }   // the trampoline has its own task affinity
     } catch (Throwable ignored) { }
     finish();
     overridePendingTransition(0, 0);
   }
   ```
   Manifest: `<activity android:name=".tracker.NotificationEntryActivity" android:exported="false" android:excludeFromRecents="true" android:noHistory="true"
   android:taskAffinity="" android:theme="@android:style/Theme.Translucent.NoTitleBar" />`.
   `P/tracker/TrackerNotifications.java`: `static Intent wrap(Context c, Intent target, String notif)` returns
   `new Intent(c, NotificationEntryActivity.class).putExtra(EXTRA_TARGET, target).putExtra(EXTRA_NOTIF, notif).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`.
   A null target → return the target unchanged (null).
   At each notification site, wrap the Intent passed to `PendingIntent.getActivity(...)`. **Keep the request codes and flags exactly as they are.** Names:
   `ScreenRecordService` → `"recording"`, `BgReplaceService` → `"bg_replace"`, `AiEditService` → `"ai_edit"`, `AutoPublishNotifier` content → `"auto_publish"`,
   its actions → `"auto_publish_setup"` / `"auto_publish_topup"`. Broadcast PendingIntents (Stop action, `UndoNotifier`) are **not** wrapped.
   The trampoline's own start/stop must not count as a screen (step 2).

**Tests:** `AppOpenSourceTest` (all four outcomes; notification wins over the flags) and `ExitReasonNamesTest` (6 → ANR, 5 → CRASH_NATIVE, 99 → UNKNOWN).
**Done when:** the tests pass, `assembleDebug` succeeds, and a debug run shows (logcat tag `ActivityTracker`) app_open → screen → app_background → app_foreground with source.
Commit `feat(tracker): A3 automatic app, screen, crash and exit tracking`.

---

## A4.1 — Convert calls in `MainActivity`
**Read first:** Shared context → *Old-call conversion table*; `P/MainActivity.java` (≈ 38 calls; list them with `grep -n "ActivityLog" P/MainActivity.java`).
Convert every call by the table. For `record_error` near L351 pass the caught `t`. Make sure every `catch` that shows a failure Toast also has an
`ActivityTracker.error(...)` (Extra rule). Remove the `activitylog` import.
**Done when:** `grep -n "ActivityLog" P/MainActivity.java` finds nothing and `assembleDebug` succeeds. Commit `feat(tracker): A4.1 MainActivity on ActivityTracker`.

## A4.2 — Convert calls in `P/ui/*`
Files (from `grep -rln "ActivityLog" P/ui`): SettingsActivity, VideoPlayerDialog, DrawingBoardController, AutoPublishActivity, PaywallSheet,
PublishActivity, AccountActivity, SlideInkController, PlansSheet, OnboardingActivity, AiEditPanel, DevicesActivity, ContactActivity, YouTubeDialog,
WorkspaceActivity, WebLinks, SignInActivity, EditorLauncher, BrandRowBinder (StartupActivity was done in A3). The same rules as A4.1.
**Done when:** `grep -rn "ActivityLog" P/ui` finds nothing and `assembleDebug` succeeds. Commit `feat(tracker): A4.2 screens on ActivityTracker`.

## A4.3 — Convert calls in services, publish, adapter, SavedRecordings
Files: `P/service/ScreenRecordService.java`, `P/service/BgReplaceService.java`, `P/publish/PublishPipeline.java`, `P/adapter/RecordingsAdapter.java`, `P/SavedRecordingsActivity.java`.
- **`record` span** in `ScreenRecordService`: add the field `private Tracked recordSpan = Tracked.NOOP;`. Where the recorder actually starts (where the service's
  recording state becomes true), set `recordSpan = ActivityTracker.start("record", "screen recording");`. At the old `record_stop` site (≈ L403) call
  `recordSpan.end(Meta.of("stop_source", source))`. Make the old `record_saved` (≈ L411) `log("record_saved", "file saved", Meta.of("duration_ms", d, "size_mb", mb))`.
  `record_error` → `error("record", kind, t or null)`, and also end the span with `Meta.of("failed", true)`.
- **`bg_replace` span** in `BgReplaceService`: `start("bg_replace", "background replace")` at the old `bgreplace_start`. `end(Meta.of("ok", true))` at `bgreplace_done`,
  `end(Meta.of("cancelled", true))` at `bgreplace_cancel`. The failure (`record_error` kind `bgreplace_failed`) → `error("bg_replace", "failed", <throwable if any>)` + `end(Meta.of("failed", true))`.
- `PublishPipeline` (publish_result, ai_captions_result) and the adapter/SavedRecordings video actions go by the table.
**Done when:** `grep -rn "ActivityLog" P/service P/publish P/adapter P/SavedRecordingsActivity.java` finds nothing and `assembleDebug` succeeds.
Commit `feat(tracker): A4.3 services and publish on ActivityTracker`.

## A4.4 — Convert calls in auth, entitlements, the rest
Files: `P/auth/SessionManager.java` (signin_*, signout, session_ended), `P/entitlements/EntitlementsRefresher.java` (entitlement_change), and anything
else `grep -rln "ActivityLog\.log" P | grep -v activitylog/` still lists. **Never** put the email, a token or a code into meta or description.
**Done when:** that grep lists nothing outside `P/activitylog/` and `FaceCamApp.java`. Commit `feat(tracker): A4.4 sign-in and plans on ActivityTracker`.

## A4.5 — Delete the old activity-log system
1. Delete with **`git rm`** (plain `rm` is blocked for runner sessions): `git rm -r P/activitylog T/activitylog P/db/ActivityEvent.java P/db/ActivityEventDao.java` (write the full paths).
2. `FaceCamApp`: remove the old block (`ActivityLog.init`, `setListener`, `ActivityLogScheduler.schedulePeriodic`, `CrashLogger.install`) and its imports. Fix the class comment.
3. `ActivityTracker.init`: once per process, on the tracker thread:
   `WorkManager.getInstance(c).cancelUniqueWork("activity-log-periodic")`, the same for `"activity-log-count"` and `"activity-log-drain"`, and
   `c.deleteSharedPreferences("activity_log_prefs")`, each inside its own try/catch. (Otherwise WorkManager keeps trying to start the deleted worker class.)
4. `AppDatabase`: remove `ActivityEvent.class` and `activityEventDao()`. Keep `MIGRATION_6_7` and `CREATE_ACTIVITY_EVENTS` (migration history), but change that
   constant's javadoc `{@link ActivityEvent}` to plain text ("the old activity-log table, dropped in 9→10"). Add
   `MIGRATION_9_10` → `db.execSQL("DROP TABLE IF EXISTS \`activity_events\`")`, version **10**, and append it to `MIGRATIONS`.
5. `AppDatabaseMigrationTest`: version **10 / 9 steps**, plus `migration9to10_dropsOnlyTheOldActivityTable`.
6. `data_extraction_rules.xml`: remove the two `activity_log_prefs.xml` excludes and their comment (the `tracker_prefs.xml` ones stay).
7. `P/aiedit/capture/EventLog.java` L14 comment: `ActivityLog` → `ActivityTracker`.
**Done when:** `grep -rn "activitylog\|ActivityLog\b\|ActivityEvent\|CrashLogger" app/src` finds nothing, and the migration test and `assembleDebug` pass.
Commit `feat(tracker): A4.5 remove the old activity log`.

## A5 — Privacy disclosure
`git rm ACTIVITY_LOG_PRIVACY.md`. Write `ACTIVITY_TRACKER_PRIVACY.md` (for the privacy policy and the Play Data safety form). It must say:
- **Collected:** in-app actions with start/end time and timezone (app opened and from where, screens and time on them, taps, toggles, settings,
  permission prompts and answers, recording start/stop/size, topics added/edited/deleted (type only), publish taps and results, sign-in steps, plan changes,
  paywall views), errors and crashes **including the error message and stack trace** (they may contain file names), why the app last closed (ANR, crash, low memory,
  from Android), the device id (a SHA-256 of the MediaDrm id with an app salt, else random; not the advertising id), and device info
  (Android version, SDK, manufacturer, model, locale, app version).
- **Linked to the account** when signed in (the upload carries the sign-in token).
- **Never collected:** email in the records, tokens, passwords, topic/caption text, drawings, file contents, contacts, location, screen/camera content.
- **How:** stored on the phone first (≤ 3000 records), sent over HTTPS in gzip batches every 5 days or sooner when more than 20 are waiting, on any network,
  in the background. The server keeps 90 days (the last 300 per device are kept longer). Account deletion deletes them. There is no opt-out switch in the app.
- **Play Data safety:** App activity → App interactions (collected, linked when signed in, analytics, not shared); App info and performance →
  Crash logs + Diagnostics (collected, not shared); Device or other IDs (collected, analytics, not shared); encrypted in transit.
Commit `docs(tracker): A5 privacy disclosure`.

---

## Progress (Activity Tracker app)
| Card | Repo | What | Depends on | Status |
|---|---|---|---|---|
| A1 | app | Tracker core: API, records, Meta, Room table + migration 8→9, device id, prefs | - | ✅ |
| A2 | app | Silent batched upload (policy, worker, scheduler, gzip API) | A1 | ✅ |
| A3 | app | Auto-tracking: open source, fg/bg, screens, crash, exit reasons, notification entry | A2 | ✅ |
| A4.1 | app | Convert old calls: MainActivity | A3 | ✅ |
| A4.2 | app | Convert old calls: ui/* | A3 | ✅ |
| A4.3 | app | Convert old calls: services, publish, adapter, SavedRecordings | A3 | ✅ |
| A4.4 | app | Convert old calls: auth, entitlements, rest | A3 | ✅ |
| A4.5 | app | Delete the old activity-log system + migration 9→10 | A4.1 A4.2 A4.3 A4.4 | ⬜ |
| A5 | app | Privacy disclosure doc | A4.5 | ⬜ |
