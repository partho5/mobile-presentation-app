
Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.

create a sharp plan.
What alternative approaches could have been considered besides the planned implementation? Among them, which approach would best ensure a frictionless UX while achieving the goal?
What side effects could result from the decision you made? Does it conflict with the core goal or compromise the best possible frictionless UX?

---------------------

1. 
In the screen face cam background removing, when elapsed more than 20 second, smooth animation appear message in addition to all the existing text, 
"This is the most time consuming part. Please be patient". Not bold but regular font. .
And this message stay until finished. 


2. When after recording video player preview is displayed, at the centre original video is there currently. 
At the left and right side there is small space still. There show the small preview and partially, not fully , of the other aspect ratio video. 
The UI will be traditional known feel of card swiping or previous next video swiping.
But when cropping is ongoing, no need to show anything there. Immediately after cropped versions available with smooth fed in show those, may be one or more depending on the crop settings.


3. In the topic manager currently there is no delete all topics. It is needed when one topic lecture is done and user wants to delete all those and create new.
Every topic card has delete icon at the right most, so in the header at the right most even right of  the 'more' button we can put exact same delete icon. Clicking will show delete all the topics? 

4. Slide changing animation is currently very basic and doesn't work for all kinds of slide consistently. Check what are the lacks. What's your proposal to ensure every slide changing is very premium style *eye soothing* visual. My paid customers are not going to pay for some basic UI UX.

=====================================================================
# IMPLEMENTATION PLAN (prepared 2026-10-13, against commit e4f78e8)
=====================================================================

## 0. How to use this plan

One part = one task above. Build **one part per session** ("build part N"), tick the tracker, commit.
Recommended order (small to large): **1 → 3 → 4 → 2**.

| Part | Task | Size | Status |
|------|------|------|--------|
| 1 | "Please be patient" message after 20 s | S | [x] |
| 2 | Video preview: swipeable aspect-ratio versions | L | [ ] |
| 3 | Delete all topics | S | [x] |
| 4 | Premium slide-change transition | M | [x] |

Rules for the implementer (read before every part):

1. The app is **Java** (no Kotlin). `MainActivity.java` is ~3600 lines: **find code by the symbol named here, not by line number** (line numbers are approximate).
2. Everything under "Decided" is final. Do **not** re-ask the user and do not pick a different approach.
3. Touch only what the part lists. Do not reformat or "clean up" neighbouring code.
4. Every "PITFALL" line describes a mistake that is easy to make. Read all of them before writing code.
5. After each part: `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` must pass, then run the part's manual checks on a device, then one git commit for that part.
6. Existing style: UI strings are hardcoded in layouts/Java (no strings.xml entry needed), fully-qualified `android.animation.*` names are used inside `MainActivity`. Match it.

### User's answers (confirmed 2026-10-13) — these are final, do NOT ask for confirmation again

The header of this file says "ask for my confirmation". That confirmation has been given below; start building.

1. **Patience message (Part 1):** it is a **standalone** message. Its only purpose is to reassure a user who wonders why background removal takes so long. It has **nothing to do with Cancel**. If Cancel is tapped, cancel exactly as today; no cancel code may show, hide or otherwise touch this message.
2. **Version order (Part 2):** **9:16 is always the first priority, everywhere** (Settings lists it first too). In the preview it is the version directly to the **right** of the original. Final order: `[4:5] [original] [9:16] [1:1]`.
3. **Carousel in My Videos (Part 2):** yes, same behaviour there.
4. **Delete all (Part 3):** yes — one confirm, permanent, including image/video/drawing files. Hint for the future: in the next version topics will be synced to a server API; delete will then remove the **local** copy only and the server decides what to do with its copy. Build nothing for that now, but keep the local delete in one isolated method so a sync hook can be added later.
5. **Slide transition (Part 4):** yes — build the fade-through with directional drift; the user will judge the look on a device.

---------------------------------------------------------------------

## PART 1 — "This is the most time consuming part. Please be patient"

### Intent
On the full-screen "Replacing facecam background using AI" screen, once the replace has been running for more than 20 s, an extra line fades in smoothly below the existing texts. Regular weight (not bold). Nothing existing is removed. It stays until the screen closes.

### Where the code is
- Layout: `app/src/main/res/layout/dialog_processing.xml`
- Dialog logic: `MainActivity.showProcessingDialog(String stage, int percent)` and `dismissProcessingDialog()` (≈ L3290–3400)
- Timing source: `service/BgReplaceService.java`, method `runJob(Job)` (already has a local `long startMs = SystemClock.elapsedRealtime()`)

### Decided
- The 20 s are counted from **when the replace started in the service**, not from when the dialog was created.
- Space for the message is reserved from the start (`invisible`, not `gone`), so nothing jumps when it appears.
- Text is verbatim, broken into two balanced lines: `"This is the most time consuming part.\nPlease be patient"`.
- **Standalone (user-confirmed):** the message depends on one thing only — 20 s of replace time have passed. It is not linked to Cancel in any way. Once visible it stays visible until the screen closes, also while "Cancelling…" is shown.

### Steps
1. **`BgReplaceService`**
   - Add `private static volatile long replaceStartedAtMs;` (0 = no replace running).
   - In `runJob`, set `replaceStartedAtMs = startMs;` on the line right after `long startMs = ...` — it must be set **before** the first `setReplaceStage(job, 0)`.
   - In the existing `finally { MAIN.removeCallbacks(estimate); synchronized (LOCK) { replacing = null; } }` add `replaceStartedAtMs = 0;`.
   - Add `public static long getReplaceElapsedMs()` → `0` when `replaceStartedAtMs == 0`, else `SystemClock.elapsedRealtime() - replaceStartedAtMs`.
2. **`dialog_processing.xml`** — add a `TextView` directly **after** the horizontal `LinearLayout` that holds `processing_tick` + `processing_hint`, and **before** `processing_bar`:
   - `android:id="@+id/processing_patience"`, width `match_parent`, height `wrap_content`
   - `android:layout_marginTop="10dp"`, `android:gravity="center"`, `android:minLines="2"`
   - `android:text="This is the most time consuming part.\nPlease be patient"`
   - `android:textColor="#CCFFFFFF"`, `android:textSize="13sp"`, **no** `textStyle` attribute (regular)
   - `android:visibility="invisible"`, `android:alpha="0"`
   - Change `processing_bar`'s `layout_marginTop` from `36dp` to `16dp` (keeps the overall spacing close to today).
3. **`MainActivity`**
   - Add fields: `private static final long PATIENCE_DELAY_MS = 20_000L;` and `private Runnable patienceRunnable;`.
   - Inside the existing `if (processingDialog == null) { ... }` block (the create-once block), after the cancel-button wiring:
     - `TextView patience = processingDialog.findViewById(R.id.processing_patience);`
     - `long remaining = PATIENCE_DELAY_MS - BgReplaceService.getReplaceElapsedMs();`
     - If `remaining <= 0` (dialog re-created after 20 s): show it immediately — `setVisibility(VISIBLE)`, `setAlpha(1f)`, no animation.
     - Else: `patienceRunnable = () -> { ... }` which sets `VISIBLE`, `alpha 0`, `translationY = 8dp in px`, then `animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(new DecelerateInterpolator())`; schedule with `patience.postDelayed(patienceRunnable, remaining)`.
   - Timer cleanup, in **one** place only: at the top of `dismissProcessingDialog()`, before the dialog is dismissed and nulled — if `patienceRunnable != null` and the dialog exists, `removeCallbacks(patienceRunnable)` on the `processing_patience` view, then set the field to null. This is plain resource cleanup when the screen closes.
   - Do **not** add anything to the `if (cancelling) { ... }` block or to `confirmCancelBackgroundReplace()`.

### PITFALLS
- `showProcessingDialog` runs on **every progress tick**. Scheduling must live only in the create-once block, otherwise the timer restarts forever and the message never shows.
- `onStop` calls `dismissProcessingDialog()` and `onStart` re-creates the dialog. A timer based on dialog creation would make the message disappear and come back 20 s later. That is why the service owns the start time.
- Do not use `gone`: the column is vertically centred, so a view appearing later would make every other element jump.
- Do not add `textStyle="bold"`. Do not change `processing_title`, `processing_hint`, the bar, the percent or the Cancel button.
- **Do not link the message to Cancel.** No hiding, no fading out, no timer reset when the user cancels. If it is visible it stays; if the 20 s pass while "Cancelling…" is shown, it appears as normal. The cancel flow must behave byte-for-byte as it does today.

### Alternatives considered
| Option | Verdict |
|---|---|
| A. Timer started when the dialog is created | Rejected: resets when the user leaves and returns. |
| **B. Service-owned start time, dialog computes the remaining delay** | **Chosen**: correct in every lifecycle path, ~25 lines. |
| C. Service pushes a new callback/stage to the listener | Rejected: more plumbing, and the listener is null while the app is in the background, so the value must be queryable anyway. |
| D. Show a live "about N s left" estimate instead | Better information, but the estimate is unreliable during model warm-up; out of scope. |

### Side effects
- The gap between the hint line and the progress bar is ~25 dp taller before the message appears (reserved space). No conflict with the goal.
- None on the cancel flow: the message and Cancel are independent.

### Manual checks
1. Record with face cam + background replace on, long enough that processing exceeds 20 s → at ~20 s the line fades in and slides up 8 dp; nothing else moves.
2. Press Home at 5 s, return at 30 s → message is already visible, without replaying the animation.
3. Press Home at 5 s, return at 10 s → message appears at 20 s total, not at 30 s.
4. Tap Cancel → "Yes, cancel" after the message is visible → the message stays as it is; "Cancelling…" and the rest of the cancel flow behave exactly as before this change.
5. A short replace (< 20 s) → message never appears.

---------------------------------------------------------------------

## PART 3 — Delete all topics

### Intent
In the Topic Manager, a delete icon (the exact same `ic_delete` as on every topic card) sits at the far right of the add-buttons row, right of "+ More". Tapping it asks for confirmation, then removes every topic so the user can start a new lecture.

### Where the code is
- Layout: `res/layout/dialog_slide_manager.xml` (the horizontal `LinearLayout` with `btn_add_text_slide … btn_add_more_slide`)
- Card layout for reference: `res/layout/item_slide.xml` (`btn_delete_slide`, 36 dp, card padding 12 dp)
- Logic: `MainActivity.openSlideManagerDialog()` (≈ L2665), `onDelete(Slide, int)` (≈ L3087), `loadSlidesFromDb(boolean)` (≈ L1874), `seedInitialSlides()` (≈ L1909)
- DB: `db/SlideRepository.java` (`SlideDao.deleteAll()` already exists, the repository has no wrapper yet)

### Decided
- One confirmation dialog, then **permanent** deletion of all topics, enabled and disabled alike.
- The manager stays open afterwards (the user wants to add new topics right away).
- The topics' own media files and ink drawings are deleted too.
- **Future hint (user, not to build now):** the next version will sync topics to a server API. Delete will then remove only the **local** data and the server decides how to treat its copy. So: keep everything this feature deletes inside the single method `deleteAllSlides()` (step 5), make no network call, and add no "deleted" flags or tombstones to the database. A later sync hook then has exactly one place to attach to.

### Steps
1. **`SlideRepository`**: add `public void deleteAll(Runnable onComplete)` — same shape as `delete(...)`: `executor.execute(() -> { slideDao.deleteAll(); if (onComplete != null) mainHandler.post(onComplete); });`
2. **`dialog_slide_manager.xml`**: add an `ImageButton` as the **last** child of the add-buttons row, after `btn_add_more_slide`:
   - `android:id="@+id/btn_delete_all_slides"`, `36dp` × `36dp`, `android:layout_gravity="center_vertical"`
   - `android:layout_marginStart="4dp"`, `android:layout_marginEnd="12dp"` (12 dp makes it line up exactly above the cards' delete icons)
   - `android:background="?attr/selectableItemBackgroundBorderless"`, `android:src="@drawable/ic_delete"`, `android:contentDescription="Delete all topics"`
   - No tint (the drawable is already red, same as the cards).
3. **`MainActivity.openSlideManagerDialog()`**:
   - `ImageButton btnDeleteAll = dialogView.findViewById(R.id.btn_delete_all_slides);` → click calls `confirmDeleteAllSlides()`.
   - Dim it when the list is empty: register a `RecyclerView.AdapterDataObserver` on `slideAdapter` whose `onChanged()` / `onItemRangeInserted` / `onItemRangeRemoved` set `btnDeleteAll.setEnabled(count > 0)` and `setAlpha(count > 0 ? 1f : 0.3f)` with `count = slideAdapter.getItemCount()`. Apply it once right after `slideAdapter.setSlides(...)`.
4. **`confirmDeleteAllSlides()`** (new, in `MainActivity`):
   - `if (slides == null || slides.isEmpty()) return;` (authoritative guard, independent of the dimming).
   - `AlertDialog`: title `"Delete all topics?"`, message `"All " + n + " topics will be permanently deleted, including their images, videos and drawings. This cannot be undone."` (use `"1 topic"` wording when `n == 1`), negative `"Cancel"`, positive `"Delete all"`.
   - After `show()`, colour the positive button `0xFFE53935` — copy the three lines at the end of `confirmCancelBackgroundReplace()`.
   - Positive → `deleteAllSlides()`.
5. **`deleteAllSlides()`** (new):
   - `final List<Slide> doomed = new ArrayList<>(slides);`
   - `repository.deleteAll(() -> { ... })`, and inside the callback, in this order:
     1. `stopVideoIfPlaying();`
     2. `if (slideInkController != null) for (Slide s : doomed) slideInkController.discard(s.getId());`
     3. `slides.clear(); currentSlideIndex = 0;`
     4. `if (slideAdapter != null) slideAdapter.setSlides(new ArrayList<>());`
     5. `renderCurrentSlide();` (its "no active slides" branch already blanks every slide view)
     6. Mark seeding as done (step 6 below).
     7. Delete media files off the main thread: for each `doomed` slide with a non-null `getImagePath()`, delete the file **only if** its canonical path starts with `getFilesDir().getCanonicalPath()`.
     8. `Toast "All topics deleted"`.
6. **Stop the example topics from coming back** — in `loadSlidesFromDb`, seeding currently runs whenever the list is empty and `openedTimes <= 1`:
   - Add `private static final String KEY_INITIAL_SLIDES_SEEDED = "initialSlidesSeeded";`
   - Condition becomes `openedTimes <= 1 && !prefs.getBoolean(KEY_INITIAL_SLIDES_SEEDED, false)`.
   - Set the flag to `true` at the top of `seedInitialSlides()` **and** in `deleteAllSlides()`.

### PITFALLS
- **Reseed bug**: without step 6, a first-day user who deletes all topics gets the two example topics back as soon as the manager closes (`setOnDismissListener` → `loadSlidesFromDb()`).
- Use `slideInkController.discard(id)` per slide, not only `SlideInkStorage.clearAll(...)`: the controller may hold a pending debounced save for the slide on screen and would write the drawing back after the delete.
- Never delete a file outside `getFilesDir()`; the path check is mandatory.
- `slideAdapter` can be null (the callback may run after the sheet is dismissed) → null-check, as the existing callbacks do.
- Do not route this through `onDelete(...)`: that method is the two-step disable-then-delete flow for one card and shows its own dialogs.
- Four text buttons plus the icon are tight on a 360 dp wide screen. If any label wraps or clips, set `app:iconPadding="2dp"` on the four add buttons first; only if still clipped, lower their `textSize` to `10sp`. Do not shrink the delete icon below 36 dp.

### Alternatives considered
| Option | Verdict |
|---|---|
| **A. Icon right of "+ More" + one confirm dialog** | **Chosen**: what the user described; discoverable; one tap + one confirm. |
| B. No dialog, delete at once + "Undo" snackbar for 5 s | Fewest taps, but file deletion must be deferred and a snackbar inside a bottom sheet is fiddly; a mis-tap wiping a whole lecture is too costly. |
| C. Entry inside the "+ More" popup | Safer from mis-taps but hidden, and "More" means "more topic types" today. |
| D. "Disable all" first, like the per-card flow | Two rounds of confirmation for a clear intent; pure friction. |

### Side effects
- Same icon, different strength: on a card the first tap only disables; in the header it deletes everything. Mitigated by the explicit dialog text and red "Delete all" button.
- Topic media files are now really removed from storage (single-card delete still leaves its file behind — existing behaviour, out of scope).
- After delete-all the main screen is blank until a topic is added (existing empty behaviour).

### Manual checks
1. 5 topics (one disabled, one with ink, one video) → Delete all → confirm → list empty, main screen blank, sheet still open, icon dimmed.
2. Add a text topic right after → it shows as topic 1 and renders.
3. Close and reopen the app → no example topics reappear.
4. Fresh install, first launch: delete all → close the sheet → examples do **not** come back.
5. Cancel in the dialog → nothing changes.
6. Icon is vertically in line with the cards' delete icons; no button label is clipped on a 360 dp wide device.

---------------------------------------------------------------------

## PART 4 — Premium slide-change transition

### Intent
Every slide change, for every slide type and in both directions, must look calm, deliberate and identical in style. No hard cuts, no content popping in late.

### What is lacking today (findings, all in `MainActivity`)
1. **Direction is ignored.** `animateMediaSlideExit` always moves left and `animateMediaSlideEntry` always enters from the right, so "Previous" looks like "Next".
2. **Mixed motion.** Text drops in from the top (`animateTextSlideEntry`, vertical); image/video slide in horizontally; website/YouTube do not animate at all.
3. **No exit for text and web.** They are hidden with `setVisibility(GONE)` — a hard cut.
4. **Same-type changes are hard cuts.** Image→image: Glide swaps the bitmap in place and `onResourceReady` skips the animation (`visible && translationX == 0`), and the view height jumps. Video→video: black box, then the picture pops. Web→web: the previous page stays visible until the new one paints.
5. **Entry is not tied to "content is ready".** Video slides in as a black rectangle; a website appears blank or stale.
6. **Harsh motion.** A full-screen-width travel in 300–350 ms with a plain decelerate, no fade — tiring to watch.
7. **Replays without a slide change.** `renderCurrentSlide()` has ~15 callers (`onResume`, edit, reorder, move up/down, disable…). The text drop replays every time even though the slide is the same.
8. **No single owner.** Rapid taps race: `resetAllSlideZoom()` zeroes `translationX` of a view that is still sliding out, giving a visible snap.
9. **Ink is on its own clock.** `SlideInkController.setSlide` hides the ink and fades it in after a hard-coded 350 ms delay that is not linked to the slide.

### Proposal (Decided): fade-through with a small directional drift, one controller
- Out: the current slide dims to black while drifting 20 dp in the travel direction (180 ms, accelerate).
- Swap: while fully covered, the content is replaced instantly and the new content is given time to become ready.
- In: the new slide appears from black while settling the last 20 dp into place (320 ms, decelerate).
- Next → content moves left. Previous → mirrored. A jump that is not next/previous (manager, delete, disable, first render) → no drift, fade only.
- Because the stage background is black, the "fade" is done with **one black scrim view above the slides**, not by changing the slides' alpha.

Why a scrim: `VideoView` is a `SurfaceView` (view alpha is not reliably applied to it) and a `WebView` with YouTube must not be alpha-blended. A black scrim over a black stage looks identical to a fade, works for all five slide types, and hides every loading artefact (image decode, video first frame, web paint, height jumps, zoom reset).

### Steps
1. **`activity_main.xml`** — inside `root_layout`:
   - Wrap exactly these five existing views, in the same order, in a new `<RelativeLayout android:id="@+id/slide_stage" android:layout_width="match_parent" android:layout_height="match_parent">`: `image_slide_view`, `text_slide_container`, `video_slide_container`, `web_slide_container`, `slide_ink_canvas`. Do not change any attribute of the five views.
   - Directly after `slide_stage` (before `empty_state_view`) add `<View android:id="@+id/slide_transition_scrim">`: `match_parent` × `match_parent`, `android:background="@android:color/black"`, `android:alpha="0"`, `android:visibility="gone"`, `android:importantForAccessibility="no"`. Not clickable, not focusable.
2. **New class `ui/SlideTransitionController.java`** (owns the scrim and the stage translation; nothing else may animate them):
   ```java
   public interface Binder { void bind(Runnable onReady); } // swap content now; call onReady once the new content is drawable
   public SlideTransitionController(View stage, View scrim)
   public void show(int direction, boolean animate, Binder binder) // direction: +1 next, -1 previous, 0 none
   public void cancel()                                            // onDestroy: cancel animators, remove callbacks
   ```
   Constants: `OUT_MS = 180`, `IN_MS = 320`, `READY_TIMEOUT_MS = 700`, `DRIFT_DP = 20`, out interpolator `new PathInterpolator(0.4f, 0f, 1f, 1f)`, in interpolator `new PathInterpolator(0f, 0f, 0.2f, 1f)`.
   States: `IDLE`, `OUT`, `WAITING`, `IN`. Keep an `int token` that is incremented on every `show(...)`.
   - `show(dir, animate=false)` while `IDLE` → `binder.bind(() -> {})` immediately, nothing else. While **not** `IDLE` → treat it as `animate=true`.
   - `show(dir, animate=true)`: store `pendingBinder`, `pendingDir`, `++token`, then by state:
     - `IDLE` or `IN` → cancel running animators, make the scrim `VISIBLE`, animate scrim alpha from its **current** value to 1 and stage `translationX` to `-dir * drift` over `OUT_MS * (1 - currentAlpha)`; state = `OUT`.
     - `OUT` → do nothing more (the end of OUT picks up the newest `pendingBinder`).
     - `WAITING` → call `swap()` again right away (the scrim is already opaque).
   - `swap()` (end of OUT): state = `WAITING` **first**; `stage.setTranslationX(0)`; build `ready` = a run-once runnable bound to the current `token`; `pendingBinder.bind(ready)`; `handler.postDelayed(ready, READY_TIMEOUT_MS)`.
   - `ready` runs → ignore if its token is stale or the state is not `WAITING`; remove the timeout; state = `IN`; `stage.setTranslationX(pendingDir * drift)`; animate stage to 0 and scrim alpha to 0 over `IN_MS`; at the end: scrim `GONE`, state = `IDLE`.
3. **`MainActivity` — fields and wiring**
   - `private SlideTransitionController slideTransition;` created after `findViewById` of `slide_stage` / `slide_transition_scrim`.
   - `private int displayedSlideId = SlideInkController.NO_SLIDE;` and `private int pendingNavDirection = 0;`
   - `goToNextSlide()`: set `pendingNavDirection = 1` right before its `renderCurrentSlide()`; `goToPreviousSlide()`: `-1`.
   - `onDestroy`: `slideTransition.cancel()`.
4. **Split `renderCurrentSlide()`** (keep its name and signature — all callers stay untouched):
   - New body: compute `targetId` (`NO_SLIDE` when there is no active slide, else `slides.get(currentSlideIndex).getId()`); `boolean changed = targetId != displayedSlideId`; `int dir = pendingNavDirection; pendingNavDirection = 0;`; call `updateNavigationButtonsState()` (buttons react at once); then `slideTransition.show(dir, changed, onReady -> bindSlideContent(onReady));`
   - `bindSlideContent(Runnable onReady)` = the **old body**, re-reading `slides.get(currentSlideIndex)` at call time, setting `displayedSlideId`, with all animation calls removed and `onReady` wired per type:
     - Replace every `animateMediaSlideExit(view)` with `view.setVisibility(View.GONE)`.
     - TEXT: remove `animateTextSlideEntry()`; make sure `textSlideView.setTranslationY(0f)` and `setAlpha(1f)`; ready → `textSlideView.post(onReady)`.
     - IMAGE: in `onResourceReady` replace the `if/else` that calls `animateMediaSlideEntry` with `imageSlideView.setAlpha(1f); imageSlideView.post(onReady);`. `onLoadFailed`, missing file and null path → `onReady.run()`.
     - VIDEO: in `onPrepared` call `mp.start()` directly (remove the `animateMediaSlideEntry` branch). Add `videoSlideView.setOnInfoListener((mp, what, extra) -> { if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onReady.run(); return false; });`. Error listener, missing file and null path → `onReady.run()`.
     - WEBSITE / YOUTUBE: add field `private Runnable webSlideReadyCallback;`. Set it to `onReady` before `loadUrl` / `loadDataWithBaseURL`. In the existing `WebViewClient` add `onPageCommitVisible(WebView, String)` and extend `onReceivedError`: both run and null the callback. If the website URL equals `webSlideView.getUrl()` (no reload happens) → `onReady.run()` immediately.
     - "No active slides" branch → hide everything as today, then `onReady.run()`.
   - Delete `animateMediaSlideExit`, `animateMediaSlideEntry`, `animateTextSlideEntry` once nothing calls them.
5. **`stopVideoIfPlaying()`**: also `videoSlideView.setOnInfoListener(null)` next to the two listeners it already clears.
6. **Ink follows what is on screen**: `getCurrentSlideView()` must resolve the slide by `displayedSlideId` (look it up in `slides`), not by `currentSlideIndex`. Otherwise the ink vanishes at tap time, 180 ms before the slide is covered.
7. **`SlideInkController.setSlide`**: remove the delayed reveal (`ink.setAlpha(0f)` + `animate().alpha(1f).setStartDelay(REVEAL_DELAY_MS)…` and the two constants). The ink now sits inside `slide_stage`, under the scrim, and is revealed together with the slide. Keep `ink.animate().cancel(); ink.setAlpha(1f);`.

### PITFALLS
- **The scrim must always be released.** Every branch of `bindSlideContent` (success, failure, missing file, unknown type) must reach `onReady`, and the 700 ms timeout is the safety net. A missing `onReady` = a permanently black screen during a recording.
- `onReady` can fire **synchronously** inside `bind` (Glide memory cache, text). Set state = `WAITING` before calling `bind`, and make `ready` run-once.
- Do not animate `alpha` on `videoSlideView`, `videoSlideContainer`, `webSlideView` or `slide_stage`. Only the scrim's alpha and the stage's `translationX` are animated.
- Do not put the drift on the individual slide views: their `translationX/scale` belong to pinch-zoom (`resetAllSlideZoom`, `setupSlideZoomTouchListeners`).
- The wrapper must be a `RelativeLayout`: `applyMediaTopMargin` and `syncSlideInk` cast the children's params to `RelativeLayout.LayoutParams`. A `FrameLayout` wrapper crashes with `ClassCastException`.
- `ViewPropertyAnimator.withEndAction` does **not** run when the animation is cancelled. Rely on that plus the token check; never start the "in" phase from a cancelled "out".
- `renderCurrentSlide()` with the **same** slide id (onResume, text edit, reorder) must rebind instantly with no transition — but the rebind must still happen (onPause stops the video, onResume has to restart it).
- Taps during a transition must be accepted and retarget to the newest slide. Never block or queue input.
- The scrim must sit above `slide_stage` and below everything else (`empty_state_view`, menu bar, drawing board, face cam, nav zones). Check its position in the XML; do not call `bringToFront()` on it.
- Leave `System animator duration scale` alone: with animations disabled in system settings the framework already makes this instant.

### Alternatives considered
| Option | Verdict |
|---|---|
| **A. Fade-through scrim + 20 dp directional drift, one controller** | **Chosen**: identical for all 5 types, hides async loading, small motion (eye-soothing), low risk in a 3600-line activity. |
| B. True cross-fade using a bitmap snapshot of the outgoing slide (`PixelCopy`) | Most cinematic, old and new visible together. Async capture, `SurfaceView`/DRM/YouTube capture gaps, memory per change. High risk. |
| C. `ViewPager2` with a page transformer (finger-tracked paging) | Would need every slide type as a pager page (WebView, VideoView, ink overlay, pinch-zoom) and conflicts with the rule that swipes starting on the slide are ignored. A rewrite. |
| D. Keep the push animation, only fix direction and missing cases | Cheapest, but full-width lateral motion on every change is the opposite of eye-soothing and async types still pop in. |
| E. Material `MaterialFadeThrough` / `MaterialSharedAxis` via `TransitionManager` | Same look on paper, but it animates view alpha/visibility: breaks on `SurfaceView`, no control over "content ready". |

A is also the most frictionless for the presenter: input is never blocked, and the slide is never shown half-loaded.

### Side effects
- Every change takes ~0.5 s (0.18 out + load + 0.32 in), ~0.15 s more than today. Website slides stay covered until first paint, at most 0.7 s.
- There is a brief full-black moment between slides. On the black stage this reads as a fade, but two near-identical images no longer morph directly.
- The transition is part of recorded videos (intended: this is the premium look the viewers see).
- Text slides lose their "drop from top" entrance; all types now share one style.

### Manual checks
1. Text→image→video→website→YouTube→text with Next, then back with Previous: same style everywhere, direction mirrored, no hard cut, no black box sliding, no stale web page.
2. Image→image and video→video: no pop, no height jump.
3. Tap Next five times quickly: no snap, no stuck black screen, ends on the right slide.
4. Topic whose image file is missing / a video that fails / airplane mode on a website slide: the screen is revealed again within ~0.7 s.
5. Edit a text topic, reorder topics, leave and return to the app: the current slide does **not** replay a transition; a video slide resumes playing.
6. Pinch-zoom a slide, draw ink on it, change slide and come back: zoom is reset, ink appears together with its slide.
7. Delete or disable the current topic, and delete all (Part 3): fades to the next slide / to blank.
8. Record a short video while changing slides: transitions look the same in the recording.

---------------------------------------------------------------------

## PART 2 — Video preview: swipeable aspect-ratio versions

### Intent
The post-recording preview shows the original video in the centre, as today. The free space left and right of it shows a **small, partly cut-off** preview of the other aspect-ratio versions (the auto-crops). It behaves like familiar card / previous-next swiping. While the crops are still being made, the sides stay empty; each version fades in the moment it is ready.

### Where the code is
- Dialog: `ui/VideoPlayerDialog.java` (one static `show(...)`, 380 lines) + `res/layout/dialog_video_player.xml`
- Callers: `MainActivity.onRecordingReady` + `bindCropStatus` (≈ L3400–3452) and `adapter/RecordingsAdapter` (≈ L464, with a rename/delete listener)
- Version link: `util/VideoGroupStore.java` (SharedPreferences map `path → groupId`; a source and its crops share one id)
- Crop lifecycle: `util/VideoCropHelper.java` — `ACTION_CROP_STATE_CHANGED` broadcast, `getPendingCrops()`, `isPartialFile(...)`
- Receiver registration to copy: `SavedRecordingsActivity` ≈ L149–153

### Facts the design depends on
- Crops keep the full width and drop rows from the bottom, so the original is always the **tallest** version (smallest width/height).
- A crop is written to a hidden `.part.mp4` and renamed into place only when complete; `ACTION_CROP_STATE_CHANGED` is sent after each crop finishes (success or failure) and once when the crops are queued.
- The group id of a fresh recording is created only when cropping **starts**, which can be after the dialog is already open.
- `minSdk` is 31. `ViewPager2` and `DiffUtil` are available (add `implementation 'androidx.viewpager2:viewpager2:1.1.0'` to `app/build.gradle` explicitly).

### Decided
- `ViewPager2` + a `PageTransformer` (real finger-tracked swiping with standard snap physics).
- One page per version. Every page shows a thumbnail; only the centred page owns a playing `VideoView`.
- Fixed order, independent of arrival time. Sort versions by width/height ascending (tallest first) → `L`. Display order = `[L2] [L0] [L1] [L3] [L4…]` (skip what does not exist). So: first crop appears on the right, second on the left, later ones further right.
- **9:16 is always first priority (user-confirmed):** whenever a 9:16 version exists it must be the version directly to the **right** of the original. The sort above already guarantees this (9:16 is the tallest crop, so it is `L1`); with all three crops the result is `[4:5] [original] [9:16] [1:1]`. If this ordering code is ever changed, this rule must still hold. If 9:16 is switched off in Settings, the next ratio in the Settings order (4:5, then 1:1) takes the right-hand place.
- Tap on a side preview = go to it. Tap on the centre = play/pause (as today).
- Switching version keeps the playback position and the play/pause state (same moment, other framing).
- With one version only, the dialog must look and behave exactly as it does today.
- Works wherever `VideoPlayerDialog` is shown (post-recording and My Videos), starting on the file that was opened.
- Delete keeps today's behaviour: deletes the current file and closes the dialog.
- `VideoPlayerDialog.show(Context, File, OnVideoActionListener)` keeps its signature and still returns the `Dialog`; the ids `crop_status_row`, `tv_crop_icon`, `tv_crop_status` stay in the dialog layout (`MainActivity.bindCropStatus` looks them up).

### Geometry (all values in px; convert dp with display density)
- `dialogW = 0.92 * screenW` (unchanged). `stageW = dialogW - 2dp` (pager spans the dialog, see layout step).
- `maxCardH = max(200dp, 0.90 * screenH - 270dp)` (unchanged).
- `maxCardW` = `min(0.82 * screenW, stageW)` when there is 1 version (unchanged); `stageW - 2 * (30dp + 10dp)` when there are 2 or more (reserves room for a peek on each side).
- Card size per version (same rule as today's `sizePlayerCard`): `aspect = w / h`; if `aspect < maxCardW / maxCardH` → `cardH = maxCardH`, `cardW = round(maxCardH * aspect)`; else `cardW = maxCardW`, `cardH = round(maxCardW / aspect)`; then `cardW = max(cardW, 140dp)`.
- Pager height = the largest `cardH` among the versions.
- Constants: `SIDE_SCALE = 0.80`, `SIDE_ALPHA = 0.5`, `GAP = 10dp`.
- For a page at adapter index `i` with transformer position `d` (0 = centred, +1 = one to the right):
  - `scale = lerp(1, SIDE_SCALE, min(|d|, 1))`
  - `alpha = |d| <= 1 ? lerp(1, SIDE_ALPHA, |d|) : max(0, lerp(SIDE_ALPHA, 0, |d| - 1))`
  - `towardCentreW = d > 0 ? cardW[i-1] : cardW[i+1]` (use `cardW[i]` if that index is out of range)
  - `D = towardCentreW / 2 + GAP + SIDE_SCALE * cardW[i] / 2`
  - `centreOffset = |d| <= 1 ? d * D : sign(d) * (D + (|d| - 1) * stageW)`
  - `page.setTranslationX(centreOffset - d * stageW)` (ViewPager2 has already placed the page at `d * stageW`)
  - `page.setScaleX/Y(scale)`, `page.setAlpha(alpha)`; pivot stays at the page centre.
  - This keeps exactly `GAP` between the centre card and its neighbour during the whole swipe.

### Steps
1. **`VideoGroupStore`**: add `public static synchronized List<String> getGroupPaths(Context, String path)` → the group id of `path`; if none, a list with just `path`; else every key in the prefs with the same id (always include `path` itself).
2. **New pure class `ui/PlayerCarouselMath.java`** (no Android imports): `fitCard(...)`, `displayOrder(List<T> sortedTallestFirst)`, `scale(d)`, `alpha(d)`, `centreOffset(d, ownW, towardCentreW, gap, stageW)`. Add `PlayerCarouselMathTest` in `app/src/test/.../ui/`:
   - order: 1 → `[L0]`; 2 → `[L0, L1]`; 3 → `[L2, L0, L1]`; 4 → `[L2, L0, L1, L3]`
   - 9:16 priority: for original + 9:16 + 4:5 + 1:1 (in any input order, sorted first) the element right after the original is the 9:16 one
   - `centreOffset(0, …) == 0`; at `d = ±1` equals `±D`; at `d = ±0.5` the distance between two neighbouring cards' edges equals `GAP`
   - `fitCard` for 1080×2340, 1080×1920, 1080×1080 and 1920×1080.
3. **New layout `res/layout/item_player_page.xml`**: root `FrameLayout` `match_parent` × `match_parent`; inside it a `MaterialCardView` `@+id/page_card` with `layout_gravity="center"` and the **same** card attributes as today's `player_card_container` (black, 16 dp radius, no elevation, 1 dp `#1FFFFFFF` stroke); inside the card a `FrameLayout` with, in this z-order: `VideoView @+id/page_video` (`match_parent`, `visibility="gone"`), `ImageView @+id/page_thumb` (`match_parent`, `centerCrop`), `View @+id/page_tap` (`match_parent`, transparent).
4. **`dialog_video_player.xml`**:
   - Root `LinearLayout`: add `android:clipToPadding="false"` and `android:clipChildren="false"`.
   - Replace the whole `MaterialCardView @+id/player_card_container` block with `<androidx.viewpager2.widget.ViewPager2 android:id="@+id/player_pager" android:layout_width="match_parent" android:layout_height="280dp" android:layout_marginStart="-15dp" android:layout_marginEnd="-15dp" android:layout_marginBottom="8dp" />`.
   - Everything else in the layout stays.
5. **Refactor `VideoPlayerDialog`** into an instance-based controller behind the unchanged static `show(...)` (private constructor, fields instead of the `final` one-element arrays):
   - Model `Version { long id; File file; int w, h; int cardW, cardH; boolean animateIn; }` — `id` from a counter (stable across rename).
   - Fields: `List<Version> versions`, `String playingPath`, `VideoView activeVideo`, `PageAdapter adapter`, `ViewPager2 pager`, `boolean dismissed`, a single-thread executor, the existing progress handler/runnable.
   - `PageAdapter extends RecyclerView.Adapter`: `setHasStableIds(true)`, `getItemId = version.id`; `onBind` sets `page_card` size to `cardW × cardH`, loads the thumbnail with `Glide.with(context).load(version.file).apply(RequestOptions.frameOf(1_000_000L)).centerCrop().into(page_thumb)`, sets the tap listener; if `version.animateIn` → set `page_card` alpha 0 and animate to 1 over 400 ms (decelerate), then clear the flag.
   - Pager setup: `setOffscreenPageLimit(3)`, `setPageTransformer(...)` using the geometry above, inner `RecyclerView` (`pager.getChildAt(0)`) → `setOverScrollMode(View.OVER_SCROLL_NEVER)`, `pager.setLayoutDirection(View.LAYOUT_DIRECTION_LTR)`, `pager.setUserInputEnabled(versions.size() > 1)`.
   - `reloadVersions()`: on the executor — `getGroupPaths`, keep only files that `exists() && length() > 0`, are not `VideoCropHelper.isPartialFile(name)` and whose path is not an `outputPath` in `VideoCropHelper.getPendingCrops()`; read width/height with `MediaMetadataRetriever` (swap them when `METADATA_KEY_VIDEO_ROTATION` is 90/270); sort by `w/h` ascending; apply `displayOrder`. Post to main → `applyVersions(newList)`.
   - `applyVersions(newList)`: return if `dismissed`; reuse existing `Version` objects by path (keeps ids); mark versions that were not present before as `animateIn = true` (not on the very first load); recompute `maxCardW`, every `cardW/cardH`, the pager height; update the adapter through `DiffUtil` (never `notifyDataSetChanged`, never a new adapter); then `pager.post(() -> { pager.setCurrentItem(indexOf(playingPath), false); pager.requestTransform(); })`.
   - Opening: size the pager from the opened file's own dimensions **before** `dialog.show()` (read once on the main thread, as `updateMetadataUI` already does), set `versions = [opened]`, activate it, then call `reloadVersions()`.
   - `activate(index)`: if `versions.get(index).file` path equals `playingPath` **and** `activeVideo` is already bound → return. Otherwise remember `seekMs` and `wasPlaying` from the old `activeVideo` (first activation: 0 / true), deactivate the old page (`stopPlayback()`, `page_video` `GONE`, `page_thumb` visible with alpha 1), then on the new holder: `page_video` `VISIBLE`, `setVideoPath`, `onPrepared` → seek bar max, total duration, `seekTo(seekMs)`, start or stay paused per `wasPlaying`, play/pause icon, progress runnable; `setOnInfoListener` → on `MEDIA_INFO_VIDEO_RENDERING_START` fade `page_thumb` out (120 ms); `onCompletion` as today. Update `playingPath`, `activeVideo`, and the filename/metadata card via `updateMetadataUI`.
     - Holder lookup: `((RecyclerView) pager.getChildAt(0)).findViewHolderForAdapterPosition(index)`; if null, retry once via `pager.post(...)`.
   - `pager.registerOnPageChangeCallback`: `onPageSelected(pos)` → `activate(pos)`.
   - Tap on `page_tap`: if the holder's `getBindingAdapterPosition() == pager.getCurrentItem()` → toggle play/pause; else `pager.setCurrentItem(position, true)`.
   - Seek bar, play/pause button, rename, share, delete: same logic as today, operating on `activeVideo` and the current `Version.file`. Rename: in `onRenamed` update that `Version.file` and `playingPath`, re-bind the active page with the saved position, refresh its thumbnail.
   - Crop updates: register a receiver for `VideoCropHelper.ACTION_CROP_STATE_CHANGED` with `ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)` **before** the first `reloadVersions()`; `onReceive` → `reloadVersions()`.
   - Dismiss listener: `dismissed = true`, remove progress callbacks, `stopPlayback()`, unregister the receiver (try/catch), shut the executor down, `pager.setAdapter(null)`.
   - Remove `sizePlayerCard` (replaced by `PlayerCarouselMath.fitCard`). Before deleting the old ids, grep the project for `player_card_container`, `player_video_view`, `btn_center_play_pause` — they must only be referenced from this dialog.
6. **`MainActivity.bindCropStatus`**: change the success text `"Edited versions saved"` to `"Edited versions saved · swipe to preview"`. Nothing else in `MainActivity` changes; `BgReplaceService` is untouched.

### PITFALLS
- **Identity is the file path, never the index.** A version inserted on the left shifts every index; `onPageSelected` then fires with a new index for the same file. `activate` must do nothing in that case, or the video restarts every time a crop finishes.
- Never rebuild the adapter or call `notifyDataSetChanged()` when versions change — it recreates the playing `VideoView`. Use stable ids + `DiffUtil`.
- The transformer sets alpha/scale/translation on the **page root**. The arrival fade must animate the **child** `page_card`. Two animations on the same property of the same view fight each other and the side preview flickers or stays invisible.
- "Nothing while cropping": the group store lists crop paths **before** the files exist. Filter by `exists()`, non-zero length, not pending, not a `.part.mp4`. Do not show placeholders or spinners in the side space.
- The dialog can open before the group exists (crop not started yet): the first load returns only the original. That is correct — the broadcast triggers the next reload.
- Register the receiver before the first load, otherwise a crop finishing in between is missed and its preview never appears.
- A newly arrived version must not resize or restart the playing card. For a normal portrait recording the centre card is height-limited, so its size is the same with 1 or 2+ versions — verify this on a device.
- `ViewPager2` pages must be `match_parent` in both directions (it throws otherwise). The hug-the-aspect sizing is on `page_card` inside the page.
- Set the pager's pixel height in code before showing; the dialog window is `WRAP_CONTENT`.
- Do not animate `alpha` on the `VideoView`. The thumbnail on top of it is what fades.
- Keep exactly one active `VideoView`. Leaving a second one prepared plays two audio tracks at once.
- The receiver and the executor outlive the dialog unless released in the dismiss listener (leak + callbacks into a dead dialog). Check `dismissed` at the start of every posted callback.
- `OnVideoActionListener` (rename/delete callbacks used by My Videos) must be called with the file of the **current** version.

### Alternatives considered
| Option | Verdict |
|---|---|
| **A. `ViewPager2` + transformer, thumbnails on the sides, one live `VideoView`** | **Chosen**: the "known" swipe feel with system snap physics, exact peek geometry, modest code. |
| B. Custom stage view with own drag/fling handling | Full control and no adapter subtleties, but hand-written gesture and settle code is the classic source of bugs. |
| C. Keep one fixed card, add two static thumbnails; tap swaps with an animation | Simplest, but no finger tracking — it would not feel like swiping. |
| D. Row of aspect-ratio chips ("Original · 9:16 · 4:5") under the video | Very clear and cheap, but not what was asked and uses no side space. Could be added later as a complement. |
| E. Plain order `[original, crops…]` (append only) | Zero insert-on-the-left risk, but the left side would stay empty for ever. Rejected because the request is explicitly "left and right". |

A gives the least friction: versions appear by themselves, one swipe or one tap switches, the position in the video is kept.

### Side effects
- With 2+ versions a wide (landscape / 1:1) centre card is up to ~18 % narrower than today, to keep a visible peek. Portrait recordings are unaffected.
- The pager keeps the height of the tallest version, so a short version (1:1) is centred with empty space above and below instead of the dialog shrinking. Chosen to avoid the dialog jumping while swiping.
- Opening a crop from My Videos now also shows its sibling versions on the sides (consistent, but new).
- Up to 4 thumbnails are decoded per dialog (small memory cost); only one video is ever decoded.
- A second or third crop can appear on the left while the user watches; the playing video must stay put (see pitfalls).

### Manual checks
1. Auto-crop off → preview looks exactly like today; no swipe, no side previews.
2. Only 9:16 on (default): record → original plays, sides empty, status row "Editing video…"; when done, a dimmed, smaller, cut-off preview fades in on the right; playback of the original never restarts.
3. Swipe left → 9:16 version centres, continues from the same position; the original peeks on the left; filename/size/ratio text updates.
4. Tap the side preview → same result as swiping. Tap the centre → play/pause.
5. 9:16 + 4:5 + 1:1 on → right, then left, then (after swiping right) the third; correct order `[4:5] [original] [9:16] [1:1]`.
6. Pause, swipe → the new version is paused at the same position.
7. Rename and share on a cropped version act on that version; delete removes it and closes the dialog; My Videos list updates.
8. Background replace on + auto-crop on → after the processing screen the preview opens and the crops still arrive correctly.
9. Open a crop from My Videos → starts on that crop, siblings on the sides.
10. Close the dialog while crops are still running → no crash, no audio left playing; reopen from My Videos works.

