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
| `edit-test` | editing a job after completion, driven against the 109-job import |
| `extra-edit-test` | the AD-19 tab's one-off jobs, their editor and photos |
| `ship-test` | the Ship tab, prefill, and the particulars paste parser |
| `blank-test` | the three-column blank sheet round-tripping through import |
| `banner-test` | the backup reminder, its thresholds and snooze |
| `collapse` | the entry form collapsing without clipping |

`pic.jpg` is a tiny JPEG for photo attachment. `sample-import.csv` is the real
109-job import, used by several suites.

## A trap worth knowing

Several suites broke over time because they targeted `.first()` on a job card.
Pending jobs sort above completed ones, so after a repeat raises a new
occurrence `.first()` is the *new pending* card, not the one just completed.
Target `.task.done` or `.task:not(.done)` explicitly.
