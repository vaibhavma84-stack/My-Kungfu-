# Deck Log — Android wrapper, tests and release

The app itself is written in `../expenses/GasPlanet_ToDoList.html`. That repo's
`CLAUDE.md` holds the working rules; `DECISIONS.md` here holds the reasoning.

## Run the tests

    APP_HTML=$PWD/../expenses/GasPlanet_ToDoList.html SP=$PWD bash tests/run.sh

Every `tests/*-test.js` is picked up automatically. `site-test.js` serves the
two hosted apps over HTTP, because the map fetches its data and `fetch` does not
work from a `file://` URL.

## Releasing

Only when asked. Then:

1. bump `APP_BUILD` in the app source
2. `python3 ../expenses/build-site.py`
3. copy `site/index.html` and `site/sw.js` here — **this is what triggers the
   APK build**; a push that touches neither `android/**` nor `index.html` does
   not build anything
4. `python3 verify-release.py vNN` — it checks the published APK reports that
   version **and** was built from HEAD. Never send a link before it says OK.

## Before any Android change

    cd android && python3 check-resources.py

It rejects the three faults that have each shipped a broken widget: a view type
`RemoteViews` does not accept, an unescaped apostrophe in `strings.xml`, and
`--` inside an XML comment.
