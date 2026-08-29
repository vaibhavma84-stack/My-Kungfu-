# Tests

Browser tests that drive the real page in Chromium — no mocks. They exist
because most of this app's behaviour is only observable through the UI: dates
landing in the right planner block, photos pairing two to a row, a repeat
raising the next occurrence.

```bash
npm install playwright-core
APP_HTML=../index.html ./run.sh
```

Each suite is standalone: it serves `index.html` over localhost, drives it, and
asserts. `OUT` is where downloads and screenshots land (a temp dir by default).

| Suite | Covers |
|---|---|
| `browser-test` | the eight original correctness fixes — dates, storage rollback, photo inputs, form clipping |
| `repeat-test` | intervals counted in days from the done date, what carries to the next occurrence, the duplicate guard, the interval colours |
| `pms-test` | a job plans under its due date until closed, then moves to the date it was done |
| `auto19-test` | closing a job puts it on that day's planner with no tick |
| `report-test` | the Weekly Work Done Report — heading wording, ordinal week range, photos two-up with repeated captions |
| `wr-test` | the WR CSV grouped Monday to Sunday |
| `wwr-test` | the WWR tab — work added there is a completed job on the to do list, and reaches both the WR export and the AD-19 planner |
| `edit-test` | editing a job after completion, driven against the 109-job import |
| `extra-edit-test` | the AD-19 tab's one-off jobs, their editor and photos |
| `ship-test` | the Ship tab, prefill, and the particulars paste parser |
| `blank-test` | the three-column blank sheet round-tripping through import |
| `banner-test` | the backup reminder, its thresholds and snooze |
| `tools-test` | the Tools tab, the converter and the unit guide — known conversions, the formula line, search, what it remembers |
| `eta-test` | the ETA tool — local-to-UTC conversion, half-hour and negative zones, the speed band and its arithmetic |
| `instr-test` | the Instruments tool — procedures, steps, spares, the log, and the due-date states |
| `photostore-test` | photographs in IndexedDB — migration from inline storage without loss, and far past the old ceiling |
| `report-layout-test` | prints the weekly report to a real PDF and reads it back — repeating header, statements alone on page one, four photos a page, page numbers |
| `collapse` | the entry form collapsing without clipping |
| `marsec-test`, `newfeat-test`, `print-test`, `project-test`, `theme-test` | MARSEC defaults, later additions, printing to PDF, the project sweep, light and dark |

`report-layout-check.py` reads the printed PDF that `report-layout-test.js`
produces. The report's layout rules only exist on paper — on screen it is one
long scroll — so nothing in the DOM can prove them. It needs `pypdf` (and
`cffi`); without them it prints SKIP rather than failing.

A warning from writing it: counting the image *objects* in a PDF page does not
count the photographs on it. Identical images are stored once and referenced
many times, so four copies of one picture looked like one image and the
four-per-page check passed no matter what. It counts draw operations in the
content stream now, and the suite gives every photo a different colour so
deduplication cannot hide a miscount.

`factor-check.py` is not a browser test: it re-derives all 70 conversion
factors from published definitions (231 cubic inches to the US gallon, 0.45359237
kg to the pound) and compares them with what the page carries. Run it after
touching `CONV_CATS` — a wrong factor produces a plausible-looking number that
no UI test would catch. It found two errors in the eighth significant figure
on its first run.

```bash
python3 tests/factor-check.py
```

`pic.jpg` is a tiny JPEG for photo attachment. `sample-import.csv` is the real
109-job import, used by several suites.

## A trap worth knowing

Several suites broke over time because they targeted `.first()` on a job card.
Pending jobs sort above completed ones, so after a repeat raises a new
occurrence `.first()` is the *new pending* card, not the one just completed.
Target `.task.done` or `.task:not(.done)` explicitly.

The same trap in another shape: a suite drove the extras row-photo control by
taking the **last** `input[type=file]` in the document. Adding a fourth hidden
file input to the page silently redirected it, and four assertions failed in a
feature nobody had touched. Those inputs now carry ids — `taskPhotoInput`,
`exRowPhotoInput`, `instrPhotoInput` — and nothing should locate an element by
its position among its siblings.

## Keep the suites runnable

Every suite reads `APP_HTML` and `OUT` from the environment. They have twice
been committed with those reads broken — `fs.readFileSync('process.env.APP_HTML')`
quoted as a string, and `require(process.env.SP + '/node_modules/playwright-core')`
against a variable nothing sets — which makes the whole file fail before its
first assertion, and a suite that cannot start looks much like a suite that
passes if only the tail of the output is read. `run.sh` prints `FAILED` for
both cases; read it.

Ports are hardcoded, one per suite, because `run.sh` runs them in sequence.
Two suites on the same port fail with `ERR_CONNECTION_REFUSED` on whichever
runs second. Check the port is unused before adding a suite.

Assertions that pin a colour go stale when the colour is deliberately changed —
the weekly-job yellow was dulled on request, and two suites kept asserting the
bright value. A failing colour check means look at the app before assuming the
app is wrong.
