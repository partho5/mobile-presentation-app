Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.
-----------------

# Zero-friction Auto Publish — mobile side (decided 2026-10-20)

Goal: a first-time user opens the drawer item, sees every supported platform, and simply follows the next step shown until auto-publish works. No Set, no web hunting, no second login, no debug info. Backend side: `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md` (API contract lives there; copy below).

## Decisions
- Drawer item label never changes: always `nav_connect_publishing` ("Auto upload to social media"). Signed out → sign-in; signed in → new **AutoPublishActivity** (not AccountActivity).
- AutoPublishActivity, top to bottom:
  1. **Next-step card**: nothing connected → "Connect your first platform" (tap = YouTube's action); connected but switch off → big switch "Auto publish my recordings"; ready → switch on + status "Posts to YouTube, TikTok · N credits each · M available"; plus the "Undo before publishing" choice (Off/30 s/60 s) when on.
  2. **Platform list** (server order): section "Video platforms", then "Other platforms". Row = Glide icon + name + state:
     - `connected` → green check; under it one row per channel (avatar, name) with a switch (include in auto-publish). If `autoPublishSupported:false` → grey "Manual posting only", no switch. `refreshNeeded` → small amber "Reconnect" (tap = connect action).
     - `needs_credentials` → small red "Not connected"; tap → `web-link target=credentials` → open in browser. If `canManageCredentials:false` → dialog "Ask your workspace owner to add <Platform> keys".
     - `ready_to_connect` → small red "Not connected"; tap → `web-link target=connect`.
  3. Footer row "Account · <email> ›" → AccountActivity.
  - If `autoPublish.mode=='preset'`: small grey line "Using your web preset “<name>”".
  - Refresh on `onResume` (user returns from browser). Loading / error-with-retry states. No URLs, IDs or debug text anywhere.
- Settings' auto-publish section collapses to one row that opens AutoPublishActivity (or sign-in).
- `AUTO_NOT_CONFIGURED` error + notification "Set up" action open AutoPublishActivity instead of the web sets page.
- AccountActivity: remove the debug "Backend URL" row and its dialog; remove the debug base-URL override in SessionManager (always `BuildConfig.API_BASE_URL`).

## API contract (from backend)
- `GET /public/v1/platforms` → `{ canManageCredentials, autoPublish:{mode:'automatic'|'preset', presetName?}, platforms:[{ identifier, name, icon, group:'video'|'other', status:'connected'|'needs_credentials'|'ready_to_connect', guideUrl, autoPublishSupported, channels:[{id,name,picture,disabled,refreshNeeded,autoPublish}] }] }`
- `PUT /public/v1/auto-publish/channels/:integrationId` `{enabled:boolean}` → 204
- `POST /public/v1/web-link` `{target:'credentials'|'connect', identifier}` → `{url}` (single use, 60 s — request it on tap, never cache)
- `GET /public/v1/auto-publish` unchanged shape (status line, cost, available).

## Parts
- [x] **M1** Routing + cleanup: fixed drawer label, signed-in → AutoPublishActivity shell (toolbar, footer account row, manifest); remove debug backend row + SessionManager override.
- [ ] **M2** Platforms list: `PublishApi.platforms()/setChannelAutoPublish()/webLink()` + model; RecyclerView with sections, states, taps, per-channel switches (optimistic, revert on failure), onResume refresh, loading/error.
- [ ] **M3** Next-step card + master switch + undo choice + status line; Settings section → one row; not-configured error/notification → AutoPublishActivity; copy updates.

## Progress (runner-maintained)
- M1 done 2026-10-20: drawer always "Auto upload to social media" → AutoPublishActivity (sign-in returns there); shell ids `card_next_step`, `rv_platforms`, `progress_auto_publish`, `auto_publish_error_box`/`tv_auto_publish_error`/`btn_auto_publish_retry`, `row_account`; debug backend URL row + SessionManager override removed; Publish screen shows "YouTube" not raw `youtube`.
