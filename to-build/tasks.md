<!-- runner-config
REPOS=/home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder /home/haku/projects/Next.js/Social-Media-Manager
BRANCH=feat/ai-video-edit
SCRATCH=test_audio.wav test_video.mp4 run.log
-->

RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 5 tests in `EngineContractTest` /
`SoundTrackTest` fail on a clean HEAD; they are not yours.

One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

# FaceCam app: AI edit text styles and animations, phone side (cards C1-C10)

The server half is in `/home/haku/projects/Next.js/Social-Media-Manager/to-build/tasks.md` (cards S0-S9). Design input:
`/home/haku/projects/Next.js/Social-Media-Manager/_planning/00_inputs/ai-edit-text-styles.md` (written before the decisions
below, which win where they differ); the three reference sheets (images) are in
`/home/haku/projects/Next.js/Social-Media-Manager/_planning/00_inputs/ai-edit-text-styles/`.

**OWNER GATE:** `[ ]` ENGINE GO, in the server `tasks.md`. Every card waits until the owner ticks it there. Cards that need a
server card name it in **Depends on**; do not start a card whose server card is not ticked.

Earlier cards A1 (real server status text, commit `d820d7b`) and L6 (render receipt, commit `92006f5`) are done; read
`git log --oneline -- to-build/tasks.md` for them. This file replaces them.

## Owner decisions (2026-11-19)
- **Two vertical slices.** Slice 1 = motion and fit (no change to the look format); slice 2 = looks.
- **Any language in the world.** Nothing is specific to one language or script. No bundled font: system font families
  (their fallback covers every script). Line breaks, graphemes, case and script behaviour come from Unicode data
  (`java.text.BreakIterator`, `Character.UnicodeScript`, `Bidi`, `toUpperCase(Locale)`), never a per-language `if`.
  Right-to-left text is in this round.
- **Glass blur is out of this round.**

## Shared context

### Goal
Text overlays that look edited, not plain: many **animations** (entry, hold, exit, from any direction) and **looks** (box,
border, gradient, shadows, fonts), chosen by the server's planner and **drawn completely by the phone for any text length in
any script**. The phone measures and draws what the plan says; it never chooses a look or animation.

### Requirements from the owner (non-negotiable)
1. A well edited video does not drop plain text on a background. Text appears with motion (typewriter at high speed, fade
   in and out, slide in from left, right, top or bottom, and more) and is drawn in a deliberate style.
2. Every style property below must be drawn. A missing shadow, stroke or left-to-right gradient is a defect, not a gap.
3. A different character count (1 grapheme up to the longest allowed text, any script, either reading direction) must never
   break the layout.
4. Every micro decision has one owner function; nothing is left to "whoever draws it" by accident.
5. No bug may ship silently: gates block release and the phone fails closed.

### App facts
- App (Android, Java, minSdk 31), branch `feat/ai-video-edit`. Java paths are relative to
  `app/src/main/java/com/jovoc/facecampresentationrecorder/`. Tests: `app/src/test/java/...` (JUnit 4, `org.json`; **no
  Robolectric, no androidTest**, so JVM tests cannot use `Bitmap`, `Canvas`, `Paint` or `android.icu`; pixel checks are the
  owner's on a device, cards C4 and C9).
- Server plans (`aiedit/flow/AiEditRunner`, `AiEditApi`, `AiEditRequestBuilder`); the phone renders (`aiedit/engine/*`).
- Text code today: `aiedit/engine/text/{TextLookRenderer (StaticLayout, at most 2 lines, shrinks by 0.92 down to 0.5 of the
  size, then cuts with an ellipsis; `Typeface.DEFAULT_BOLD`), TextPlacement, TextAnimator (knows only `pop`, `stamp`,
  `slide_up`, `word_by_word`, `sweep`; anything else silently becomes `pop`), TextOverlayPainter (tilt from a hash of the
  item id; reads `params.tilt_deg` but keeps only its size and takes the sign from the hash), PaletteResolver}`;
  `aiedit/engine/contract/{EngineContract (parses text_styles; a downloaded contract that fails to parse falls back to the
  bundled one), TextLook (throws on a gradient other than `vertical`), Capabilities (hard-coded ids)}`;
  `aiedit/engine/{RenderJob, RenderNotes, Engine}`; bundled contract `app/src/main/assets/aiedit/contract.json`; tests
  `app/src/test/.../aiedit/engine/text/{PaletteResolverTest, TextPlacementTest, TextAnimatorTest}`, `aiedit/TestContract.java`.
- **Old phones stay safe because the server only adds fields** (server "Compatibility facts"). This app's new reader reads
  the new fields; it must still accept the old ones.
- Commit per card: `feat(ai-edit): <card id> <what>` (or `fix(ai-edit): ...`), ending with the `Co-Authored-By` line the
  session gives. Never commit `test_audio.wav`, `test_video.mp4`, `run.log`.
- Rules: engine code in `aiedit/engine/` imports only `aiedit/engine/*`, the Android SDK and `java.*`; flow glue in
  `aiedit/flow/`; no new Gradle module. The master recording is never modified. **Never log transcript words, plan texts or
  event log contents** (`ACTIVITY_LOG_PRIVACY.md`). Only video/* files may be created under DCIM (names end in `.mp4`);
  other temp files go to `filesDir` / `cacheDir`. **Do not touch the subtitle code** (`subtitle/*`,
  `aiedit/flow/SubtitleCaptionPainter`).
- The six shipped looks (`sticker`, `label_pill`, `3d`, `glow`, `gradient`, `highlighter`) must draw exactly as before.

### Animations (independent of look)
| Group | Animations |
|---|---|
| Entry | `fade`, `pop` (scale overshoot), `slide_left`, `slide_right`, `slide_top`, `slide_bottom`, `typewriter` (about 30 ms a step, capped at about 450 ms), `word_by_word`, `wipe` (reveal in reading direction), `drop_bounce`, `blur_in`, `flip`, `stamp`, `glitch` |
| During the hold | `float`, `pulse`, `shake`, `shimmer` (light sweep), `neon_flicker` |
| Exit | `fade`, `slide_out`, `shrink`, `pop_out`, `typewriter_delete`, `wipe_out` |

The server picks the animation (`params.entry`, `params.hold`, `params.exit`, `exit_dir`, `exit_ms`; server S3) and the slide
direction (from the zone). The phone owns the reveal direction (the text's bidi direction: `typewriter`, `wipe`,
`word_by_word` and their exits run right to left for right-to-left text) and the typewriter step (grapheme, or word for
scripts whose letters join or reorder, from one `Character.UnicodeScript` table, so a half-drawn conjunct or a broken Arabic
join is never shown). Entry is fast (120 to 300 ms).

### Looks
Axes: container (pill, rounded rectangle, sharp rectangle, tilted sticker, none); fill (solid, gradient, dark); border (thin,
thick, double ring, neon glow border); shadow (hard offset block, soft, none); text colour; font (system family, weight,
case). Colours are roles (`accent`, `accent_dark`, `accent_light` (new), `on_accent`, `ink`, `paper`) resolved by
`PaletteResolver`. Sheets (examples, not a closed list) and the ten new looks (`neon_box`, `pill_ring`, `gradient_block`,
`tilt_sticker`, `solid_round`, `pastel_pill`, `flat_rect`, `yellow_bold`, `dark_accent`, `coral_pill`): see the server file.
A look is data (the contract JSON); the phone draws it through one generic painter, not one code path per look.

### Properties the phone must never miss
- Text: solid fill; gradient fill with direction (left-to-right, top-to-bottom, diagonal) and stops spanning the actual text
  width; stroke (width, colour, round joins); text shadow (offset, blur, colour, alpha); glow; 3D extrude; opacity; font
  family, weight, case; letter spacing (0 for joining scripts); line height for 2 lines; centred alignment of a wrapped text
  in either reading direction.
- Box: solid fill; gradient fill with direction; border (width, colour); double ring; corner radius (pill = fully round);
  padding x and y; box shadow, soft and hard offset block; neon glow; opacity; highlighter bar.
- Whole overlay: tilt; **layer order** box shadow, box fill, box border, text shadow, text stroke, text fill, glow; no
  clipping of shadow, glow or tilt at the safe-rectangle edge; the same relative proportions in 9:16, 4:5, 1:1; animation
  entry, hold and exit with direction, duration and easing.

### Fit rule: any length, any script
(1) Measure the real text with the real font. (2) Too wide: shrink down to a minimum scale, then wrap at a Unicode line-break
opportunity (`BreakIterator.getLineInstance`: between words, or between characters in scripts without spaces) into at most 2
balanced lines, never inside a grapheme cluster. (3) Still not fitting: skip the overlay and note `overlay_skipped_no_room`;
never an ellipsis. (4) Box, border, gradient and shadow follow the measured text box, padding in text-size units.
Paragraph direction comes from the text (`java.text.Bidi`), alignment is centred.

### Gates (the definition of done)
1. Nothing is enabled until it passes: a look, motion or feature id goes into `Capabilities.current()` only after its JVM
   check passed and the owner ticked it in **Passed on a device** below.
2. One source generates the tests: vectors come from the server (`look_vectors`); every `TextLook` field has a handler; a look
   with a field the painter has no handler for is refused.
3. Fail closed: a property the phone cannot draw means the whole overlay falls back to one known-good plain look, never
   half-styled, and the receipt carries `property_not_drawn`.
4. The server never sends a text that cannot lay out (its job); the phone still applies the fit rule.
5. No silent change to a shipped look (the phone must not "fix" a look in place).
6. **The string matrix** on every change (same list as the server's S1 fixture, copied into one JVM fixture
   `aiedit/TestStrings.java` in C1): 1 grapheme; 1 word; the longest allowed text; all caps Latin; Bengali with a conjunct
   (`শ্রেষ্ঠ`); Devanagari; Arabic; Hebrew; Chinese and Japanese (no spaces); Thai (no spaces); Cyrillic; Turkish dotted i in
   caps; mixed left-to-right and right-to-left; descenders; an emoji inside text. Across 3 ratios and the zones.
JVM caveat: the JVM's `BreakIterator` is not Android's ICU one, so JVM tests assert invariants (a break is a line-break
opportunity, never inside a grapheme, balanced, deterministic), not exact break positions for Thai or Japanese.

### Server API and where to read it (do not re-derive from the server code)
The server half (cards S0-S9) is **built and committed** (server repo `feat/ai-video-edit`, last card commits
`1faf7fc5` S6, `9eb9628b` S7, `55c3f8c8` + `fbf78bab` S8). Its written contract is the one file to read:
`/home/haku/projects/Next.js/Social-Media-Manager/_planning/02_contracts/ai.video-editing.md`. Read only the sections a card
needs: "Routes" (endpoints), "Motion params" (C2, C3), "Look format, additive" and "The ten new looks" (C5-C10), "Look
vectors" (C8), "Fit rule" and "Text size `s` per ratio" (C1), "Decision register" (who owns what). No new endpoints exist;
the phone's `AiEditApi` already calls all of these:
| Route (`/public/v1`) | What the text-styles work changed |
|---|---|
| `GET /ai-edits/contract` | serves 16 looks (old fields + new blocks), plus new top-level keys `color_roles`, `text_layer_order`, `text_features`, `look_vectors`; `contract_version` stays 2 |
| `POST /ai-edits` | `request.capabilities` accepts optional `text_motions: string[]` and `text_features: string[]` (names `^[a-z0-9_]{1,64}$`, at most 50 each); absent or null = legacy only |
| `GET /ai-edits/:id` | the plan may carry `params.entry`, `hold`, `exit`, `exit_dir`, `exit_ms`, signed `tilt_deg` |
| `POST /ai-edits/:id/render-report` | accepts warning codes `property_not_drawn`, `text_wrapped`, `layer_clipped` (code + item id only), beside the old ones |
Facts the server fixed that the cards must match exactly:
- **Capability ids.** `text_motions` lists entry ids plain (`fade`, `slide_left`, ...), hold ids as `hold_<id>` and exit ids as
  `exit_<id>` (`fade` is both an entry and an exit). The still hold needs no id. Legacy five (always assumed): `pop`, `stamp`,
  `slide_up`, `word_by_word`, `sweep`. Send only ids whose JVM check passed and the owner ticked.
- **`text_features`** ids come from the contract's `text_features` list; a look is planned only if the phone lists the look in
  `text_styles` AND every feature that look needs. The six shipped looks need none; all ten new looks need `box_border` and
  `case_as_written`.
- **Old fields stay.** Every look still carries the legacy `fill` (solid or `vertical` only), `stroke`, `shadow`, `glow`,
  `extrude`, `background`, `bar`, `tilt_deg`; the new blocks (`text_fill`, `text_stroke`, `text_shadow`, `box`, `opacity`,
  `font`, `case`, `letter_spacing`) sit beside them. New reader: read the new blocks when present, never reshape the old ones.
- **Tilt.** The server writes a signed `params.tilt_deg` inside the look's range; the new code must use the sign as given
  (today's painter ignores it and hashes the item id).
- **Layer order vs glow.** The contract's layer order puts `glow` last; today's renderer draws the legacy glow behind the text.
  The six shipped looks keep their legacy glow behind the text (they must draw as before); decide the new-look glow meaning in
  C5 from the contract text and the S7 vectors, and record the decision in the C5 commit message.
- **Letter spacing** is 0 for joining or reordering scripts: the server's list (`NO_SPACING_SCRIPTS` in
  `contract/edit.vectors.ts`: Arabic, Syriac, Mongolian, N'Ko, the Indic scripts, Khmer, Myanmar, Thai, Lao) must match C2's
  script table: compare them in C2 and C6 and report a difference in the commit message.
- **Fonts.** `font.family` is one of `sans`, `sans_condensed`, `serif`, `mono`. Sheet 3 looks (`dark_accent`, `coral_pill`) use
  `sans_condensed` (a guess): C9's device sheet is where the owner confirms it.
- **Size.** The server estimates fit per look (box, border, ring, hard shadow, glow add size); the phone still measures for
  real. `s` per ratio is in the contract's "Text size `s`" section (96 / 64 / 64 px at 20 sp for 9:16 / 4:5 / 1:1).
- **`look_vectors`** (C8): per look `{look, facts, probes}`; facts are relative to the measured text box (fractions of `s`);
  each fact lists the `TextLook` fields it exercises in `fields`; each probe has `direction`, `text_drawn` (case applied with
  the plan's language) and `letter_spacing_s`.
- **Server is not machine-run yet.** Its specs ran only through a light script, not `pnpm build` or jest. If the served
  contract looks wrong against the contract doc, stop and report it at the top of the card's report; do not patch around it.

### Context hints for every card (keep the session small)
1. Read in this order and stop: this file's **Shared context** (once), the card's own block, the card's "Read first" files.
   Do not read the whole server repo, the whole contract doc, other cards, or git history beyond what the card names.
2. Open large files by range (`grep -n` the symbol first), never in full; `TextLookRenderer`, `TextOverlayPainter`,
   `TextAnimator` are the only text files most cards touch.
3. Write the pure logic first (layout policy, paint plan, motion math: no `Bitmap`, `Canvas`, `Paint`, `android.icu`), test it
   on the JVM with the shared string matrix (`aiedit/TestStrings.java`, created in C1), and keep the Canvas side a thin
   translator so the device-only part stays small.
4. A card edits only the files it names; if it needs another file, add one line to the report and make the smallest change.
   Never touch `subtitle/*`, the master recording, or the six shipped looks' drawn result.
5. One gradle command at a time, only `./gradlew --offline :app:assembleDebug` and targeted `--tests '<pattern>'`. Run the
   targeted test for the code you changed, not a suite. If a command would be heavy, stop and say so in the report.
6. Commit with explicit paths (`git add <files>`, `git commit <files> -m ...`), never `git add -A`; never commit
   `test_audio.wav`, `test_video.mp4`, `run.log`.
7. The final report is short and fixed: files changed, decisions made where the card was silent, commit hash, what was not run,
   anything the next card must know (one line each). The next card starts from the report and this file, not from your chat.
8. Where a card needs the owner (C4, C9, C10: run the device sheet, tick **Passed on a device**), end the report with the exact
   thing the owner must do and stop; the runner then asks the owner and continues after the tick.

## Progress

| Card | Slice | What | Depends on | Status |
|---|---|---|---|---|
| C1 | 1 | Layout policy for any script: measure, shrink, wrap at most 2 lines, skip (no ellipsis), bidi; receipt notes | server S1, S2 | ☑ |
| C2 | 1 | Entry motions, reveal direction, typewriter step, signed tilt | server S3, S4 | ☑ |
| C3 | 1 | Hold effects and exits; send `text_motions` for ticked ids | C2 | ☑ |
| C4 | 1 | Debug preview sheet: every motion x string matrix x 3 ratios; owner runs it and ticks | C1, C3 | ☐ |
| C5 | 2 | Pure paint plan from the new look blocks; thin Canvas translator; six looks unchanged | server S6 | ☐ |
| C6 | 2 | Fonts (system families), case, letter spacing, generic for every script | C5 | ☐ |
| C7 | 2 | Strict parse, capabilities by evidence, `text_features`, fail closed | C5, C6 | ☐ |
| C8 | 2 | JVM conformance runner: `look_vectors` and the string matrix against the paint plan | C7, server S7 | ☐ |
| C9 | 2 | Debug sheet gains looks and pixel probes; owner runs it and ticks | C8 | ☐ |
| C10 | 2 | Wire the new looks, 3 at a time after the owner ticks them | C9, server S8 | ☐ |

**Passed on a device (owner ticks, gate 1):** motions: none yet. looks: none yet.

---

## C1 - Layout policy for any script [slice 1]
**Goal:** a text of any length in any script lays out by one written rule, the same on every phone.
**Read first:** Shared context (fit rule, JVM caveat); `TextLookRenderer.java` (`layout(...)`, `MIN_SIZE_SHARE`, `SHRINK`,
`MAX_LINES`, the ellipsis cut), `TextPlacement.java`, `RenderNotes.java`, `flow/RenderReceipt.java`.
**Build:** a pure `TextLayoutPolicy` with a `TextMeasurer` interface (the real one wraps `Paint`/`StaticLayout`): shrink to the
minimum scale, then wrap at a line-break opportunity into at most 2 balanced lines, never inside a grapheme, then skip.
Paragraph direction from `java.text.Bidi`. Replace the ellipsis cut: the overlay is skipped and `overlay_skipped_no_room` is
noted; `text_shrunk` and `text_wrapped` are noted. Typeface stays `DEFAULT_BOLD` (C6 changes it). Add
`aiedit/TestStrings.java` (the matrix).
**Tests:** a fake measurer (one width per grapheme, wider for wide scripts) over the matrix: fits; wraps only at a
line-break opportunity with balanced lines; never splits a grapheme; a Chinese sentence wraps without spaces; skips when
nothing fits; same input, same result; right-to-left detected.

## C2 - Entry motions [slice 1]
**Goal:** every entry in the table is drawn, in the right direction, for any script.
**Read first:** Shared context; `TextAnimator.java`, `TextAnimatorTest.java`, `TextOverlayPainter.java` (`tilt`), the server's
S3 motion table in its contract doc.
**Build:** `fade`, `typewriter`, `slide_left`, `slide_right`, `slide_top`, `slide_bottom`, `wipe`, `drop_bounce`, `blur_in`,
`flip`, `glitch` from `params.entry` and `entry_ms`, clamped to the registry limits. One `revealUnits(text)` owns the
typewriter step (graphemes, or words for scripts in the joining/reordering table) and one `readingDirection(text)` owns
reveal direction. Blur is computed once on the cached sprite, not per frame. Tilt: use `params.tilt_deg` as given (sign
included); the hash stays only as the fallback when it is absent. Unknown entry still falls back to `pop`.
**Tests:** transform at t = 0, mid and end for each entry; start edge of each slide; right-to-left `wipe` reveals from the
right; a Bengali conjunct and an Arabic word are never revealed half; no motion after the entry; signed tilt kept.

## C3 - Hold effects and exits [slice 1]
**Goal:** `float`, `pulse`, `shake`, `shimmer`, `neon_flicker` while shown; exits `fade`, `slide_out`, `shrink`, `pop_out`,
`typewriter_delete`, `wipe_out`.
**Read first:** Shared context; `TextAnimator.java`, C2's code, `Capabilities.java`, `flow/AiEditRequestBuilder.java` (~line 78).
**Build:** as C2, from `params.hold`, `params.exit`, `exit_dir`, `exit_ms`; an exit never runs past the item's end; exits that
reveal follow the reading direction. `Capabilities` gains `text_motions` (sent in `toJson()`): the legacy five plus the ids in
**Passed on a device**; nothing else.
**Tests:** start, mid and end of each; the exit ends exactly at `end_ms`; `toJson()` carries only ticked motions.

## C4 - Debug preview sheet [slice 1, owner runs]
**Goal:** the owner sees every motion on a real device before users do.
**Read first:** Shared context; an existing debug screen if any (else a debug-only activity, not in the release build).
**Build:** a debug-only screen that plays each motion with each matrix string in 3 ratios through the real
`TextOverlayPainter`, with a scrubber and a PASS/FAIL tick per motion that prints the id list to copy into **Passed on a
device**. Agents build it and check with `./gradlew --offline :app:assembleDebug`; **agents do not run it**.
**Tests:** a JVM test for the grid (which motion, string and ratio sit where).

## C5 - Pure paint plan [slice 2]
**Goal:** every property of a look becomes an explicit, testable draw operation.
**Needs:** server S6 and the refreshed `contract.json`.
**Read first:** Shared context; `TextLookRenderer.java`, `contract/TextLook.java`, `EngineContract.java` (~line 217),
`PaletteResolver.java`.
**Build:** `TextLook` reads the new blocks (`text_fill`, `text_stroke`, `text_shadow`, `box`, `opacity`, `font`, `case`,
`letter_spacing`) and still accepts the old fields. A pure `LookPaintPlan` (no `Bitmap`, `Canvas` or `Paint`) takes a
`TextLook`, the resolved colours and the C1 layout result and returns ordered ops in the layer order, numbers in text-size
units; it throws `UnsupportedLookException` (a code, no text) for a field it has no handler for. `TextLookRenderer` becomes a
thin translator from ops to `Canvas`. The six looks draw exactly as before.
**Tests:** per property: the op exists with the right numbers for each of the six looks; layer order; unknown field throws;
`ltr`, `ttb`, `diag` give different stops.

## C6 - Fonts, case, letter spacing [slice 2]
**Goal:** `font`, `case` and `letter_spacing` are drawn, the same way for every script.
**Read first:** Shared context; `TextLookRenderer.java` (`Typeface`), C5's code.
**Build:** `font.family` (`sans`, `sans_condensed`, `serif`, `mono`) to the system families (`sans-serif`,
`sans-serif-condensed`, `serif`, `monospace`) with `Typeface.create(family, weight, false)`; the system fallback draws every
script. `case: upper` uses `toUpperCase(plan language locale)`; scripts without case are unchanged. `letter_spacing` as a
fraction of the text size, forced to 0 for the joining/reordering script table (C2's). An unknown family falls back to
`sans` and notes `property_not_drawn`.
**Tests:** family table; Turkish `i` uppercases to `İ`; Bengali and Chinese unchanged by `upper`; spacing 0 for Arabic and
Bengali, kept for Latin.

## C7 - Strict parse, capabilities by evidence, fail closed [slice 2]
**Goal:** the phone never lists a look it cannot draw completely, never ignores a field, never draws half a look.
**Read first:** Shared context; `EngineContract.java`, `Capabilities.java`, `TextOverlayPainter.java`, `RenderNotes.java`,
`aiedit/TestContract.java`.
**Build:** a look with a field the painter has no handler for is not listed (log a code only); one bad look never drops the
whole contract. `Capabilities` text styles = painter-supported and ticked looks; `text_features` = the feature ids the painter
implements and the owner ticked. At draw time, an `UnsupportedLookException` or a missing feature draws the whole overlay in
one built-in plain look (bold text with a stroke in the accent colour) and notes `property_not_drawn` with the item id.
**Tests:** unknown field: look not listed, other looks still listed; capabilities are a subset of supported and ticked; an
unsupported look gives the plain plan; a supported one never does; the note carries the item id only.

## C8 - JVM conformance runner [slice 2]
**Goal:** the server's generated vectors are checked against the paint plan.
**Needs:** server S7 (`look_vectors` in the contract).
**Build:** a JVM test that, for every listed look and every vector, builds the paint plan with the fake measurer and asserts
the facts (layers and order, gradient direction, shadow offset, stroke width, box contains text plus padding), and that every
`TextLook` field has a handler (gate 2).
**Tests:** the runner itself; a look with a missing handler fails it.

## C9 - Look sheet with pixel probes [slice 2, owner runs]
**Goal:** pixels checked on a real device, like the reference sheets.
**Read first:** Shared context; the three sheet images; C4's screen.
**Build:** C4's screen gains a looks tab: every look x matrix string x 3 ratios in a grid like the sheets, with pixel probes on
the `Bitmap` (left and right gradient pixels differ; the shadow colour at the shadow offset; the stroke colour on the stroke;
box contains text plus padding; nothing clipped at the safe-rectangle edge) and PASS/FAIL per look. Agents build it only.
**Tests:** a JVM test for the probe geometry.

## C10 - Wire the new looks [slice 2]
**Goal:** the new looks reach users, 3 at a time.
**Needs:** server S8; C9 passed for each look on a device.
**Build:** refresh `contract.json` from the server; no per-look code; add the ticked look ids (and the features they need) to
`Capabilities.current()`; update `TestContract`. Repeat per batch of 3.
**Tests:** C8 green for the new looks; capabilities remain a subset of the ticked list.
