# Production "Connect publishing" flow — Plan & Progress Tracker

Builds on M1-M3 (AUTH_PLAN, PUBLISH_PLAN, WORKSPACE_PLAN). Source prompt: `PROD_AUTH_CLIENT_PROMPT.md`.
"Signed in" only means this device holds a session; there is no data sync. Offline features never need it.
Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## Status

| Step | Scope | Status |
|---|---|---|
| 1 | A: no launch gate, entry points, return-to-origin, signed-out handling | ✅ Done |
| 2 | B: sign-in polish (SignInFlow state machine, messages, a11y, product name) | ✅ Done |
| 3 | C: release config (HTTPS URL TODO, cleartext debug-only, network security config, R8, backup) | ✅ Done |
| 4 | D: Delete account UI + call (endpoint stubbed), privacy/terms links, Publish "no channels" web button | ✅ Done |
| 5 | E: tests (102 pass), debug + release builds, QA checklist | ✅ Done; device QA left to the user |

## Decisions

- `StartupActivity` goes straight to `MainActivity`. Sign-in is optional.
- `SignInActivity` takes an optional `EXTRA_NEXT` intent. After success it starts that intent (e.g.
  `PublishActivity` for the recording the user tapped), then finishes, so the user lands where they came from.
  No next intent: it just finishes and returns to the previous screen. Task is never cleared.
- `SessionManager.openSignIn(ctx, next)` replaces the old clear-task `openSignIn(ctx)`. Screens that need a
  session (Publish, Workspace, Devices, Account) call it with their own intent, then `finish()`.
- Session ended without the user asking (revoked / refresh refused): toast "You were signed out" and
  `MainActivity` in a cleared task. No sign-in wall.
- Sign out and Delete account end at `MainActivity` (cleared task), not the sign-in screen.
- Drawer item `nav_account`: "Connect publishing" when signed out (opens sign-in), "Account" when signed in.
  Title refreshed in `onResume`.
- `auth/SignInFlow` (pure Java): step, email, resend cooldown, per-action lockouts, error mapping to a
  `Problem` enum, save/restore as remaining-millis (rotation-safe). Activity maps `Problem` to string ids.
- Delete account: `AccountApi.deleteAccount()` = `POST /user/delete-account` (the backend's existing route, Bearer, no
  workspace). 400 = refused (teammates share an owned workspace, or billing could not be cancelled): message, user stays
  signed in. 404/405/501 = "not available". Success: local session cleared, back to home. The backend does not yet
  revoke the account's other sessions in the list, but a deleted user's tokens stop working (403, then refresh 401).
- URLs: debug API `http://localhost:3000` (needs `adb reverse tcp:3000 tcp:3000`); release web `https://social-media-schedular.nanybot.com`, release API the same host under `/api`
  (the deployed backend serves its API at `<host>/api`). Channels page = frontend route `/launches` (exists in
  the backend repo). Privacy/terms: no page exists, `PRIVACY_URL`/`TERMS_URL` are empty (TODO) and the
  Account links hide until set.
- Product name "Nany Social" in `strings_auth.xml` (`product_name`), not the launcher label.

## Files

New: `auth/SignInFlow.java`, `ui/WebLinks.java`, `res/xml/network_security_config.xml`,
`src/debug/res/xml/network_security_config.xml`, tests `auth/SignInFlowTest`, `account/DeleteAccountTest`.
Edited: `StartupActivity`, `SignInActivity`, `SessionManager`, `AccountActivity`, `DevicesActivity`,
`WorkspaceActivity`, `PublishActivity`, `RecordingsAdapter`, `MainActivity` (drawer hooks only),
`AccountApi/AccountException`, `AndroidManifest.xml`, `app/build.gradle`, `proguard-rules.pro`, layouts
`activity_sign_in/account/publish`, strings. The working tree also holds unrelated bg-replace work: stage by path.

## Manual QA checklist

- [ ] Fresh install opens straight to the main screen; no sign-in; slides, recording, drawing board work offline.
- [ ] Drawer shows "Connect publishing"; tap opens email step; Back returns to main.
- [ ] Saved Recordings > Publish while signed out opens sign-in; after the code, Publish opens for that recording.
- [ ] Email is trimmed/lowercased; bad email shows an error; keyboard is the email one; Done sends.
- [ ] Paste "123 456": digits only, auto-submits at 6; button disabled while the request runs.
- [ ] Wrong/expired code, 429 (wait time shown, buttons locked), offline, 5xx: each own message; retry works.
- [ ] Resend disabled for 30 s; rotate the phone mid-cooldown and mid-typing: email, step and countdown kept.
- [ ] Signed in: drawer shows "Account"; Account shows email, device, Sign out, Delete account (Privacy and Terms only once their URLs are set).
- [ ] Signed in with no channels: Publish explains channels are connected on the website; Open website + Refresh.
- [ ] Sign out lands on main (not sign-in). Revoke from web: next call toasts and lands on main.
- [ ] Delete account: confirm dialog; with backend missing shows "not available yet" and stays signed in.
- [ ] Dark theme and a 360x640 screen: nothing clipped, error text readable.
- [ ] Release build: https only, no cleartext, tokens not in backup, no token/code in logcat.

## After the backend is on the real VPS

Nothing here ran against a live backend; the debug build points at `localhost`. Once deployed:
1. Backend repo: `node deploy/scripts/e2e-auth.mjs https://social-media-schedular.nanybot.com/api <your email>` must pass (needs real Mailgun).
2. Install the release build; run the manual QA checklist above on a real phone.
3. Delete account: test with a throwaway email first; with a teammate in the workspace it must say it was refused.
