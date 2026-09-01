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
- **Work added in the WWR tab is a job, not a second kind of record.** It is
  pushed into `tasks` already closed out — `done`, `dateCompleted` set,
  `weeklyReport:'yes'` — rather than into a store of its own. That is why it
  appears on the to do list as completed, why its photos and comments travel
  with it, why the job editor opens from either tab, and why deleting it in one
  place deletes it in both. The AD-19 tab is the opposite case and keeps its
  own store: those are one-off planner entries that were never jobs and should
  not clutter the pending list.
- **The converter shows every unit at once, not a from/to pair.** On a phone,
  picking two dropdowns to get one number is slower than reading the answer off
  a list. Each line carries its own working — `psi = bar × 14.50377` — because a
  number with no arithmetic behind it cannot be checked, and a factor below 1 is
  shown as a division so the printed number is always the readable one.
- **Conversion factors are stated against the base unit, never chained.** Each
  is written out in full against Pa, J, m³, kg, m, kg/m³, m³/h or m/s, so an
  error in one factor cannot travel into the others. `tests/factor-check.py`
  re-derives all 70 from published definitions and compares. It is the only
  check that can catch a wrong factor: the UI renders a wrong number exactly as
  convincingly as a right one.
- **The Instruments tool ships empty, and that is the point.** It supplies the
  structure — procedures as numbered steps with photographs, a calibration and
  bump test log, a spares list, interval-driven due dates — and every word of
  content is typed in from the maker's manual by the ship. Nothing is
  pre-filled. A span gas figure, an alarm setpoint menu path or a spare part
  number written from memory would read exactly as convincingly as a correct
  one, on an instrument people rely on before entering an enclosed space.
- **The Android file picker must honour the page's accept types.** It hardcoded
  `image/*` and ignored them, so every file input in the app opened a photo
  gallery and none of the four data imports could pick their file. Photos
  worked, so nothing looked broken — the failure only showed up the first time
  someone tried to import on the phone rather than in a browser. Data pickers
  are also deliberately unfiltered (`*/*`): file managers report .csv and .json
  as text/plain or octet-stream often enough that a filtered picker greys out
  the file the user came for.
- **The Instruments tab is a reference shelf, not a second record.** It holds
  the makers' procedures and spares lists to be read while working, and keeps
  no log, no calibration dates and no intervals. The ship's log is filled by
  hand and is the record; a half-kept duplicate of it in an app is worse than
  none, and a stale OVERDUE badge on a reference card is worse still. Old
  instruments are stripped of those fields on load.
- **Instrument content is imported, never shipped in the app.** The
  transcriptions live in `instruments/` as files the ship imports, so the app
  itself still carries no manufacturer content — which is what keeps the
  pre-sale position honest rather than merely stated.
- **A symbol font nearly put wrong keystrokes in a safety procedure.** The
  GX-8000 maintenance manual sets its ▲/▼ keys in Wingdings 3 with no Unicode
  map, so text extraction reads them as the letters S and T. The glyph outlines
  were read directly to settle which was which. Anything extracted from a PDF
  that will become an instruction is worth checking against the font.
- **Duplicating an instrument copies the model, never the unit.** Procedures and
  the spares list belong to the model and come across; the serial number belongs
  to the physical unit in your hand and is left empty. Step photographs are
  shared by reference rather than duplicated — one picture of a screen serves
  both, and the collector counts every use before deleting anything.
- **A field edit in the Instruments detail never rebuilds the panel.** Only the
  two due tags are rewritten. The first version re-rendered on change, which
  destroyed the field being typed into and made every edit land one step behind
  the one after it.
- **Photographs live in IndexedDB; localStorage keeps only their ids.**
  Measured, not guessed: localStorage stops at **4.94 MB** on this engine, and a
  photo costs about a third more than its file size once base64 encoded. At the
  80-150 KB the app compresses to, that is roughly **thirty photographs for the
  whole app** — jobs, crew and cargo included, since they share the one quota.
  IndexedDB on the same engine offered **1067 MB**. A photo is stored as
  `p:<id>`; anything else is passed through untouched, which is what keeps
  photos written by older versions working until they are migrated.
- **Migration rewrites localStorage only after every photo has landed.** An
  interrupted migration therefore loses nothing — it simply runs again next
  time. Deleted photos are collected at startup only, before anything can be
  sitting queued in a form and not yet attached to a job.
- **Without IndexedDB the app behaves exactly as before.** `keepPhoto` hands the
  data URL straight back, and every render path already accepts one. Smaller,
  but working, rather than broken.
- **The weekly report is a table, because that is what repeats a header.** The
  letterhead sits in a real `<thead>`. A fixed-position header is dropped by
  print engines and a plain div prints once, at the top of page one. Each page
  is one `<tr>`, so pagination is decided by the app rather than by wherever the
  content happens to break — which is also what lets each page carry its own
  number, since Chrome does not support CSS page counters.
- **A caption may never sit under a photograph from another job.** Photos are
  paired within a job, so a job with an odd number of them leaves half a pair
  empty rather than borrowing from the next. Four to a page is therefore a
  maximum, not a promise. Arranging works on the two levels this allows: the
  order of the jobs, and the order of photos inside one.
- **Reordering a photo reorders the job's own array.** So the new order shows on
  the card and in every export, not only in the report. The job order, which is
  report-specific, is kept per week in its own key.
- **The ETA tool lays out a band of speeds rather than answering one.** A tenth
  of a knot moves an arrival by about an hour over an ocean passage, so the
  useful output is the whole band — nine tenths below through one knot above, in
  tenths — with the entered speed highlighted. Departure is typed in local time
  and shown in UTC immediately, because UTC is the number that goes in the
  message and the one that gets fumbled.
- **All ETA arithmetic is done in minutes from the epoch, in UTC.** No Date
  object is ever trusted with a time zone: the offset is applied as a number and
  every field is read with getUTC*. Zones run the full range in half-hour steps,
  because half-hour zones are in daily use east of Suez.
- **The Information guide is plain text carried in the page.** Roughly 17 KB for 74
  entries, which is nothing against working with no signal. A build check
  asserts every unit in the converter has a guide entry and vice versa, so the
  two lists cannot drift.
- **The converter cannot turn mass into volume.** Tonnes to barrels needs a
  density, which depends on the product and its temperature, so there is no
  factor to carry. That is a cargo calculation, not a unit conversion, and
  putting a single number on it would be wrong more often than right.
- **The WWR tab lists every WR job ever, banded by week.** Not just the current
  week. Reports get asked for again months later, and a list that quietly drops
  older weeks is a list that cannot answer that.

## Building and shipping

- **Web**: `index.html` is the app. `sw.js` caches it for offline use — **bump
  `CACHE` in `sw.js` whenever `index.html` changes**, or installed devices keep
  serving the old copy. `build-site.py` (in the working repo) does that bump
  along with the three transforms the hosted copy needs over the source file:
  the home-screen meta tags, the notch-safe header padding, and the service
  worker registration. Updates apply on the launch *after* the one that
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
- The Instruments tool is designed to hold transcriptions of manufacturers'
  manuals. That is fine for one ship's own use; distributing an app with another
  company's manual content inside it is not. Any saleable version must keep the
  instrument content as user data only, and must never ship with it.
- Play Store also needs an App Bundle rather than an APK, a release keystore, a
  privacy policy, and — for a new personal account — a closed test with 12
  testers for 14 days.

---

# The Ledger app (`money/`)

A second app in the same repository: income, loans, day-to-day spending,
investments and goals. Built to the deck log's rules — one HTML file, no
libraries, nothing fetched — for the same reason, and it earns them again on
its own terms: a price feed that quietly went stale is worse than no price
feed, and this is a screen people make decisions on.

## Why a separate app rather than another tab

The deck log is a work app. Someone else may be looking at it on the bridge.
Personal finances do not belong in it, and the 48 MB `index.html` does not need
another tab.

## The trap that comes with the separate folder, and the one that does not

**`localStorage` is per origin, not per path.** On GitHub Pages the deck log at
the site root and the Ledger at `/money/` are one origin, so they share one
store and one 5 MB quota. Every Ledger key is prefixed `money_`, nothing here
touches a `gasplanet_` key, and a test asserts it. The quota is genuinely
shared; the Data tab shows how much of it has gone.

**Service worker scope is per path, and that is a trap too.** The root worker's
scope is `./`, which covers `/money/`. The narrower worker wins once it is
installed — but on a first visit it is not installed yet, and the root worker
answers every navigation in its scope with the deck log's own `index.html`. The
money app would have opened as the deck log. The root worker now returns early
for anything under `/money/`, which is why its `CACHE` went to v51.

## The arithmetic, and why it is written the way it is

Every instrument runs through **one month-by-month loop** rather than a closed
form per instrument.

- A **prepayment part way through a loan** has no closed form, and prepayment
  is the whole reason anyone opens that screen.
- A **step-up SIP** has no closed form worth trusting — the instalment changes
  every twelve months.
- One loop that serves the loan, the FD, the RD and the SIP is one place for a
  mistake to live, rather than five formulas to keep in step with each other.

The monthly growth factor is written once, `g = (1 + r/(100n))^(n/12)`, with
`n` the compounds a year. That is what makes the FD in the calculator and the
FD in the portfolio the same arithmetic rather than two things that happen to
agree today.

The tests re-derive every figure from the published closed form, written out in
the test file against its own definition, and compare. It is the only check
that can catch a wrong one: the UI renders a wrong EMI exactly as convincingly
as a right one. This is the `factor-check.py` argument, applied to money.

## Things that are the way they are on purpose

- **Indian banks compound a term deposit quarterly.** It is set on the card, not
  assumed. At 7% over five years, quarterly against yearly is real money.
- **An RD is a sum over its instalments**, each compounded for the months it has
  left to run. The single-rate shortcut — everything compounding for the whole
  term — is out by thousands over a five-year RD, always in the bank's favour.
  A test pins the app away from it.
- **The return quoted is money-weighted, not the pot divided by what went in.**
  The first version divided the final value by everything ever paid in and took
  a root, which reported 5.84% on a run that returned 12.68%: it treats the last
  instalment as though it had been there since the first day. The figure is now
  the rate that balances the actual payments against the final value, solved by
  bisection. On a flat rate it comes back as exactly that rate, every period —
  which is the check that it is right, and is asserted.
- **An instalment is paid at the start of its month.** The loop grows it by that
  month's factor, so its cash flow sits at month *k*−1, not *k*. Placing it a
  month late read the return about a quarter point high — the sort of error
  nobody would ever spot on the screen.
- **An EMI at or below the first month's interest is named, not looped.** The
  balance never falls; the card says so rather than printing a century of
  instalments.
- **A prepayment larger than the balance is trimmed.** It clears the loan; it
  does not run it into credit.
- **An FD is worth exactly its maturity value on its maturity date.** Elapsed
  time is counted in months, because deposits are quoted in months. Measuring it
  in average-length years left "worth today" a few rupees under "at maturity" on
  the day itself, and two figures on one card disagreeing is how trust in all of
  them goes.
- **A rate a day needs enough days behind it.** On the 1st of a month, one
  expense divided by one elapsed day and multiplied out gave a month-end figure
  larger than the year's income. The span now also covers any expense dated
  later in the month, and below five days no rate is quoted at all.
- **Rent is not a day-to-day expense.** It arrives whether you look or not, like
  an EMI, so fixed outgoings are their own list with their own cycle. The
  day-to-day ledger is for the spending that varies, which is the only spending
  worth reading a rate into.
- **A yearly bonus is not a twelfth of itself every month.** Both figures are
  shown: the average, for planning, and the month it actually lands in.
- **A quarterly item is anchored on its own start month**, not on the calendar
  quarter. One started in February falls in February, May, August, November.
- **The pie is six hues and then "Other".** Colours are assigned in a fixed
  order and a category never gets recoloured because another one dropped out —
  a glance month to month has to mean something. Both palettes were checked by
  script for colour-blind separation against this app's own card colours rather
  than eyeballed, and every slice is labelled and repeated in the list beneath,
  so no slice is told apart by its colour alone.
- **The prices are the ones you wrote down, and the card says when.** There is
  no feed. A price more than a month old is marked as old rather than shown as
  current.
- **Nothing in the calculator is a forecast**, and it says so. A fund does not
  return the same percentage every year.

## Ledger on Android

The same WebView-shell approach as the deck log, and mostly the same file. Its
own Gradle project at `money/android/`, its own workflow, its own release tag.

- **The release tag is `ledger-latest`, not `latest`.** The deck log's workflow
  deletes and recreates `latest` on every one of its builds. Sharing the tag
  would have meant each app's build quietly replacing the other's download, and
  the symptom would have been someone installing the deck log expecting Ledger.
- **Its own signing key, committed, and verified before publishing.** Same
  reasoning as the deck log — a build signed by a different key cannot install
  as an upgrade, and the only way in is to uninstall. There it costs the jobs
  and photos. Here it costs every loan, holding and expense. The check reuses
  `android/verify-signing.py` rather than a copy of it.
- **`check-resources.py` takes a project directory now** and is run against both.
  It also checks `mipmap/` references, which it previously skipped: a manifest
  naming a launcher icon that is not there is an aapt error like any other, and
  costs the same full build cycle to find. There is still no Android SDK in the
  environment this is developed in, so CI remains the only compiler and the
  script is the only thing standing between a typo and a wasted cycle.
- **The shell is smaller than the deck log's on purpose.** No camera, no
  location, no widgets, no print — no photographs and no map in this app. The
  permission list is empty, which for an app holding a person's salary and
  holdings is worth having.
- **`allowBackup` is off, and this is the one real disagreement with the deck
  log.** Android's auto backup copies the app data directory — where the WebView
  keeps localStorage, where all of this lives — to the user's Google Drive, on a
  schedule, with no prompt. The app's own Data tab says nothing is uploaded and
  nothing syncs; that has to be true rather than nearly true.
  `data_extraction_rules.xml` says the same for Android 12 and later, which
  reads it instead of `fullBackupContent`. The cost is stated plainly in the
  README: lose the phone and the data goes with it unless it was exported.
- **The page-to-shell contract is pinned by a test on the page's side.** The
  shell catches a click on `<a download>` and reads the blob back, because a
  WebView ignores that click entirely — no error, no file, nothing. If the
  export is ever rewritten to save some other way, the APK silently loses the
  ability to back anything up, and nothing on the phone would say so. The test
  fails instead.
- **`APP_BUILD` in the page is what CI names the release after**, and the Data
  tab prints it, so "which version is actually on this phone" is answered by
  looking rather than guessed.
