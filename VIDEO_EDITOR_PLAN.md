# Video Editor — Plan & Progress Tracker

A **handy, simple** editor: cut bad parts, tidy the sound, save. Few features, done best.
Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.
Build **one part per session** ("build part N"); tick the boxes and commit as you go.

## Status

| Part | Scope | Status |
|---|---|---|
| 1 | Edit model + video cut renderer (MediaCodec H.264), no UI | ⬜ |
| 2 | Audio: volume / mute / remove original / add audio, mixed in the renderer | ⬜ |
| 3 | Editor screen: player, timeline, trim / split / delete segment, undo-redo | ⬜ |
| 4 | Audio lane UI: separate audio track(s), add / remove / volume / offset | ⬜ |
| 5 | Post-recording preview: Edit + Publish buttons, launch editor on the finished master | ⬜ |
| 6 | Edit an old recording from the 3-dots menu (new group) | ⬜ |
| 7 | QA + hardening (rotation, process death, storage, long videos) | ⬜ |

## What the editor is for

The common case: the user records, notices a bad stretch, wants it gone, then saves or publishes.
After recording, the **existing preview panel stays exactly as today** (no auto-open of the editor). Two new
buttons sit in its action row: **Edit** and **Publish**. Edit opens the editor on the group's master. Nothing is
rendered until the user confirms, and then every aspect-ratio version (**9:16 first**, then 4:5, 1:1) carries
the same cuts.

## Decisions (settled — do not re-litigate)

1. **Scope v1**: trim, split at playhead, delete any segment (cut from anywhere, like a typical editor), plus
   **separate audio track editing**: volume, mute, remove the original audio, add an audio file.
   No text, filters, speed, transitions, or clip reordering.
2. **Non-destructive**: the editor only builds an edit list (kept time ranges + audio settings). The master file
   is **never modified**. Original is always preserved.
3. **One re-encode, only when needed**:
   - No cuts and no audio change: nothing is re-encoded; existing flow (FFmpeg lossless crop) runs as today.
   - Cuts: the master is re-encoded **once** with Android's hardware **H.264 `MediaCodec`**
     (decode → encode, same shape as `bgreplace/OfflineBgProcessor`), at ~1.5× the recording bitrate
     (recording is 8 Mbps, so ~12 Mbps). Must show no perceivable quality loss.
   - Output is H.264, so the existing lossless header crop keeps working on it.
   - Size matters downstream: crops are stream copies (same size as the master) and the direct-upload cap
     `MOBILE_UPLOAD_MAX_BYTES` is **4 GiB** (see `to-build/tasks.md`, DU Decision 2). At ~12 Mbps that is ~47 min of
     edited video; Part 1 must make the renderer refuse or lower bitrate so output never exceeds the cap.
4. **FFmpeg is not touched.** It stays for the lossless crop only (`util/VideoCropHelper`). The editor uses
   `MediaExtractor` / `MediaCodec` / `MediaMuxer`, no FFmpeg dependency.
5. **Order with background replace**: bg-replace runs on the full uncut master first; cuts are applied after as
   plain timestamps. So the face-cam `BubbleTrack` timeline is never remapped. Via the **Edit button** the editor
   waits for the final master (Edit disabled until bg-replace finishes). Via the checkbox-ON path the editor opens
   on the raw video while bg-replace runs; Save waits if it is still running.
6. **Settings checkbox "Show video editor after recording", default OFF.**
   - OFF (default): recording finishes → today's flow, unchanged (bg-replace, crops, preview panel). The user
     enters the editor by tapping **Edit** in the panel.
   - ON: the editor opens directly after recording, on the master, as the original design (crops deferred until
     Save, see "Post-recording flow").
   - Auto-publish ON always skips the editor, whatever the checkbox says.
   - One small gate method decides; nothing else knows about it.
7. **Preview-panel action row** (left → right): delete, share, all videos, **Edit**, **Publish**.
   - Edit: opens the editor on the group's master (same launcher as the 3-dots Edit, Part 6).
     Disabled with a small "Preparing…" state while bg-replace is still running on the master; enabled once the
     master is final. Crops still in flight do not block it (the edit makes its own new group).
   - Publish: the existing publish flow for the version in the centre, as-is, no edit. Reuse whatever the card
     publish uses (verify in Part 5).
   - Auto-publish ON: **Publish hidden** (the 9:16 is already queued); Edit stays.
   - The same dialog is also opened from My Videos, so the two buttons show there too (consistent).
8. **Editor has a vivid Save button** (primary) and Publish. "Save as-is" is just Save with no edits.
9. **The existing cropped-versions / preview screen after recording is kept as is.** It is only extended with the
   two buttons above.
10. **Editing an old recording** (3-dots → Edit):
    - Always opens the group's **master**, even when tapped on a crop card (crops are already framed; editing one
      would leave siblings with different cuts).
    - Save creates a **new group**: `name_edit.mp4` + its own crops (`name_edit_9x16.mp4`, …), same ratios the old
      group had. The old group is untouched (publish rows point at files by path; originals stay).
    - If the master is missing (only crops remain), hide Edit for that group.

## Facts a fresh session needs

- Pipeline today (`service/BgReplaceService.java`): recording stops → optional bg replace rewrites the file
  in place (original kept only on failure) → `VideoCropHelper.cropAsync` writes `<name>_<w>x<h>.mp4` crops.
  Crops are a **stream copy + H.264 SPS crop flag**, plus `Mp4VideoSize` patching the container size.
- A recording's versions are a **group** (`util/VideoGroupStore`: path → group id; rename moves the entry).
  Group id must be assigned to every new file before the list is scanned (see `cropAsync`'s registration lock).
- Crops in flight are `PendingCrop` entries + `ACTION_CROP_STATE_CHANGED` broadcast; `.part.mp4` temp files
  must keep ending in `.mp4` (MediaProvider derives MIME from extension).
- Auto-publish picks `<name>_9x16.mp4` by name from the master path (`publish/AutoPublishFiles`).
  `PublishJob` rows are keyed by file path.
- Recording audio is AAC 44.1 kHz 128 kbps, and only exists if `key_record_audio` is on, so the master may have
  **no audio track**: adding audio must work then.
- The card 3-dots menu is `res/menu/recording_card_menu.xml`, shown by `adapter/RecordingsAdapter.showCardMenu`;
  `ui/VideoPlayerDialog` has its own rename / delete / share buttons.
- This FFmpeg build has **no x264**; do not plan any FFmpeg re-encode.

## Design

### Edit model (pure Java, unit-testable)

```
EditProject
  masterPath
  List<Range> keep            // kept source ranges in ms, sorted, non-overlapping; empty edit = whole file
  AudioSettings original      // volume 0..2, muted, removed
  List<AddedAudio> added      // uri, sourceInMs, sourceOutMs, startOnTimelineMs, volume
```

All math (map output time ↔ source time, merge/split ranges, total duration) lives in one class with tests.
Undo/redo = a stack of `EditProject` snapshots (the object is small and immutable).

### Renderer (`edit/EditRenderer`, background thread, cancellable, progress callback)

- **Video**: `MediaExtractor` seek to each kept range's preceding sync frame, decode, drop frames before the
  range start, encode with `MediaCodec` H.264 (surface in/out), re-stamp timestamps contiguously.
- **Audio, cuts only** (original audio untouched): copy AAC samples for the kept ranges (cut at AAC frame
  boundaries, ~23 ms, inaudible) → no audio quality loss.
- **Audio changed** (volume / mute / added track): decode to PCM, apply gain, sum tracks with clipping guard,
  encode AAC 128–192 kbps.
- `MediaMuxer` writes to a `.part.mp4`, then rename into place (same pattern as `VideoCropHelper`).
- Output is verified before use: duration within tolerance, A/V in sync at start and end.

### Editor UI (`ui/VideoEditorActivity`)

- Top: player. Playback **skips deleted segments** so the user hears the real result.
- Timeline: thumbnails strip, draggable trim handles, playhead, **Split** and **Delete** on the selected segment,
  **Undo / Redo**. Large touch targets; one-hand friendly.
- Audio lane under the video lane: original track (volume, mute, remove) and added tracks (add from file
  picker `ACTION_OPEN_DOCUMENT audio/*`, drag to offset, trim, volume, remove).
- Bottom: **Save** (primary, vivid) and **Publish**. Back with unsaved edits asks "Discard edits?".

### Post-recording flow (Part 5)

- `ui/VideoPlayerDialog` + `res/layout/dialog_video_player.xml`: add two `ImageButton`s to the existing action
  row (after `btn_all_videos`). Edit / Publish act on the group master / the centre version respectively.
- **Gate**: `EditorGate.shouldShowAfterRecording(context)` = auto-publish **off** AND checkbox **on**
  (default off). `MainActivity.onRecordingReady` calls only this: no → show the preview panel as today.
- **Checkbox OFF (default path)**: BgReplaceService / crop pipeline **unchanged**: crops run immediately. The
  editor (via Edit) always produces a **new group** (Decision 10); no deferred-crop state.
- **Checkbox ON path**: the editor opens on the raw master while bg-replace runs; crops are deferred with the
  pending job and the editor's Save renders (if edits) then `cropAsync`. Leaving the editor without choosing =
  treat as Save as-is (crops made, nothing lost); pending state survives app kill via the existing startup sweep.
  Save waits ("Waiting for background…") if bg-replace is still running; the player reloads the same path and
  keeps its position after the swap. No new group here, since no crops exist yet.
- Edit enable state follows bg-replace for that master (already tracked: `BgReplaceService`), updated via the
  existing state broadcast.
- Trade-off accepted: an edited recording leaves the unedited group beside it (extra crops + storage). Original
  stays safe; user deletes it manually.

## Parts

### Part 1 — Edit model + video cut renderer
- [ ] `edit/EditProject` + range math + unit tests (merge, split, delete, time mapping, empty = whole file)
- [ ] `edit/EditRenderer` video path: extract → decode → encode (H.264, ~1.5× source bitrate) → mux
- [ ] Audio passthrough for cuts (sample copy at frame boundaries)
- [ ] Output verification (duration, sync); cancel + progress; `.part.mp4` then rename
- [ ] Output size check against the 4 GiB upload cap (cap bitrate if the estimate would exceed it)
- [ ] Manual check on device: cut at start, middle, end; compare quality vs master; confirm FFmpeg crop works on the output

### Part 2 — Audio rendering
- [ ] Gain / mute / remove original; master with no audio track works
- [ ] Decode + mix + AAC encode for added tracks (offset, trim, volume, clipping guard)
- [ ] Added track longer or shorter than the video (clip / pad with silence)
- [ ] Tests for mixing math

### Part 3 — Editor screen (video)
- [ ] `VideoEditorActivity` + layout; reuse the app's existing player stack (check what `VideoPlayerDialog` uses)
- [ ] Timeline, trim handles, split, delete, undo/redo, skip-deleted playback
- [ ] Save renders via `EditRenderer` with progress; discard-confirm on back

### Part 4 — Audio lane UI
- [ ] Original track controls (volume, mute, remove)
- [ ] Add audio from file; offset / trim / volume / remove per added track
- [ ] Waveform or simple bar so the user can see where sound is

### Part 5 — Post-recording preview buttons
- [ ] Two icons (Edit, Publish) in the `VideoPlayerDialog` action row, after "all videos"; new vector drawables
- [ ] Edit → launch `VideoEditorActivity` on the group master; disabled "Preparing…" while bg-replace runs;
      hidden if the master is missing
- [ ] Publish → existing publish flow for the centre version; hidden when auto-publish is on
- [ ] `EditorGate` + settings checkbox "Show video editor after recording" (`RecordSettings` key, default **off**)
- [ ] Checkbox ON path: `BgReplaceService` defers crops, hands off to the editor; bg-replace swap while the
      editor is open; leave-without-choosing = Save as-is
- [ ] Row fits 5 icons at 360 dp width; dialog is also used from My Videos (check both)
- [ ] Editor's own Save / Publish wiring: Publish = Save then the existing publish flow

### Part 6 — Edit old recordings
- [ ] 3-dots menu item "Edit" (same launcher as the preview-panel Edit button; hidden if master missing)
- [ ] Save writes `name_edit.mp4` + crops as a **new group**; old group untouched
- [ ] Naming collisions (`name_edit`, `name_edit_2`, …)

### Part 7 — QA + hardening
- [ ] Rotation, process death mid-edit and mid-render, low storage, cancel mid-render
- [ ] Long recording (30+ min), many splits, very short remaining result
- [ ] Videos with no audio track, with Bluetooth-mic recordings
- [ ] Group list shows edited group correctly; publish still works on edited files

## Open items to confirm

- Checkbox ON path is a second flow to maintain (deferred crops). Alternative: drop the ON path and have the
  checkbox only auto-open the editor as a **new group** on the finished master (simpler, wastes the first crops).
- Publish button when signed out: route to sign-in via the existing flow (proposed), vs hide.
- After a successful edit Save: offer "delete the original group?" (proposed: no, leave both).
- Whether bg-replace keeps duration/timestamps exactly (assumed; verify in Part 1).
- Voice-over recording inside the editor: left out of v1; add only if asked.
