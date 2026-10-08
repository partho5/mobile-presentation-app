# Workspace, Credits & Devices (M3) — Plan & Progress Tracker

Builds on M1 (`AUTH_PLAN.md`) and M2 (`PUBLISH_PLAN.md`). Backend is read-only reference. This file covers **M3 only**.
Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## Status

| Step | Scope | Status |
|---|---|---|
| 1 | Contracts + backend routes checked, plan | ✅ Done |
| 2 | Core: workspace in TokenStore + `x-workspace-id` on every authed call, WORKSPACE_FORBIDDEN fallback, `account/` API + models + tests | ✅ Done |
| 3 | Room v4: `publish_jobs.workspace_id` (jobs stay in the workspace they were made in) | ✅ Done |
| 4 | UI: WorkspaceActivity, DevicesActivity, Account screen rows (workspace, credits, devices), debug backend URL | ✅ Done (device QA pending) |
| 5 | Build, tests (85 pass), report | ✅ Done |

## Contract / route facts used (exact)

- Workspace header: `x-workspace-id: <uuid>` on `/public/v1/*` and app routes; without it the server uses the user's first enabled workspace. Non-member -> 403 `WORKSPACE_FORBIDDEN` (api.mobile-access, tenancy.workspace-routing). `x-postiz-org` is refused for device tokens; never sent.
- Workspace list: `GET /user/organizations` (app route, Bearer accepted, api.token-auth) -> `[{id, name, users:[{role, disabled}], ...}]`, disabled ones are already filtered out. Current default: `GET /user/self` -> `orgId`, `role`. **Not in a frozen contract**; read straight from `users.controller.ts`. Only `id`, `name`, role, `orgId` are read.
- Devices (api.device-sessions): `GET /auth/sessions` -> `[{id, name, userAgent, ip, createdAt, lastSeenAt, current}]`; `DELETE /auth/sessions/:id` -> 204 / 404; `DELETE /auth/sessions?except=current` -> 204. Revoking the current one ends this phone's session (`logout: true` header on the server). The routes never use `x-workspace-id`.
- **Credit balance: no usable route exists.** `ux.balance-header` declares `GET /credits/balance` -> `{available, reserved, plan, allotment, low, canTopUp}` but the backend has not built it (no controller, no `CreditBalanceService`). The only live read is `GET /copilot/credits` -> `{credits}` (= available, current workspace's account). The client tries the contract route first (remembers a 404 for the process) and falls back to `/copilot/credits`, showing only "Available". No reserved/low/top-up is faked. Needs the backend to ship `/credits/balance`.

## Design

- `auth/TokenStore`: `workspaceId()`, `workspaceName()`, `saveWorkspace(id, name)`, `clearWorkspace()`; wiped by `clear()` (sign-out).
- `auth/AuthRepository`: `execute` / `executeUpload` add `x-workspace-id` from the store unless the caller sets one (publish jobs pin their own). A 403 `WORKSPACE_FORBIDDEN` for the stored workspace clears the selection (next call uses the server default) and the response is returned so the caller can tell the user. Also `endLocalSession()` for "this device was revoked".
- `account/AccountApi` (blocking, pure parse): `listWorkspaces`, `currentWorkspaceId`, `balance`, `listDevices`, `revokeDevice`, `revokeOtherDevices`. Failures are `account/AccountException(Kind)`: NETWORK, SIGNED_OUT, RATE_LIMITED, WORKSPACE_FORBIDDEN, NOT_FOUND, SERVER. Models: `Workspace`, `CreditBalance`, `DeviceSession`.
- `ui/ApiErrors`: one place that turns an `AccountException` / `AuthException` into a string, reusing the M1 `err_*` strings.
- Publish: `PublishJob.workspaceId` captured at enqueue, sent by `PublishApi` on upload + post; WORKSPACE_FORBIDDEN -> `PublishException.Kind.WORKSPACE_FORBIDDEN` (final, user message "no longer have access to that workspace"). Room v3 -> v4: `ALTER TABLE publish_jobs ADD COLUMN workspace_id TEXT` (no data loss).
- UI: `ui/WorkspaceActivity` (list, current marked, loading / empty / error+retry; picking saves and finishes). `ui/DevicesActivity` (list, "This device", per-row Sign out with confirm, "Sign out all other devices", empty/loading/error; revoking this device = sign-out). `ui/AccountActivity`: rows Workspace (name, tap), Credits (value or "unavailable"), Devices, then Sign out; debug builds only: Backend URL row (saved in plain prefs, applied live by `ApiClient.setBaseUrl`, signs out because tokens belong to the old backend).
- Never logged: tokens, codes. Device IP/user agent shown to the user only.

## Files

New: `account/{AccountApi,AccountException,Workspace,CreditBalance,DeviceSession}.java`, `ui/{WorkspaceActivity,DevicesActivity,ApiErrors}.java`, `res/layout/{activity_workspace,activity_devices,item_workspace,item_device}.xml`, `res/values/strings_account.xml`, tests under `app/src/test/.../account/`.
Edited: `auth/{TokenStore,AuthRepository,ApiClient,SessionManager}.java`, `db/{PublishJob,AppDatabase}.java`, `publish/{PublishApi,PublishException,PublishLabels,PublishManager}.java`, `ui/AccountActivity.java`, `res/layout/activity_account.xml`, `AndroidManifest.xml` (two activities; file also carries unrelated uncommitted work).

## Manual QA checklist

- [ ] Account shows Workspace name, Credits and Devices rows; Credits shows "Available N" (or "unavailable" offline / when the backend has no balance route).
- [ ] Two phones, same email: same workspace list, same default, same balance.
- [ ] Pick another workspace: Account updates; Publish > channels are that workspace's; kill/reopen keeps the choice.
- [ ] Remove the user from the workspace on the web (or tamper): next call falls back to the default workspace and says so; no crash.
- [ ] Publish queued in workspace A, switch to B before it runs: post still goes to A.
- [ ] Devices lists both phones, "This device" marked; revoke the other: it is signed out on its next request (forced sign-out screen).
- [ ] Sign out all other devices works; revoking this device returns to sign-in.
- [ ] Airplane mode on each screen: offline message + Retry, no crash. Empty states render.
- [ ] Debug build: Backend URL row visible, changing it signs out; release build: row absent, http URL still refused.
- [ ] DB upgrade from v3 keeps slides and publish history.
