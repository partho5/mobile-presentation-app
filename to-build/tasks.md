
RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 
One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

<!-- runner-config
REPOS=/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder /home/haku/projects/Next.js/Social-Media-Manager
BRANCH=feat/content-plan
SCRATCH=test_audio.wav test_video.mp4 run.log
-->

# FaceCam app: AI content plan, phone side (cards P1-P4, decided 2026-11-22)

The server half is in `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md` (cards B1-B7). It holds the full
product flow; this file holds only what the phone needs. **The server card a phone card depends on must be ticked there
first** (see **Depends on**). P1 and P2 only need the contract below, so they may be built before the server is deployed.

Branch `feat/content-plan` (create it from the current HEAD if it does not exist). Never commit `test_audio.wav`,
`test_video.mp4`, `run.log`, `app/src/debug/AndroidManifest.xml` or `app/src/main/assets/seed_slide_deep_breath.webp` (they
are the owner's uncommitted local changes). Commit with explicit paths.

## Shared context

### Goal
The web plans the brand's videos: 1 to 7 videos a week (the owner's choice, on spread-out days), each with a title, an angle, a "why" line, 3-6 **beats** and a closing
line. A beat is `{ hook, say }`: `hook` = the short hint shown on screen while recording; `say` = the script spoken while that
hook shows. The phone **only consumes**:
1. It shows *Today's video* on a card. A day can also be the owner's own idea (`kind` INSTANT_IDEA, added on the web with
   "Add my own idea"): it comes in the same `days` list, is shown and recorded exactly like an AI day, and only differs by a
   "Your idea" chip instead of the angle chip. A date can therefore hold two videos.
2. One tap loads the hooks as the topics shown while recording, one slide per hook. They go into their own set and never
   overwrite the user's own topics. **No script is ever shown while recording**: the screen is captured, so anything on it
   ends up in the video.
3. **Practice** (optional) is a separate screen with no facecam and no recording. It shows each hook the way it will look on
   screen, with its `say` under it, one beat at a time, then the closing line. Practising each cue with its words means the
   hook alone brings the words back while recording.
4. It caches the week for offline use.
5. When a recording made on that day's topics finishes, it reports "recorded" to the server automatically. The user never
   marks anything.

The test for every choice: **trust, satisfaction, less cognitive effort**.

### Naming trap (read this)
In this app's UI a *slide* is called a **"Topic"** ("Edit Topics", "Topic Manager"). Keep that. The AI day is called
**"Today's video"** in the UI and `PlanDay` in code. The Java package is **`contentplan`**. `aiedit/engine/plan/` already
exists and is unrelated; do not touch it or reuse its classes.

### App facts
Java paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/` (`M/`); tests are in
`app/src/test/java/com/jovoc/facecampresentationrecorder/` (`T/`); minSdk 31.

**Tests**
- JUnit 4 with `org.json`, plus `mockwebserver`.
- There is no Robolectric and no androidTest, so JVM tests cannot use Context, SharedPreferences, Room or Views. Keep the
  logic in pure classes with a `Store` interface (pattern `M/aiedit/flow/AiEditStore`).

**Network**
- OkHttp through `M/auth/AuthRepository.execute(method, path, body, headers)`, obtained with
  `SessionManager.get(ctx).repository()`.
- The brand goes in the header `AuthRepository.HEADER_WORKSPACE` (`x-workspace-id`).
- `SessionManager.brandForNewJob()` gives the brand for a call. It blocks, so never call it on the main thread.
- The API class template is `M/aiedit/flow/AiEditApi.java` (`send(...)` maps `AuthException.Kind` to the class's own
  exception).
- JSON is `org.json` only.
- Threading uses a single-thread `ExecutorService` plus `new Handler(Looper.getMainLooper())`.

**Slides**
- `M/db/Slide.java` (table `slides`), `SlideDao`, `SlideRepository` (executor + main handler + `Callback<T>`).
- `M/db/AppDatabase.java` is **version 10**; migrations are `static final Migration MIGRATION_X_Y` fields collected in
  `MIGRATIONS`.
- Test: `T/db/AppDatabaseMigrationTest.java`.
- Text slides are at most 200 characters (editor limit).

**MainActivity** (`M/MainActivity.java`, about 4241 lines, so read it by line range)
- `private List<Slide> slides` (line 150), `currentSlideIndex` (151), `isRecording` (315).
- `loadSlidesFromDb(boolean)` (2267) loads **all** rows today.
- `openSlideManagerDialog()` (3118), `deleteAllSlides()` (3649), `seedInitialSlides()` (prefs key `initialSlidesSeeded`).
- Inserts: `showAddTextSlideDialog` (about 3347), the photo picker (about 2128), and the video, website and YouTube dialogs.
- `updateUIState()` (1778) shows `top_menu_bar` only when `!isRecording && isMenuBarVisible`.
- `screenCaptureLauncher` (about 1850) builds the `ScreenRecordService` `ACTION_START` intent.
- Prefs `RecordSettings.PREF_NAME` (`"app_prefs"`).

**Recording**
- `M/service/ScreenRecordService.java`: `ACTION_START` handling (about line 120), `startRecordingInternal` (225),
  `stopRecordingInternal` (365). Its success branch (`file.exists() && file.length() > 0`) runs even when MainActivity is
  gone.
- The screen itself is what is recorded (MediaProjection), so **anything visible while recording ends up in the video**.

**Brand**
- `TokenStore.workspaceId()` (`SessionManager.get(ctx).repository().store()`).
- A brand switch restarts `MainActivity` (`WorkspaceActivity.restart()`).
- Switching is blocked during recording (`FeatureSnapshot.recordingNow()`).

**Web link**
- `new PublishApi(repo).webLink(target, identifier)` (`M/publish/PublishApi.java` about line 191), opened with
  `WebLinks.open(host, url)`.
- Pattern: `M/ui/BillingLink.open`. Fallback URL: `BuildConfig.WEB_BASE_URL + "/plan"`.

**Retry pattern**
- `M/aiedit/flow/AiEditReceipts.java` (`flushAsync` on a daemon thread; `flush(store, api, now)` keeps retryable failures
  and drops final ones).
- It is started in `M/FaceCamApp.onCreate` inside try/catch.

**UI style**
- Dark: background `#121212`, card `#222222` with radius 12dp, accent `#64B5F6`, text `#FFFFFF`, secondary `#B3FFFFFF`.
- `MaterialCardView`, `BottomSheetDialog`.
- Strings go in a new `res/values/strings_content_plan.xml`, prefix `cplan_`, with an XML comment naming the class that
  uses each group. No hard-coded UI text.

**Privacy**
- Never send titles, hooks, says or closing lines to `ActivityTracker` meta or any log. Ids only.

**Any language in the world**
- Texts come from the server in the brand's language. Never measure, cut or transform them per language.
- Set `textDirection="locale"` / `TEXT_DIRECTION_FIRST_STRONG` on TextViews showing plan text, so right-to-left text reads
  right.

### API contract (copied from the server file; keep exact)
```
GET  /public/v1/content-plan/week                     (device token; header x-workspace-id)
  200 { status: 'NONE'|'SETUP'|'PROPOSED'|'ACTIVE', today: 'YYYY-MM-DD', timezone: string,
        week: null | { id, weekStart, weekEnd, idea, status: 'IDEA'|'GENERATING'|'READY'|'FAILED',
                       days: [ { id, kind: 'PLANNED'|'INSTANT_IDEA', date, dayIndex: number|null, title, angle: string|null, why,
                                 beats: [ { hook, say } ],      // 3-6, in speaking order
                                 closingLine,
                                 status: 'PLANNED'|'RECORDED'|'MISSED', version } ] } }
  `days` is [] unless status is READY. `week` is the week containing `today`. `days` holds only the week's video days
  (1-7), so today may have no day. An `INSTANT_IDEA` row has `dayIndex` null and `angle` null and may share its `date`
  with an AI day, so one date can have two days (the AI day first). Rows of both kinds are shown and recorded the same way,
  by `id`; the phone never branches on how a day was made, except for the chip label. A missing `kind` means `PLANNED`.
  Instant-idea rows only appear once the server card B8 is live; until then every day is `PLANNED`.
  Slides: exactly one TEXT slide per beat from `hook`, in order. The server already cleaned `hook` (plain text, no markdown,
  list markers or line breaks; at most 200 characters, normally at most 100). `say` and `closingLine` are for Practice
  only, never shown while recording.
Example day:
  { "id": "d7f1", "kind": "PLANNED", "date": "2026-11-23", "dayIndex": 0, "title": "One Idea Beats Ten Tips", "angle": "mistake",
    "why": "Your viewers leave when a video tries to say everything.",
    "beats": [ { "hook": "Pick one takeaway", "say": "Stop trying to say everything today. Choose one clear idea first." },
               { "hook": "Talk to one person", "say": "Explain it the way you would explain it to a client." },
               { "hook": "Stop at one idea",   "say": "One idea keeps it short. Save the next one for tomorrow." } ],
    "closingLine": "Follow for a new tip tomorrow.", "status": "PLANNED", "version": 1 }
POST /public/v1/content-plan/days/:id/recorded   {}   + Idempotency-Key
  200 { id, status: 'RECORDED' }   (already recorded → the same 200)   404 DAY_NOT_FOUND
Web link target: 'content-plan' (identifier null) → the web page /plan.
angle ∈ problem | mistake | story | question | comparison | steps | fear_loss | behind_the_scenes | customer_voice | trend
```

### Slide sets (decided)
- New nullable column `slides.set_id TEXT`: **NULL = the user's own topics** (all existing rows), and `"plan:<dayId>"` = the
  topics of one plan day.
- The active set id is kept in prefs `"app_prefs"` under key `active_slide_set` (absent = own).
- A plan set is one TEXT slide per beat, `text_content` = `hook`, in beat order. It is only cut to 200 characters as a
  safety net; the phone adds no title slide and never rewrites the text. The day's
  `version` is kept in prefs `cplan_set_version:<dayId>`. If the server's version is newer, the next tap rebuilds the set.
- The user may edit a plan set in the Topic Manager like any topics. Inserts go to the active set.

---

## P1 — Content plan API, model, cache and report queue (pure Java + tests)
**Depends on:** nothing (contract above). **Read first:** `M/aiedit/flow/AiEditApi.java`, `AiEditException.java`,
`AiEditStore.java`, `AiEditReceipts.java`; `M/auth/ApiResponse.java`; `T/account/AccountApiTest.java`,
`T/account/AccountModelsTest.java`, `T/aiedit/flow/AiQuoteCacheTest.java`.

**Do**: new package `M/contentplan/`. Every new file starts with a short comment saying what it is for.
1. `PlanWeek.java`: an immutable model plus `static PlanWeek parse(String json) throws JSONException`.
   - Fields: `status`, `today`, `timezone`, and `week` (nullable `Week { id, weekStart, weekEnd, idea, status,
     List<Day> days }`). `Day` has `{ id, kind, date, dayIndex, title, angle, why, List<Beat> beats, closingLine, status,
     version }`, and `Beat` is `{ hook, say }`.
   - Use `optString`/`optJSONArray`. Skip a day with no id. Drop a beat with an empty hook; a missing `say` becomes "".
   - `kind` is `"INSTANT_IDEA"` only when the server says so; anything else, or missing, is `"PLANNED"`. `dayIndex` is -1
     when null or missing. `angle` is "" when null or missing (use `isNull` before `optString`, because `optString` turns
     a JSON null into the text "null"). Keep the server's order for days.
   - Helpers:
     - `List<Day> daysFor(String date)` (all days of that date in server order, so an AI day comes before an instant idea;
       empty list when none)
     - `boolean isInstantIdea()` on `Day`
     - `boolean isActive()` (status ACTIVE)
     - `boolean isReady()` (week non-null and READY)
2. `ContentPlanException.java`: a copy of `AiEditException`'s shape, with codes `NETWORK`, `SIGNED_OUT`, `NOT_FOUND`,
   `RATE_LIMITED`, `SERVER`, `retryable`, and `from(ApiResponse)` (404 → NOT_FOUND, not retryable).
3. `ContentPlanApi.java` (copy the `AiEditApi` `send` pattern):
   - `PATH_WEEK = "/public/v1/content-plan/week"`, `String week(String workspaceId)`, which returns the raw body so the
     cache stores exactly what came.
   - `void recorded(String dayId, String workspaceId)`, a POST to `"/public/v1/content-plan/days/" + dayId + "/recorded"`
     with body `{}` and header `Idempotency-Key: "plan-recorded-" + dayId`.
4. `ContentPlanStore.java` (pattern `AiEditStore`, a `Store` interface plus `get(Context)` on SharedPreferences
   `"content_plan"`):
   - `saveWeek(brandId, json, nowMs)`, `String weekJson(brandId)`, `long fetchedAt(brandId)`;
   - `savePendingRecorded(dayId, brandId, nowMs)`, `List<PendingRecorded> pendingRecorded()`, `removePending(dayId)`;
   - `markRecordedLocally(dayId)`, `boolean recordedLocally(dayId)` (kept 30 days, pruned on save).
5. `ContentPlanReports.java` (pattern `AiEditReceipts`):
   - `flushAsync(Context)`.
   - `flush(store, api, nowMs)`: for each pending item call `recorded`. Success or `NOT_FOUND` → `removePending`.
     Retryable → keep. A pending item older than 14 days → drop.

**Tests (run only these, `--tests '*contentplan*'`)**:
- `PlanWeekTest`: the contract sample parses; `days` is empty when not READY; a null week; a day without an id is skipped;
  `daysFor`; an `INSTANT_IDEA` day with `dayIndex` and `angle` null parses (`dayIndex` -1, `angle` "", not the text
  "null"); a day without `kind` is `PLANNED`; two days on one date come back in server order.
- `ContentPlanStoreTest` (map store): a week per brand is kept apart; pending add and remove; local recorded prune.
- `ContentPlanApiTest` (MockWebServer): path, `x-workspace-id`, Idempotency-Key, 404 → NOT_FOUND.
- `ContentPlanReportsTest`: success removes; a network error keeps; a 404 removes; old items are dropped.

## P2 — Slide sets (database, repository, MainActivity scoping)
**Depends on:** nothing. **Read first:** `M/db/Slide.java`, `SlideDao.java`, `SlideRepository.java`, `AppDatabase.java`,
`T/db/AppDatabaseMigrationTest.java`; `MainActivity` lines 2260-2300 (`loadSlidesFromDb`), the insert sites listed in
App facts, 3640-3700 (`deleteAllSlides`), and `seedInitialSlides`.

**Do**
1. `Slide`: `@ColumnInfo(name = "set_id") private String setId;` (nullable) with a getter and setter. Leave the
   `@Ignore` constructor as it is.
   - Constants: `public static final String PLAN_SET_PREFIX = "plan:";` and
     `public static String planSetId(String dayId) { return PLAN_SET_PREFIX + dayId; }`.
2. `AppDatabase`:
   - `version = 11`.
   - `MIGRATION_10_11`: `database.execSQL("ALTER TABLE slides ADD COLUMN set_id TEXT")`, with a one-line comment (house
     style). Append it to `MIGRATIONS`.
   - Update the migration test: the count is now 10 steps and the end version 11. Add
     `migration10to11_addsOnlyTheSetColumn` (exactly one statement, equal to the SQL above).
3. `SlideDao`:
   - `getOwnOrdered()` = `SELECT * FROM slides WHERE set_id IS NULL ORDER BY order_index ASC`
   - `getSetOrdered(String setId)` = `... WHERE set_id = :setId ...`
   - `deleteOwn()`, `deleteSet(String setId)`
   - `List<Integer> idsOfSet(String setId)`, `List<Integer> idsOfOwn()`
   - `List<String> planSetIds()` = `SELECT DISTINCT set_id FROM slides WHERE set_id LIKE 'plan:%'`
   - `@Insert void insertAll(List<Slide>)`
   - Keep `getAllOrdered()` and `deleteAll()` but leave no caller in MainActivity.
4. `SlideRepository` (same executor and handler pattern):
   - `getSet(String setIdOrNull, Callback<List<Slide>>)` (null → own)
   - `replaceSet(String setId, List<Slide> slides, Callback<List<Integer>> removedIds)` (one transaction via
     `db.runInTransaction`: ids of the old set, deleteSet, insertAll)
   - `deleteSetOrOwn(String setIdOrNull, Callback<List<Integer>> removedIds)`
   - `planSetIds(Callback<List<String>>)`
5. `MainActivity`:
   - Field `private String activeSetId`, read from prefs key `active_slide_set` in `onCreate` before the first
     `loadSlidesFromDb`.
   - `loadSlidesFromDb` uses `repository.getSet(activeSetId, …)`.
   - Every insert site sets `slide.setSetId(activeSetId)`.
   - `deleteAllSlides()` uses `deleteSetOrOwn(activeSetId, …)` and discards ink and board data for the **returned ids**
     only. Image files are deleted only for slides of that set (keep the existing logic but limit it to `slides`, the
     in-memory list of that set).
   - `seedInitialSlides` checks and seeds only the own set (`setId` null).
   - New method `void switchSlideSet(String setIdOrNull)`:
     1. Save or remove the pref.
     2. `currentSlideIndex = 0`.
     3. `loadSlidesFromDb(false)`.
     4. Do nothing while `isRecording` (show `R.string.cplan_locked_recording`).
   - New method `void discardSlideData(List<Integer> ids)`: the same discard calls `deleteAllSlides` makes for ink and
     board.
   - Add `strings_content_plan.xml` with `cplan_locked_recording` ("Finish recording first").

**Check**: `./gradlew --offline :app:assembleDebug` and `--tests '*AppDatabaseMigrationTest*'`. An existing user upgrading
sees exactly their topics as before (every old row has `set_id` NULL).

## P3 — Today's video card, Practice screen, week sheet, load as topics
**Depends on:** P1, P2, and server **B5** ticked (routes live). **Read first:** P1 and P2 files; `activity_main.xml`
(`root_layout`, `top_menu_bar`); `MainActivity` `onCreate` (find the view lookups), `onResume`, `updateUIState()` (1778),
`openSlideManagerDialog()` (3118) for BottomSheetDialog style; `res/layout/item_recording_card.xml` (card style);
`M/ui/BillingLink.java` (web link with fallback); `M/ui/AiEditPanel.java` (the `loadQuote` executor pattern).

**Do**
1. Layout `res/layout/view_content_plan_today.xml`:
   - A `MaterialCardView` (`#222222`, radius 12dp, elevation 3dp, margin 12dp).
   - Inside, a vertical stack:
     - label `cplan_today` ("Today's video") + date, 12sp secondary;
     - title, 17sp bold white, max 3 lines;
     - angle chip (`#64B5F6` text on `#1A64B5F6`, 12sp, GONE for an unknown key). For an instant idea the same chip shows
       `cplan_your_idea` ("Your idea") instead;
     - why, 13sp secondary, max 2 lines;
     - a button row of TextButtons: **Use as topics** / **Practice** / **Week**;
     - a status line (Recorded ✓ / message).
   - Include it in `activity_main.xml` inside `root_layout`, `layout_below="@id/top_menu_bar"`, initially `gone`.
2. `M/contentplan/ContentPlanCard.java` (UI glue, so MainActivity stays small). Constructor
   `(MainActivity host, View card, ContentPlanStore store)`.
   - `refresh(boolean force)`:
     - Runs on an executor and skips when the last fetch for this brand was under 10 minutes ago, unless `force` is true or
       the local date changed.
     - Flow: `SessionManager.hasSession()`, then `brandForNewJob()`, then `api.week`, then `store.saveWeek`, then `bind` on
       the main thread.
     - On any error it binds from the cache silently.
     - No session or no brand → hidden.
   - `bind(PlanWeek pw)`. States:
     - `status` NONE, SETUP or PROPOSED → title `cplan_setup_title` ("Plan your videos with AI") and one button
       `cplan_setup_open` ("Set up on the web"). The button opens the web link `content-plan` like `BillingLink.open`,
       falling back to `WEB_BASE_URL + "/plan"`.
     - ACTIVE with the week null, IDEA or GENERATING → `cplan_writing` ("Writing this week's videos…"), no buttons.
     - FAILED → `cplan_failed` ("This week's videos aren't ready yet"), no buttons.
     - READY → the selected day: by default the first day of `daysFor(localToday)` that is not recorded (server status or
       `recordedLocally`), or the first one when all are recorded, where `localToday` is the device date in the server's
       `timezone`, through `java.time.ZonedDateTime.now(ZoneId.of(tz))` (minSdk 31, so it is available) with a fallback to
       the server's `today`.
       - Today has no video day (the owner makes fewer than 7 a week) → select the first day after today that is not
         recorded (any kind, in date order, an AI day before an instant idea on the same date) and label the card `cplan_next` ("Next video") with its date instead of "Today's video". If none is left
         this week → `cplan_week_done` ("All videos for this week are done ✓"), with only the **Week** button.
       - `localToday` is after the week's `weekEnd` (the week is over and the phone is offline) → `cplan_stale` ("Go
         online to get this week's videos").
       - The day is recorded (server status or `recordedLocally`) → status line `cplan_recorded` ("Recorded ✓").
       - Today has more than one video (an AI day and an instant idea) and some other one is not recorded → the status line
         shows `cplan_more_today` ("+1 more today", with the count). The other one is chosen in the **Week** sheet.
   - **Use as topics**:
     1. Build the slides (Shared context → Slide sets).
     2. If the set does not exist or its version is older, `replaceSet` and then `host.discardSlideData(removedIds)`.
     3. `host.switchSlideSet(planSetId)`.
     4. The button becomes **Back to my topics**, which calls `host.switchSlideSet(null)`.
   - **Practice**: starts `ContentPlanPracticeActivity` with the brand id and day id (step 5).
   - **Week**: a `BottomSheetDialog` listing every day of the week (AI days and instant ideas, in the server's order):
     weekday and date, title, and the status Today / Recorded ✓ / Missed. An instant idea also shows the small
     `cplan_your_idea` label. Tapping a day selects it in the card, so a missed day can still be recorded.
   - Cleanup after each successful fetch: every `planSetIds()` entry whose day id is not in the current week is deleted
     (`deleteSetOrOwn`, then `discardSlideData`). If the active set was one of them, call `switchSlideSet(null)` first.
   - Angle labels: `cplan_angle_problem` … `cplan_angle_trend` (all 10, plain English: "Problem", "Common mistake", "Story",
     "Question", "Comparison", "Steps", "What you could lose", "Behind the scenes", "Customer voice", "Trend"), plus
     `cplan_your_idea` ("Your idea") and `cplan_more_today` ("+%d more today").
   - Nothing else depends on how a day was made: slide sets (`plan:<dayId>`), Practice, cleanup and the recorded report
     all work from the day id, so they treat an instant idea exactly like an AI day.
3. `MainActivity` wiring:
   - Create the card in `onCreate`.
   - `card.refresh(false)` in `onResume`.
   - In `updateUIState()` the card is VISIBLE only when `!isRecording && isMenuBarVisible && card.hasContent()`, otherwise
     GONE. **It must be GONE while recording** because the screen is captured.
4. Privacy: no `SlideLogger`, `ActivityTracker` or `Log` call with plan texts.
5. `M/contentplan/ContentPlanPracticeActivity.java` + `res/layout/activity_content_plan_practice.xml`. It is a separate
   screen on purpose: it never starts the camera or MediaProjection, so no facecam and no recording can happen here, and
   MainActivity's recording state is not touched.
   - Register it in `AndroidManifest.xml` with the same theme and orientation as `MainActivity`.
   - Read the day from `ContentPlanStore` (the cached week of the given brand). If it is missing, finish.
   - Layout, top to bottom on a `#121212` background:
     1. The same card header (title, angle chip, why).
     2. A **stage** that looks like the recording slide: a dark box with the hook centred, `@font/bitter`, white, line
        spacing 1.2, and the size from the same length rule as recording.
     3. Under it, "You say" (12sp secondary) and the `say` text (18sp white, line spacing 1.3, selectable).
     4. A row: **Previous** · "2 / 4" · **Next**. After the last beat, Next shows the closing line under the label
        "End with".
     5. A **Show whole script** toggle that lists every beat (hook in bold, say below) and the closing line in one
        ScrollView.
     6. Bottom buttons: **Use as topics** (does the same as on the card, then `finish()`, so the user is back on the
        recorder with the slides loaded) and **Done**.
   - Font-size rule: move the body of `MainActivity.applyTextSlideFontScaling` into
     `M/ui/TextSlideSize.java` (`static float sizeSp(int length)`: 26sp up to 100 characters, linear down to 15sp at 200,
     exactly the current numbers). MainActivity calls it, so recording looks the same as before. Add
     `T/ui/TextSlideSizeTest` (0 → 26, 100 → 26, 150 → 20.5, 200 and more → 15).
   - Plan text views use `textDirection` first-strong, so right-to-left text reads right.

**Check**: assembleDebug and `--tests '*TextSlideSizeTest*'`. Then the owner, on a device (record the result in **Passed on
a device**):
- No plan → the card shows "Set up on the web" and opens /plan.
- Plan ready → today's title. Use as topics → the presentation shows one slide per hook (no script anywhere); Back to my
  topics → the old topics are unchanged.
- Practice → no camera light and no facecam; each hook appears as it will in the recording with its say under it; Next and
  Previous work; the closing line shows after the last beat; Use as topics returns to the recorder with the slides loaded.
- Recording → the card is not in the video.
- Airplane mode + app restart → the card shows from the cache.
- Brand switch → the other brand's day (or the setup card).
- Once server B8 is live: add an instant idea for today on the web → the phone shows it ("Your idea" chip, "+1 more today"
  on the card of the other video, both listed in **Week**); Use as topics and Practice work on it; recording it marks it
  Recorded on the web.

## P4 — Report "recorded" automatically
**Depends on:** P1, P3, and server **B5**. **Read first:** `M/service/ScreenRecordService.java` lines 110-140
(`ACTION_START`), 225-260 (`startRecordingInternal`) and 365-460 (`stopRecordingInternal`, success branch);
`MainActivity` `screenCaptureLauncher` (about 1850); `M/FaceCamApp.java` `onCreate`; P1's `ContentPlanReports`.

**Do**
1. `ScreenRecordService`:
   - Constants `EXTRA_PLAN_DAY_ID`, `EXTRA_PLAN_BRAND_ID`.
   - In `ACTION_START` read them into fields `planDayId` and `planBrandId` (null when absent).
   - In `startRecordingInternal` store `planStartMs = SystemClock.elapsedRealtime()`.
   - In the success branch of `stopRecordingInternal`, inside its own try/catch so a recording never fails because of this:
     when `planDayId != null` and the recording lasted at least 10 s (an accidental tap must not mark the day),
     `ContentPlanStore.get(this).savePendingRecorded(planDayId, planBrandId, now)`, then `markRecordedLocally(planDayId)`,
     then `ContentPlanReports.flushAsync(this)`.
   - Clear both fields at the end of stop, whatever happened.
2. `MainActivity` `screenCaptureLauncher`: when `activeSetId` starts with `Slide.PLAN_SET_PREFIX`, put
   `EXTRA_PLAN_DAY_ID = activeSetId.substring(prefix length)` and `EXTRA_PLAN_BRAND_ID =
   SessionManager.get(this).repository().store().workspaceId()` on the start intent.
3. `FaceCamApp.onCreate`: `try { ContentPlanReports.flushAsync(this); } catch (Exception e) { }`, with the comment "retries a
   recorded report that could not be sent", next to the `AiEditReceipts` call.
4. `ContentPlanCard`: on `onResume`, re-bind so "Recorded ✓" shows from the local mark straight away.

**Tests**: extend `ContentPlanReportsTest` if anything changed. Check: assembleDebug. Owner on a device:
- Use as topics → record 15 s → stop → the card shows Recorded ✓ and the web day card shows Recorded (same for an
  instant idea, once server B8 is live).
- A 5 s recording → not marked.
- Recording with the own topics → not marked.
- Airplane mode while stopping → it is marked after the next app start online.

## Passed on a device (owner ticks)
- [ ] P3 checks
- [ ] P4 checks

## Progress (runner-maintained)
| Card | What | Depends on | Status |
|---|---|---|---|
| P1 | API, model, cache, report queue | contract | ☐ |
| P2 | Slide sets + migration 10→11 | — | ☐ |
| P3 | Today's video card, Practice screen, week sheet | P1, P2, server B5 | ☐ |
| P4 | Automatic "recorded" report | P1, P3, server B5 | ☐ |
