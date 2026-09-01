# Ledger

Income, loans, day-to-day spending, investments and goals. One self-contained
page that works with no signal, meant to live on the home screen of a phone.

A companion to the deck log in the folder above, built to the same rules: one
HTML file, no libraries, nothing fetched over the network, everything on the
device.

## Putting it on a phone

1. Open `…/money/` in Safari (iPhone/iPad) or Chrome (Android) **once while
   connected**. That first visit caches the whole app on the device.
2. Share → **Add to Home Screen**.
3. From then on it opens from the icon, full screen, with no signal needed.

Opening the HTML file straight from the Files app does **not** work: iOS renders
it in Quick Look, where JavaScript is restricted and nothing is saved between
opens. It has to be served over https.

## What it holds

| Tab | |
|---|---|
| **Month** | what came in, what went out, what is left — and the pie: where the month's money goes, what you own, or spending alone |
| **Income** | as many sources as you have, each on its own cycle: monthly salary, quarterly rent, a yearly bonus. What is due, and what actually arrived |
| **Loans** | as many loans as you carry. Reducing-balance schedule, interest to date, the month it clears, and prepayments that recalculate the rest of it |
| **Spend** | fixed outgoings that arrive whether you look or not (rent, insurance, fees), and the day-to-day ledger by category |
| **Invest** | equity, mutual funds, bonds, fixed deposits and recurring deposits, each valued its own way — plus the projection and step-up calculator |
| **Goals** | a target, a date, and what it would take each month to get there |
| **Data** | the backup, the settings, and the counts |

## The calculator

Projection and step-up in one. Put in what you can save each month, the rate
you expect and how long for, and it runs the whole thing month by month — the
instalment stepping up by a set percentage every twelve months if you want it
to. It shows the result every three months and every year, with the return
each period actually earned.

It also solves backwards: name a target and it gives the monthly amount, or
the lump sum, that reaches it.

Nothing in it is a forecast. It is what the rate you typed produces,
arithmetically. A fund does not return the same percentage every year and the
calculator does not pretend it does.

## Where the data lives

In the browser's own storage, on that one device. Nothing is uploaded and
nothing syncs between devices.

`localStorage` is per **origin**, not per folder, so this app and the deck log
share one store and one quota on the same site. Every key here is prefixed
`money_` and nothing here touches a `gasplanet_` key — but the roughly 5 MB is
shared between them, and the Data tab shows how much of it is gone.

**Export is the backup.** Clearing the browser's website data erases
everything, and iOS can evict storage for a page left unopened for a long
stretch. The app nags when the last export is more than 14 days old.

The JSON backup restores the app exactly. The CSV is for reading and for a
spreadsheet; it flattens prepayments and goal payments and cannot rebuild them.

## Changing the app

Edit `index.html`, then bump the `CACHE` constant at the top of `sw.js`
(`ledger-v1` → `-v2`). Without that bump, devices keep serving the old cached
copy. Updates are picked up on the launch *after* the one that downloads them,
since pages are served from cache first.

Run `tests/run.sh` before pushing.
