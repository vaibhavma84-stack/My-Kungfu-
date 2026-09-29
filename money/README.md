# Ledger

Income, loans, day-to-day spending, investments and goals. One self-contained
page that works with no signal, meant to live on the home screen of a phone.

A companion to the deck log in the folder above, built to the same rules: one
HTML file, no libraries, nothing fetched over the network, everything on the
device.

## Putting it on a phone

The app is served from `docs/money/`, because GitHub Pages for this repository
is set to **main, folder `/docs`**. So the URL is:

```
https://vaibhavma84-stack.github.io/My-Kungfu-/money/
```

It only exists there once `docs/money/` is on **main** — a feature branch is
not published.

### iPhone and iPad

1. Open that URL in **Safari** — it has to be Safari, and you have to be
   **connected** for that first visit, which caches the whole app on the device.
2. Share → **Add to Home Screen**.
3. From then on it opens from the icon, full screen, with no signal needed.

Opening the HTML file straight from the Files app does **not** work: iOS renders
it in Quick Look, where JavaScript is restricted and nothing is saved between
opens. It has to be served over https.

**One thing to know about backing up on an iPhone.** A home-screen app on iOS
cannot save a file the ordinary way — a download there does nothing at all, with
no error. The app handles it: on the home-screen app, exporting opens the
**share sheet** instead, and you save the file to Files or send it to yourself.
If you cancel that sheet, nothing is recorded as a backup, because nothing was
backed up. On an iPad or iPhone old enough to be short of the share sheet, the
app says so and tells you to export from Safari instead, rather than quietly
doing nothing.

### Android

Either the same way in Chrome, or install the APK — see `android/README.md`.
The APK and the website are separate stores and never see each other's data.

## What it holds

| Tab | |
|---|---|
| **Month** | what came in, what went out, what is left — and the pie: where the month's money goes, what you own, or spending alone |
| **Income** | as many sources as you have, each on its own cycle: monthly salary, quarterly rent, a yearly bonus. What is due, and what actually arrived |
| **Loans** | as many loans as you carry. Reducing-balance schedule, interest to date, the month it clears, and prepayments that recalculate the rest of it |
| **Spend** | fixed outgoings that arrive whether you look or not (rent, insurance, fees), and the day-to-day ledger by category |
| | **50 categories in nine groups** — home, food, getting about, family, health, personal, at sea, money. Cut the list down in Settings to the ones you use; anything you type in yourself appears under "Yours" |
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

## Two phones, one ledger

Both phones hold the whole thing. There is no server and no account — a file
is carried across by hand, which on two iPhones means **AirDrop**.

1. **Data** tab → **Send this phone's file** → AirDrop it to the other phone.
2. On the other phone: **Data** → **Merge their file** → pick it.
3. Send one back the same way, and merge it here too.

After that round trip both phones hold the same ledger.

**Merging never loses what is on the phone doing the merging.** It works
record by record: whoever edited a thing last wins that one thing, and nothing
else is disturbed. Adding an expense on each phone gives you both. Merging the
same file twice does nothing the second time. Merging in either order lands on
the same answer, so it does not matter who goes first.

**A wrong clock on one phone cannot lose an edit.** Every change carries a
counter as well as a time, and merging a file advances it — so once a phone
has seen an edit, nothing it writes afterwards can sort below it. Her phone
being ten minutes slow does not make her later change lose to your older one.
Changes made when neither of you had seen the other's are genuinely at the
same moment; there the newer stamp wins and both phones pick the same one.

Two cases it tells you about rather than deciding quietly:

- **Both of you edited the same thing since the last sync.** The newer edit
  stands and the merge names what it was. This is somebody's salary; it is not
  going to swallow that.
- **One deleted something the other then edited.** The edit wins and the record
  comes back, and the merge says so. Delete it again if you meant it to go.

**Merge and Restore are different.** Restore *replaces* everything on the
phone — it is for putting a backup back on a wiped phone. Using Restore with
the other phone's file would throw away everything entered on this one. The
button says so.

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

1. Edit `index.html`.
2. Bump `APP_BUILD` at the top of its script. CI names the APK release after
   it and the Data tab prints it, so you can tell what a phone is running.
3. Bump `CACHE` in `sw.js` (`ledger-v7` → `-v8`). Without it, devices keep
   serving the old cached copy. Updates are picked up on the launch *after*
   the one that downloads them, since pages are served from cache first.
4. Run `publish.sh`. It copies the six runtime files into `docs/money/`, which
   is what Pages actually serves. **Forgetting this is silent** — every test
   passes against `money/index.html` while the phone keeps running last week's
   build.
5. Run `tests/run.sh`, which fails if the published copy has drifted.

The Android workflow copies `index.html` into the APK on every build, so the
phone app and the web version cannot drift either.

## Known limits

- **iOS 15.4 or later** for the record editor, which uses `<dialog>`. Older
  than that and it falls back to a plain panel, which works but is plainer.
- **The home-screen app exports through the share sheet**, not a download. See
  above.
- **No price feed.** Share and NAV prices are the ones you type in. The card
  shows the date you wrote them down and marks a price more than a month old.
- **Syncing is by hand.** Two phones stay level by swapping a file, not by
  themselves. Nothing happens in the background and nothing happens over
  Bluetooth — Safari has no Web Bluetooth, and two phones could not pair
  through a web page even where it exists.
