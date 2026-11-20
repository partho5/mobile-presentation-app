<!-- runner-config
REPOS=/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder
BRANCH=feat/ai-video-edit
SCRATCH=test_audio.wav test_video.mp4 run.log
-->

RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 5 tests in `EngineContractTest` /
`SoundTrackTest` fail on a clean HEAD; they are not yours.

One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

# FaceCam app: switch brand with no friction and no leak (cards WS1-WS3)

The web half and the reasons for each choice are in
`/home/haku/projects/Next.js/Social-Media-Manager/WORKSPACE_SWITCH_PLAN.md`. These cards need **no server change**: every
route they use already exists. The web cards are not required first.

The previous card set is kept in `to-build/tasks--ai-video-edit.md`.

## Shared context

### Goal
A signed-in person with several brands always sees which brand the phone is publishing to, changes it in two taps from
where they are, and after a change sees nothing of the old brand: no channels, preset, credits or plan of the old brand on
any screen. A change is a **fresh restart of the app's screens** that brings the person back to the screen they changed it
from (the phone's version of the web's full page reload). Work already started (uploads, publish jobs, AI edits) finishes
in the brand it was started in.

### Owner decisions (2026-11-20)
- The visible word is **Brand**, the same as the website. Class, field, route and header names keep "workspace".
- The recording screen stays clean: no brand chip on the camera view. The brand shows where it matters, on the publishing
  screens and in the drawer.
- A change restarts the screens. Never kill the process: running uploads must keep going.
- The choice stays per phone, as today. Syncing it with the website is out of scope.

### What already exists (reuse, don't rebuild)
- `auth/TokenStore`: `workspaceId()`, `workspaceName()`, `saveWorkspace(id, name)`, `clearWorkspace()`.
- `auth/AuthRepository`: sends `x-workspace-id` on every authed call. A 403 `WORKSPACE_FORBIDDEN` clears the pick.
- `account/AccountApi`: `listWorkspaces()` (`GET /user/organizations`) and `currentWorkspaceId()` (`GET /user/self`,
  the server default when nothing is picked).
- `ui/WorkspaceActivity`: the picker (list, current marked, loading, empty, error and retry). `pick()` pins default
  jobs, saves the pick, refreshes the plan, shows a toast, then `finish()`es.
- `ui/AccountActivity`: a "Workspace" row that opens the picker. It's reached from the Auto-publish screen's account row.
- Pinned by brand already: `db/PublishJob.workspaceId`, `aiedit/flow/AiEditStore.PendingReceipt.workspaceId`,
  `publish/CaptionUsage`, and `entitlements/LapseWatch` (scoped by session and brand).
- Navigation: the drawer (`MainActivity.setupDrawer`, header `res/layout/nav_header.xml`). The `nav_account` item opens
  `ui/AutoPublishActivity`. `ui/PublishActivity` takes `EXTRA_FILE_PATH`. `ui/StartupActivity` is the launcher and routes
  to onboarding or `MainActivity`.

### App facts
- Android, Java, minSdk 31. Java paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.
  Tests are JUnit 4 under `app/src/test/java/...`, **with no Robolectric and no androidTest**. Logic that tests must reach
  goes in a plain Java class with no Android types, as `account/Workspace` does. Activities are checked by the owner on a
  device.
- Commit per card: `feat(brand): <card id> <what>` (or `fix(brand): ...`), ending with the `Co-Authored-By` line the
  session gives. Never commit `test_audio.wav`, `test_video.mp4`, `run.log`.
- Never log tokens, codes, brand names or ids in `ActivityLog`. Event names only (`ACTIVITY_LOG_PRIVACY.md`).
- Do not touch `aiedit/engine/*`, `subtitle/*` or the recording pipeline.

## Progress

| Card | Repo | What | Depends on | Status |
|---|---|---|---|---|
| WS1 | app | "Brand" wording; one brand row shown on Auto-publish, Publish and the drawer header, each opening the picker | - | ⬜ |
| WS2 | app | A brand change restarts the screens and returns to the screen it came from; same brand just closes | WS1 | ⬜ |
| WS3 | app | Brand-data audit: everything held for a brand is keyed by brand or dropped on change; tests | WS2 | ⬜ |

## WS1: Brand wording and a visible brand row

**Read first:** `ui/WorkspaceActivity`, `ui/AccountActivity` (its workspace row and how it names the server default),
`ui/AutoPublishActivity` (top of `onCreate`, `onResume`), `ui/PublishActivity` (header), `MainActivity.setupDrawer`,
`res/layout/{nav_header,activity_auto_publish,activity_publish,item_workspace}.xml`, `res/values/strings_account.xml`.

**Do:**
- Change every *visible* "Workspace" string to "Brand", for example "Brand", "Default brand", "You no longer have access
  to that brand…", and the hint "Posts, channels and credits belong to the brand you pick…". Keep the string keys.
- Add one small reusable brand row (layout plus a binder in `ui/`): an initials avatar, the brand name and a "Change"
  affordance. The label comes from the same source the Account row uses: the picked name, or the server default's name
  when nothing is picked, or nothing while loading. Signed out, the row is hidden.
- Show the row:
  - **Auto-publish screen:** at the top, above channels and preset.
  - **Publish screen:** as "Posting to <Brand>", so the person sees where a manual post goes.
  - **Drawer header:** as the subtitle when signed in, in place of "Features & Settings".
- Tapping the row opens `WorkspaceActivity`. Refresh it in `onResume`.
- With only one brand, the row still shows and opens the picker. The picker's hint already says brands are created on the
  website.
- Put the label choice (picked, server default, or none) in a plain class so a JVM test covers its three cases.

**Done when:** the three places show the same brand name; the wording says Brand everywhere visible; the label test
passes; `assembleDebug` passes.

## WS2: A change is a fresh restart back to the same screen

**Read first:** `ui/WorkspaceActivity.pick`, the WS1 binder, `ui/StartupActivity`, and how `AutoPublishActivity`,
`PublishActivity` and `AccountActivity` read their intent.

**Do:**
- Every screen that opens the picker passes its own intent along as the place to return to.
- When the picked brand **differs** from the current one (or nothing was picked before):
  1. Keep today's steps (pin default jobs, save, refresh the plan).
  2. Clear the whole task.
  3. Start the stack `MainActivity` → the screen to return to, rebuilt from its original intent, so `PublishActivity`
     reopens the same recording. Android's task stack builder does exactly this.
  4. Show "Switched to <Brand>" after the restart.
  The process keeps running, so `PublishManager` and `AiEditService` work goes on.
- When the **same** brand is picked: just close the picker, as today.
- With no screen to return to (opened from somewhere new later), return to `MainActivity`.
- Block a double tap while the change is being applied.
- Put the restart decision (changed or not) in a plain class with a JVM test. Cover: same id, different id, none picked
  before, and the server default picked explicitly.

**Done when:** a change from Auto-publish comes back to a freshly loaded Auto-publish with the new brand's channels and
preset; a change from Publish reopens the same recording; Back from there goes to the recording screen, never to a
screen of the old brand; the tests pass; `assembleDebug` passes.

## WS3: Brand-data audit

**Read first:** every `static` field and singleton in `publish/`, `entitlements/`, `account/`, `aiedit/flow/`, `ui/`,
plus the `SharedPreferences` files those packages use (see `getSharedPreferences` call sites).

**Do:**
- List each value that belongs to a brand: channels, preset, credit balance, plan and entitlements, AI edit quotes and
  contract, caption usage, upload sessions. For each one, confirm it is either keyed by brand id, re-read after the
  restart, or cleared in `pick()`. Fix the ones that are none of these.
- Work started in brand A must keep A: a queued `PublishJob`, a running `AiEditService` job and its receipt, and a direct
  upload session. Confirm each sends its own pinned brand, never the current pick.
- Device-level values stay as they are: device id, recording settings, onboarding, theme.
- Write the audit result as a short table in the commit message body (value, where, how it is guarded).
- Add JVM tests for any guard this card adds.

**Done when:** no value of the old brand can appear after a change; each fix has a test; `assembleDebug` passes.

## Owner device QA (after WS3)

1. [ ] Signed in with three brands: the drawer subtitle, the Auto-publish top row and Publish's "Posting to" line all name
   the same brand.
2. [ ] Auto-publish → brand row → pick another: the app restarts, comes back to Auto-publish, and shows the new brand's
   channels, preset and credits. The toast says "Switched to <Brand>".
3. [ ] Publish (a recording) → brand row → pick another: the same recording reopens with the new brand's channels.
   Back goes to the recording screen.
4. [ ] Pick the brand that is already current: the picker just closes, with no restart.
5. [ ] Queue a publish in A, switch to B before it runs: the post still goes to A.
6. [ ] Start an AI edit in A, switch to B: the edit finishes and is charged in A.
7. [ ] On the website, remove this user from the picked brand: the next screen says access was lost, and the brand row
   shows the default brand.
8. [ ] One brand only: the row shows its name, and the picker explains that brands are created on the website.
9. [ ] Airplane mode: the row shows the last known name, and opening the picker shows the offline message and Retry.
10. [ ] Every visible label says Brand, not Workspace.
