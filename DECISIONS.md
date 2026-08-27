# Why this app is built the way it is

Written down because the reasoning lived in a conversation that no longer
exists. Commit messages carry the detail; this is the shape of it.

## The one constraint everything else follows from

**It has to work at sea with no signal.** That single rule explains most of what
looks odd here:

- One HTML file, no libraries, no CDN. A single `<script src>` to anywhere would
  break the app the moment the ship loses its link.
- CSV rather than `.xlsx`. Reading and writing real Excel files needs a library
  fetched over the network. CSV is the format that can be written by hand.
- The Weekly Work Done Report prints to PDF rather than producing a `.docx`.
  Same reason. Word files need a library.
- Photos are compressed client-side to 900px at JPEG quality 0.65. `localStorage`
  caps around 5 MB and a raw camera photo is 3–5 MB, so two untouched photos
  would fill it. Compressed they run 80–150 KB.
- No OCR of the ship's particulars sheet. The smallest usable recognition engine
  is ~10 MB over the network. Instead the Ship tab parses text copied off the
  photo with the phone's own text selection (iOS Live Text and equivalents).

## Where the data lives, and the trap in it

Everything is in `localStorage`, on one device, in one browser.

| Key | Holds |
|---|---|
| `gasplanet_todo_v1` | jobs |
| `gasplanet_crew_v1` | crew |
| `gasplanet_cargo_v1` | cargo log |
| `gasplanet_ship_v1` | ship's particulars |
| `gasplanet_extra_v1` | one-off AD-19 jobs |
| `gasplanet_lastBackup_*` | per-list export dates, driving the backup banner |

**Storage is per origin.** The GitHub Pages site, a Claude artifact preview, a
file opened from the Files app and the Android APK are four separate stores that
never see each other. Data entered in one is invisible in the others — this
looked like "the app reset itself" more than once. Settle on one and stay there.

Export CSV is therefore the backup, not a convenience. Hence the banner that
nags per list after 14 days.

## Dates

Two rules that took a while to get right, and are easy to break again.

**Everything is local-time.** `toISOString()` returns UTC; slicing it gives the
wrong calendar day east of Greenwich in the morning, west of it in the evening.
Every date is built from local components via `isoFromDate()`. On a ship crossing
time zones this is not academic.

**Which day a job belongs to:**

- not yet closed → its **due date**, because that is the day it is planned for
- closed → the **date it was actually done**, wherever that fell

So a PMS job closed early or late leaves its due-date planner and appears on the
real one. `ad19Day()` is the whole rule.

**Repeat intervals count days from the date the job was done**, not from the due
date, because that is how the ship's PMS counts them: 3-monthly means 90 days
after it was done. Weekly 7, 2-weekly 14, monthly 30, 3-monthly 90, 6-monthly
180, yearly 365. Only 90 was confirmed by the user; the rest follow from it and
the day counts are shown on the labels so the assumption stays visible.

## The two ship's documents this mirrors

**AD-19 Daily Work Planner** (`AD19_Work_Planner_TEMPLATE.xlsm`). The export
carries the eighteen columns of that workbook's *Planner* tab, headers taken
from rows 11–12, columns A–Q, with the three-row PPE block flattened into
PPE / Type of Glove / Other PPE.

- Numbering starts at **4**. S.NO 1–3 are the standing watchkeeping,
  accommodation cleaning and food preparation entries already on the form.
- Standing values filled so they are not retyped: toolbox meeting time
  `0800 - 0830`, work & rest `Yes`, MARSEC `Level 1` on the first row of each
  date group only — matching how the sheet fills its once-a-day block.
- Permit types come from `Sheet4!S13:S23` of that workbook, so an exported value
  drops into the sheet's own validation list unedited.
- **The Planner tab cannot be pasted into.** It merges columns A and B across
  three rows per activity and Excel refuses a paste over merged cells. The export
  is laid out to be read and transferred, not pasted. A version of the workbook
  where the Planner pulls from the Job Log by formula was built but never
  verified in Excel.

**Weekly Work Done Report** (`Deck_Weekly_Workdone_*.docx`). Heading block, the
week's work as a list of statements, then the photographs **two to a row with
the same statement repeated in a grey band beneath each row** — a job with six
photos gives three identical caption bands. That repetition is the original's
own pattern, not an accident.

Weeks run Monday to Sunday; the report goes out Sunday evening, so a report
generated on a Sunday must fall in the week that is ending. `getMonday()` handles
it — Sunday 30-Aug resolves to week commencing 24-Aug.

## Things that are the way they are on purpose

- **Closing a job puts it on the planner by itself.** No tick to remember. The
  AD 19 tick now only means "plan this before it is done".
- **A planner entry not yet done is not marked "NOT YET COMPLETED".** The planner
  is drawn up before the work. The Work Done Report does mark it, because there
  it means something.
- **The AD 34 label was a mistake for AD 19.** Renamed everywhere; the stored key
  is still `ad34Planner` so existing jobs keep their tick. Import accepts both
  spellings.
- **Storage failures roll back.** A quota failure used to be swallowed: the
  change stayed in memory, the screen looked normal, and everything since the
  failure vanished on reload. Writes are now verified and reverted to the last
  state that reached disk.

## Building and shipping

- **Web**: `index.html` is the app. `sw.js` caches it for offline use — **bump
  `CACHE` in `sw.js` whenever `index.html` changes**, or installed devices keep
  serving the old copy. Updates apply on the launch *after* the one that
  downloads them, because pages are served cache-first for speed at sea.
- **Android**: `android/` is a WebView shell. GitHub Actions builds a debug APK
  on every push touching `android/` or `index.html`, and copies the current
  `index.html` in at build time so the two cannot drift.
- **Tests**: `tests/` — see its README.

## Before this could ever be sold

It is built around one company's documents and one specific ship.

- The Ship tab ships with GAS PLANET's real particulars hardcoded in
  `SHIP_DEFAULT`, including the vessel's e-mail address, owners and managers.
  **Start it blank.**
- The AD-19 column set, the permit-to-work list and the Weekly Work Done layout
  are reproduced from the operator's forms. They are almost certainly the
  operator's property. A saleable version has to read structure from whatever
  template the user supplies rather than carrying theirs.
- Play Store also needs an App Bundle rather than an APK, a release keystore, a
  privacy policy, and — for a new personal account — a closed test with 12
  testers for 14 days.
