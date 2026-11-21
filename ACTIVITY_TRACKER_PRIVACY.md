# Activity tracker: what the app collects (for the privacy policy and the Play Data safety form)

Written 2026-11-21 from the code (`app/src/main/java/com/jovoc/facecampresentationrecorder/tracker/` and the
`ActivityTracker.log` / `start` / `error` calls).
Backend: `https://social.nanybot.com/api`, `POST /tracker/batches`. Plan and event list: `to-build/tasks.md`.

## What is collected
- **In-app actions**, each with its start (and end, for spans) time, the phone's time-zone offset, a session id
  that changes after 30 minutes without use, the app build and the current screen (the screen's class name):
  - app opened (cold start), moved to the background or the foreground, and where the open came from
    (launcher, a notification, recents, other);
  - screens opened and how long each was on screen;
  - buttons and menu items tapped, and switches toggled (on or off);
  - settings changed: the setting's name and its new value (for example on or off, or a chosen option);
  - permission prompts, rationale screens, and whether each permission was allowed;
  - recording started, stopped and saved: how long it ran, how it was stopped, and the saved size in MB;
  - topics added, edited or deleted (type only, never the text), and topic moves;
  - background replacement started, finished, cancelled or failed;
  - videos played, renamed, shared or deleted, and the menu items chosen (counts and types only);
  - publish taps and results (channels, mode, and the error type on failure);
  - sign-in steps (for example the code was requested, sign-in succeeded or failed, and the failure kind), and sign-out;
  - plan changes (the plan name before and after, for example free to pro), the upgrade sheet being shown
    (which feature, which plan) and its buttons tapped, and the plans list being opened;
  - AI captions: switches turned on or off, and how a caption step ended (its state, how long the upload waited,
    the publish mode). Never the caption or transcript text;
  - the "text post" channel switch turned on or off;
  - web links opened: only the website host, never the path or query (but see error messages below).
- **Errors and crashes**: the error's type, its message, its stack trace (with the code locations), up to 5 nested
  causes, the thread name, the last action before the error, and whether it was fatal. **Messages and stack traces
  can contain file names, web addresses (with path and query, for example when no app can open a link)** and other
  text that the system or a library puts into an error message.
- **Why the app last closed**, as reported by Android since the last check: for example an unresponsive app (ANR),
  a crash, a native crash, low memory, or the user or system stopping it. Stored with the reason, a short system
  description of the exit, and the app's memory use at that time. For an ANR the system's thread dump (its first
  8000 characters) is stored too. The first run after install stores no old history.
- **A device id**: a UUID made from a SHA-256 of the phone's MediaDrm (Widevine) unique id, with an app-specific
  salt. Where MediaDrm is unavailable, a random UUID is used instead. The raw hardware id is never stored or sent.
  It is not the advertising id and is not shared with anyone else.
- **Device and app info** with every upload: Android version and SDK level, manufacturer, model, language and
  region setting, app package name, app version and build number.

## Linked to the account
- When the user is signed in, the upload carries the sign-in token. The server then links the phone's records
  (and the earlier records of the same phone, sent before sign-in) to that account.
- The server also stores the account's email address with the phone's device record. The app itself never puts
  the email in a record.

## Never collected
Email address in the records (the server adds the account email to the device record, see above), passwords,
sign-in tokens or codes, topic text, caption or transcript text, drawings, file names or file contents (except
inside error messages, see above), channel or account names, contacts, location, other apps, and screen or camera
content. Web addresses are reduced to the website host, except inside error messages (see above).

## How it is sent and kept
- **On the phone:** records are stored in the app's private database first, up to 3000 records. When the buffer is
  full, the oldest 100 are dropped and one `tracker_overflow` record notes how many were dropped.
- **When it is sent:** every 5 days, or sooner when more than 20 records are waiting (at most once every 15 minutes
  for this rule). The check runs about once a day in the background, and a waiting upload can run while the app is
  closed.
- **Which network:** any network that Android has confirmed reaches the internet, including mobile data. It is
  not limited to Wi-Fi.
- **How:** records are sent in batches of up to 50, as gzip-compressed JSON over HTTPS. Release builds do not permit
  cleartext HTTP (the debug build allows it for a local test backend).
- **Retries:** if a send fails because of the network or a server error, the records are kept and sent later.
  A batch the server rejects as invalid is deleted from the phone.
- **Server:** records are kept for 90 days (by the time they happened). The newest 300 records of each device are
  kept longer, so the last steps of a user who left stay visible. Deleting the account deletes its records.
- **Backup and transfer:** the device id is excluded from cloud backup and device-to-device transfer, so a restored
  or transferred copy never gives two phones the same id.
- **No opt-out:** there is no switch in the app to turn tracking off (decision of the owner, 2026-10-20).

## Play Data safety form (suggested answers)
- **App activity** → "App interactions": collected, linked to the user when signed in, purpose: analytics, not shared.
  "In-app search history": no.
- **App info and performance** → "Crash logs" and "Diagnostics" (the errors, crashes and exit reasons above):
  collected, linked to the user when signed in, purpose: analytics and app functionality, not shared.
- **Device or other IDs** → the hashed MediaDrm device id: collected, linked to the user when signed in, purpose:
  analytics, not shared.
- **Collection is required**: the app has no opt-out.
- **Data is encrypted in transit** (HTTPS).
- **Deletion:** on account deletion the account's records are deleted. Other records follow the 90-day rule above
  (the newest 300 per device are kept longer).
