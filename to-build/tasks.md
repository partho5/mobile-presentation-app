
RULE: this PC hangs under load. Run at most one gradle command at a time, only `./gradlew --offline :app:assembleDebug`
and targeted unit tests (`--tests '<pattern>'`). No emulator, no whole-suite runs. 
One task card = one session (one sub-agent). Build exactly one card, tick it in **Progress**, commit, stop. A card is
self-contained: read **Shared context** below, then the card's own "Read first" list.

-----------------

# Practice for every topic set (PR1–PR4)

## Intent (confirmed by the owner, 2026-11-27)
The "Today's video" strip under the top bar covered the first slide's text. Remove it. Put one **Practice** button in
the top menu bar (left of "Edit Topics"); it opens the topic set that is **on screen** in Practice, whatever that set
is: a plan video's, an older week's, or the user's own. Practice must help a user who is not eloquent: every topic
shows "what you say" under it. Plan topics show the plan's spoken line; a topic without one gets a "What I'll say" note
box with prompts (Why it matters · An example · What to do next), saved to that topic. No AI suggestions in this change.

## Shared context
- Package `com.jovoc.facecampresentationrecorder` (`J` below = `app/src/main/java/com/jovoc/facecampresentationrecorder`).
- Topics are `db/Slide` rows; `set_id` NULL = own topics, `plan:<dayId>` = a plan video's. `MainActivity.activeSetId()`
  is the set on screen; `MainActivity.loadSlidesFromDb(false)` reloads it and keeps the current index.
- A plan set is built by `contentplan/PlanSetSync.buildSlides` (one TEXT slide per beat hook). `PlanSetSignature` is the
  "did the user edit it" fingerprint; an unedited set is silently replaced on a newer server version.
- `contentplan/PlanSetIndex.Entry` remembers per plan video: brand, title, date, kind, version, signature.
- Plan texts are never logged.
- Contract — new column `slides.speaker_note TEXT` (nullable), DB version 11 → 12, migration
  `ALTER TABLE slides ADD COLUMN speaker_note TEXT`. Written only through `SlideDao.updateNote(id, note)` so a stale
  Slide object elsewhere never matters; MainActivity reloads its slides after Practice.
- Contract — `PlanSetSignature`: the note joins the fingerprint only when non-empty, so every set saved before this
  change keeps its old signature.
- Contract — Practice screen `practice/PracticeActivity`, extra `set_id` (String, null = own topics). Result
  `RESULT_OK` + extra `set_id` = "put this set on screen" (only offered when it is not on screen already).

## Progress
- [x] PR1 Data: speaker note column, signature, plan sets carry their spoken lines, index keeps the closing line
- [x] PR2 PracticeScript: pure step builder (+ tests)
- [ ] PR3 Practice screen for any set (new `practice` package, replaces ContentPlanPracticeActivity)
- [ ] PR4 Home: strip removed, Practice in the top bar, Topic sets sheet uses the same screen

## PR1 Data
Read first: `J/db/Slide.java`, `J/db/SlideDao.java`, `J/db/SlideRepository.java`, `J/db/AppDatabase.java`,
`J/contentplan/PlanSetSignature.java`, `J/contentplan/PlanSetSync.java`, `J/contentplan/PlanSetIndex.java`,
tests `AppDatabaseMigrationTest`, `PlanSetSignatureTest`, `PlanSetIndexTest`.
- `Slide`: field `speakerNote` (`@ColumnInfo(name = "speaker_note")`), getter/setter.
- `AppDatabase`: version 12, `MIGRATION_11_12`, added to `MIGRATIONS`.
- `SlideDao.updateNote(int id, String note)`; `SlideRepository.updateNote(id, note, Runnable)` on its executor,
  callback on main.
- `PlanSetSignature.of`: append `'\u0001' + note` only when the note is non-empty.
- `PlanSetSync.buildSlides`: each slide's note = beat `say` (null when empty).
- `PlanSetIndex.Entry`: new `closingLine` (JSON key `closing`, "" when absent); old 8-arg constructor kept.
- Tests: migration 11→12 SQL, version 12; signature unchanged for null/empty note and changed by a note; index round
  trip of `closing`.

## PR2 PracticeScript
Read first: PR1, `J/contentplan/PlanWeek.java`.
- `J/practice/PracticeScript.java`, pure Java: `build(List<Slide> slides, PlanWeek.Day dayOrNull, String closingOrNull)`
  → `steps` (one per enabled slide, in order: slide id, type, text, image path, note, `planLine`) + `closingLine`.
  `planLine` = the slide's own note when set, else the plan beat whose hook equals the slide text (trimmed), else null.
  Closing line = day's when the day is known, else the given one, else null.
- Tests: disabled skipped; own note wins; plan match by hook; unmatched edited topic has no line; closing fallback.

## PR3 Practice screen
Read first: PR1, PR2, `J/contentplan/ContentPlanPracticeActivity.java`, `res/layout/activity_content_plan_practice.xml`.
- Move to `J/practice/PracticeActivity.java` + `res/layout/activity_practice.xml`; delete the old ones; manifest.
- Loads the set's slides, the plan day (cached week of the index entry's brand) when it is a plan set, builds a script.
- Header: plan title (or "Your topics"), angle chip / why when the day is known.
- Stage: TEXT as on camera (TextSlideSize), IMAGE via Glide, other types a short label.
- Under it a "What I'll say" box prefilled with the step's line, hint "Why it matters · An example · What to do next";
  edits are saved to that topic (`updateNote`) when moving step, opening the script or leaving.
- Closing step ("End with") when there is a closing line. Swipe / Previous / Next, count, full script toggle.
- Bottom: "Set for Recording" only when the set is not on screen; "Done".

## PR4 Home
Read first: `J/MainActivity.java` (top bar, `practicePlanDay`, `syncPlanCardVisibility`), `J/contentplan/ContentPlanCard.java`,
`J/contentplan/TopicSetsSheet.java`, `res/layout/activity_main.xml`, `res/layout/view_content_plan_today.xml`.
- Delete the strip layout and its include; `ContentPlanCard` becomes headless `ContentPlanLoader` (refresh, set sync,
  auto switch kept; label/title/button/visibility removed). Drop unused strings.
- Top bar: Practice text button left of "Edit Topics". Tap: no enabled topic → toast "Add a topic to practise it" and
  open Edit Topics; else open Practice for `activeSetId`. MainActivity owns the launcher; on return reload slides; on
  `set_id` result switch the set.
- Topic sets sheet "Practice" opens the same screen for that set.
