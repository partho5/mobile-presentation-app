# Short Video Recorder — Feature Plan & Progress Tracker

Each new session: the user says **"build part N"**. Read this file, build only that part,
compile (`./gradlew -q --offline :app:compileDebugJavaWithJavac`), tick its boxes here,
update **Status**, and commit (`feat(...)`/`fix(...)` style, one commit per part).

All code paths below are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.
Line numbers are approximate (as of commit after Part 1) — grep the method names.

## Status

| Part | Scope | Status |
|---|---|---|
| 1 | Rename app, recordings folder, new seed slides | ✅ Done |
| 2 | First-run swipe-finger tutorial | ✅ Done |
| 3 | Flip camera button (front/back) | ✅ Done |
| 4 | Full-screen recording foundation (FGS types, entire-screen capture, external stop) | ✅ Done |
| 5 | Floating face cam overlay over other apps | ✅ Done |
| 6 | Overlay permission onboarding + final QA pass | ⬜ Todo |

## Decisions already confirmed by the user (do not re-ask)

- Full-screen recording: **hybrid overlay** — face cam stays in-app as today; a floating bubble
  appears only while the user is outside the app *during a recording*.
- Flip icon: **bottom-left 45°** on the camera circle border (mirror of the resize icon).
- Swipe hints: shown on **first launch** and keep showing **until the user actually swipes**.
- Recordings folder: new videos go to `DCIM/Short Video Recorder`; Saved Recordings also lists
  the legacy `DCIM/FaceCam Presentation Recorder`.
- `applicationId` / package name stay `com.jovoc.facecampresentationrecorder` (store identity).

---

## Part 1 — Rename + seed slides ✅

- [x] `res/values/strings.xml`: `app_name` = "Short Video Recorder".
- [x] `util/RecordingStorage.java`: `getAppDir()` (DCIM/<app_name>) and `getAllDirs()` (+ legacy folder).
- [x] `ScreenRecordService`, `SlideLogger` use `RecordingStorage.getAppDir()`; `SavedRecordingsActivity.scanRecordings()` scans `getAllDirs()`.
- [x] Seed image bundled as `app/src/main/assets/seed_slide_deep_breath.webp` (from the Cloudinary PNG, 155 KB WebP).
- [x] `ImageStorageHelper.copyAssetImageToInternalStorage()` copies it into `filesDir/slides_images/`.
- [x] `MainActivity.seedInitialSlides()`: text slide `"(Example topic)\nHow to get rid of anxiety ?"` + image slide.

Side effects to remember: file name suffix becomes `-Short.mp4` (first word of app name);
the old "Double-tap anywhere to toggle Menu" seed slide is gone → Part 2 adds a one-time toast.

---

## Part 2 — Swipe-finger tutorial ✅

**Goal:** first launch → animated hand swiping LEFT on slide 1; once on slide 2 → hand swiping RIGHT.

Facts from the code:
- `incrementAppOpenedTimes()` runs in `onCreate` *before* slides load, so on first launch the
  counter is already 1. Read the value **before** incrementing.
- Swipes are detected in `setupGestureDetector()` → `onFling`, fed from `dispatchTouchEvent`.
  Flings that **start on the active slide view, the camera, or while zoomed are ignored** — so the
  hint must sit in the free black area *below* the slide and *not* over the camera
  (camera defaults to screen centre, 240dp).
- `updateNavigationButtonsState()` is called from `renderCurrentSlide()` and `updateUIState()` — a good hook.

Steps:
- [x] Drawable `res/drawable/ic_swipe_hand.xml` — white Material "touch_app" hand
      (path `M9,11.24V7.5C9,6.12 10.12,5 11.5,5S14,6.12 14,7.5v3.74c1.21,-0.81 2,-2.18 2,-3.74C16,5.01 13.99,3 11.5,3S7,5.01 7,7.5C7,9.06 7.79,10.43 9,11.24zM18.84,15.87l-4.54,-2.26c-0.17,-0.07 -0.35,-0.11 -0.54,-0.11H13v-6C13,6.67 12.33,6 11.5,6S10,6.67 10,7.5v10.74l-3.43,-0.72c-0.08,-0.01 -0.15,-0.03 -0.24,-0.03c-0.31,0 -0.59,0.13 -0.79,0.33l-0.79,0.8l4.94,4.94C9.96,23.83 10.34,24 10.75,24h6.79c0.75,0 1.33,-0.55 1.44,-1.28l0.75,-5.27c0.01,-0.07 0.02,-0.14 0.02,-0.2C19.75,16.63 19.37,16.09 18.84,15.87z`),
      thin dark stroke for contrast.
- [x] `activity_main.xml`: after `iv_stop_arrow_hint`, add non-clickable `swipe_hint_container`
      (vertical LinearLayout, `gone`, elevation ~22dp) with `iv_swipe_hint_hand` (56dp) and a small
      label `tv_swipe_hint_label` ("Swipe left for next slide" / "Swipe right to go back").
- [x] Prefs: `KEY_SWIPE_TUTORIAL_ACTIVE` (set true in `onCreate` only if appOpenedTimes was 0
      before increment), `KEY_SWIPE_HINT_NEXT_DONE`, `KEY_SWIPE_HINT_PREV_DONE`, `KEY_DOUBLE_TAP_TIP_SHOWN`.
- [x] `updateSwipeHint()`: hide if tutorial inactive, recording, countdown visible, or < 2 active slides.
      Else: `!nextDone && hasNext` → LEFT hint; else `!prevDone && hasPrev` → RIGHT hint; else hide.
      Call it from `updateNavigationButtonsState()`.
- [x] Mark done **only on real swipes** in `onFling` (left → nextDone, right → prevDone). Arrow taps
      do not count, so the hint keeps teaching until the gesture is used.
- [x] Positioning (post ~400ms after render, image loads async): free band = bottom of
      `getActiveSlideView()` → top of `btnRecord`; if the camera rect overlaps it, use the larger
      sub-band above/below the camera; centre the hint in it.
- [x] Animation: one infinite `ValueAnimator` (~1800ms) — fade in + press (scale 1.1→0.95),
      slide ±56dp with decelerate, fade out, short pause. Cancel on hide and in `onDestroy`.
- [x] Hide instantly on recording start (`updateUIState` with `isRecording`) so it never gets recorded.
- [x] When both done: one-time toast "Double-tap anywhere for menu & slide editing", then
      clear `KEY_SWIPE_TUTORIAL_ACTIVE`.

Verify: fresh install → hint left on slide 1; swipe → slide 2 shows right hint; swipe back → hints gone,
toast once; restart app → no hints. Existing users (appOpenedTimes > 0) never see it.

---

## Part 3 — Flip camera ✅

Facts: camera is `camera_root_wrapper` → `camera_card_container` (MaterialCardView) → `camera_preview_view`.
Resize icon `btn_resize_handle` is placed by `updateResizeHandlePosition(cardSize)` at
`cardSize * 0.85355` (bottom-right 45°), shown 3s by `showResizeHandleFor3Seconds()`, hidden by
`hideHandleRunnable`. `startCameraPreview()` hard-codes `LENS_FACING_FRONT` and calls `unbindAll()`.

Steps:
- [x] Drawable `ic_flip_camera.xml` (Material "flip_camera_android"/"cameraswitch").
- [x] `activity_main.xml`: `btn_flip_camera` ImageView (40dp, `bg_rect_resize_handle`, elevation 16dp,
      `gone`) inside `camera_root_wrapper`; add `iv_camera_freeze_frame` ImageView (match_parent,
      `gone`, centerCrop) inside the card above the PreviewView.
- [x] Position: translationX = `cardSize*0.14645 - h/2`, translationY = `cardSize*0.85355 - h/2`;
      update everywhere `updateResizeHandlePosition` is called (or fold into it).
- [x] Show/hide together with the resize icon (same 3s timer, same fade). Tapping flip resets the timer.
- [x] `KEY_CAM_LENS_FACING` pref; `startCameraPreview()` uses it; if `!cameraProvider.hasCamera(selector)`
      fall back to front and hide the flip button permanently.
- [x] Flip UX: grab `cameraPreviewView.getBitmap()` into the freeze frame, spin the flip icon 180°,
      rebind, then fade the freeze frame out when `getPreviewStreamState()` reaches `STREAMING`
      (fallback timeout 1500ms). Do **not** rotate the card (SurfaceView ignores 3D transforms).
- [x] Works during recording too (lets the user show something with the back camera).

Verify: tap cam → both icons appear; flip toggles front/back with no long black flash;
choice survives restart; device with one camera shows no flip icon.

---

## Part 4 — Full-screen recording foundation ✅

Conflicts found in the existing system:
1. `ScreenRecordService` is declared only as `mediaProjection` → **mic is silenced when the app is
   backgrounded** (Android 11+ needs FGS type `microphone`); camera in background needs type `camera`.
2. Android 14+ capture picker allows "A single app" → leaving the app records nothing.
3. Stop button lives only in `MainActivity`; stopping elsewhere leaves `isRecording` UI stale
   (`recordingFinishedReceiver` shows the player but never resets UI).

Steps:
- [x] Manifest: `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `SYSTEM_ALERT_WINDOW`;
      service `foregroundServiceType="mediaProjection|camera|microphone"`.
- [x] `startForeground(...)`: build the type mask dynamically — add CAMERA / MICROPHONE only if the
      runtime permission is granted (else SecurityException).
- [x] `MainActivity.proceedToScreenCapture()`: on API 34+ use
      `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`.
- [x] Notification: `contentIntent` = launcher intent (brings existing task to front) + "Stop" action
      (`PendingIntent.getService` → `ACTION_STOP`).
- [x] `recordingFinishedReceiver`: if `isRecording` is still true, reset UI exactly like
      `stopRecordingFlow()` minus sending ACTION_STOP (extract `onRecordingStoppedUi()`).
      Also covers the system "stop sharing" chip.

Verify: record → go home → open another app → talk → stop from notification → video has the other
app on screen **and audio**; app UI is back in edit mode.

---

## Part 5 — Floating face cam overlay ✅

Design: overlay owned by `ScreenRecordService` (outlives the activity).

- [x] New `ui/FloatingCamOverlay.java`: own `LifecycleOwner` (`LifecycleRegistry`), `PreviewView` in a
      circular `MaterialCardView` + small low-alpha stop button below it, added via `WindowManager`
      with `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`,
      `layoutInDisplayCutoutMode = ALWAYS`, gravity TOP|START (rootLayout is full-bleed, so in-app
      x/y == screen x/y). Draggable via `updateViewLayout`; position not persisted.
- [x] Binds CameraX with lens from `KEY_CAM_LENS_FACING`; on hide → lifecycle DESTROYED (unbinds).
      CameraX suspends the older lifecycle camera automatically when the activity restarts.
- [x] Service actions `ACTION_SHOW_OVERLAY` (extras: x, y, size) / `ACTION_HIDE_OVERLAY`.
- [x] `MainActivity.onStop()`: if `isRecording && !isChangingConfigurations() && Settings.canDrawOverlays()`
      → send SHOW with current `cameraRootWrapper` x/y and card size. `onStart()` → send HIDE.
- [x] Overlay stop button → `ACTION_STOP`, then start launcher intent (overlay-permission apps are
      exempt from background-activity-launch limits) so the user lands on the playback dialog.
- [x] Remove overlay in `stopRecordingInternal()` and `onDestroy()`.

Verify: bubble appears at the same spot when leaving the app, follows drags, is in the video,
disappears on return, ~0.5s black on handoff is the accepted trade-off.

---

## Part 6 — Overlay permission onboarding + QA

- [ ] Track "left app during recording without overlay permission" (flag set in `onStop`).
- [ ] On return (`onStart`) show once (`KEY_OVERLAY_PROMPT_SHOWN`): dialog "Your face cam wasn't visible
      while you were in other apps. Allow 'Display over other apps' so it follows you." →
      `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with package URI. Never ask at record start.
- [ ] Update `README.md` / `SPEC.md` mentions of the old name and the new features.
- [ ] Full regression on a device: fresh install flow, swipe hints, flip, in-app recording + auto-crop,
      full-screen recording with overlay, Saved Recordings lists old + new folder.
