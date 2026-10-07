# Drawing Board — Intent Confirmation & Open Questions

**Status: waiting for your answers. No code written, nothing built yet.**
To answer: edit the "Your answer" column / lines in this file, or just reply "yes to all defaults".

---

## 1. What I understood (please confirm)

1. The floating **stop button** at the bottom gets more opaque. Right now it is `alpha 0.25` at 32dp, which is almost invisible.
2. A new **Drawing board button** sits beside it, with a blackboard-style icon.
3. One tap slides the board in **from the top** over the current slide, whatever type that slide is (image, text, video or web). A second tap on the same button slides it back out. Both directions are animated smoothly.
4. The board is a **black canvas with a silver frame**, with a very small margin so the drawing area is as large as possible. Its **height equals the device width**, so it is square.
5. The bottom silver bar holds three tools: **pen**, **eraser** and **clear**. The active tool scales to **2×**.
6. When the **pen** is active, a **circular colour palette** is available: white, cyan, amber/orange, green, red, purple. These are high-contrast on black.
7. After a colour is picked, the palette **hides** so it does not clutter the UI. It reappears only for a short time when the user taps the pen icon.
8. There is **one moderate pen width** that works for all cases.
9. Users see the name **"Drawing board"**, not "blackboard". Everything stays minimal.
10. The deliverable for now is **this plan only**. No code.

---

## 2. Gaps & ambiguities — with my default for each

| # | Question | My default | Your answer |
|---|---|---|---|
| 1 | When is the board button available? The stop button only exists while recording. | **Recording mode only**, next to stop. Edit mode stays uncluttered. | |
| 2 | Where does the board sit relative to the camera circle and bottom nav? The face cam is part of the recorded video. | Board covers the slide area. The **face cam stays on top** of it. Stop and the new button stay clickable. | |
| 3 | What happens to the drawing when the board is hidden? | **Hide does not clear.** The drawing is wiped only by Clear or when a new recording starts. | |
| 4 | Slide swipe and double-tap-for-menu clash with drawing. | While the board is open it **consumes all touches** and slide swipe is disabled. | |
| 5 | What kind of eraser? | **Pixel eraser**, wider than the pen. | |
| 6 | Does Clear ask for confirmation? | **Instant clear, no dialog**, to stay minimal. Risk: an accidental wipe mid-recording. | |
| 7 | What does "circular palette" mean? | **Six round swatches fanned in an arc above the pen icon.** The arc auto-hides after about 3 seconds or on selection. | |
| 8 | Stop button opacity and spacing. | Raise opacity to about **0.6**. Put the board button on the **right** of stop, since the first-run arrow hint is on the left. Leave a clear gap so nobody taps stop by mistake. | |
| 9 | Landscape orientation? The manifest is `screenOrientation="unspecified"`. | Board size is **`min(width, available height)`**. | |
| 10 | Can the user tell which colour is active? | The **pen icon is tinted** with the chosen colour. | |
| 11 | What does "silver side" mean exactly? | A silver **frame on left, right and top**, plus the thicker silver **bottom bar** for tools. Margins about 4dp. | |
| 12 | Which icon for the board button? | A custom simple vector: a board with a small chalk stroke. There is no standard Material "blackboard" icon. | |
| 13 | Animation details. | Slide from top, about 250ms, decelerate on show and accelerate on hide. | |

---

## 3. Alternatives considered

- **Transparent annotation layer over the slide.** Better for pointing at slide content, but you want a blank board, and slide content would clash with the ink.
- **Gesture to open the board**, such as a two-finger swipe down. No button needed, but it is hidden from the user and conflicts with existing swipes.
- **Vertical tool rail on the side.** It wastes drawing width.
- **Full-screen board instead of square.** More space, but it hides the face cam and the bottom controls.

**Best for a frictionless UX: your design.** One tap to open, one tap to close, drawing survives hiding. Two refinements I'd add: the palette auto-hides, and the pen icon is tinted with the active colour.

---

## 4. Side effects of this decision

- **Mis-tap risk.** A new button next to stop could end a recording by accident. Mitigation: a clear gap and button order.
- **Covered slide.** The audience loses sight of the slide while the board is open. That is intended, and the reason the drawing persists on hide.
- **No undo.** An accidental Clear or erase cannot be undone. I'd leave undo out to stay minimal. Tell me if you want it.
- **Square board on tall phones.** The square leaves unused space below it. The bottom controls stay reachable there.
- **Space cost.** The 2× active icon needs a taller silver bar, which takes a little drawing space.
- **Conflict with the core goal?** None found. The core goal is recording a short video with a face cam, and the board only appears in-app, which the recorder captures. No conflict with frictionless UX, apart from the mis-tap risk above.

---

## 5. Proposed build plan (once you confirm)

I will use the same part-by-part format as `FEATURE_PLAN_SHORT_VIDEO.md`: one part per session, you say **"build part N"**.

| Part | Scope | Status |
|---|---|---|
| 1 | Stop-button opacity, new board button, board view with slide-in/out animation | Not started |
| 2 | Drawing: pen, pixel eraser, clear, touch handling and slide-swipe lock | Not started |
| 3 | Colour palette arc, 2× active icon, pen tint, polish and device QA | Not started |

Detailed per-part checklists will be added here after you answer the questions in section 2.

---

## 6. Next step for you

Reply **"yes to all defaults"**, or list which question numbers to change and how.


MY ANSWERS:
1. **When is the Drawing Board button available?**
   The Drawing Board button is available **while recording and always visible beside the Stop button**.

2. **Where does the board appear relative to the face cam?**
   The board appears **over the face cam**. However, if the face cam is already at that position, **nothing should override or cover the face cam**.

3. **What happens to the drawing when the board is hidden or the app is closed?**
   Hiding the board **retains the drawing state**. Only the Clear action removes the drawing. The board drawing state should also be **saved when the app is closed**, so it is restored when the app is opened again.

4. **What happens to slide gestures while the board is visible?**
   When the board is visible, **swiping on the board triggers the board's minimize/hide action**. If applicable, the underlying slide also changes as it normally would.

5. **How should the eraser work?**
   The eraser should be **as wide as practically possible**. The entire area touched by the user's finger should be erased. **Undo and redo must work seamlessly with the board's drawing state.**

6. **What should Clear and Undo/Redo do?**
   Clear means **immediately clear the entire drawing**, without confirmation. However, the cleared drawing must be recoverable through **Undo**. Add **Undo and Redo** as additional tools using traditional Undo/Redo icons, alongside the original Pen, Eraser, and Clear buttons.

7. **How should the colour palette behave?**
   The six colours should appear in an **arc above the Pen button**. After a colour is selected, the palette should **automatically hide after 2 seconds**.

8. **What should the Stop button opacity and spacing be?**
   Set the Stop button opacity to approximately **0.8**. Keep a **10dp gap** between the Stop button and Drawing Board button.

9. **What should happen in landscape orientation?**
   In landscape orientation, the board's **maximum height should be the device width**, which is the screen height in landscape. The board should remain square within that limit.

10. **How should the active pen colour be indicated?**
    The **entire Pen icon should be tinted with the selected colour**, not just the top of the pen. Add a **thin black outline** so that the white pen remains clearly visible against the silver background.

11. **What does the silver frame consist of?**
    Keep only a **4–6dp margin around the board**. There should be no additional layered frame or decorative elements.

12. **What icon should be used for the Drawing Board button?**
    Use a **simple custom vector icon** representing a drawing board. **No chalk stroke is needed for now.**

13. **What should the board animation be?**
    Yes, as described: the board should **slide in from the top in approximately 250ms**, using a decelerating animation when opening and an accelerating animation when hiding.


---
---

# PART B — Resolved plan (written after reading "MY ANSWERS")

**Status: plan complete, 1 question left to confirm (section B2). No code written.**

## B1. Final decisions (from your answers)

| Topic | Decision |
|---|---|
| Availability | Board button shows **only while recording**, always visible, right of Stop. Gap **10dp**. |
| Stop / board button opacity | **0.8** for both. |
| Board geometry | Square. Side = screen **width** in portrait; `min(width, height)` in landscape (= screen height). Margin **5dp** (silver) on top/left/right; silver **bottom tool bar** inside the square. No extra frame or decoration. |
| Z-order | Board is **above slides, below the face cam**. Cam is never covered; to draw under it the user moves the cam. Stop and board buttons stay above the board. |
| Animation | Slide in from top, **250ms**, decelerate on open; accelerate on hide. Same button toggles. |
| Tools (left to right) | **Pen, Eraser, Undo, Redo, Clear**. Pen and Eraser are *modes*; Undo, Redo and Clear are one-shot *actions*. |
| Active tool size | Active mode (Pen or Eraser) scales to **2×** with a short scale animation. Actions do not scale. |
| Pen | One moderate width (**4dp**, round caps/joins). Icon is fully **tinted with the active colour** and has a **thin black outline** so white stays visible on silver. |
| Palette | **Six swatches in an arc above the Pen button**: white, cyan, amber, green, red, purple. Shown when the pen is tapped. **Hides 2s after a colour is picked** (also 2s after opening with no pick). |
| Eraser | Very wide (**about 40dp**), erases everything under the finger. Round cap. |
| Clear | Instant, no dialog. **Recoverable through Undo.** |
| Undo / Redo | Unlimited in spirit, capped at **200 actions**. Strokes, erase strokes and Clear are each one action. A new action after an undo drops the redo list. Buttons look disabled when there is nothing to undo/redo. |
| Hide | Hiding keeps the drawing. |
| Persistence | Drawing is **saved to disk and restored on next app launch**. It survives across recordings until the user taps Clear. |
| Naming | "Drawing board" in all labels and content descriptions. |
| Icon | Simple custom vector for the board button. No chalk stroke. Undo/Redo use the traditional curved-arrow icons. |

## B2. One remaining question for you (everything else I resolved)

**Your answer #4: "swiping on the board triggers the board's minimize/hide action. If applicable, the underlying slide also changes."**

A finger drag on the black canvas must draw, so it cannot also mean "hide". I read your intent as this, and will build it unless you say otherwise:

> **The existing slide-change swipe (a horizontal swipe left/right outside the slide area, e.g. on the bottom area or on the silver margin/bar) keeps working. When it fires while the board is open, it also hides the board and then changes the slide.**
> Drags on the black canvas always draw. Double-tap on the canvas does not open the menu bar (it just draws a dot).

My answer: _[ ] yes, as written   [ ] no, I meant: ____________________

## B3. Resolved contradictions / notes to be aware of

1. **#2 "over the face cam" vs "nothing should cover the face cam"** — I follow the second: the cam stays on top. If the cam sits over the canvas, that part of the canvas is not drawable until the cam is moved.
2. **#3 persistence vs earlier default** — Earlier I proposed wiping on a new recording. Now the drawing persists across recordings. Side effect: an old drawing can appear at the start of the next recording. Mitigation: Clear is one tap and undoable.
3. **Undo history after app restart** — Only the strokes are saved. The undo/redo stacks start empty after a restart. Say so if you want them saved too (it makes the file larger).
4. **Landscape** — The square equals the full screen height, so it can cover the Stop button area. Stop and the board button are drawn above the board so they stay tappable, and they may sit over the canvas or tool bar.
5. **Palette timer** — If the user opens the palette and picks nothing, it also hides after 2s.

## B4. Technical design (for the builder session)

Paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

**Facts about existing code**
- `res/layout/activity_main.xml`: root is `RelativeLayout#root_layout`. Slides are declared first; `camera_root_wrapper` is declared later (so it draws above slides). `btn_stop_record_floating` is 32dp, `alpha 0.25`, elevation 12dp, bottom margin 70dp, centred; `iv_stop_arrow_hint` sits to its left.
- `MainActivity.java`: `dispatchTouchEvent()` feeds every touch into `gestureDetector`. `onDoubleTap` toggles the menu bar. `onFling` changes slides only when the touch started **outside** the camera and **outside** the active slide view, with `|dx| > 100` and `|vx| > 100`. `updateUIState()` shows/hides the Stop button per `isRecording`. `onRecordingStoppedUi()` resets UI.
- Screen is captured by MediaProjection, so anything drawn in-app is automatically in the video.

**New files**
- `ui/DrawingBoardView.java` — custom `View` for the canvas. Keeps a list of actions (`STROKE`, `ERASE`, `CLEAR`) with an undo cursor. Renders by replaying actions into an offscreen bitmap (erase = `PorterDuff.Mode.CLEAR`). Handles touch, mode, colour.
- `ui/DrawingBoardLayout.java` (or inline in the activity layout) — the square container: black canvas, silver margin, bottom tool bar, palette arc.
- `util/DrawingBoardStorage.java` — save/load strokes as JSON in `filesDir/drawing_board.json`. Points stored **normalised 0..1** relative to the canvas, so orientation and size changes keep the drawing correct. Stroke width is constant, so it is not stored. Debounced save after each action, plus on hide and in `onStop`.
- `res/drawable/`: `ic_drawing_board.xml`, `ic_pen.xml` (two layers: fixed black outline + tintable fill), `ic_eraser.xml`, `ic_undo.xml`, `ic_redo.xml`, `ic_clear_all.xml`, plus a silver/black background shape.
- `res/anim/` is not needed; use `ViewPropertyAnimator` (`translationY`) with `DecelerateInterpolator` / `AccelerateInterpolator`.

**Edits to existing files**
- `activity_main.xml`: add the board container (declared before `camera_root_wrapper`, `visibility=gone`), add `btn_drawing_board` next to the stop button (32dp, alpha 0.8, same elevation, 10dp gap), change stop alpha 0.25 to 0.8.
- `MainActivity.java`: wire the button; show/hide only in recording mode; hide and reset when recording stops; in `dispatchTouchEvent` skip `onDoubleTap` when the touch is on the canvas; in `onFling` hide the board before changing slides (see B2); apply insets and size the board (`side = min(width, availableHeight)`).

**Gotchas for the builder**
- Do not let canvas touches reach `gestureDetector.onDoubleTap` (it would toggle the menu bar).
- Call `requestDisallowInterceptTouchEvent(true)` on canvas touch so parents never steal the drag.
- Eraser must erase pen strokes only on the drawing layer, never the black background colour: draw on a transparent bitmap over a black background.
- The 2× scale of active icons needs a bar tall enough; size the bar for the doubled icon so layout never jumps.
- Persist normalised points, not pixels.
- Keep the arrow hint left of stop; do not move stop.

## B5. Build parts (one per session — say **"build part N"**)

Each part: compile with `./gradlew -q --offline :app:compileDebugJavaWithJavac`, tick the boxes, update the status table, commit.

| Part | Scope | Status |
|---|---|---|
| 1 | Button + board shell + animation | Not started |
| 2 | Drawing engine: pen, eraser, undo, redo, clear | Not started |
| 3 | Palette, persistence, gesture integration, polish | Not started |

### Part 1 — Button + board shell + animation
- [ ] Stop button alpha to 0.8.
- [ ] `ic_drawing_board.xml` vector; `btn_drawing_board` right of stop, 10dp gap, alpha 0.8, shown only in recording mode.
- [ ] Silver/black board container, 5dp margin, empty bottom bar, square sizing (portrait + landscape).
- [ ] Z-order: above slides, below cam; buttons above board.
- [ ] Toggle with 250ms slide from top (decelerate in, accelerate out). Same button toggles.
- [ ] Hide board when recording stops.
- [ ] Compile and commit.

### Part 2 — Drawing engine
- [ ] `DrawingBoardView`: pen (4dp), eraser (40dp), action list with undo cursor, 200-action cap.
- [ ] Tool bar: Pen, Eraser, Undo, Redo, Clear icons; active mode scales to 2× (animated).
- [ ] Clear is one undoable action; Undo/Redo enable/disable state.
- [ ] Canvas consumes touches; double-tap does not toggle the menu bar.
- [ ] Compile and commit.

### Part 3 — Palette, persistence, gesture integration, polish
- [ ] Six-swatch arc above Pen; tap pen to show; hide 2s after pick (or 2s idle).
- [ ] Pen icon fully tinted with active colour, thin black outline.
- [ ] `DrawingBoardStorage`: save debounced + on hide + `onStop`; restore on launch (normalised points).
- [ ] Slide-change swipe hides the board then changes the slide (per B2).
- [ ] Update `README.md` / `SPEC.md` (feature, name "Drawing board").
- [ ] Device QA: portrait, landscape, cam over canvas, recording captures the drawing, restart restores it.
- [ ] Commit.

## B6. Alternatives and side effects — recap

Alternatives considered (annotation overlay, gesture to open, vertical rail, full-screen board) are in sections 3 and 4 above. Your design remains the best fit for a frictionless UX. Main risks: mis-tapping Stop (mitigated by the 10dp gap), an old drawing showing at the start of a new recording (mitigated by one-tap undoable Clear), and the square covering the whole screen in landscape.

## B7. Next step

1. Answer **B2** (yes / change).
2. Then say **"build part 1"** in a new session.
