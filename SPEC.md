# Custom Screen — Presentation Viewer App Specification

## Purpose

A **screen-recording-focused presentation viewer** for Android (Java + XML, minSdk 31 / Android 12+).  
The app has two distinct visual modes: a normal **Edit Mode** with full UI, and a **Presentation Mode** that hides everything except slide content.

---

## App Modes

### Edit Mode (default)
- Standard Android UI is fully visible: top action bar/menu, slide list or current-slide view, visible navigation buttons in the bottom corners.
- User can **add, edit, reorder, and delete** slides.
- Each slide is either a **Text Slide** or an **Image Slide**.
- All UI chrome is visible.

### Presentation Mode
- Triggered by a **double-tap anywhere on the screen**.
- Double-tap again **returns to Edit Mode**.
- When entering Presentation Mode:
  - All UI elements hidden (action bar, menus, controls, navigation button labels/backgrounds).
  - System bars hidden — full immersive full-screen via `WindowInsetsController` (Android 12 API).
  - Only the slide content is visible on a pure black background.
  - The navigation tap areas (bottom-left / bottom-right corners) remain **functionally active** but are visually invisible — same black background.
- When exiting Presentation Mode:
  - All UI elements restored.
  - System bars restored.

> The double-tap toggle **replaces** the previous standalone full-screen toggle. There is no separate full-screen action — entering Presentation Mode IS the full-screen action.

---

## Slide Types

### Text Slide
- **Content area:** top 30% of the screen height.
- **Text color:** white (`#FFFFFF`).
- **Background:** black (same as app background).
- **Font:** any system font for now; will be changed later.
- **Entry animation:** text slides in from the **top edge of the 30% zone** and settles at the **vertical middle of the 30% zone**.  
  - Animation is subtle (translate Y, ~300–400ms, ease-out interpolator).
- **Horizontal alignment:** centered.
- **Text size:** TBD by developer (a comfortable readable size, adjustable later).

### Image Slide
- **Placement:** anchored to the **top** of the screen.
- **Sizing:** fitted to **screen width**, height scales proportionally to maintain aspect ratio.
- **Constraints:** never cropped, never stretched or distorted.
- **Background:** black fills any remaining screen area below/around the image.
- Images are stored **locally** in app-internal storage.

---

## Navigation (in both modes)

### Tap Zones
| Zone | Position | Action |
|---|---|---|
| **Next** | Right 20% of screen width × Bottom 40% of screen height | Advance to next slide |
| **Previous** | Left 20% of screen width × Bottom 40% of screen height | Go back to previous slide |
| **Dead zone** | Center 60% of bottom 40%, and entire top 60% | No action |

### Edge Behavior
- On the **first slide**: tapping Previous does nothing.
- On the **last slide**: tapping Next does nothing.
- No wrap-around.

### Dev Mode Note
> **CURRENT STATE:** Navigation tap areas have a visible background/label so they can be confirmed working during development.  
> **TODO (later):** Make them fully invisible (transparent, no label) — user will request this once tested.

---

## Data & Storage

- **Slides are stored locally** on the device.
- Storage mechanism: Room database (SQLite) for slide metadata + order; image files in app internal storage.
- Slide schema (minimum):
  - `id` (int, primary key)
  - `type` (enum: TEXT | IMAGE)
  - `order` (int, for sequence)
  - `textContent` (String, nullable — for TEXT slides)
  - `imagePath` (String, nullable — local file path, for IMAGE slides)

---

## Technical Stack

| Item | Choice |
|---|---|
| Language | Java |
| UI | XML layouts |
| Min SDK | 31 (Android 12) |
| Compile/Target SDK | 35 |
| Build tool | AGP 8.5.2 + Gradle 8.9 |
| Full-screen API | `WindowInsetsController` |
| Local DB | Room (androidx.room) |
| Image loading | Glide |
| Gesture detection | `GestureDetector` (built-in) |
| No Kotlin / No Compose | ✅ |

---

## Screen Layout (Presentation Mode)

```
+------------------------------------------+
|                                          |
|         [TEXT — top 30%]                 |
|   text animates top→middle of this zone  |
|                                          |
|   — — — — — — — — — — — — — — — — — —   | 30% mark
|                                          |
|         [IMAGE or black space]           |
|                                          |
+----------+--------------------+----------+
| PREVIOUS |    (dead zone)     |   NEXT   |  <- bottom 40%
| left 20% |    center 60%      | right 20%|
+----------+--------------------+----------+
```

---

## Open Items / Future Work

- [ ] Slide editor UI design (how user adds/edits slides)
- [ ] Font selector
- [ ] Text size control
- [ ] Slide transition animations between slides
- [ ] Export / share presentation
- [ ] Hide navigation buttons visually (user will request after testing)

---

## Status

| Phase | Status |
|---|---|
| Project scaffold (Java + XML, Gradle, git) | ✅ Done |
| Blank black screen + double-tap full-screen | ✅ Done |
| Spec document | ✅ This file |
| Presentation mode toggle + UI | 🔲 Next |
| Slide model + Room DB | 🔲 Next |
| Text slide + animation | 🔲 Next |
| Image slide + Glide | 🔲 Next |
| Navigation tap zones | 🔲 Next |
| Edit mode UI | 🔲 Next |