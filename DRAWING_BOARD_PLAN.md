# Drawing Board — Plan & Progress Tracker

Each new session: the user says **"build part N"**. Read this file, build only that part,
compile (`./gradlew -q --offline :app:compileDebugJavaWithJavac`), tick its boxes here,
update **Status**, and commit (`feat(...)` style, one commit per part). Do not re-ask decisions below.

All code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.
Line numbers drift; grep method names. Original Q&A is archived in `DRAWING_BOARD_QA_ARCHIVE.md`.

## Status

| Part | Scope | Status |
|---|---|---|
| 1 | Stop button opacity, board button, board shell, slide animation | ✅ Done (device QA pending) |
| 2 | Drawing engine: pen, eraser, undo, redo, clear, tool bar | ✅ Done (device QA pending) |
| 3 | Palette, persistence, swipe-outside-hides, docs, device QA | ✅ Done (device QA pending) |

Part 1 notes: board is `LinearLayout#drawing_board_container` (silver bg, 5dp padding) holding `drawing_board_canvas` (black View) and `drawing_board_tool_bar` (60dp bar, empty — Part 2 fills it). Logic is in `MainActivity`: `toggleDrawingBoard / showDrawingBoard / hideDrawingBoard / sizeDrawingBoard`. Board hides instantly in `updateUIState()` when not recording. Landscape/cam-over-board/button tap checks still need a device.

Part 2 notes: `ui/DrawingBoardView` (canvas, id `drawing_board_canvas`) and `ui/DrawingBoardController` (tool bar wiring, `setPenColor`, `onActionCommitted` is an empty hook for Part 3 persistence). Cap 200 limits undo depth only (older actions stay so the picture is intact; compacted after a clear). `exportStrokes()` returns actions after the last clear; `importStrokes()` resets undo/redo. `MainActivity.dispatchTouchEvent` skips `gestureDetector` for touches that start inside the visible board (so Part 3 must still handle swipe-outside-hides in `onFling`).

Part 3 notes: palette/persistence live in `DrawingBoardController` + `util/DrawingBoardStorage` (debounced 1s save, flushed on hide and `onStop`; undo/redo also re-save). Palette overlay `drawing_board_palette` sits above the canvas in a wrapper FrameLayout and is non-clickable. `onFling` blocks only on the board rect while it is open, and hides the board before changing slide.

(Mark a part ✅ Done when finished; add a one-line note if something deviated.)

## Goal

While recording, one tap on a **Drawing board** button slides a square drawing board over the
slide (from the top, smooth). Tap again to hide it. The user draws with a pen (6 colours),
erases, undoes/redoes and clears. Minimal. In-app only, so the screen recorder captures it.
User-facing name is always **"Drawing board"** (never "blackboard").

## Decisions confirmed by the user (do not re-ask)

| Topic | Decision |
|---|---|
| Availability | Button only **while recording**, always visible, **right of Stop**, **50dp** gap. Stop stays where it is. |
| Opacity | Stop and board buttons both **0.8** (Stop was 0.25). |
| Geometry | Square. Side = screen width (portrait); `min(width, available height)` in landscape. **5dp** silver margin top/left/right, silver **bottom tool bar** inside the square, black canvas. No other frame/decoration. |
| Z-order | Above slides, **below the face cam** (cam is never covered). Stop + board buttons above the board. |
| Animation | Slide in from top **250ms**, decelerate on show, accelerate on hide. Same button toggles. |
| Tools, left to right | **Pen, Eraser, Undo, Redo, Clear**. Pen/Eraser are modes; Undo/Redo/Clear are one-shot actions. |
| Active icon | Active mode (Pen or Eraser) scales to **2×** with a short animation. Actions never scale. Bar sized for the 2× icon so layout never jumps. |
| Pen | One width, **4dp**, round caps/joins. Whole icon **tinted** with the active colour + **thin black outline** (white must stay visible on silver). |
| Palette | **6 swatches in an arc above the Pen button**: white, cyan, amber, green, red, purple. Tap pen to show. **Hides 2s after a pick** (or 2s after opening with no pick). |
| Eraser | About **40dp** wide, erases everything under the finger. |
| Clear | Instant, no dialog, **recoverable via Undo**. |
| Undo/Redo | Strokes, erase strokes and Clear are each one action. Cap **200**. New action after undo drops redo. Disabled look when empty. |
| Hide | Hiding keeps the drawing. |
| Persistence | Saved to disk, **restored on next launch**, survives across recordings until Clear. Only strokes saved; undo/redo stacks start empty after restart. |
| Gestures | **Any drag on the board draws** (never hides it). Only the existing slide-change swipe made **outside the board** hides the board and then changes the slide. Double-tap on the board must not toggle the menu bar. |
| Icons | Custom simple vector for the board button (no chalk stroke). Traditional curved-arrow Undo/Redo. |

## Facts about existing code

- `res/layout/activity_main.xml`: `RelativeLayout#root_layout`. Slides declared first; `camera_root_wrapper` declared later (draws above slides). `btn_stop_record_floating`: 32dp, `alpha 0.25`, elevation 12dp, bottom margin 70dp, centred. `iv_stop_arrow_hint` sits left of it.
- `MainActivity.java`: `dispatchTouchEvent()` feeds every touch to `gestureDetector`. `onDoubleTap` toggles the menu bar. `onFling` changes slides only when the touch started **outside the camera and outside the active slide view** (`|dx|>100`, `|vx|>100`). `updateUIState()` shows/hides Stop per `isRecording`; `onRecordingStoppedUi()` resets UI after recording.
- Screen is captured by MediaProjection, so in-app drawing is in the video automatically.

---

## Part 1 — Button + board shell + animation   ✅

Goal: tapping the new button smoothly shows/hides an empty silver/black square board. No drawing yet.

- [x] `activity_main.xml`: Stop alpha 0.25 → 0.8.
- [x] `res/drawable/ic_drawing_board.xml` vector.
- [x] `btn_drawing_board` (32dp, alpha 0.8, elevation 12dp) right of Stop, 50dp gap, both centred as a row (`recording_buttons_row`); visible only in recording mode (`updateUIState()`); circular background like `bg_circle_stop_minimal`.
- [x] Board container (`drawing_board_container`): declared **before** `camera_root_wrapper`, `visibility=gone`. Black canvas area, 5dp silver margin, silver bottom bar (empty for now, tall enough for 2× icons ≈ 72dp).
- [x] Size at runtime: `side = min(screenWidth, availableHeight)`, anchored top (below system insets if shown), centred horizontally in landscape.
- [x] Toggle animation with `ViewPropertyAnimator` on `translationY`: 250ms, `DecelerateInterpolator` in, `AccelerateInterpolator` out; set `GONE` at end of hide. Ignore taps mid-animation.
- [x] Hide the board instantly when recording stops (`onRecordingStoppedUi()`).
- [x] Check Stop + board buttons stay above the board and tappable (portrait and landscape).
- [x] Compile, tick boxes, update Status table, commit.

## Part 2 — Drawing engine + tool bar   ✅

Goal: fully working drawing inside the board; no palette, no persistence yet (pen is white).

- [x] `ui/DrawingBoardView.java`: transparent drawing layer over black. Action list (`STROKE`, `ERASE`, `CLEAR`) with an undo cursor, cap 200, redo dropped on new action. Render by replaying into an offscreen bitmap; erase via `PorterDuff.Mode.CLEAR` (never paints black over strokes).
- [x] Pen 4dp round; eraser 40dp round; smooth strokes (quadratic midpoint smoothing); a single tap draws a dot.
- [x] Touch handling: `requestDisallowInterceptTouchEvent(true)`; canvas consumes all touches; multi-touch ignored (second finger doesn't draw).
- [x] Block double-tap menu toggle on the canvas (skip `gestureDetector` for touches inside the board).
- [x] Tool bar icons in the silver bar: Pen, Eraser, Undo, Redo, Clear (`ic_pen` with fixed black outline layer + fillable layer, `ic_eraser`, `ic_undo`, `ic_redo`, `ic_clear_all`).
- [x] Mode switch Pen/Eraser; active icon scales to 2× (animated, ~150ms); default mode Pen.
- [x] Undo/Redo buttons disabled-look when nothing to do; Clear is one undoable action (no confirm).
- [x] Expose API for Part 3: `setPenColor(int)`, `exportStrokes()` / `importStrokes(...)` (normalised 0..1 points + type + colour), listener `onActionCommitted`.
- [x] Compile, tick boxes, update Status table, commit.

## Part 3 — Palette, persistence, gesture rule, docs, QA   ✅

Goal: finish the feature.

- [x] Palette arc above the Pen button: white `#FFFFFF`, cyan `#00E5FF`, amber `#FFB300`, green `#00E676`, red `#FF1744`, purple `#D500F9`. Tap pen → show (if pen already active, tap re-opens palette); pick → apply colour; hide 2s after pick or 2s idle. Palette is drawn inside the board and never blocks the canvas when hidden.
- [x] Pen icon fully tinted with the chosen colour; outline stays black.
- [x] `util/DrawingBoardStorage.java`: save/load `filesDir/drawing_board.json` (type, colour, normalised points). Save debounced after each action, on hide, and in `onStop`. Load on launch before first show. Remember last pen colour too (SharedPreferences).
- [x] Gesture rule: touches **on the board** only draw. A slide-change swipe made **outside the board** (e.g. in the area below it, or on the face-cam-free margin) hides the board then changes the slide. While the board is open, treat the board's rectangle (not the slide view) as the blocked area in `onFling`, so swipes below the board still work even if the slide image extends under it.
- [x] Update `README.md` / `SPEC.md` (feature, "Drawing board" naming, behaviours above).
- [ ] Device QA (still to do on a device): portrait + landscape; cam over canvas; recording captures drawing; restart restores drawing; Clear + Undo; palette timing; Stop button not mis-tapped; slide types (image, text, video, web) all covered by the board.
- [x] Compile, tick boxes, update Status table, commit.

## Side effects to keep in mind

- Mis-tapping Stop next to the board button → keep the 50dp gap, do not move Stop.
- Face cam stays on top, so the canvas under it can't be drawn on until the cam is moved.
- A persisted old drawing may appear at the start of a new recording → Clear is one tap, undoable.
- Landscape square equals screen height and may sit under Stop/board buttons → buttons are drawn above the board.
- Eraser and Clear can't be undone after an app restart (history is not saved).
