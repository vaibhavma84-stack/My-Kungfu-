# Deck Log — Android wrapper, tests and release

The app itself is written in `../expenses/GasPlanet_ToDoList.html`. That repo's
`CLAUDE.md` holds the working rules; `DECISIONS.md` here holds the reasoning.

## Run the tests

    APP_HTML=$PWD/../expenses/GasPlanet_ToDoList.html SP=$PWD bash tests/run.sh

Every `tests/*-test.js` is picked up automatically. `site-test.js` serves the
two hosted apps over HTTP, because the map fetches its data and `fetch` does not
work from a `file://` URL. `offline-test.js` is the one that matters most: it serves the real hosted
build, lets the service worker take hold, then removes the network entirely and
does the day's work with nothing to fetch — open the app, read a photograph,
open and mark a PDF, write a marked copy, sign off. `notes-test.js` does the
same, and reads
`sample.pdf` — two pages of real text, written by `tests/make-sample-pdf.py`
rather than downloaded, so the suite has nothing to fetch.

## Releasing

Only when asked. Then:

1. bump `APP_BUILD` in the app source
2. `python3 ../expenses/build-site.py`
3. copy **`apk/index.html`** here as `index.html` — **this is what triggers the
   APK build**; a push that touches neither `android/**` nor `index.html` does
   not build anything. It must be `apk/`, never `site/`: `site/index.html` is
   the deck log with the world map taken out, and copying that here would
   quietly remove the map from the Android app. That nearly shipped once.
   `docs/` is the hosted deck log — copy `site/` there, and note that its
   service worker cache is keyed on `APP_BUILD`, so a page updated without a
   version bump reaches an installed phone only on its second opening
4. `python3 verify-release.py vNN` — it checks the published APK reports that
   version **and** was built from HEAD. Never send a link before it says OK.

## Before any Android change

    cd android && python3 check-resources.py

It rejects the three faults that have each shipped a broken widget: a view type
`RemoteViews` does not accept, an unescaped apostrophe in `strings.xml`, and
`--` inside an XML comment.
