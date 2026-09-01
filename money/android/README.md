# Ledger — Android app

A shell around `money/index.html`. The page is copied into the APK at build
time and runs from local files, so the app never touches a network.

## Getting the APK onto a phone

**From the phone, easiest:** open the repo's **Releases** on GitHub, find
**Ledger**, tap `Ledger.apk`. Android will ask to allow installing from this
source — allow it, then Install.

**From a computer:** Actions → **Build Ledger APK** → newest green run →
Artifacts → `Ledger-…-apk`.

Check it installed: the **Data** tab reports the build it is running.

The APK is debug-signed, which is fine for installing directly. The Play Store
needs a release key, which is only needed for a store listing.

## What the shell adds

A plain WebView will not do three things the page needs:

| | |
|---|---|
| `domStorageEnabled` | keeps localStorage between launches — this is where every figure lives |
| click interception | the page saves its CSV and JSON by clicking a hidden `<a download>`, which a WebView ignores entirely: no error, no file, nothing. The click is caught, the blob read back and written to **Downloads** |
| `onShowFileChooser` | lets "Restore a backup" open a file. Deliberately unfiltered — file managers report `.csv` and `.json` as `text/plain` or `octet-stream` often enough that a filtered picker greys out the file you came for |

`money/tests/money-test.js` pins the first two from the page's side, so an
export rewritten to work some other way fails a test here rather than silently
leaving the APK unable to back anything up.

## What it does not add, on purpose

No camera, no location, no widgets, no print. This app has no photographs and
no map. Its permission list is empty, and for an app holding somebody's salary,
loans and holdings that is worth keeping.

## Backup is off, and that is deliberate

`android:allowBackup="false"`, and `data_extraction_rules.xml` excludes
everything from both cloud backup and device-to-device transfer.

With backup on, Android copies the app's data directory — which is where the
WebView keeps localStorage, which is where all of this app's data lives — to
your Google Drive, on a schedule, with no prompt. The app's own Data tab tells
you nothing is uploaded and nothing syncs. That has to be true.

**The cost is real: lose the phone and the data goes with it.** Export a JSON
backup from the Data tab and keep it somewhere. That is the trade this app
already asks you to make, and the 14-day reminder exists for it.

## The installed app and the website are separate stores

The APK runs the page from `file:///android_asset/`, which is a different
origin from the hosted site. They never see each other's data. Pick one and
stay with it; a JSON backup is how data moves between them. The Data tab says
which one you are looking at.

## Signing

`ledger-debug.keystore` is committed and every build uses it. Without a fixed
key the Android plugin generates one per machine, a CI runner starts with none,
and each build is signed by a different stranger — Android then refuses every
upgrade and the only way in is to uninstall, which here would take every loan,
holding and expense with it. The deck log lost data to exactly this, which is
why CI verifies the signature before publishing anything.

It is a debug key with the conventional password. Not a secret, and it must
never publish to a store.

## Changing the app

Edit `money/index.html`, bump `APP_BUILD` at the top of its script (CI reads it
to name the release), and bump `CACHE` in `money/sw.js` for the web copy. The
workflow copies the page into the APK on every build, so the phone app and the
web version cannot drift.

Before pushing:

```bash
money/tests/run.sh                              # the app's own suite
python3 android/check-resources.py money/android   # resource sanity, no SDK needed
```
