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

# FaceCam app: switch brand seamlessly, never post to the wrong brand (cards WS1-WS4)

The web half is in `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md` (cards W1-W2). These cards need
**no server change**: every route they use already exists. The web cards are not required first.

The previous card set is kept in `to-build/tasks--ai-video-edit.md`.

## Shared context

### Goal
A signed-in person with several brands always sees which brand the phone is posting to, changes it in two taps from
where they are, and after a change sees nothing of the old brand anywhere. **Nothing is ever posted to a brand the person
did not choose:** no floating default, no silent fallback, and no switch during a recording. Work already started finishes
in the brand it was started in.

### Owner decisions (2026-11-20)
- **Seamless UX is the top priority.** No confirm dialogs, no extra taps, no new screens. People with one brand see
  nothing new except its name on the publishing screens. Being offline never blocks seeing or keeping the brand.
- **Never switch brand during a recording.** While a recording runs, the brand row shows the name but cannot change it.
- **Don't touch the recording screen.** No edits to `MainActivity`, `res/layout/activity_main.xml`,
  `res/layout/nav_header.xml`, `res/menu/drawer_menu.xml` or `service/*`.
- The visible word is **Brand**, as on the website. Class, field, route and header names keep "workspace".
- A change restarts the app's other screens and brings you back where you were. Never kill the process: running uploads
  and AI edits must keep going.
- The choice stays per phone. Syncing it with the website is out of scope.

### Safety rules every card keeps
1. **The phone always holds a real brand id.** No job, request or screen ever relies on "the server's default".
2. **Losing access fails closed.** The phone never moves to another brand by itself. It asks the person to choose.
3. **A job keeps the brand it was made in**: publish jobs, AI edits, caption usage and upload sessions.
4. **The plan follows the brand.** One brand's plan never decides what another brand may do.
5. **Anything outside a screen that is about a job names its brand,** when the person has more than one: notifications,
   the undo countdown and history.

### What already exists (reuse, don't rebuild)
- `auth/TokenStore`: `workspaceId()`, `workspaceName()`, `saveWorkspace(id, name)`, `clearWorkspace()`.
- `auth/AuthRepository`: sends `x-workspace-id` on every authed call. On a 403 `WORKSPACE_FORBIDDEN` for the stored
  brand, `noteWorkspaceResult` clears the pick, so later calls go to the server default. That breaks rule 2 and is
  fixed in WS3.
- `account/AccountApi`: `listWorkspaces()` (`GET /user/organizations`) and `currentWorkspaceId()` (`GET /user/self`).
- `ui/WorkspaceActivity`: the picker. `pick()` pins default jobs, saves, calls `EntitlementsRefresher.refreshNow()`, shows
  a toast, then `finish()`es. `ui/AccountActivity` has a "Workspace" row that opens it.
- Pinned already: `db/PublishJob.workspaceId`, set in `PublishManager.beginAuto` at recording stop, but it can be
  **null**, which means the server default. Also `aiedit/flow/AiEditStore.PendingReceipt.workspaceId` (the runner
  sends the job's own id), `publish/CaptionUsage`, `PublishManager.pinDefaultWorkspace` / `PublishJobDao.pinUnpinned`, and
  `publish/PostStatusSync` (groups jobs by brand, null = default).
- Plan: `entitlements/Entitlements` builds `EntitlementState` with a scope supplier that returns only `sessionId()`, not
  the brand. That breaks rule 4 and is fixed in WS2. `FeatureSnapshot.beginRecording()` / `endRecording()` mark a
  recording in progress: its private static `recording` is non-null meanwhile. This is the one read-only signal for
  "recording now"; add a small public getter on `FeatureSnapshot` if needed. That file is not recording code.
- Screens that show a brand: `ui/AutoPublishActivity` (opened from the drawer item `nav_account`),
  `ui/PublishActivity` (`EXTRA_FILE_PATH`), `ui/AccountActivity`. Messages outside screens:
  `publish/AutoPublishNotifier`, `publish/AutoPublishUndo`, `publish/PublishLabels`, `SavedRecordingsActivity` and
  `adapter/RecordingsAdapter` (My Videos; not the recording screen).

### App facts
- Android, Java, minSdk 31. Java paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.
  Tests are JUnit 4 under `app/src/test/java/...`, **with no Robolectric and no androidTest**. Logic that tests must reach
  goes in a plain Java class with no Android types, as `account/Workspace` does. Activities are checked by the owner on a
  device.
- Commit per card: `feat(brand): <card id> <what>` (or `fix(brand): ...`), ending with the `Co-Authored-By` line the
  session gives. Never commit `test_audio.wav`, `test_video.mp4`, `run.log`.
- Never log tokens, codes, brand names or ids in `ActivityLog`. Event names only (`ACTIVITY_LOG_PRIVACY.md`).
- Do not touch `aiedit/engine/*`, `subtitle/*`, the recording screen or the recording pipeline.

## Progress

| Card | Repo | What | Depends on | Status |
|---|---|---|---|---|
| WS1 | app | "Brand" wording; one brand row on Auto-publish, Publish and Account; locked while recording | - | ⬜ |
| WS2 | app | A change loads the new brand's plan, then restarts the screens back where you were; never while recording | WS1 | ⬜ |
| WS3 | app | Always one real brand: pinned at sign-in and on startup, jobs never unpinned, lost access asks instead of falling back | WS2 | ⬜ |
| WS4 | app | Notifications, the undo countdown and My Videos name the job's brand when there is more than one | WS3 | ⬜ |

## WS1: Brand wording and a visible brand row

**Read first:** `ui/WorkspaceActivity`, `ui/AccountActivity` (its workspace row), `ui/AutoPublishActivity` (top of
`onCreate`, `onResume`), `ui/PublishActivity` (header), `entitlements/FeatureSnapshot`,
`res/layout/{activity_auto_publish,activity_publish,activity_account,item_workspace}.xml`,
`res/values/strings_account.xml`.

**Do:**
- Change every *visible* "Workspace" string to "Brand", for example "Brand", "You no longer have access to that brand",
  and "Posts, channels and credits belong to the brand you pick". Keep the string keys.
- Add one small reusable brand row (layout plus a binder in `ui/`): an initials avatar, the brand name and a "Change"
  affordance.
- Show the row:
  - **Auto-publish:** at the top, above channels and preset.
  - **Publish:** as "Posting to <Brand>".
  - **Account:** replaces the existing workspace row.
- Tapping the row opens `WorkspaceActivity`. Refresh it in `onResume`. With one brand, the row still shows and opens the
  picker, whose hint says brands are created on the website.
- **While a recording runs** (`FeatureSnapshot`): the row shows the name without "Change". A tap shows a one-line note,
  "You can change the brand after the recording", and opens nothing.
- The label comes from the stored pick. Until WS3 always stores one, an empty pick shows the server default's name, as
  the Account row does today.
- Put the label choice and the locked state in a plain class, with a JVM test for each case.

**Done when:** the three screens show the same brand; every visible label says Brand; the row is locked during a
recording; the tests pass; `assembleDebug` passes; the recording screen files are unchanged.

## WS2: A change is a fresh restart, with the new brand's plan

**Read first:** `ui/WorkspaceActivity.pick`, the WS1 binder, `entitlements/{Entitlements,EntitlementState,
EntitlementsRefresher,FeatureSnapshot}`, `ui/StartupActivity`, and how `AutoPublishActivity`, `PublishActivity` and
`AccountActivity` read their intent.

**Do:**
- **The plan follows the brand:** the entitlement scope becomes session id plus brand id. After a change, the old brand's
  plan token no longer counts. Until the new one arrives, gated features behave as "plan unknown", the same path as a
  fresh install. Nothing runs under the old plan. Test it with a JVM test on `EntitlementState`.
- **Every screen that opens the picker passes its own intent along** as the place to return to.
- **When the picked brand differs from the current one:**
  1. Mark that row "Switching…" and ignore further taps.
  2. Keep today's steps (pin default jobs, save the pick).
  3. Wait up to about 2 s for the new brand's plan. Slow or offline: go on anyway, because rule 4 already keeps the old
     plan out.
  4. Clear the whole task and start the stack `MainActivity` → the screen to return to, rebuilt from its original intent,
     so Publish reopens the same recording. Use Android's task stack builder; `MainActivity` is only started, not edited.
  5. Show "Switched to <Brand>".
- **When the same brand is picked:** just close the picker.
- **When a recording started meanwhile:** at the moment of picking, if a recording is running, do nothing and show the
  WS1 note.
- With no screen to return to, return to `MainActivity`.
- Put the decision (changed, same, or blocked by a recording) in a plain class with a JVM test. Cover: same id,
  different id, nothing stored, and recording running.

**Done when:** a change from Auto-publish comes back to a freshly loaded Auto-publish with the new brand's channels,
preset, credits and plan; a change from Publish reopens the same recording; Back never shows a screen of the old brand;
a pick during a recording does nothing; the tests pass; `assembleDebug` passes.

## WS3: Always one real brand, and lost access asks

**Read first:** `auth/{TokenStore,AuthRepository,SessionManager}`, `account/AccountApi`, `ui/WorkspaceActivity`,
`publish/{PublishManager,PostStatusSync}`, `db/PublishJobDao`, `entitlements/EntitlementsApi` (its own
`WORKSPACE_FORBIDDEN` handling), and the WS1 binder.

**Do:**
- **Pin at sign-in.** Right after sign-in, read the server default (`currentWorkspaceId()` plus the list for its name)
  and save it as the pick. Sign-in is not slowed: this runs in the background, and the publishing screens wait for it the
  way they wait for channels today.
- **Pin on startup for existing installs.** When a session exists but no pick is stored, do the same once on startup.
  Then pin every unpinned job to that brand (`pinUnpinned`).
- **Jobs never go unpinned.** When a job is created and no pick exists yet, resolve and pin first. A job is never saved
  with a null brand.
- **Lost access asks instead of falling back.** On 403 `WORKSPACE_FORBIDDEN` for the picked brand, mark it as "access
  lost" rather than clearing it (in `AuthRepository` and `EntitlementsApi` alike).
  - The brand row shows "Choose a brand"; tapping it opens the picker.
  - Publish and Auto-publish open the picker in place of their content, and the person continues on the chosen brand
    with no other step.
  - Auto-publish of a new recording stays off until a brand is chosen. The recording itself is kept.
  - Jobs pinned to the lost brand fail with "No access to <Brand> anymore".
  - No call ever goes out without a brand id.
- Put the rules (when to pin, what "access lost" blocks) in a plain class with JVM tests. Cover: sign-in pins; an
  unpinned install is pinned once; a 403 marks access lost and never clears to the default; jobs are pinned at creation.

**Done when:** no code path sends a request without a brand id; a lost brand never turns into another brand by itself;
the tests pass; `assembleDebug` passes.

## WS4: Messages outside screens name the brand

**Read first:** `publish/{AutoPublishNotifier,AutoPublishUndo,PublishLabels}`, `SavedRecordingsActivity`,
`adapter/RecordingsAdapter`, `res/values/strings_publish.xml`, and the WS1 label class.

**Do:**
- When the person has **more than one brand**, every message about a job names the job's own brand (not the current
  pick):
  - the auto-publish notifications: scheduled, failed, needs plan, signed out
  - the undo countdown, for example "Posting to Acme in 10 s · Undo"
  - the publish status line in My Videos
- With one brand, the messages stay exactly as they are today.
- Brand names come from the stored list; a missing name shows nothing extra rather than an id.
- Tapping a notification about a job in another brand opens that job's status without switching the brand.
- Add a JVM test for the label rule: one brand gives no name; several brands give the job's brand; an unknown brand gives
  no name.

**Done when:** a person with two brands can always tell which brand a message is about; one-brand users see no change;
the tests pass; `assembleDebug` passes.

## Owner device QA (after WS4)

1. [ ] Three brands: Auto-publish, Publish ("Posting to …") and Account show the same brand.
2. [ ] Auto-publish → brand row → pick another: the app restarts back into Auto-publish, which shows the new brand's
   channels, preset, credits and plan; the toast says "Switched to <Brand>".
3. [ ] Publish (a recording) → pick another brand: the same recording reopens with the new brand's channels; Back goes to
   the recording screen.
4. [ ] Pick the current brand: the picker just closes.
5. [ ] During a recording, the brand row shows the name without "Change", and a tap shows the note. After the recording,
   the switch works.
6. [ ] Queue a publish in A, switch to B: the post still goes to A, and its notification says A.
7. [ ] Start an AI edit in A, switch to B: the edit finishes and is charged in A.
8. [ ] Fresh sign-in: a brand is shown at once; the first auto-publish goes to that brand.
9. [ ] On the website, remove this user from the picked brand: the app shows "Choose a brand", never moves to another
   brand by itself, and continues after one tap on the picker.
10. [ ] B on a free plan, A paid: right after switching to B, no paid feature works for B.
11. [ ] One brand only: no brand names in notifications or the countdown; the row still shows the name.
12. [ ] Airplane mode: the row shows the last known brand; the picker shows the offline message and Retry.
13. [ ] The recording screen looks and behaves exactly as before.
