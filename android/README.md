# Deck Log — Android app

A shell around `index.html`. The page is copied into the APK at build time and
runs from local files, so the app never needs a network.

## Getting the APK onto a phone

1. Open the repo on GitHub → **Actions** → **Build Android APK**
2. Open the newest green run → **Artifacts** → download `DeckLog-…-apk`
3. Unzip it and open `app-debug.apk` on the phone
4. Android will ask to allow installing from this source — allow it, then Install

The APK is debug-signed, which is fine for installing directly. It cannot go on
the Play Store without a release key, which is only needed for store listing.

## What the shell adds

A plain WebView will not do four things the page needs, so `MainActivity.kt`
supplies them:

| | |
|---|---|
| `domStorageEnabled` | keeps localStorage between launches — this is where all the data lives |
| `onShowFileChooser` | makes the photo buttons open the gallery, with the camera offered alongside |
| click interception | the page saves CSVs by clicking a hidden `<a download>`, which a WebView ignores; the click is caught, the blob read back and written to **Downloads** |
| `window.print` | routed to Android's print service so the weekly report can be saved as a PDF |

## Changing the app

Edit `index.html` in the repo root. The workflow copies it into the APK on
every build, so the phone app and the web version never drift apart.
