# Account & Session (M1) — Plan & Progress Tracker

Client of the Social-Media-Manager backend (read-only reference). Contracts:
`_planning/02_contracts/api.email-otp.md`, `api.token-auth.md`, `api.mobile-access.md`, `api.device-sessions.md`.
Later modules: M2 publish recordings, M3 workspace/credits/devices. This file covers **M1 only**.
Code paths are relative to `app/src/main/java/com/jovoc/facecampresentationrecorder/`.

## Status

| Step | Scope | Status |
|---|---|---|
| 1 | Deps, BuildConfig `API_BASE_URL`, plan | ✅ Done |
| 2 | `auth/` core: ApiClient, TokenStore, AuthRepository, SessionManager + unit tests (34 pass) | ✅ Done |
| 3 | UI: StartupActivity gate, SignInActivity, AccountActivity, drawer entry | ✅ Done (device QA pending) |
| 4 | Build debug APK, tests, report | ✅ Done |

## Contract facts used (exact)

- `POST /auth/otp/request {email}` -> 204 (same for known/new address); 400 bad email, 429 `AUTH_RATE_LIMITED` + `Retry-After`, 503 `MAIL_DELIVERY_FAILED`.
- `POST /auth/token {grant_type:"email_otp", email, code(6 digits), device_name?(<=100)}` -> 200 `{access_token, token_type:"Bearer", expires_in, refresh_token, session_id}`; header `onboarding: true` when an account was created. 401 `INVALID_GRANT` is the one answer for wrong/expired/used/dead code; 429 `AUTH_RATE_LIMITED`.
- `POST /auth/token {grant_type:"refresh_token", refresh_token}` -> new pair (rotation; same pair again within 60 s grace). 401 `INVALID_GRANT` = session over.
- `POST /auth/token/revoke {refresh_token}` -> 204 always.
- `/public/v1/*` (M2): `Authorization: Bearer <access>` + `x-workspace-id`; 401 `TOKEN_EXPIRED` -> refresh once + retry once; 401 `SESSION_REVOKED` -> forced sign-out; 403 `WORKSPACE_FORBIDDEN` (M3).
- Error body is `{statusCode, code}`. Access TTL 900 s.

## Design

- `auth/ApiClient` – OkHttp wrapper, JSON in/out, `ApiResponse` (code, body, errorCode, Retry-After); IOException = offline/timeout. Release: TLS only (`ConnectionSpec.MODERN_TLS`, https URL required); debug also allows cleartext (LAN backend).
- `auth/TokenStore` – tokens + email + device name behind a small `KeyValueStore`; Android impl = `EncryptedSharedPreferences` (corrupt/undecryptable store is wiped = signed out). Excluded from backup.
- `auth/AuthRepository` – `requestCode`, `exchangeCode`, `refresh` (single in-flight, stale-access check), `signOut` (best-effort revoke, always clears), `execute()` authed call with proactive refresh, one refresh+retry on `TOKEN_EXPIRED` (same headers), forced sign-out on `SESSION_REVOKED`. Errors as `AuthException(Kind)`.
- `auth/SessionManager` – app singleton (store, repo, executor, main-thread handler, signed-out listener). On forced sign-out it opens sign-in with a cleared task (no Application subclass needed).
- `ui/StartupActivity` (new LAUNCHER, no UI) -> MainActivity if a session exists, else SignInActivity. Startup never touches the network, so offline features keep working.
- `ui/SignInActivity` – email step, code step (one field, digits only, paste-friendly, auto-submit at 6), 30 s resend cooldown (or `Retry-After`), states: wrong/expired code, 429, offline, mail failure.
- `ui/AccountActivity` – email, device name, Sign out (confirm). Drawer item "Account" in `MainActivity` (one-line hook).
- Base URL: `BuildConfig.API_BASE_URL` per build type in `app/build.gradle`; release value is a TODO placeholder until production is decided.
- Not logged anywhere: tokens, codes. Email only in UI.

## Files

New: `auth/{ApiClient,ApiResponse,AuthException,AuthRepository,KeyValueStore,TokenStore,SecureKeyValueStore,SessionManager}.java`, `ui/{StartupActivity,SignInActivity,AccountActivity}.java`, `res/layout/{activity_sign_in,activity_account}.xml`, `res/values/strings_auth.xml`, `res/xml/data_extraction_rules.xml`, tests under `app/src/test/java/.../auth/`.
Edited: `app/build.gradle`, `AndroidManifest.xml`, `MainActivity.java` (these three also carry unrelated uncommitted work; only M1 hunks were committed), `res/menu/drawer_menu.xml`. MainActivity is no longer exported/launcher; StartupActivity is.

## Manual QA checklist

- [ ] Fresh install opens to the sign-in screen; no network calls before the user taps Continue.
- [ ] New email: code arrives, entering it signs in and opens MainActivity. Existing email: same flow.
- [ ] Paste "123 456" or "123456" into the code field: digits only, submits at 6.
- [ ] Wrong code shows the wrong/expired message and keeps the field; resend works after the cooldown.
- [ ] 6+ code requests / many wrong codes: rate-limit message with a wait time, buttons disabled until it passes.
- [ ] Airplane mode on sign-in: offline message, no crash, retry works after reconnect.
- [ ] Kill and reopen: still signed in (also offline). Slides, recording, drawing board unchanged.
- [ ] Account screen shows email + device name; Sign out returns to sign-in; back button does not return to Main.
- [ ] Revoke the session from the web: next authed call forces sign-out (M2 exercises this; unit-tested in M1).
- [ ] Release build refuses an `http://` base URL.
