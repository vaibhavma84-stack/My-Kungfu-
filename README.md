# My-Kungfu-

All in on to do and log.

To do list, crew list and cargo log for the Gas Planet. One self-contained page
that works with no signal, meant to live on the home screen of a phone or tablet.

## Putting it on a phone or tablet

1. Open the site URL in Safari (iPhone/iPad) or Chrome (Android) **once while
   connected**. That first visit caches the whole app on the device.
2. Share → **Add to Home Screen**.
3. From then on it opens from the icon, full screen, with no signal needed.

Opening the HTML file straight from the Files app does **not** work: iOS renders
it in Quick Look, a document previewer where JavaScript is restricted and nothing
is saved between opens. It has to be served over https.

## Where the data lives

In the browser's own storage, on that one device. Nothing is uploaded and nothing
syncs between devices — an iPad and a phone each keep their own separate copy.

**Export CSV is the backup.** Clearing the browser's website data erases
everything, and iOS can evict storage for a page left unopened for a long stretch.
The app tracks each list separately and reminds you when one has not been exported
for 14 days.

## What's in here

| File | |
|---|---|
| `index.html` | the entire app — markup, styles and logic in one file, no libraries |
| `sw.js` | service worker; caches the app so it opens offline |
| `manifest.webmanifest` | home-screen name, icon and full-screen behaviour |
| `icon-*.png` | app icons |

## Changing the app

Edit `index.html`, then bump the `CACHE` constant at the top of `sw.js`
(`gasplanet-deck-log-v1` → `-v2`). Without that bump, devices keep serving the
old cached copy. Updates are picked up on the launch *after* the one that
downloads them, since pages are served from cache first for speed at sea.
