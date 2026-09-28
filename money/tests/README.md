# Tests

Two suites, driving the real page in Chromium.

```bash
(cd ../../tests && npm install playwright-core)   # once, shared with the deck log
./run.sh
```

The point of it is the arithmetic. The app runs every instrument — loan, FD,
RD, SIP — through one month-by-month loop. The suite re-derives each figure
from the published closed form instead, written out in the test file against
its own definition, and compares. Two independent routes to the same number is
the only check worth having here: the screen renders a wrong EMI exactly as
convincingly as a right one.

| Covers | |
|---|---|
| dates | local calendar day, month arithmetic across year ends, a day-31 anchor in February |
| loans | EMI and balance against the closed forms, the schedule landing on zero, total interest, prepayments shortening the loan, an oversized prepayment trimmed, an EMI too small to cover the interest, a zero-interest loan |
| deposits | FD against `P(1+r/(100m))^(mt)`, quarterly beating yearly, interest paid out not compounding, value meeting maturity exactly on the maturity date; RD against the per-instalment sum, and against the naive single-rate figure it must not be |
| calculator | flat and step-up SIP against a closed form summed by year, the calculator agreeing with the portfolio on the same FD and RD, both backward solves landing on their target |
| returns | the money-weighted return coming back as the rate that went in, every period of a flat run returning the same rate, and the pot-over-paid-in shortcut shown to be the wrong answer |
| the pie | slices summing to the whole, the fold into Other past six, a category keeping its colour when a smaller one is removed |
| the app | a record saved, reloaded and listed; a loan working out its own EMI; the month view free of NaN; a rate a day suppressed at the start of a month |
| storage | a failed write rolled back, with the screen and the store still agreeing |
| separation | the app never writes a `gasplanet_` key |

`sync-test.js` is the second suite, and it drives **two pages in two browser
contexts** — genuinely separate storage, the same situation as two phones,
with files carried between them the way AirDrop carries them.

The property it proves is that no merge can lose what was entered on the phone
doing the merging. The old import replaced the lot, which for two people meant
whoever imported second lost their own week.

| Covers | |
|---|---|
| the headline case | both phones add something, both end up with both |
| convergence | after a round trip the two ledgers are byte-identical, and merging in either order gives the same answer |
| idempotence | the same file merged twice changes nothing |
| conflicts | the newer of two edits wins on both phones, and equal stamps break the tie the same way on each — without that they settle differently and stay different for ever |
| deletes | a tombstone stops a deleted record walking back in from the other phone, and the delete carries across |
| revivals | an edit newer than the delete brings the record back, and the merge reports it instead of silently dropping the edit |
| nested records | a prepayment added on each phone gives two, not one; deleting one sticks |
| old files | a version-1 file with no stamps merges without duplicating, its stamp recovered from the record's own id, and an id from elsewhere decodes to no date rather than a wild one |
| identity | a restore keeps this phone its own device id, so ties keep breaking correctly afterwards |
| settings | spending categories are additive across phones |
