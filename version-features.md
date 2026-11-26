# Version features

New features in each release, written before the release goes out. Add a new section at the top for every version.
Version codes are the Play Store `versionCode`; names are `versionName`.

---

## v2.0 (versionCode 5), not yet released

Everything built after v1, from git history after `e9ebe01`.

### App
- The app is renamed **Face Branding** (v1 was "FaceCam Presentation Recorder"). Recordings saved under the earlier names still show up.
- First-run onboarding: a short story of what the app does, a how-to video, and a "Watch on YouTube" button. **How to use** in the menu reopens it.
- A **Contact** screen with the developer's photo and a personal support note.
- **Settings** lists any missing permissions with Allow buttons, plus a "How it helps you" note under each section.
- Optional sign-in with an email code, an account screen, sign out, and account deletion.
- Brands (workspaces): pick a brand, and see its credit balance and signed-in devices.
- Free and premium plans: an upgrade sheet, a premium crown on paid features, and a notice when a plan ends.

### Topics (slides)
- New topic types: **website** and **YouTube** topics. Website topics include a Google browser card with a back/forward/reload bar and remember their page.
- Pinch-zoom on every topic type. Text topics scale their font to fit and have a 200-character limit with a warning.
- The Topic Manager adds drag-and-drop reordering, an animated slide when moving a card with ↑/↓, and two-step delete (disable first, then delete). It also has a delete-all button.
- A new fade-through transition between topics, plus a first-run hint that teaches the swipe gestures.
- **Content plan:** today's planned video shows as a strip with a Practice screen and a week view, and can be loaded as topics.
  - Each planned video gets its own topic set, which you can switch between with chips or the Topic sets sheet. Long-press a set to delete it.
  - Topics you create yourself are labelled **Made on this device**.
  - The app reports a planned video as recorded automatically.

### Recording
- Switch between the front and back camera, with a larger default face cam circle that moves freely.
- **Full-screen recording**: the face cam bubble floats over other apps while you record.
- External microphone support.
- **Drawing board**: pen, eraser, text, colour palette, undo/redo. It remembers what you drew on each topic.
- **Pen on the current topic**, before and during recording.

### After recording
- **Auto-crop** into other aspect ratios (9:16 first, then 4:5, 1:1). The preview swipes between the versions.
- **AI background replacement** for the face cam, with preset or your own backgrounds.
- **Video editor**: trim, split and cut sections on a zoomable timeline, change audio volume and add audio tracks, with undo. You can edit old recordings too.
- **Subtitles**: word-synced karaoke-style subtitles, burned into every cropped version.
- **AI captions** for posts, which you can view and edit.
- **AI Edit**: automatic edits with text looks, motions, effects, transitions, icon overlays and sound.
- **My Videos** groups all versions of one recording (original, crops, edited) together.
- Multi-select recordings, and a bigger preview player where you tap to play or pause.

### Publishing
- Upload and post recordings to connected social platforms, with live post status on each recording.
- Auto-publish after recording, with an undo window, Wi-Fi-only uploads, and a result notification.
- Resumable uploads that keep going on slow or unstable networks.

### Under the hood
- Privacy-respecting activity tracking with batched Wi-Fi uploads (see `ACTIVITY_TRACKER_PRIVACY.md`).
- The app detects whether it was installed fresh or updated, and from which version (an update from v1 is recognised).

---

## v1.0 (versionCode 4), released on Google Play 2026-09-23 17:27

Built from `e9ebe01` (the last commit before the release; the versionCode 4 bump was committed afterwards).

- Presentation-style recording: topics (text, image and video slides) shown full screen while you talk.
- A draggable, resizable circular front-camera overlay recorded together with the screen.
- A Topic Manager to add, edit, reorder (↑/↓) and delete topics, plus starter topics on first launch.
- Swipe left or right anywhere to change topics, with horizontal slide transitions. Videos in topics start after the transition.
- Record button with a countdown and start flash, immersive recording mode, and a small floating stop button. On the first two recordings, a red arrow points to the stop button.
- Permissions are asked only when you first tap Record, with an explanation first.
- Recordings saved to the phone's camera gallery (DCIM) with readable file names.
- A Saved Recordings screen to play, rename and delete videos, plus a video player popup after each recording.
