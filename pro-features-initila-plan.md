# Pro Features: Initial Plan

Status: idea / strategy only. Nothing implemented.

## Goal

Sell Pro without Play Billing, using our own server (the same one as auto-upload) and third-party payment links.

Constraints:
- No forced registration or login.
- Free features work offline. App open never requires internet.
- If the subscription status cannot be determined (no internet, no token), treat the user as **Free**.
- Internet is required only at payment time, and for restore.

## Design

### 1. Anonymous device identity
- On first launch, create a random install ID and a keypair in the Android Keystore.
- Register the public key and install ID with the server (the auto-upload feature may already do this).

### 2. Payment
- App opens `checkout?device=<installId>` in the browser (third-party payment link).
- Checkout collects the email.
- Payment-provider webhook hits our server and the server stores the entitlement: `email -> plan, expiry, devices[]`, with the device bound.
- After payment, the app polls the server (request signed with the device key) or uses a deep link back, then receives its token.

### 3. Signed entitlement token (JWT-style)
- Contents: device ID, plan, `issued_at`, `expires_at`.
- Signed with an **asymmetric** key (ES256 / EdDSA). Never HS256, because a shared secret in the APK can be extracted.
- App embeds only the public key and verifies locally.
- Valid, unexpired token for this device = Pro. Anything else = Free.
- Token lifetime is the offline grace period (e.g. 14-30 days).
- Refresh silently in the background when online and past half the token life. Never block the UI on it.

### 4. Where each check lives

| Concern | Handled by |
|---|---|
| Server features (auto-upload, etc.) | Server checks entitlement (device ID / email) on every request. No token needed. |
| Client-only features (crop, bg remove) | Signed token, verified locally and offline. |
| Everything else | Free tier by default. |

### 5. Restore (new phone / reinstall)
Email is for **restore only**, not a login.

1. User taps **Restore purchase** and enters their email.
2. Server sends a one-time code (or magic link) to that email.
3. User enters the code. The server links the new device ID to the entitlement.
4. Server returns a fresh signed token.

Rules:
- Verify the email with a code. Never trust a typed email.
- Take the email from checkout, so the user is not asked twice.
- Cap devices per email (2-3) to stop sharing.
- A user who never syncs still works: the checkout link carries the device ID, so the entitlement binds to that device immediately.

### 6. Refunds / revocation
- Handled by short token expiry. The next refresh returns Free.

## What this does and does not protect

- **Stops:** proxy tampering and fake "paid" responses (signature check fails). Token replay on another device (device ID check).
- **Does not stop:** a patched APK that forces `isPro = true`. Client-side features (crop, bg remove) can be cracked. Accepted for now.
- **Cheap extras:** R8 obfuscation, certificate pinning (stops casual proxies, not Frida), device cap per purchase.
- **If abuse appears:** move only the expensive client feature (bg removal) to the server for Pro users.

## Risks / open items

- **Google Play policy:** unlocking digital features via an external payment link in a Play-distributed app violates Play's billing policy and can lead to removal. Exceptions exist (US external-links rulings, user-choice billing in some regions). Check current rules for target countries before shipping. Not an issue for direct APK or other stores.
- Choose the payment provider (must support webhooks and collect email).
- Decide token lifetime and device cap.
- Decide where the entitlement endpoint fits in the existing server (ArWrench-Api).
