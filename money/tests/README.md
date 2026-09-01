# Tests

One suite, driving the real page in Chromium.

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
