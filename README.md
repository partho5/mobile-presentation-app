# Mobile Presentation App

A **screen-recording-focused presentation viewer** for Android, built with native Java and XML.

## What it does

This app lets you build and present sequential slide decks directly from your Android phone — designed so you can **screen-record the presentation without any app UI appearing in the recording**.

It has two modes:

### Edit Mode
The normal app view. You can add, edit, reorder, and delete slides. Navigation controls and the action bar are fully visible.

### Presentation Mode
Triggered by a **double-tap anywhere on the screen**. All UI disappears — no toolbar, no buttons, no controls. Just your content on a pure black background in immersive full-screen. Double-tap again to return to Edit Mode.

## Slide Types

- **Text Slide** — White text displayed in the top 30% of the screen. On slide entry, the text animates subtly from the top of the zone downward to its resting position in the middle of the zone.
- **Image Slide** — Image anchored to the top of the screen, fitted to screen width, aspect ratio preserved. Never cropped or stretched.

## Navigation (in Presentation Mode)

The bottom corners of the screen are invisible tap zones:

| Area | Action |
|---|---|
| Bottom-left (left 20% × bottom 20%) | Previous slide |
| Bottom-right (right 20% × bottom 20%) | Next slide |

Reaching the first or last slide does nothing — no wrap-around.

## Tech Stack

| Item | Detail |
|---|---|
| Language | Java |
| UI | XML layouts |
| Min SDK | 31 (Android 12) |
| Target SDK | 35 (Android 15) |
| Full-screen | `WindowInsetsController` |
| Local storage | Room (SQLite) + internal file storage |
| Image loading | Glide |
| Build | AGP 8.5.2 + Gradle 8.9 |

## Project Status

- [x] Project scaffold (Java + XML, Gradle, git)
- [x] Black full-screen Activity
- [x] Double-tap presentation mode toggle
- [ ] Slide model + Room database
- [ ] Text slide + entry animation
- [ ] Image slide + Glide
- [ ] Navigation tap zones
- [ ] Edit mode UI (add / edit / reorder slides)
- [ ] Make nav buttons invisible (dev visible for now)