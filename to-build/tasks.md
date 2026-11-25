
RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 
One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

# FaceCam app: content plan v2, "plan lands in topics by itself, nothing is ever lost" (cards S1-S4, decided 2026-11-25)

Branch `feat/content-plan` (already exists). Plan first, then code: build the cards in order S1 → S4, one per session.
**Owner's pace (2026-11-25): nothing is built yet. Build starts only when the owner says so; then build S1 and pause for
the day. S2-S4 are built on later days, each only when the owner says so.**
Never commit `test_audio.wav`, `test_video.mp4`, `run.log`, `app/src/debug/AndroidManifest.xml` or
`app/src/main/assets/seed_slide_deep_breath.webp` (the owner's uncommitted local changes). Commit with explicit paths.
Commit message ends with the line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
This file replaces the older P1-P4 cards (done and committed; see git history of this file for their text).

## Shared context

### Core goal
The plan written on the website arrives on the phone and **turns itself into ready-to-record topics**. The user never
types anything on the phone, **never loses anything**, and is **never pushed** (no popups, banners, toasts or alerts for
any of this, least of all when the app opens).

### Decisions (owner, final)
- **D1 Automatic.** After every fetch of the week, every planned video that has beats gets **its own topic set**
  (one TEXT topic per beat from `hook`). The whole week is saved, not only today (so it works offline).
- **D2 Ready on open.** Today's set is already on screen, editable in the Topic Manager like any topics. After a
  recording the **same set stays on screen** (so the user can retake with the same topics). The next video's set is put on
  screen the **next time the app is opened fresh** (a new launch, not coming back from a recording or a rotation), **once per
  video**, so a set the user picks by hand is never taken away again.
- **D3 Nothing is ever lost.** Each video keeps its own set next to the user's own topics ("My topics"). The app **never
  deletes or overwrites** a set the user may have edited, and never deletes old weeks. Only the user deletes a set, from
  the sets sheet, after a confirmation.
- **D4 Server sends a newer version of a day.** A set the user did **not** edit is replaced silently. A set the user
  **did** edit stays exactly as it is, **with no alert, no badge, no button**.
- **D5 No prompts.** Users with no plan see nothing about AI planning. After their **first recording** (or if a plan set
  already exists) a drawer entry **"Topic sets"** appears in the left drawer. That is the only hint, and it never pops up.
- **D6 Card.** The big card becomes a **slim strip** shown only on a day that has a video still to record
  ("Today's video · title · Practice"). It disappears once today's videos are recorded and when there is no plan. The setup
  promo card ("Plan your videos with AI") is removed completely.
- **D7 Switching sets.** The **Topic Manager** (the card opened by "Edit Topics") has a **row of chips** at the top: "My topics"
  plus one chip per video set; one tap switches, the set on screen is highlighted, recorded videos carry a ✓, the row
  scrolls sideways and is hidden while there are no plan sets. The **"Topic sets" sheet** (drawer, and a tap on the strip)
  lists the same sets and adds the heavier jobs: delete with confirmation and a link to the website planner.
- **D8 Buttons** are normal-size, in the app's own theme style, 48dp tap height.

### Root causes this fixes (found 2026-11-25)
- Topics were written only on a tap of "Use as topics" (`ContentPlanCard.useAsTopics`), so a user who just pressed Record
  recorded the old topics and the day was never reported.
- `ContentPlanCard.bind()` called `setContent(true)` in every state, so the card never hid.
- `SlideRepository.replaceSet` (called from `useAsTopics` when the server `version` is newer) **deletes the user's edits**,
  and `ContentPlanCard.cleanUp` **deletes the sets of old weeks**. Both break D3.
- The buttons use `Widget.Material3.Button.TextButton` inside `Theme.MaterialComponents` (`res/values/themes.xml`), so they
  render as tiny plain text; the card is a flat `#222222` block floated over the topic text.

### App facts
Java paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/` (`M/`); tests are in
`app/src/test/java/com/jovoc/facecampresentationrecorder/` (`T/`); minSdk 31. Strings for this feature go in
`res/values/strings_content_plan.xml` (prefix `cplan_`, an XML comment naming the class that uses each group, no hard-coded
UI text). Every new file starts with a short comment saying what it is for. Plan texts are never logged and never sent to
`ActivityTracker` (ids only). Texts are in any language: never cut or transform them, keep
`textDirection="firstStrong"` / `TEXT_DIRECTION_FIRST_STRONG` on TextViews that show plan text.
- Tests: JUnit 4 with `org.json`; no Robolectric, no Context/SharedPreferences/Room/Views in JVM tests. Keep logic in pure
  classes with a `Store` interface. `ContentPlanStore.Store` (get/put/remove/keys) already exists and is public: reuse it.
- Sets: `slides.set_id` NULL = the user's own topics, `"plan:<dayId>"` = one video (`Slide.planSetId(dayId)`,
  `Slide.PLAN_SET_PREFIX`). `db/SlideRepository`: `getSet`, `replaceSet`, `deleteSetOrOwn`, `planSetIds` (ids of plan sets
  that have rows). Callbacks run on the main thread.
- `MainActivity`: `activeSetId()`, `switchSlideSet(idOrNull)` (toasts and refuses while recording), `slideRepository()`,
  `discardSlideData(List<Integer>)`, `syncPlanCardVisibility()`, private `isRecording`, private `managerDialog`
  (BottomSheetDialog of `openSlideManagerDialog()`, about line 3144), drawer in `setupDrawer()` (about line 799) with items in
  `res/menu/drawer_menu.xml` and `drawerItemName()` (about line 1746), `onResume` calls `contentPlanCard.rebind()` and
  `refresh(false)` (about line 2380). Prefs file `RecordSettings.PREF_NAME` (`"app_prefs"`).
- Recording: `service/ScreenRecordService.java` success branch (about line 510-535) reports a plan day recorded when the
  recording started on a plan set.
- Week model: `contentplan/PlanWeek.java` (`Day`: id, kind, date, title, angle, why, beats, closingLine, status, version;
  `PlanWeek.daysFor(date)`, `isActive()`, `isReady()`), cache `contentplan/ContentPlanStore.java` (`recordedLocally(dayId)`).
- Web link: `PublishApi(repo).webLink("content-plan", null)` opened with `WebLinks.open(host, url)`; fallback
  `BuildConfig.WEB_BASE_URL + "/plan"`. The current code is `ContentPlanCard.openWebPage()`.

### New contracts (built in S1, used by S2-S4)
**`M/contentplan/PlanSetIndex.java`**: remembers, per plan video, what the app saved, in SharedPreferences file
`content_plan_sets` through `ContentPlanStore.Store`. Key `set:<dayId>`, value JSON
`{"brand","title","date","kind","version","sig","created"}` (`sig` may be null).
`Entry` fields: `dayId, brandId, title, date, kind, version, signature (nullable), createdMs`.
Methods: `get(String dayId)` (null if absent), `put(Entry)`, `remove(String dayId)`, `all()` (date ascending, then
`createdMs`), `static PlanSetIndex get(Context)`.

**`M/contentplan/PlanSetSignature.java`**: `static String of(List<Slide> orderedSlides)` = lowercase hex SHA-256 (UTF-8) of
the slides joined by `\u0002`, each slide being `type + \u0001 + text + \u0001 + imagePath + \u0001 + disabled` (null → "").
Order matters; ids and order indexes are not part of it. Used to tell "the user changed this set".

**`M/contentplan/PlanSetPlanner.java`** (pure): `enum Action { CREATE, REPLACE, KEEP, ADOPT }`;
`static Action decide(PlanSetIndex.Entry entryOrNull, boolean setHasSlides, String currentSignature, int serverVersion)`:
1. `entry == null && !setHasSlides` → `CREATE`.
2. `entry == null && setHasSlides` → `ADOPT` (a set from the older app version: index it with `signature = null`, so it is
   treated as edited and is never overwritten).
3. `entry != null && !setHasSlides` → `KEEP` (the user emptied or deleted it on purpose; never bring it back).
4. `entry != null && setHasSlides && serverVersion > entry.version && entry.signature != null
   && entry.signature.equals(currentSignature)` → `REPLACE` (unedited, silently updated).
5. everything else → `KEEP`.

**`M/contentplan/PlanAutoTarget.java`** (pure):
- `static PlanWeek.Day target(PlanWeek pw, String today, Predicate<String> isRecorded)`: the first day, in date order (server
  order within a date), with `date >= today`, not recorded, and `!beats.isEmpty()`; null when none or when the plan is not
  active+ready.
- `enum Switch { NONE, MARK_ONLY, SWITCH }`;
  `static Switch decide(PlanWeek.Day target, String lastAutoDayId, String activeSetId, boolean recording, boolean managerOpen,
  boolean setExists)`: `target == null || recording || managerOpen || !setExists` → `NONE`; `target.id.equals(lastAutoDayId)`
  → `NONE`; `activeSetId` equals `Slide.planSetId(target.id)` → `MARK_ONLY` (remember the day, no switch); else `SWITCH`.
  The caller stores `target.id` as `lastAutoDayId` (prefs key `cplan_last_auto_day`) on `MARK_ONLY` and after a `SWITCH`.

---

## S1 — Set index, signature, save/switch decisions (pure Java + tests)
**Depends on:** nothing. **Read first:** `M/contentplan/ContentPlanStore.java` (the `Store` interface and `get(Context)`
pattern), `M/contentplan/PlanWeek.java`, `M/db/Slide.java`; tests `T/contentplan/ContentPlanStoreTest.java` (map-store
pattern).

**Do**: create `PlanSetIndex`, `PlanSetSignature`, `PlanSetPlanner`, `PlanAutoTarget` exactly as in **New contracts**. No
Android UI, no changes to existing files.

**Tests (run only these, `--tests '*contentplan*'`)**:
- `PlanSetIndexTest` (map store): put/get/remove; `all()` order; a malformed JSON value is skipped, not a crash.
- `PlanSetSignatureTest`: same slides → same hash; changing text, type, imagePath, disabled or order changes it; null
  text equals empty text; ids and order indexes are ignored.
- `PlanSetPlannerTest`: one test per rule 1-5, plus: newer version but edited → `KEEP`; newer version, `signature == null`
  → `KEEP`; same version → `KEEP`.
- `PlanAutoTargetTest`: today's first unrecorded; today's all recorded → the next future day; past days ignored; a day without
  beats skipped; inactive plan → null; every `Switch` outcome incl. `lastAutoDayId` equal, manager open, recording, no set.

**Check**: the tests above pass; `:app:assembleDebug`.

## S2 — Automatic save of every video's set, and the once-per-video switch
**Depends on:** S1. **Read first:** `M/contentplan/ContentPlanCard.java` (`refresh`, `bind`, `useAsTopics`, `buildSlides`,
`cleanUp`, `practiceLauncher`), `M/db/SlideRepository.java`, `MainActivity` lines 3705-3760 (`activeSetId`,
`switchSlideSet`), `contentplan/ContentPlanPracticeActivity.java` (result `EXTRA_DAY_ID`).

**Do**:
1. New `M/contentplan/PlanSetSync.java` (main-thread glue, constructor `(SlideRepository slides, PlanSetIndex index,
   SlideRepository.Callback<List<Integer>> onRemoved)`; `onRemoved` is `host::discardSlideData`).
   `static List<Slide> buildSlides(PlanWeek.Day day)`: move the body of `ContentPlanCard.buildSlides` here unchanged (TEXT
   slide per beat, hook cut to 200 chars as a safety net); first grep `app/src/test` for `buildSlides` and update any caller.
   `void sync(PlanWeek pw, Runnable done)`: for every day of `pw.week.days` that has beats, one after the other (chain the
   callbacks, no parallel work): `slides.getSet(setId, existing -> …)`; compute `PlanSetSignature.of(existing)`;
   `PlanSetPlanner.decide(...)`:
   - `CREATE` / `REPLACE`: `slides.replaceSet(setId, built, removed -> { onRemoved.onResult(removed);
     index.put(new Entry(day.id, brand, day.title, day.date, day.kind, day.version, PlanSetSignature.of(built), now)); next })`.
   - `ADOPT`: `index.put(new Entry(..., signature = null ...))`, next.
   - `KEEP`: next (when an entry exists and the version did not change nothing is written).
   Call `done` after the last day. Never throws into the UI: wrap in try/catch, a failure just calls `done`.
2. `ContentPlanCard`:
   - Delete `cleanUp(...)` and its call (D3: old weeks are kept). Delete `useAsTopics`, `onUseClicked`, the
     `cplan_set_version:` prefs use and `PREF_VERSION_PREFIX`. Keep a small `showSet(PlanWeek.Day day)`: if the day's set has
     rows, `host.switchSlideSet(Slide.planSetId(day.id))` then `afterSwitch()`; the Practice result calls it. The
     `btnUse` button keeps compiling for now but is removed in S4: make its click call `showSet(selected)` or, when the
     plan set is already on screen, `host.switchSlideSet(null)` (the old toggle), nothing else.
   - In `refresh`, after `store.saveWeek(...)` and the `bind(fetched)` post: call `sync.sync(fetched, () -> maybeAutoSwitch(fetched))`.
     Also call `maybeAutoSwitch(pw)` at the end of `rebind()`.
   - `maybeAutoSwitch(PlanWeek pw)`: **first line: `if (!host.isFreshLaunch()) return;`** (D2: never right after a
     recording, never on a rotation or coming back to the screen). Then `today = localToday(pw)`;
     `day = PlanAutoTarget.target(pw, today, id -> store.recordedLocally(id) || (the day's server status is RECORDED))`;
     `slides.getSet(setId, existing -> { Switch s = PlanAutoTarget.decide(day, prefs.getString("cplan_last_auto_day", null),
     host.activeSetId(), host.isRecordingNow(), host.isTopicManagerOpen(), !existing.isEmpty());
     if (s != NONE) prefs.putString("cplan_last_auto_day", day.id); if (s == SWITCH) host.switchSlideSet(setId);
     if (day == null || s != NONE || day.id.equals(prefs.getString("cplan_last_auto_day", null))) host.clearFreshLaunch();
     afterSwitch(); })` (read the stored day id **before** writing it, so the clear rule sees the earlier value). **No Toast, no dialog.** `prefs` = `host.getSharedPreferences(RecordSettings.PREF_NAME, MODE_PRIVATE)`.
     The flag stays set when `NONE` was caused only by recording, an open Topic Manager or a set that is not saved yet (the
     later `rebind()`/`sync` call retries); it is cleared once a real decision was made or there is no target.
3. `MainActivity`: add next to `activeSetId()` (about line 3714)
   `public boolean isRecordingNow() { return isRecording; }`,
   `public boolean isTopicManagerOpen() { return managerDialog != null && managerDialog.isShowing(); }`, and a field
   `private boolean freshLaunch;` set in `onCreate` to `savedInstanceState == null` (before the card is created, about
   line 357), with `public boolean isFreshLaunch() { return freshLaunch; }` and `public void clearFreshLaunch() { freshLaunch = false; }`.
4. `PlanSetSync` is created in the `ContentPlanCard` constructor: `new PlanSetSync(slides, PlanSetIndex.get(host), host::discardSlideData)`.

**Check**: `:app:assembleDebug`; `--tests '*contentplan*'`. Owner on a device (not you): open the app with a plan → the
week's videos exist as sets (visible in S3), today's topics are on screen without a tap; record → the same topics stay on
screen (retake possible); close and open the app again → the next video's topics are on screen; edit a topic of a set,
change the server text → the edit stays; delete nothing happens on its own.

## S3 — Set chips in the Topic Manager, Topic sets sheet, drawer entry, first-recording flag
**Depends on:** S2. **Read first:** `MainActivity` `setupDrawer()` (about 799-845), `tintDrawerIcons()` (about 786),
`drawerItemName()` (about 1746), `openSlideManagerDialog()` (about 3144-3260: `slideAdapter` is the manager's adapter and
`switchSlideSet` reloads it through `loadSlidesFromDb`), `onResume` (about 2370-2385);
`res/menu/drawer_menu.xml`; `res/layout/dialog_slide_manager.xml` (header row, lines 10-40);
`ContentPlanCard.showWeekSheet()` (programmatic bottom-sheet rows, the style to copy) and `openWebPage()`;
`service/ScreenRecordService.java` lines 505-540.

**Do**:
1. New `M/contentplan/ContentPlanWeb.java`: `static void open(MainActivity host)` = the body of `ContentPlanCard.openWebPage()`
   moved (own single-thread executor, same fallback). `ContentPlanCard.openWebPage()` now just calls it.
1b. New `M/contentplan/TopicSetList.java` (shared by the sheet and the chips):
   `static void load(MainActivity host, SlideRepository.Callback<List<Item>> cb)`; `Item` = `setId` (null = My topics),
   `title`, `date` (null for My topics), `recorded` (boolean), `missed` (boolean, `date < today` and not recorded), `isToday`,
   `onScreen` (`Objects.equals(setId, host.activeSetId())`). Order: My topics first, then the videos by date ascending. A
   video is listed when its id is in `slides.planSetIds(...)`; its title/date come from `PlanSetIndex.get(host)` (an id with no
   entry gets title `cplan_set_unknown`, date null, sorted last). `today` comes from the cached week
   (`ContentPlanStore.get(host).weekJson(workspaceId)` → `PlanWeek.parse` → `ContentPlanCard.localToday`; make `localToday`
   package-visible if it is not), or the device date when there is no cache.
1c. New `M/contentplan/TopicSetChips.java`: `static void bind(MainActivity host, HorizontalScrollView scroll, LinearLayout row)`:
   calls `TopicSetList.load`; if the list has **no video sets** → `scroll.setVisibility(GONE)` and return; else `VISIBLE`,
   clear `row`, add one chip (a `TextView`) per item: height 40dp, horizontal padding 14dp, 4dp gaps, text 14sp, one line,
   `ellipsize end`, max width 220dp, text `title` (My topics → `cplan_my_topics`; videos → `"<EEE d MMM> · <title>"` plus
   " ✓" when `recorded`). On screen: filled accent `#64B5F6`, text `#0B1620`; others: `#2A2A2A`, text `#FFFFFF`; rounded
   20dp (a `GradientDrawable`). Tap → `host.switchSlideSet(item.setId)` (it reloads the manager list), then
   `bind(...)` again to move the highlight, and `scroll.post(() -> scroll.smoothScrollTo(selectedChip.getLeft() - dp(16), 0))`.
   While recording `switchSlideSet` refuses with its own toast; do nothing else. No other toasts or dialogs.
2. New `M/contentplan/TopicSetsSheet.java`: `static void show(MainActivity host)`; a `BottomSheetDialog`, dark `#121212`, rows
   `#222222`, accent `#64B5F6`, same look as the old week sheet, built from `TopicSetList.load`. Content, top to bottom:
   - Heading "Topic sets" (string `cplan_sets_title`).
   - Row **"My topics"** (`cplan_my_topics`), always first. Tap → `host.switchSlideSet(null)`, dismiss.
   - One row per video from `TopicSetList` (date `EEE d MMM`, title, and a state word: "Recorded ✓", "Missed" or "Today").
     Tap → `host.switchSlideSet(Slide.planSetId(dayId))`, dismiss. The row of the set on screen shows
     "On screen" (`cplan_on_screen`) in accent colour. A small text button "Delete" (`cplan_delete`) at the end of each video row
     (not on My topics): an `AlertDialog` "Delete these topics? This cannot be undone." with Cancel / Delete; on Delete
     `host.slideRepository().deleteSetOrOwn(setId, removed -> { host.discardSlideData(removed); if it was on screen,
     host.switchSlideSet(null); rebuild the sheet })`. Do **not** remove the `PlanSetIndex` entry (so the set is not
     brought back, rule 3 of the planner).
   - Footer button "Plan videos on the website" (`cplan_open_web`) → `ContentPlanWeb.open(host)`. With no video sets, one calm
     line above it: "Your AI video plans appear here." (`cplan_sets_empty`).
   All rows 48dp minimum height, `textDirection` first-strong on titles. No alert, no toast.
3. Drawer: add to `res/menu/drawer_menu.xml`, right after `nav_edit_slides`, an item `nav_topic_sets` (icon `@drawable/ic_slides`,
   title `@string/cplan_nav_topic_sets` = "Topic sets", `android:visible="false"`). In `setupDrawer` handle it:
   `TopicSetsSheet.show(this)`. Add `"topic_sets"` to `drawerItemName`. New `private void refreshTopicSetsEntry()`: visible when
   prefs `recorded_once` (boolean, in `app_prefs`) is true **or** `PlanSetIndex.get(this).all()` is not empty; call it at the end
   of `setupDrawer` and in `onResume`.
4. First-recording flag: in `ScreenRecordService`, in the same success branch as the plan report (its own try/catch), set
   `getSharedPreferences(RecordSettings.PREF_NAME, MODE_PRIVATE).edit().putBoolean("recorded_once", true).apply()`. Nothing
   else: people who recorded before this update get the drawer entry after their next recording, or at once if a plan set
   exists (owner decision 2026-11-25: no recordings-folder lookup).
5. Chip row in the Topic Manager: in `res/layout/dialog_slide_manager.xml` add, directly under the header `RelativeLayout`, a
   `HorizontalScrollView` `@+id/manager_set_chips` (`scrollbars none`, `visibility gone`, `layout_marginBottom 12dp`) holding
   a horizontal `LinearLayout` `@+id/manager_set_chips_row`. In `openSlideManagerDialog()` call
   `TopicSetChips.bind(this, chipsScroll, chipsRow)` once the dialog view exists. It is bound afresh on every open, and a
   chip tap re-binds itself, so no other call is needed.

**Check**: `:app:assembleDebug`. Owner on a device: a fresh user sees no "Topic sets" in the drawer and no chip row until the
first recording / a plan exists; a user with plan sets sees both at once; "Edit Topics" shows the chips, one tap switches the
set and the list below changes with it; the sheet lists every set, deleting asks first and the set does not come back after
the next refresh.

## S4 — Slim strip instead of the big card; proper buttons
**Depends on:** S2, S3. **Read first:** `res/layout/view_content_plan_today.xml`, `res/layout/activity_main.xml` lines 190-240
(where `top_menu_bar` and the include sit), `ContentPlanCard.java` (whole file), `res/values/themes.xml`,
`res/layout/activity_content_plan_practice.xml` (it uses `cplan_use_topics`).

**Do**:
1. Rewrite `res/layout/view_content_plan_today.xml` (keep the root id `card_content_plan`, the `layout_below` the menu bar and
   `visibility="gone"`): a `MaterialCardView`, `cardBackgroundColor="#CC1E1E1E"`, stroke 1dp `#33FFFFFF`, radius 12dp,
   `cardElevation="0dp"`, margin 8dp. One horizontal row, `minHeight 48dp`, start padding 12dp, end 4dp, centred vertically:
   a vertical block (weight 1) with `cplan_label` (11sp, `#B3FFFFFF`, 1 line) over `cplan_title` (15sp bold white, 1 line,
   `ellipsize end`, `textDirection firstStrong`), then a **filled** button `cplan_btn_practice`
   (`style="@style/Widget.MaterialComponents.Button"`, `backgroundTint #64B5F6`, text colour `#0B1620`, 14sp,
   `minHeight 40dp`, `insetTop/insetBottom 4dp` so the tap area is 48dp, `cornerRadius 20dp`, text `@string/cplan_practice`).
   Do not use any `Widget.Material3.*` style. The ids `cplan_chip`, `cplan_why`, `cplan_buttons`, `cplan_btn_use`,
   `cplan_btn_week`, `cplan_btn_setup`, `cplan_status` are removed.
2. `ContentPlanCard.java`: remove every use of the removed views and of `showWeekSheet`, `pickedDayId`, `showBackIfPlanSetActive`,
   `openWebPage`, `btnUse`, `btnWeek`, `btnSetup`, `status`, `chip`, `why`, `buttons`, `angleLabel`/`ANGLES` (no longer needed
   here; the Practice screen has its own). New `bind(PlanWeek pw)`: the strip is shown (`setContent(true)`) **only** when the plan
   is active and ready, and `pw.daysFor(today)` has at least one unrecorded day with beats; that first day is `selected`, the label is
   `cplan_today` (+ `cplan_status_join` with `cplan_more_today` when other unrecorded days exist today), the title is `day.title`.
   In every other case `setContent(false)` (no plan, setup, writing, failed, stale, nothing left today, all recorded).
   Practice button → the existing Practice intent. Tapping the strip (not the button) → `TopicSetsSheet.show(host)`.
   `refresh`, `rebind`, `maybeAutoSwitch`, `showSet`, `localToday` stay as S2 left them.
3. Delete the now-unused strings from `strings_content_plan.xml` only after `grep -rn "<name>" app/src` shows no use
   (candidates: `cplan_setup_title`, `cplan_setup_open`, `cplan_writing`, `cplan_failed`, `cplan_stale`, `cplan_week_done`,
   `cplan_back_topics`, `cplan_week`, `cplan_next`, `cplan_video`, `cplan_week_title`, `cplan_angle_*` if unused by the
   Practice screen). Keep `cplan_use_topics`, `cplan_recorded`, `cplan_day_today`, `cplan_day_missed`, `cplan_your_idea` and the
   Practice strings.
4. Tick all four cards in **Progress**.

**Check**: `:app:assembleDebug`; `--tests '*contentplan*'`. Owner on a device: with a video to record today the strip shows
one line with a filled Practice button that is easy to tap; after recording it is gone; with no plan nothing shows; the strip
does not hide the topic text (if it does, reduce its margin, do not add more UI).

---

## Progress
- [x] S1 set index, signature, decisions (pure + tests)
- [x] S2 automatic save of every video's set, once-per-video switch
- [ ] S3 set chips in the Topic Manager, topic sets sheet, drawer entry, first-recording flag
- [ ] S4 slim strip, proper buttons

| Card | What | Depends on | Done |
|---|---|---|---|
| S1 | Index, signature, planner, target policy | contract | ☑ |
| S2 | Auto-save sets, auto-switch once per video | S1 | ☑ |
| S3 | Manager chip row, sets sheet, drawer entry | S2 | ☐ |
| S4 | Slim strip, buttons, string cleanup | S2, S3 | ☐ |
