# Activity log — what the app collects (for the privacy policy and the Play Data safety form)

Written 2026-10-20 from the code (`activitylog` package and the `ActivityLog.log(...)` calls).
Backend: `social.nanybot.com/api` `POST /activity/batches`. Plan and event list: `to-build/tasks.md`.

## What is collected
- **In-app actions**, each with time, the phone's time-zone offset and a random visit id: app opened /
  moved to the foreground or background, screens opened, buttons and menu items tapped, switches and
  options changed in Settings, permission prompts and whether each was allowed, recording started /
  stopped (with how long it ran and how it was stopped, and the saved size in MB), topics added /
  edited / deleted (type only), background replacement started / done / cancelled, videos played /
  renamed / shared / deleted (counts only), publish taps and results (platform and error type), sign-in
  steps, changes of the subscription plan (the plan name before and after, e.g. free to pro), the
  upgrade sheet being shown (which feature, which plan the user is on) and which of its buttons was
  tapped (upgrade / sign in / refresh / close), the plans list being opened, and crashes (the exception type and the code location, never its message).
- **A device id**: a UUID made from the phone's MediaDrm (Widevine) id with SHA-256 and an app-specific
  salt, or a random UUID where MediaDrm is unavailable. The raw hardware id is never stored or sent.
  It is not the advertising id and is not shared with anyone else.
- **Device info** with every upload: Android version and SDK, manufacturer and model, app version and build.
- **The account**, when the user is signed in: the upload carries the sign-in token, so the backend links
  the earlier anonymous events of this phone to the account.

## What is never collected
Email address, tokens, passwords, file names, topic or caption text, drawings, channel or account names,
URLs with their path or query (only the website host of a link the app opens), contacts, location,
other apps, screen or camera content.

## How it is sent and kept
- Stored on the phone first (up to 2000 events, oldest dropped), sent in batches of up to 100 over HTTPS,
  **only on Wi-Fi / an unmetered network**, also when the app is closed: when 10 events are waiting
  (at most every 15 minutes), or 3 days after the last upload whatever is waiting.
- Backend keeps a batch for 90 days; deleting the account deletes its rows.
- Not removable from the app: there is no opt-out switch (decision of the owner, 2026-10-20).

## Play Data safety form (suggested answers)
- App activity -> "App interactions" and "In-app search history: no", collected, linked to the user when
  signed in, purpose: analytics / app functionality, not shared.
- App info and performance -> "Crash logs", "Diagnostics" (the same events), collected, not shared.
- Device or other IDs -> collected (the hashed MediaDrm id), purpose: analytics, not shared.
- Data is encrypted in transit (HTTPS). Deletion: on account deletion.
