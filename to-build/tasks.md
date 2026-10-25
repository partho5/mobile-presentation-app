Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.
-----------------

RULE: this PC hangs under load, so do not run gradle, unit tests or the emulator. Write code only and verify by reading it against this plan; the owner builds and runs tests on a higher-RAM PC. Build one part at a time and update Progress below.

------------------

# Video Editor

Simple editor: cut bad parts, tidy the sound, save. Few features, done best. Priorities: frictionless UX, highest quality.
Code paths relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`. Full design rationale: `VIDEO_EDITOR_PLAN.md`.
Build one part at a time; tick boxes and commit per part.

## Progress

| Part | Scope | Status |
|---|---|---|
| 1 | Edit model + video cut renderer (MediaCodec H.264), no UI | ✅ code done; device check pending |
| 2 | Audio: volume / mute / remove original / add audio, mixed in the renderer | ✅ code done; device check pending |
| 3 | Editor screen: player, timeline, trim / split / delete, undo-redo | ✅ code done; device check pending |
| 4 | Audio lane UI: original + added tracks, volume / offset / trim / remove | ⬜ |
| 5 | Post-recording preview: Edit + Publish buttons, editor gate + setting | ⬜ |
| 6 | Edit an old recording from the 3-dots menu (new group) | ⬜ |
| 7 | QA + hardening | ⬜ |

## Decisions (settled)

1. **Scope v1**: trim, split at playhead, delete any segment; audio volume / mute / remove original / add audio file. No text, filters, speed, transitions, reordering, or voice-over.
2. **Non-destructive**: editor builds an edit list only; the master is never modified.
3. **One re-encode, only when needed**: no cuts and no audio change = existing lossless FFmpeg crop flow. Cuts = one hardware H.264 `MediaCodec` re-encode (decode, encode, like `bgreplace/OfflineBgProcessor`) at ~1.5x recording bitrate (~12 Mbps), no perceivable quality loss. Output must stay under `MOBILE_UPLOAD_MAX_BYTES` (4 GiB): lower bitrate or refuse.
4. **FFmpeg untouched** (lossless crop only, `util/VideoCropHelper`). Editor uses `MediaExtractor` / `MediaCodec` / `MediaMuxer`. This FFmpeg build has no x264.
5. **Order with bg-replace**: bg-replace runs first on the full master; cuts apply after as plain timestamps, so the `BubbleTrack` timeline is never remapped. Editor opens only on the final master.
6. **Setting "Show video editor after recording"**, default OFF. OFF: today's flow unchanged, user taps **Edit** in the preview panel. ON: after bg-replace finishes, the editor opens automatically on the final master (shows "Preparing..." until then); crops run immediately as today; Save makes a **new group** (same as Decision 10). **No deferred-crop flow, one editor flow only.** Auto-publish ON always skips the auto-open. One gate method `EditorGate` decides.
7. **Preview-panel action row** (left to right): delete, share, all videos, **Edit**, **Publish**. Edit: opens the editor on the group master; disabled "Preparing..." while bg-replace runs; crops in flight do not block it. Publish: existing publish flow for the centre version, no edit; hidden when auto-publish is ON (9:16 already queued). Dialog is also opened from My Videos, so both buttons show there.
8. **Publish when signed out**: no upload. Show a message: "Couldn't upload because you are signed out". (Owner decision.)
9. Existing cropped-versions / preview screen kept as is, only extended with the two buttons.
10. **Editing an old recording** (3-dots, Edit): always opens the group's **master** (even from a crop card). Save creates a **new group**: `name_edit.mp4` + its own crops (`name_edit_9x16.mp4`, ...), same ratios the old group had. Old group untouched. Master missing = hide Edit.
11. **After a successful Save**: keep both groups; show a non-blocking one-tap "Delete original?" offer, default is keep. Never delete automatically.
12. Editor has a vivid **Save** (primary) and **Publish** (= Save, then the existing publish flow; if signed out, see Decision 8). Back with unsaved edits asks "Discard edits?".
13. Aspect-ratio order wherever listed: **9:16 first**, then 4:5, 1:1.
14. **Voice-over inside the editor: out of v1.**

## Facts a fresh session needs

- Pipeline (`service/BgReplaceService.java`): recording stops, optional bg replace rewrites the file in place, then `VideoCropHelper.cropAsync` writes `<name>_<w>x<h>.mp4` crops (stream copy + H.264 SPS crop flag + `Mp4VideoSize` container patch).
- A recording's versions are a **group** (`util/VideoGroupStore`: path to group id; rename moves the entry). Assign the group id to every new file before the list is scanned (see `cropAsync` registration lock).
- Crops in flight are `PendingCrop` entries + `ACTION_CROP_STATE_CHANGED` broadcast. `.part.mp4` temp files must end in `.mp4` (MediaProvider derives MIME from extension).
- Auto-publish picks `<name>_9x16.mp4` by name from the master path (`publish/AutoPublishFiles`). `PublishJob` rows are keyed by file path.
- Recording audio is AAC 44.1 kHz 128 kbps and only exists if `key_record_audio` is on: the master may have **no audio track**; adding audio must work then.
- Card 3-dots menu: `res/menu/recording_card_menu.xml`, shown by `adapter/RecordingsAdapter.showCardMenu`. `ui/VideoPlayerDialog` has its own rename / delete / share buttons.

## Design

**Edit model** (pure Java, unit-testable): `EditProject { masterPath; List<Range> keep (sorted, non-overlapping ms; empty = whole file); AudioSettings original (volume 0..2, muted, removed); List<AddedAudio> added (uri, sourceIn/Out, startOnTimeline, volume) }`. All time math (output to source mapping, merge/split, duration) in one tested class. Undo/redo = stack of immutable snapshots.

**Renderer** (`edit/EditRenderer`, background thread, cancellable, progress callback):
- Video: seek each kept range to the preceding sync frame, decode, drop frames before range start, encode H.264 (surface in/out), re-stamp timestamps contiguously.
- Audio, cuts only: copy AAC samples for kept ranges at frame boundaries (~23 ms), no quality loss.
- Audio changed: decode to PCM, apply gain, sum with clipping guard, encode AAC 128-192 kbps.
- Mux to `.part.mp4` then rename. Verify output (duration tolerance, A/V sync at start and end).

**Editor UI** (`ui/VideoEditorActivity`): player that skips deleted segments; thumbnail timeline with draggable trim handles, playhead, Split, Delete, Undo/Redo; large one-hand touch targets; audio lane under the video lane (original: volume / mute / remove; added: file picker `ACTION_OPEN_DOCUMENT audio/*`, drag offset, trim, volume, remove); bottom Save + Publish.

**Post-recording flow**: `VideoPlayerDialog` + `res/layout/dialog_video_player.xml` gain two `ImageButton`s after `btn_all_videos`. `EditorGate.shouldShowAfterRecording(context)` = auto-publish off AND setting on; `MainActivity.onRecordingReady` calls only this. Edit enabled-state follows bg-replace for that master via the existing state broadcast.

## Part 1 - Edit model + video cut renderer
- [x] `edit/EditProject` + range math + unit tests (merge, split, delete, time mapping, empty = whole file)
- [x] `edit/EditRenderer` video path: extract, decode, encode (H.264, ~1.5x source bitrate), mux
- [x] Audio passthrough for cuts (sample copy at frame boundaries)
- [x] Output verification (duration, sync); cancel + progress; `.part.mp4` then rename
- [x] Size check against the 4 GiB upload cap (cap bitrate if the estimate exceeds it)
- [x] Confirm bg-replace preserves duration/timestamps exactly (assumed); fall back if not (done: bg-replace re-stamps frames with source pts and copies audio untouched; renderer reads the real duration/timestamps from the file anyway, documented in `EditRenderer`)
- [ ] Owner check on device: cut at start / middle / end; compare quality vs master; FFmpeg crop works on output

## Part 2 - Audio rendering
- [x] Gain / mute / remove original; master with no audio track works
- [x] Decode + mix + AAC encode for added tracks (offset, trim, volume, clipping guard)
- [x] Added track longer or shorter than video (clip / pad with silence)
- [x] Tests for mixing math
- [ ] Owner check on device: gain / mute / remove; add a longer and a shorter track, mono and stereo, with and without master audio

## Part 3 - Editor screen (video)
- [x] `VideoEditorActivity` + layout; reuse the app's existing player stack (check what `VideoPlayerDialog` uses)
- [x] Timeline, trim handles, split, delete, undo/redo, skip-deleted playback
- [x] Save renders via `EditRenderer` with progress; discard-confirm on back (output `name_edit.mp4` / `name_edit_2.mp4`; Publish button present but hidden until Part 5)
- [ ] Owner check on device: trim handles, split/delete, undo/redo, skip-deleted playback, rotation, save + cancel

## Part 4 - Audio lane UI
- [ ] Original track controls (volume, mute, remove)
- [ ] Add audio from file; offset / trim / volume / remove per added track
- [ ] Waveform or simple bar so the user can see where sound is

## Part 5 - Post-recording preview buttons
- [ ] Two icons (Edit, Publish) in the `VideoPlayerDialog` action row after "all videos"; new vector drawables
- [ ] Edit launches `VideoEditorActivity` on the group master; "Preparing..." while bg-replace runs; hidden if master missing
- [ ] Publish uses the existing publish flow for the centre version; hidden when auto-publish is ON; signed out shows "Couldn't upload because you are signed out", no upload
- [ ] `EditorGate` + settings checkbox "Show video editor after recording" (`RecordSettings` key, default off); ON = auto-open on the final master after bg-replace, new group on Save
- [ ] Editor Save / Publish wiring (Publish = Save then existing publish flow)
- [ ] Row fits 5 icons at 360 dp; check both entry points (post-recording and My Videos)

## Part 6 - Edit old recordings
- [ ] 3-dots menu item "Edit" (same launcher as the panel Edit; hidden if master missing)
- [ ] Save writes `name_edit.mp4` + crops as a **new group**; old group untouched
- [ ] Naming collisions (`name_edit`, `name_edit_2`, ...)
- [ ] Post-save "Delete original?" one-tap offer (default keep)

## Part 7 - QA + hardening
- [ ] Rotation, process death mid-edit and mid-render, low storage, cancel mid-render
- [ ] Long recording (30+ min), many splits, very short remaining result
- [ ] Videos with no audio track, Bluetooth-mic recordings
- [ ] Group list shows the edited group correctly; publish works on edited files
