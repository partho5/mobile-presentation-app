# Onboarding — design plan

The words live in [onboarding.md](onboarding.md). This file covers how the 3 screens look, move and connect.

This screen decides whether a new user stays. So the plan has one test for every choice: does it remove effort, or add trust? If neither, it is left out.

## Progress

- [x] Part 1 — Shell and flow
- [ ] Part 2 — The three screens
- [ ] Part 3 — Story screen and re-entry (blocked, see open decision)
- [ ] Part 4 — Measure and test with real people

## Decided

- **App name:** "Talk Branding", from `@string/app_name`. "(App Name)" in the copy means this string. Never type the name by hand in a layout.
- **No "Give me 10 minutes" in onboarding.** Screen 3 ends with two buttons: "Start recording video" and "Proven strategy of brand building". The story starts only when the user chooses it.
- **Proof on screen 3:** Protim's real photo and GitHub link.
  - Photo: `res/drawable-nodpi/founder_photo.webp`, 384×384, 26 KB, transparent background, head and shoulders already cropped for the circle. Put a background color behind it in the circle (`onb_bg_top` or a little lighter). Source: `~/Dropbox/partho images/partho savar basa-transparent-2.png`.
  - GitHub: https://github.com/partho5/
- **Screen 2:** cut to 3 rows.
- **Gradient:** quiet, never flashy. Rules are in Colors and gradient.

## Still open

- **The story's format and length** (video, or swipe cards). Only Part 3 waits for this. Parts 1 and 2 can be built now.

## Flow

```
Launch
  └─ first time? ── no ──► MainActivity
        │ yes
        ▼
   Screen 1 ──► Screen 2 ──► Screen 3 ──┬─ Start recording video ──────────► MainActivity
      │            │                    └─ Proven strategy of brand building ► Story ► MainActivity
      └── Skip ────┴───────────────────────────────────────────────────────► MainActivity
```

- Shown once. Finishing or skipping both mark it as seen.
- People who already use the app do not see it: on the first run of this version, if the app was updated (not freshly installed), mark it as seen.
- No sign-in, no permission prompts, no text fields. Sign-in stays optional as it is today, and permissions are asked later, when the user needs them.
- Next: tap the button or swipe left. Back: system back or swipe right. Back on screen 1 closes the app.
- "Proven strategy of brand building" is also in the side menu, so a user who starts recording first can still find the story.
- Skip shows on screens 1 and 2 only. On screen 3 "Start recording video" does the same job, so Skip is hidden there: two choices, not three.

## One skeleton for all three screens

Same positions on every screen, so the user learns the layout once.

```
┌──────────────────────────────┐
│ ▬▬▬ ▬▬▬ ▬▬▬            Skip  │  progress (3 bars) + Skip
│                              │
│          [ visual ]          │  top 40%: one picture
│                              │
│  Title                       │  the question, 28sp bold
│                              │
│  Body, one idea per line     │  17sp
│                              │
│      text above button       │  14sp, muted
│  ┌────────────────────────┐  │
│  │         Button         │  │  56dp tall, bottom, thumb zone
│  └────────────────────────┘  │
└──────────────────────────────┘
```

- The button text of one screen is the title of the next. The user asks the question by tapping, and the next screen answers it. The wording must be identical on both sides.
- Side margins 24dp (same as sign-in). Content width capped at 480dp on tablets and landscape.
- Everything fits without scrolling on a 360×640dp phone. If the user has large font size, only the body scrolls; the buttons stay fixed.

## Colors and gradient

The accent, the amber and the white already exist in the app, so onboarding and the app look like one product.

| Role | Color | Contrast, worst case |
|---|---|---|
| Background top | `#0B0D1A` | — |
| Background bottom | `#07080D` | — |
| Title | `#FFFFFF` | 17.1:1 |
| Body | `#C9CDD6` | 10.7:1 |
| Muted (Skip, text above button) | `#8A8F9C` | 5.3:1 |
| Accent (icons, progress, outlines) | `#22D3EE` | 9.5:1 |
| Highlight (one number per screen) | `#FBBF24` | 10.2:1 |
| Main button | solid `#22D3EE` | — |
| Main button text | `#04141A` | 10.4:1 |

Worst case means measured on the brightest point of the glow.

**The gradient is felt, not seen.** What makes a gradient look cheap is many colors, strong saturation, hard edges and movement. So:

- One hue family only. Dark navy `#0B0D1A` at the top fades to near-black `#07080D` at the bottom. No purple, no pink, no second color.
- One glow: cyan `#22D3EE` at 8% opacity in the center, fading to 0% with no visible edge, about 80% of the screen width. At its brightest it only lifts the background to `#0D1D2B`.
- The glow sits behind the visual and slides to its new place when the user swipes. It never pulses and never changes color.
- No gradient on buttons, text or icons. No neon shadows or outer glow on anything.
- Dark gradients show ugly stripes (banding) on many phones. Add a fine noise texture over the background at 2–3% opacity to hide them. Check on a real low-end phone, not only the emulator.
- Amber is used once per screen, only on the number that matters: "30+" on screen 2, "5 minutes" on screen 3.
- No red, no pure-white panels.

If in doubt, lower the opacity. A user should not be able to say what the background is, only that it looks calm and expensive.

## Text

- Title: system font, bold, 28sp, white.
- Body: system font, 17sp, line height 26sp, one idea per line, left aligned.
- One emotional line may use Bitter italic (already in the app): "Sounds unbelievable?" on screen 3.
- Button: 17sp bold. Text above button: 14sp muted, centered.

## The three screens

**Screen 1 — What does this app do?**
- Visual: a face in a circle (same shape as the launcher icon) with two thin cyan rings slowly growing outward and fading. Meaning: your face becoming known.
- Body: the one sentence, with "Talk Branding" in white and the rest in body color.
- Small line above the button: "No account needed to start." It is true today and removes the fear of a sign-up wall.

**Screen 2 — How does it do?**
- Visual: one video card on the left, thin lines fanning out to a row of platform icons, with "30+" in amber.
- Body: the 3 paragraphs from [onboarding.md](onboarding.md), one row each, with a small cyan icon on the left. Rows appear one after another, 80ms apart.
  1. `ic_share` — the "30+ social platforms" paragraph
  2. `ic_ai_sparkle` — the "Our AI" paragraph
  3. `ic_edit` — the "basic video editing" paragraph
- The two cut paragraphs are kept at the bottom of [onboarding.md](onboarding.md) for the story or the Play Store listing.

**Screen 3 — What do I have to do?**
- Top: "5 minutes" very large in amber, "every day" under it. This replaces the visual.
- Middle: "Sounds unbelievable?" in Bitter italic.
- Founder card: real photo in a 72dp circle, "Protim", "Software engineer, AI specialisation", the "10+ years" line, and the GitHub link with a small icon. The link opens in the browser; onboarding stays open behind it.
- Two buttons, stacked, same width and height:
  - "Proven strategy of brand building" — outlined in cyan, cyan text, placed above.
  - "Start recording video" — solid cyan, in the same place as the button on screens 1 and 2.
- Recording is the main button because the first recording is where the user gets value. Swap the two styles if the story should lead.

## Motion

- Page change: 280ms slide; content fades in and rises 12dp.
- Rings on screen 1: one slow loop, 2.4s. Nothing else loops.
- Buttons: no bounce, no shimmer. Standard press ripple only.
- If the user turned animations off in system settings, show everything at once with no motion.

## Trust checklist

- [ ] App name comes from `@string/app_name` everywhere
- [x] Skip visible on screens 1 and 2, same place
- [x] Progress bars show it is only 3 steps
- [ ] No sign-in, no permission, no payment asked
- [ ] Real photo, real name and working GitHub link on screen 3
- [ ] Real platform icons on screen 2
- [ ] Nothing fake: no invented reviews, user counts or countdowns

## Friction checklist

- [x] 3 taps to reach the app, 1 tap to skip
- [ ] No scrolling at default font size on a 360×640dp phone
- [x] Main button in the same place and same size on all screens
- [ ] All tap targets 48dp or larger
- [ ] Works with TalkBack: titles marked as headings, visuals have descriptions
- [x] Draws correctly behind the status bar and navigation bar (the theme makes both transparent)

## How we know it works

- **Before polishing:** show the three screens to 5 people who never saw the app. Say nothing. Then ask: "What does this app do?" If they cannot answer in one sentence, screen 1 failed. Fix the words first, the visuals second.
- **After release:** count how many people reach each screen, tap Skip, and choose each button on screen 3. The app has no analytics today, so this needs either one small event call to the server or an analytics library. Without it we are guessing.

## Build parts

One part per session. Start a session with "build part N".

**Part 1 — Shell and flow**
- `OnboardingActivity` with `ViewPager2` (already a dependency), 3 pages.
- `StartupActivity` routes to onboarding when not seen; otherwise to `MainActivity` as today.
- Seen flag in SharedPreferences; the update-versus-fresh-install rule from Flow.
- Skip (hidden on screen 3), progress bars, back behavior, window insets.
- Background: gradient, glow that moves with the swipe, noise texture.
- `values/colors_onboarding.xml` and `values/strings_onboarding.xml` (the app has no `colors.xml`; strings are already split per feature).

Built. What Part 2 needs to know:
- `OnboardingActivity` owns the shell: progress, Skip, pager and the main button. The main button sits outside the pager, so it never moves; its text is the next screen's title (`TITLES`), or `onb_start_recording` on screen 3.
- Each screen is `item_onboarding_page.xml`, the shared skeleton: `onb_visual` (top 40%), `onb_title`, `onb_body` (scrolls), `onb_above_button` (hidden while empty). Part 1 fills the title only. The outlined second button of screen 3 goes at the bottom of that page, right above the main button.
- Every way out must go through `finishOnboarding()`, which marks onboarding as seen.
- `OnboardingBackgroundView` draws gradient, glow and noise. The glow position per screen is `GLOW_CENTERS` in the activity; move it once the visuals exist.
- `OnboardingGate` holds the seen flag (`key_onboarding_seen` in `app_prefs`) and the update rule (install time differs from update time).
- To see onboarding on a phone that already has the app: uninstall first. Installing over it counts as an update and skips it; clearing app data does not help.
- Button taps use the pager's default slide. The 280ms slide and the content fade and rise are still to do, under Motion.
- Not checked yet: banding on a real low-end phone.

**Part 2 — The three screens** (photo and GitHub URL are ready, see Decided)
- Page layouts on the shared skeleton, copy from [onboarding.md](onboarding.md).
- Visuals for screens 1 and 2, founder card and two buttons for screen 3.
- "Start recording video" opens `MainActivity`. "Proven strategy of brand building" opens the story; until Part 3 exists it opens `MainActivity` too.
- Motion, the animations-off case, large font size, TalkBack.

**Part 3 — Story screen and re-entry** (blocked on the story's format)
- The story itself.
- "Proven strategy of brand building" entry in the side menu.

**Part 4 — Measure and test**
- Per-screen counts (needs the server event or a library).
- The 5-person test, then fix what it shows.
